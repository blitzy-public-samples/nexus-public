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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.maven.index.reader.Record;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Virtual Thread-specific test support for duplicate detection strategies.
 * Extends the original test support class with Virtual Thread capabilities for
 * testing concurrent operations and performance characteristics.
 * 
 * <p>This class leverages Java 21 Virtual Threads to provide enhanced testing capabilities
 * for duplicate detection strategies under high concurrency. It includes methods for
 * creating Virtual Thread executors, detecting thread pinning issues, and comparing
 * performance between platform threads and virtual threads.</p>
 *
 * @since 3.60
 * @see java.lang.Thread#ofVirtual()
 * @see java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()
 */
public class DuplicateDetectionStrategyTestSupport
    extends org.sonatype.nexus.repository.maven.internal.filter.DuplicateDetectionStrategyTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(DuplicateDetectionStrategyTestSupport.class);
  
  // Using String Templates (Java 21 feature) for more readable log messages
  private static void logInfo(String message, Object... args) {
    if (log.isInfoEnabled()) {
      log.info(STR."\{message}", args);
    }
  }
  
  private static final int DEFAULT_CONCURRENT_THREADS = 10;
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Creates a Virtual Thread executor service optimized for I/O-bound operations.
   * 
   * <p>Virtual Threads are particularly well-suited for I/O-bound operations like
   * repository access and network operations. This executor creates a new virtual
   * thread for each submitted task, which is ideal for testing duplicate detection
   * strategies under high concurrency.</p>
   * 
   * @return an executor service that creates a new virtual thread for each task
   * @since Java 21
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates a Virtual Thread factory for custom thread creation.
   * 
   * @return a thread factory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Verifies duplicate detection with concurrent Virtual Threads.
   * This method tests the strategy under high concurrency using Virtual Threads,
   * which is particularly important for repository operations that may experience
   * high load in production environments.
   *
   * @param strategy the duplicate detection strategy to test
   * @param concurrentThreads the number of concurrent threads to use
   * @throws Exception if an error occurs during testing
   */
  public void verifyDuplicateDetectionWithVirtualThreads(
      final DuplicateDetectionStrategy<Record> strategy,
      final int concurrentThreads) throws Exception {
    
    int expectedUnique = 3;
    AtomicInteger uniqueCount = new AtomicInteger(0);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentThreads);
    AtomicBoolean hasErrors = new AtomicBoolean(false);
    
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create concurrent tasks
      for (int t = 0; t < concurrentThreads; t++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // First try to add unique records
            for (int i = 1; i <= expectedUnique; i++) {
              Record uniqueRecord = buildRecord("group1", "artifact" + i, "1.0", "sources", "jar");
              if (strategy.apply(uniqueRecord)) {
                uniqueCount.incrementAndGet();
              }
            }
            
            // Then try to add duplicate records
            for (int i = 0; i < 5; i++) {
              Record duplicateRecord = buildRecord("group1", "artifact" + expectedUnique, "1.0", "sources", "jar");
              assertFalse("Duplicate record should be rejected", strategy.apply(duplicateRecord));
            }
          }
          catch (Exception e) {
            log.error(STR."Error in virtual thread test on thread \{Thread.currentThread().getName()}", e);
            hasErrors.set(true);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for completion with timeout
      if (!completionLatch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        fail(STR."Test timed out after \{DEFAULT_TIMEOUT_SECONDS} seconds");
      }
      
      // Check for errors
      assertFalse("Errors occurred during concurrent execution", hasErrors.get());
      
      // Verify that exactly the expected number of unique records were accepted
      assertEquals(STR."Expected exactly \{expectedUnique} unique records", expectedUnique, uniqueCount.get());
    }
    
    strategy.close();
  }
  
  /**
   * Convenience method that uses the default number of concurrent threads.
   *
   * @param strategy the duplicate detection strategy to test
   * @throws Exception if an error occurs during testing
   */
  public void verifyDuplicateDetectionWithVirtualThreads(final DuplicateDetectionStrategy<Record> strategy) throws Exception {
    verifyDuplicateDetectionWithVirtualThreads(strategy, DEFAULT_CONCURRENT_THREADS);
  }
  
  /**
   * Detects potential thread pinning issues when using the strategy.
   * Thread pinning occurs when a Virtual Thread cannot unmount from its carrier thread,
   * typically due to synchronized blocks or native methods, reducing the benefits of Virtual Threads.
   * 
   * <p>Thread pinning can significantly reduce the performance benefits of Virtual Threads
   * by preventing them from yielding the carrier thread during blocking operations. This method
   * helps identify potential pinning issues in the duplicate detection strategy implementation.</p>
   * 
   * <p>Common causes of thread pinning include:</p>
   * <ul>
   *   <li>Use of synchronized blocks or methods</li>
   *   <li>Calling native methods</li>
   *   <li>Using foreign function interfaces</li>
   * </ul>
   *
   * @param strategy the duplicate detection strategy to test
   * @return true if thread pinning is detected, false otherwise
   * @since Java 21
   */
  public boolean detectThreadPinning(final DuplicateDetectionStrategy<Record> strategy) {
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    int testThreads = Runtime.getRuntime().availableProcessors() * 2;
    CountDownLatch latch = new CountDownLatch(testThreads);
    
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      // Start more threads than available processors
      for (int i = 0; i < testThreads; i++) {
        executor.submit(() -> {
          try {
            long startTime = System.nanoTime();
            
            // Perform operations that might cause pinning
            for (int j = 0; j < 100; j++) {
              Record record = buildRecord("group1", "artifact" + j, "1.0", "sources", "jar");
              strategy.apply(record);
              
              // Check if this thread is taking too long (potential pinning)
              if (System.nanoTime() - startTime > TimeUnit.MILLISECONDS.toNanos(500)) {
                log.warn(STR."Potential thread pinning detected in duplicate detection strategy on thread \{Thread.currentThread().getName()}");
                pinningDetected.set(true);
                break;
              }
            }
          }
          catch (Exception e) {
            log.error("Error while testing for thread pinning", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      try {
        latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.error("Thread pinning test was interrupted", e);
      }
    }
    
    return pinningDetected.get();
  }
  
  /**
   * Compares performance between platform threads and virtual threads.
   * This method helps evaluate the performance benefits of using Virtual Threads
   * with the duplicate detection strategy.
   *
   * @param strategy the duplicate detection strategy to test
   * @param operations the number of operations to perform
   * @return a Duration representing the time saved by using Virtual Threads (negative if platform threads were faster)
   */
  /**
   * Compares performance between platform threads and virtual threads.
   * This method helps evaluate the performance benefits of using Virtual Threads
   * with the duplicate detection strategy.
   * 
   * <p>This is particularly useful for identifying operations that benefit most from
   * Virtual Threads and for quantifying the performance improvement in your specific environment.</p>
   *
   * @param strategy the duplicate detection strategy to test
   * @param operations the number of operations to perform
   * @return a Duration representing the time saved by using Virtual Threads (negative if platform threads were faster)
   * @since Java 21
   */
  public Duration compareThreadPerformance(final DuplicateDetectionStrategy<Record> strategy, final int operations) {
    // Measure platform thread performance
    long platformThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
        runConcurrentOperations(executor, strategy, operations);
      }
      return null;
    });
    
    // Measure virtual thread performance
    long virtualThreadTime = measureExecutionTime(() -> {
      try (ExecutorService executor = createVirtualThreadExecutor()) {
        runConcurrentOperations(executor, strategy, operations);
      }
      return null;
    });
    
    logInfo("Performance comparison: Platform threads: {0}ms, Virtual threads: {1}ms", 
        platformThreadTime, virtualThreadTime);
    
    return Duration.ofMillis(platformThreadTime - virtualThreadTime);
  }
  
  /**
   * Runs concurrent operations using the provided executor service.
   * 
   * @param executor the executor service to use
   * @param strategy the duplicate detection strategy to test
   * @param operations the total number of operations to perform
   */
  private void runConcurrentOperations(
      final ExecutorService executor,
      final DuplicateDetectionStrategy<Record> strategy,
      final int operations) {
    
    int threads = Runtime.getRuntime().availableProcessors() * 2;
    int opsPerThread = operations / threads;
    CountDownLatch latch = new CountDownLatch(threads);
    
    for (int t = 0; t < threads; t++) {
      final int threadNum = t;
      executor.submit(() -> {
        try {
          for (int i = 0; i < opsPerThread; i++) {
            Record record = buildRecord("group" + threadNum, "artifact" + i, "1.0", "sources", "jar");
            strategy.apply(record);
          }
        }
        catch (Exception e) {
          log.error(STR."Error during performance test on thread \{Thread.currentThread().getName()}", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    try {
      if (!latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn(STR."Performance test timed out after \{DEFAULT_TIMEOUT_SECONDS} seconds");
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error(STR."Performance test was interrupted on thread \{Thread.currentThread().getName()}", e);
    }
  }
  
  /**
   * Measures the execution time of a supplier function.
   * 
   * @param supplier the function to measure
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(final Supplier<Void> supplier) {
    long startTime = System.currentTimeMillis();
    supplier.get();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Builds a record for testing, making the method protected for use in subclasses.
   * This overrides the private method in the parent class.
   */
  protected Record buildRecord(final String g,
                             final String a,
                             final String v,
                             final String c,
                             final String e)
  {
    return super.buildRecord(g, a, v, c, e);
  }
}