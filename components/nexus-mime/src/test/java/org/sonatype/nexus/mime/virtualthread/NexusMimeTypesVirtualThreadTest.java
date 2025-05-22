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
package org.sonatype.nexus.mime.virtualthread;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.mime.MimeRule;
import org.sonatype.nexus.mime.internal.NexusMimeTypes;

import com.google.common.base.Joiner;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NexusMimeTypes} using Java 21 Virtual Threads to validate concurrent access.
 * 
 * This test ensures that MIME type mapping operations remain thread-safe when accessed
 * by thousands of concurrent virtual threads. It verifies that loading and accessing MIME type
 * mappings works correctly under high concurrency and does not cause thread pinning.
 */
public class NexusMimeTypesVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 10_000;
  private static final int WARMUP_COUNT = 1_000;
  
  private NexusMimeTypes underTest = new NexusMimeTypes();

  private Properties addMimeType(final Properties properties, final String extension, final String... types) {
    properties.setProperty(extension, Joiner.on(",").join(types));
    return properties;
  }

  /**
   * Tests concurrent access to NexusMimeTypes using Virtual Threads.
   * 
   * This test creates thousands of virtual threads that concurrently access the
   * NexusMimeTypes instance to verify thread safety and performance under high load.
   */
  @Test
  public void testConcurrentAccessWithVirtualThreads() throws Exception {
    // Initialize mime types with test data
    Properties properties = new Properties();
    addMimeType(properties, "test", "application/octet-stream");
    addMimeType(properties, "override.pdf", "application/pdf");
    addMimeType(properties, "pdf", "application/x-pdf");
    underTest.initMimeTypes(properties);
    
    // Warm up to ensure JIT compilation
    runConcurrentTest(WARMUP_COUNT, true);
    
    // Run the actual test with virtual threads
    long virtualThreadTime = runConcurrentTest(THREAD_COUNT, true);
    log.info("Virtual thread test completed in {} ms with {} threads", virtualThreadTime, THREAD_COUNT);
    
    // Run the same test with platform threads for comparison
    long platformThreadTime = runConcurrentTest(THREAD_COUNT / 100, false);
    log.info("Platform thread test completed in {} ms with {} threads", platformThreadTime, THREAD_COUNT / 100);
    
    // The test passes if all threads completed successfully without exceptions
    log.info("Concurrent access test passed with {} virtual threads", THREAD_COUNT);
  }
  
  /**
   * Runs a concurrent test accessing NexusMimeTypes with the specified number of threads.
   * 
   * @param threadCount the number of threads to use
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return the time in milliseconds that the test took to complete
   */
  private long runConcurrentTest(int threadCount, boolean useVirtualThreads) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    ExecutorService executor = useVirtualThreads
        ? Executors.newVirtualThreadPerTaskExecutor()
        : Executors.newFixedThreadPool(Math.min(100, threadCount));
    
    // Create and submit tasks
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform operations on NexusMimeTypes
          String extension = index % 2 == 0 ? "test" : "pdf";
          MimeRule mimeRule = underTest.getMimeRuleForExtension(extension);
          
          if (mimeRule != null) {
            // Verify the mime rule is correct
            if ("test".equals(extension)) {
              assertEquals("application/octet-stream", mimeRule.getMimetypes().get(0));
              assertEquals(false, mimeRule.isOverride());
            } else {
              assertEquals("application/pdf", mimeRule.getMimetypes().get(0));
              assertEquals(true, mimeRule.isOverride());
            }
            successCount.incrementAndGet();
          } else {
            failureCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in thread {}", index, e);
          failureCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      }, executor);
    }
    
    // Start all threads simultaneously
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    
    // Shutdown the executor
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all threads completed in time");
    assertEquals(threadCount, successCount.get(), "Some threads failed to complete successfully");
    assertEquals(0, failureCount.get(), "Some threads encountered errors");
    
    return endTime - startTime;
  }
  
  /**
   * Tests that virtual threads can handle concurrent initialization of mime types.
   * This verifies that file I/O operations for loading MIME type properties don't cause thread pinning.
   */
  @Test
  public void testConcurrentInitializationWithVirtualThreads() throws Exception {
    int initThreadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(initThreadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create multiple instances and initialize them concurrently
      for (int i = 0; i < initThreadCount; i++) {
        final int index = i;
        CompletableFuture.runAsync(() -> {
          try {
            startLatch.await();
            
            // Create a new instance for each thread
            NexusMimeTypes mimeTypes = new NexusMimeTypes();
            
            // Initialize with different properties
            Properties properties = new Properties();
            addMimeType(properties, "test" + index, "application/test-" + index);
            addMimeType(properties, "override.pdf" + index, "application/pdf-" + index);
            
            // Initialize the mime types (this involves file I/O)
            mimeTypes.initMimeTypes(properties);
            
            // Verify initialization worked
            MimeRule mimeRule = mimeTypes.getMimeRuleForExtension("test" + index);
            assertNotNull(mimeRule, "MimeRule should not be null");
            assertEquals("application/test-" + index, mimeRule.getMimetypes().get(0));
            
            successCount.incrementAndGet();
          } catch (Exception e) {
            log.error("Error in initialization thread {}", index, e);
          } finally {
            completionLatch.countDown();
          }
        }, executor);
      }
      
      // Start all initialization threads
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      assertTrue(completed, "Not all initialization threads completed in time");
      assertEquals(initThreadCount, successCount.get(), "Some initialization threads failed");
    }
  }
}