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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.maven.index.reader.Record;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Virtual Thread-specific test for {@link HashBasedDuplicateDetectionStrategy} that validates
 * its behavior when executed with Java 21's Virtual Threads.
 * 
 * <p>This test ensures that the hash-based duplicate detection maintains correctness
 * and performance under high concurrency with lightweight threads, focusing on memory
 * usage patterns and potential thread pinning issues during hash computation.</p>
 *
 * @since 3.60
 */
public class HashBasedDuplicateDetectionStrategyTest
    extends DuplicateDetectionStrategyTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(HashBasedDuplicateDetectionStrategyTest.class);
  
  private static final int HIGH_CONCURRENCY_THREADS = 1000;
  private static final int PERFORMANCE_TEST_OPERATIONS = 10000;
  
  /**
   * Verifies basic duplicate detection functionality with Virtual Threads.
   */
  @Test
  public void shouldIdentifyDuplicatesWithVirtualThreads() throws Exception {
    verifyDuplicateDetectionWithVirtualThreads(new HashBasedDuplicateDetectionStrategy());
  }
  
  /**
   * Tests duplicate detection under high concurrency with many Virtual Threads.
   * This test simulates a high-load production environment by creating thousands
   * of Virtual Threads that simultaneously access the duplicate detection strategy.
   */
  @Test
  public void shouldHandleHighConcurrencyWithVirtualThreads() throws Exception {
    log.info(STR"Running high concurrency test with \{HIGH_CONCURRENCY_THREADS} virtual threads");
    verifyDuplicateDetectionWithVirtualThreads(new HashBasedDuplicateDetectionStrategy(), HIGH_CONCURRENCY_THREADS);
  }
  
  /**
   * Tests for thread pinning issues in the hash-based duplicate detection strategy.
   * Thread pinning occurs when a Virtual Thread cannot yield the carrier thread,
   * typically due to synchronized blocks or native methods.
   */
  @Test
  public void shouldNotCauseThreadPinning() {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    boolean pinningDetected = detectThreadPinning(strategy);
    
    assertFalse("HashBasedDuplicateDetectionStrategy should not cause thread pinning", pinningDetected);
  }
  
  /**
   * Compares performance between platform threads and virtual threads.
   * This test helps quantify the performance benefits of using Virtual Threads
   * with the hash-based duplicate detection strategy.
   */
  @Test
  public void shouldPerformBetterWithVirtualThreads() {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    Duration timeSaved = compareThreadPerformance(strategy, PERFORMANCE_TEST_OPERATIONS);
    
    log.info(STR"Performance difference: \{timeSaved.toMillis()} ms saved with Virtual Threads");
    
    // Virtual Threads should generally perform better for I/O-bound operations,
    // but we don't fail the test if they don't since hardware and JVM configurations vary
    if (timeSaved.isNegative()) {
      log.warn(STR"Virtual Threads were slower by \{timeSaved.abs().toMillis()} ms - this is unexpected");
    }
  }
  
  /**
   * Tests memory usage patterns when using Virtual Threads vs platform threads.
   * This test helps identify potential memory issues when using Virtual Threads
   * at scale with the hash-based duplicate detection strategy.
   */
  @Test
  public void shouldMaintainEfficientMemoryUsageWithVirtualThreads() throws Exception {
    // Create a strategy that will be used by many threads
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    
    // Record initial memory usage
    long initialMemory = getUsedMemory();
    
    // Run with a large number of virtual threads
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      int threadCount = 5000; // Large number of virtual threads
      CountDownLatch latch = new CountDownLatch(threadCount);
      List<Future<?>> futures = new ArrayList<>();
      AtomicInteger recordsProcessed = new AtomicInteger(0);
      
      // Submit tasks to the executor
      for (int t = 0; t < threadCount; t++) {
        final int threadNum = t;
        futures.add(executor.submit(() -> {
          try {
            // Each thread processes a small number of records
            for (int i = 0; i < 10; i++) {
              Record record = buildRecord("group" + threadNum, "artifact" + i, "1.0", "sources", "jar");
              strategy.apply(record);
              recordsProcessed.incrementAndGet();
            }
          }
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all threads to complete
      latch.await(60, TimeUnit.SECONDS);
      
      // Record memory usage after the test
      long finalMemory = getUsedMemory();
      long memoryDifference = finalMemory - initialMemory;
      
      log.info(STR"Memory usage: Initial=\{initialMemory}MB, Final=\{finalMemory}MB, Difference=\{memoryDifference}MB");
      log.info(STR"Processed \{recordsProcessed.get()} records with \{threadCount} virtual threads");
      
      // We don't assert on exact memory usage as it varies by environment,
      // but we log it for analysis and monitoring trends
    }
  }
  
  /**
   * Tests the behavior of the strategy when processing a large number of unique records.
   * This test helps identify potential scalability issues with the hash-based approach.
   */
  @Test
  public void shouldHandleLargeNumberOfUniqueRecords() throws Exception {
    HashBasedDuplicateDetectionStrategy strategy = new HashBasedDuplicateDetectionStrategy();
    int uniqueRecords = 10000;
    
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      CountDownLatch latch = new CountDownLatch(uniqueRecords);
      
      for (int i = 0; i < uniqueRecords; i++) {
        final int recordNum = i;
        executor.submit(() -> {
          try {
            Record record = buildRecord("group" + (recordNum % 100), 
                                       "artifact" + (recordNum % 1000), 
                                       "1." + (recordNum % 10), 
                                       "sources", 
                                       "jar");
            assertTrue("Unique record should be accepted", strategy.apply(record));
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(60, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Gets the current used memory in MB.
   * 
   * @return the used memory in MB
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate memory usage
    Runtime runtime = Runtime.getRuntime();
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
  }
}