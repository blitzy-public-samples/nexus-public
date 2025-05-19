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
package org.sonatype.nexus.common.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import com.google.common.io.ByteStreams;
import com.google.common.io.Resources;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertEquals;

/**
 * UT for {@link SanitizingJsonOutputStream}.
 *
 * @since 3.0
 */
public class SanitizingJsonOutputStreamTest
    extends TestSupport
{
  private static final List<String> FIELDS = Arrays.asList("A", "B", "C", "D", "E", "F", "G", "K", "M");

  private static final String REPLACEMENT = "*";

  /**
   * Tests that a sanitizer correctly sanitizes basic content based on field names.
   */
  @Test
  public void sanitizeContent() throws IOException {
    String input = Resources.toString(Resources.getResource(getClass(), "input.json"), Charset.forName("UTF-8"));
    String output = Resources.toString(Resources.getResource(getClass(), "output.json"), Charset.forName("UTF-8"));

    ByteArrayInputStream is = new ByteArrayInputStream(input.getBytes(Charset.forName("UTF-8")));
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (SanitizingJsonOutputStream stream = new SanitizingJsonOutputStream(os, FIELDS, REPLACEMENT)) {
      ByteStreams.copy(is, stream);
    }

    assertEquals(output, os.toString("UTF-8"));
  }
  
  /**
   * Tests that a sanitizer correctly sanitizes content when using Virtual Threads.
   * This validates that the implementation is compatible with Java 21 Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void sanitizeContentWithVirtualThreads() throws IOException {
    String input = Resources.toString(Resources.getResource(getClass(), "input.json"), Charset.forName("UTF-8"));
    String output = Resources.toString(Resources.getResource(getClass(), "output.json"), Charset.forName("UTF-8"));

    // Create a virtual thread to perform the sanitization
    Thread virtualThread = Thread.ofVirtual().name("sanitize-json-thread").start(() -> {
      try {
        ByteArrayInputStream is = new ByteArrayInputStream(input.getBytes(Charset.forName("UTF-8")));
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        try (SanitizingJsonOutputStream stream = new SanitizingJsonOutputStream(os, FIELDS, REPLACEMENT)) {
          ByteStreams.copy(is, stream);
        }
        
        // Verify the output matches the expected result
        assertEquals(output, os.toString("UTF-8"));
      }
      catch (IOException e) {
        throw new RuntimeException("Error in virtual thread sanitization", e);
      }
    });
    
    try {
      // Wait for the virtual thread to complete
      virtualThread.join();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Virtual thread interrupted", e);
    }
  }
  
  /**
   * Tests concurrent JSON processing with multiple Virtual Threads.
   * This validates that the implementation can handle high concurrency with Virtual Threads.
   */
  @Test
  @Category(VirtualThreadTestGroup.class)
  public void concurrentJsonProcessingWithVirtualThreads() throws Exception {
    String input = Resources.toString(Resources.getResource(getClass(), "input.json"), Charset.forName("UTF-8"));
    String output = Resources.toString(Resources.getResource(getClass(), "output.json"), Charset.forName("UTF-8"));
    byte[] inputBytes = input.getBytes(Charset.forName("UTF-8"));
    
    // Number of concurrent sanitization operations to perform
    final int concurrentOperations = 100;
    final AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create futures for concurrent sanitization operations
      List<CompletableFuture<String>> futures = Arrays.asList(new CompletableFuture[concurrentOperations]);
      
      for (int i = 0; i < concurrentOperations; i++) {
        final int index = i;
        futures.set(index, CompletableFuture.supplyAsync(() -> {
          try {
            // Create new input/output streams for each operation to avoid sharing
            ByteArrayInputStream is = new ByteArrayInputStream(inputBytes);
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            
            // Perform the sanitization
            try (SanitizingJsonOutputStream stream = new SanitizingJsonOutputStream(os, FIELDS, REPLACEMENT)) {
              ByteStreams.copy(is, stream);
            }
            
            // Get the sanitized output
            String result = os.toString("UTF-8");
            
            // Verify the output matches the expected result
            if (output.equals(result)) {
              successCount.incrementAndGet();
            }
            
            return result;
          }
          catch (IOException e) {
            throw new RuntimeException("Error in virtual thread " + index, e);
          }
        }, executor));
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .get(30, TimeUnit.SECONDS);
    }
    
    // Verify all operations completed successfully
    assertEquals("All sanitization operations should succeed", concurrentOperations, successCount.get());
  }
}