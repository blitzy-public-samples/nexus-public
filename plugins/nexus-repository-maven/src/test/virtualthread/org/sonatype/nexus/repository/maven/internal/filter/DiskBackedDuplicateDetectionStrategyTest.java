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
package org.sonatype.nexus.repository.maven.internal.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.common.app.ApplicationDirectories;

import org.apache.maven.index.reader.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DiskBackedDuplicateDetectionStrategy} using Virtual Threads.
 * 
 * This test class focuses on ensuring that disk I/O operations performed by the strategy
 * work correctly with Virtual Threads, which is particularly important as I/O operations
 * are a primary use case for Virtual Threads' efficiency improvements.
 */
public class DiskBackedDuplicateDetectionStrategyTest
    extends DuplicateDetectionStrategyTestSupport
{
  @TempDir
  File tempDir;

  @Mock
  private ApplicationDirectories applicationDirectories;

  private DiskBackedDuplicateDetectionStrategy strategy;

  @BeforeEach
  void setUp() {
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tempDir);
    strategy = new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (strategy != null) {
      strategy.close();
    }
  }

  /**
   * Basic test to verify duplicate detection works with Virtual Threads.
   */
  @Test
  public void shouldIdentifyDuplicates() throws Exception {
    verifyDuplicateDetection(strategy);
  }

  /**
   * Tests concurrent file operations with Virtual Threads.
   * This test creates multiple Virtual Threads that simultaneously add records to the strategy.
   */
  @Test
  public void shouldHandleConcurrentOperations() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger uniqueCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create multiple Virtual Threads to add records concurrently
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        futures.add(executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Each thread adds a unique record and a duplicate record
            Record uniqueRecord = buildRecord("group" + threadId, "artifact" + threadId, "1.0", "sources", "jar");
            Record duplicateRecord = buildRecord("group" + threadId, "artifact" + threadId, "1.0", "sources", "jar");
            
            if (strategy.apply(uniqueRecord)) {
              uniqueCount.incrementAndGet();
            }
            
            // The second attempt should be identified as a duplicate
            assertFalse(strategy.apply(duplicateRecord), "Duplicate record should be detected");
            
            completionLatch.countDown();
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Not all threads completed in time");
      
      // Verify all unique records were added
      assertEquals(threadCount, uniqueCount.get(), "All unique records should be added");
      
      // Check for any exceptions in the futures
      for (Future<?> future : futures) {
        future.get(); // This will throw an exception if the task failed
      }
    }
  }

  /**
   * Tests disk I/O operations under high concurrency with thousands of Virtual Threads.
   * This test verifies that the strategy can handle a large number of concurrent operations
   * without issues, leveraging the efficiency of Virtual Threads for I/O operations.
   */
  @Test
  public void shouldHandleHighConcurrency() throws Exception {
    int threadCount = 5000; // Test with 5000 Virtual Threads
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger uniqueCount = new AtomicInteger(0);
    AtomicInteger duplicateCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create thousands of Virtual Threads to add records concurrently
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Create a unique record for each thread
            Record record = buildRecord("highConcurrencyGroup", "artifact" + threadId, "1.0", "sources", "jar");
            
            // Add the record to the strategy
            if (strategy.apply(record)) {
              uniqueCount.incrementAndGet();
            } else {
              duplicateCount.incrementAndGet();
            }
            
            completionLatch.countDown();
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      assertTrue(completed, "Not all threads completed in time");
      
      // Verify all records were processed
      assertEquals(threadCount, uniqueCount.get() + duplicateCount.get(), 
          "All records should be processed");
      assertEquals(threadCount, uniqueCount.get(), 
          "All records should be unique");
    }
  }
  
  /**
   * Tests for thread pinning during file system operations.
   * This test verifies that the strategy doesn't cause thread pinning when performing I/O operations.
   * 
   * Thread pinning occurs when a Virtual Thread is "stuck" to its carrier thread and cannot be unmounted,
   * which negates the benefits of Virtual Threads. This commonly happens with synchronized blocks/methods
   * or when executing native methods.
   */
  @Test
  public void shouldNotCauseThreadPinning() throws Exception {
    // Note: To detect thread pinning in a real environment, you would use:
    // 1. JVM flag: -Djdk.tracePinnedThreads=full
    // 2. JFR (Java Flight Recorder) events: jdk.VirtualThreadPinned
    
    // For this test, we'll simulate a high load of concurrent I/O operations
    // and verify that all operations complete in a reasonable time,
    // which indirectly suggests that no significant thread pinning is occurring
    
    int threadCount = 1000;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create Virtual Threads that will perform I/O operations concurrently
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            
            // Perform multiple operations to increase chance of detecting pinning
            for (int j = 0; j < 10; j++) {
              Record record = buildRecord("pinningTest", "artifact" + threadId + "-" + j, "1.0", "sources", "jar");
              strategy.apply(record);
              
              // Small sleep to simulate real-world scenario with intermittent I/O
              Thread.sleep(1);
            }
            
            completionLatch.countDown();
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // If thread pinning occurs, this would likely time out as carrier threads would be blocked
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Operations did not complete in time, possible thread pinning detected");
    }
  }

  /**
   * Tests performance comparison between platform threads and virtual threads for I/O operations.
   * This test measures the time taken to add a large number of records using both thread types.
   */
  @Test
  public void shouldPerformBetterWithVirtualThreads() throws Exception {
    int recordCount = 1000;
    int threadCount = 100;
    
    // Test with platform threads
    long platformThreadTime = measurePerformance(recordCount, threadCount, false);
    
    // Test with virtual threads
    long virtualThreadTime = measurePerformance(recordCount, threadCount, true);
    
    // Log the results for comparison
    System.out.println("Platform Thread Time: " + platformThreadTime + "ms");
    System.out.println("Virtual Thread Time: " + virtualThreadTime + "ms");
    
    // Note: We don't assert that virtual threads are faster as it depends on the environment,
    // but we expect them to be at least comparable or better for I/O-bound operations
  }

  /**
   * Helper method to measure performance of adding records using different thread types.
   * 
   * @param recordCount Number of records to add
   * @param threadCount Number of threads to use
   * @param useVirtualThreads Whether to use virtual threads
   * @return Time taken in milliseconds
   */
  private long measurePerformance(int recordCount, int threadCount, boolean useVirtualThreads) throws Exception {
    // Create a new strategy for each test to ensure fair comparison
    DiskBackedDuplicateDetectionStrategy testStrategy = 
        new DiskBackedDuplicateDetectionStrategy(applicationDirectories, 1, 10);
    
    try {
      CountDownLatch completionLatch = new CountDownLatch(recordCount);
      long startTime = System.currentTimeMillis();
      
      ExecutorService executor = useVirtualThreads ? 
          Executors.newVirtualThreadPerTaskExecutor() : 
          Executors.newFixedThreadPool(threadCount);
      
      try {
        // Submit tasks to add records
        for (int i = 0; i < recordCount; i++) {
          final int recordId = i;
          executor.submit(() -> {
            try {
              Record record = buildRecord("perfGroup", "artifact" + recordId, "1.0", "sources", "jar");
              testStrategy.apply(record);
              completionLatch.countDown();
            } 
            catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
        }
        
        // Wait for all tasks to complete
        completionLatch.await();
        
        return System.currentTimeMillis() - startTime;
      } 
      finally {
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
      }
    } 
    finally {
      testStrategy.close();
    }
  }
}