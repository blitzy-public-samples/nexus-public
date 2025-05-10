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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;

import static com.fasterxml.jackson.databind.SerializationFeature.FLUSH_AFTER_WRITE_VALUE;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamingObjectMapperTest
    extends TestSupport
{
  private StreamingObjectMapper underTest = new StreamingObjectMapper();

  @Test
  public void should_Write_Exactly_What_Was_Read() throws IOException {
    String json = "{}";
    ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
    ByteArrayOutputStream output = new ByteArrayOutputStream();

    underTest.readAndWrite(input, output);

    assertThat(json, equalTo(new String(output.toByteArray())));

    json = "{\"_id\":\"simple\",\"name\":\"simple\",\"description\":\"simplestuff\"}";
    input = new ByteArrayInputStream(json.getBytes());
    output = new ByteArrayOutputStream();
    underTest.readAndWrite(input, output);

    assertThat(json, equalTo(new String(output.toByteArray())));
  }

  @Test
  public void should_Write_MinimizedJson_Of_What_Was_Read() throws IOException {
    String prettyJson = "{\n\"_id\": \"simple\",\n\"name\": \"simple\",\n\"description\": \"simplestuff\"}";

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
  public void testConcurrentStreamingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service with virtual threads
    ExecutorService executorService = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    List<Future<String>> futures = new ArrayList<>(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    String json = "{\"_id\":\"concurrent\",\"name\":\"test\",\"description\":\"virtual thread test\"}";
    
    try {
      // Submit multiple concurrent streaming tasks
      for (int i = 0; i < taskCount; i++) {
        futures.add(executorService.submit(() -> {
          ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          
          underTest.readAndWrite(input, output);
          
          String result = new String(output.toByteArray());
          if (json.equals(result)) {
            successCount.incrementAndGet();
          }
          return result;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<String> future : futures) {
        String result = future.get(5, TimeUnit.SECONDS);
        assertThat(result, equalTo(json));
      }
      
      // Verify all tasks completed successfully
      assertThat(successCount.get(), equalTo(taskCount));
    } finally {
      executorService.shutdown();
      executorService.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  @Test
  public void compareVirtualThreadsWithPlatformThreads() throws Exception {
    // Create thread factories for both types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Create executor services
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    ExecutorService platformExecutor = Executors.newThreadPerTaskExecutor(platformThreadFactory);
    
    int taskCount = 1000;
    String json = "{\"_id\":\"performance\",\"name\":\"test\",\"description\":\"thread comparison\"}";
    
    try {
      // Test with platform threads
      long platformStart = System.nanoTime();
      List<Future<String>> platformFutures = new ArrayList<>(taskCount);
      
      for (int i = 0; i < taskCount; i++) {
        platformFutures.add(platformExecutor.submit(() -> {
          ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          underTest.readAndWrite(input, output);
          return new String(output.toByteArray());
        }));
      }
      
      for (Future<String> future : platformFutures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      long platformDuration = System.nanoTime() - platformStart;
      
      // Test with virtual threads
      long virtualStart = System.nanoTime();
      List<Future<String>> virtualFutures = new ArrayList<>(taskCount);
      
      for (int i = 0; i < taskCount; i++) {
        virtualFutures.add(virtualExecutor.submit(() -> {
          ByteArrayInputStream input = new ByteArrayInputStream(json.getBytes());
          ByteArrayOutputStream output = new ByteArrayOutputStream();
          underTest.readAndWrite(input, output);
          return new String(output.toByteArray());
        }));
      }
      
      for (Future<String> future : virtualFutures) {
        future.get(10, TimeUnit.SECONDS);
      }
      
      long virtualDuration = System.nanoTime() - virtualStart;
      
      // Log the performance comparison
      log.info("Platform threads execution time: {} ns", platformDuration);
      log.info("Virtual threads execution time: {} ns", virtualDuration);
      log.info("Performance ratio (platform/virtual): {}", (double) platformDuration / virtualDuration);
      
      // Note: We don't assert on the actual performance as it can vary by environment
      // This test is primarily for observational purposes
    } finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
      platformExecutor.awaitTermination(10, TimeUnit.SECONDS);
      virtualExecutor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
}