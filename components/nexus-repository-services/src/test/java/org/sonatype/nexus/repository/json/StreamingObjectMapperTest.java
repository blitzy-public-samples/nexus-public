/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.json;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static com.fasterxml.jackson.databind.SerializationFeature.FLUSH_AFTER_WRITE_VALUE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

public class StreamingObjectMapperTest
    extends TestSupport
{
  private StreamingObjectMapper underTest = new StreamingObjectMapper();
  
  // Sample JSON for testing
  private static final String SIMPLE_JSON = "{}";
  private static final String COMPLEX_JSON = "{\"_id\":\"simple\",\"name\":\"simple\",\"description\":\"simplestuff\"}";
  private static final String PRETTY_JSON = "{\n\"_id\": \"simple\",\n\"name\": \"simple\",\n\"description\": \"simplestuff\"}";
  
  // Thread factory for virtual threads
  private static final ThreadFactory VIRTUAL_THREAD_FACTORY = Thread.ofVirtual().factory();

  @Test
  public void should_Write_Exactly_What_Was_Read() throws IOException {
    String json = SIMPLE_JSON;
    ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    underTest.readAndWrite(input, output);

    assertThat(json, equalTo(new String(output.toByteArray())));

    json = COMPLEX_JSON;
    input = new ByteArrayInputStream(json.getBytes());
    output = new ByteArrayOutputStream();
    underTest.readAndWrite(input, output);

    assertThat(json, equalTo(new String(output.toByteArray())));
  }

  @Test
  public void should_Write_MinimizedJson_Of_What_Was_Read() throws IOException {
    String prettyJson = PRETTY_JSON;

    ByteArrayInputStream input = new ByteArrayInputStream(prettyJson.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    underTest.readAndWrite(input, output);

    String minifiedJson = prettyJson.replaceAll("\n", "").replace(" ", "");
    assertThat(minifiedJson, equalTo(new String(output.toByteArray())));
  }

  @Test
  public void should_Configure_SerializationFeature() {
    assertTrue(underTest.isEnabled(FLUSH_AFTER_WRITE_VALUE));

    underTest.configure(FLUSH_AFTER_WRITE_VALUE, false);

    assertFalse(underTest.isEnabled(FLUSH_AFTER_WRITE_VALUE));
  }
  
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentStreamingWithVirtualThreads() throws Exception {
    // This test validates that virtual threads can efficiently handle concurrent streaming operations
    int numThreads = 100;
    CountDownLatch latch = new CountDownLatch(numThreads);
    List<String> results = new ArrayList<>(numThreads);
    
    // Create executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VIRTUAL_THREAD_FACTORY)) {
      // Submit multiple concurrent streaming tasks
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Use a mix of simple and complex JSON based on index
            String json = (index % 2 == 0) ? SIMPLE_JSON : COMPLEX_JSON;
            ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            
            // Perform streaming operation
            underTest.readAndWrite(input, output);
            
            // Store result for verification
            synchronized (results) {
              results.add(new String(output.toByteArray()));
            }
          } 
          catch (IOException e) {
            log.error("Error in virtual thread streaming", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await();
    }
    
    // Verify results
    int simpleCount = 0;
    int complexCount = 0;
    
    for (String result : results) {
      if (result.equals(SIMPLE_JSON)) {
        simpleCount++;
      } 
      else if (result.equals(COMPLEX_JSON)) {
        complexCount++;
      }
    }
    
    // Verify we got the expected number of each type
    assertThat(simpleCount + complexCount, equalTo(numThreads));
    assertThat(simpleCount, equalTo(numThreads / 2 + numThreads % 2));
    assertThat(complexCount, equalTo(numThreads / 2));
  }
  
  @Test
  public void compareVirtualThreadsWithPlatformThreads() throws Exception {
    // This test compares performance between virtual threads and platform threads
    int numOperations = 1000;
    
    // Test with platform threads
    Duration platformDuration = assertTimeout(Duration.ofSeconds(30), () -> {
      long start = System.nanoTime();
      try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
        CountDownLatch latch = new CountDownLatch(numOperations);
        for (int i = 0; i < numOperations; i++) {
          executor.submit(() -> {
            try {
              ByteArrayInputStream input = new ByteArrayInputStream(COMPLEX_JSON.getBytes());
              ByteArrayOutputStream output = new ByteArrayOutputStream();
              underTest.readAndWrite(input, output);
            } 
            catch (IOException e) {
              log.error("Error in platform thread streaming", e);
            }
            finally {
              latch.countDown();
            }
          });
        }
        latch.await();
      }
      return Duration.ofNanos(System.nanoTime() - start);
    });
    
    // Test with virtual threads
    Duration virtualDuration = assertTimeout(Duration.ofSeconds(30), () -> {
      long start = System.nanoTime();
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(VIRTUAL_THREAD_FACTORY)) {
        CountDownLatch latch = new CountDownLatch(numOperations);
        for (int i = 0; i < numOperations; i++) {
          executor.submit(() -> {
            try {
              ByteArrayInputStream input = new ByteArrayInputStream(COMPLEX_JSON.getBytes());
              ByteArrayOutputStream output = new ByteArrayOutputStream();
              underTest.readAndWrite(input, output);
            } 
            catch (IOException e) {
              log.error("Error in virtual thread streaming", e);
            }
            finally {
              latch.countDown();
            }
          });
        }
        latch.await();
      }
      return Duration.ofNanos(System.nanoTime() - start);
    });
    
    log.info("Platform threads completed in {} ms", platformDuration.toMillis());
    log.info("Virtual threads completed in {} ms", virtualDuration.toMillis());
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }
}