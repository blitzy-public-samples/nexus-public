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
package virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Support class for virtual thread testing in the cleanup component.
 * <p>
 * Provides utilities for creating and comparing platform and virtual thread executors,
 * measuring performance metrics, and running cleanup operations concurrently with different thread types.
 * This class serves as the foundation for all cleanup component virtual thread tests.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class CleanupVirtualThreadTestSupport
    extends TestSupport
{
  /**
   * System property to enable/disable virtual thread tests.
   */
  public static final String VIRTUAL_THREAD_TESTS_ENABLED = "test.virtual.threads";

  /**
   * Default timeout for cleanup operations in seconds.
   */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Default number of concurrent tasks for performance testing.
   */
  private static final int DEFAULT_CONCURRENT_TASKS = 100;

  /**
   * Creates a platform thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a platform thread factory
   */
  public static ThreadFactory createPlatformThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = Thread.ofPlatform().name(namePrefix + "-" + counter.incrementAndGet()).build();
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Creates a virtual thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a virtual thread factory
   */
  public static ThreadFactory createVirtualThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> Thread.ofVirtual().name(namePrefix + "-" + counter.incrementAndGet()).build();
  }

  /**
   * Creates a platform thread executor service with the specified number of threads.
   *
   * @param namePrefix the prefix to use for thread names
   * @param threadCount the number of threads in the pool
   * @return a platform thread executor service
   */
  public static ExecutorService createPlatformThreadExecutor(final String namePrefix, final int threadCount) {
    return Executors.newFixedThreadPool(threadCount, createPlatformThreadFactory(namePrefix));
  }

  /**
   * Creates a virtual thread per task executor service.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a virtual thread executor service
   */
  public static ExecutorService createVirtualThreadExecutor(final String namePrefix) {
    return Executors.newThreadPerTaskExecutor(createVirtualThreadFactory(namePrefix));
  }

  /**
   * Checks if virtual thread tests are enabled.
   * Tests can use this to skip execution when virtual threads are not enabled.
   *
   * @return true if virtual thread tests are enabled
   */
  public static boolean isVirtualThreadTestsEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREAD_TESTS_ENABLED);
  }

  /**
   * Assumes that virtual thread tests are enabled, skipping the test if they are not.
   * This should be called at the beginning of tests that require virtual threads.
   */
  public static void assumeVirtualThreadsEnabled() {
    Assumptions.assumeTrue(isVirtualThreadTestsEnabled(), 
        "Virtual thread tests are disabled. Enable with -D" + VIRTUAL_THREAD_TESTS_ENABLED + "=true");
  }

  /**
   * Performance measurement result containing execution metrics.
   */
  public static class PerformanceResult {
    private final long totalDurationMs;
    private final long operationCount;
    private final String threadType;

    public PerformanceResult(long totalDurationMs, long operationCount, String threadType) {
      this.totalDurationMs = totalDurationMs;
      this.operationCount = operationCount;
      this.threadType = threadType;
    }

    /**
     * Gets the total duration in milliseconds.
     *
     * @return the total duration in milliseconds
     */
    public long getTotalDurationMs() {
      return totalDurationMs;
    }

    /**
     * Gets the number of operations performed.
     *
     * @return the number of operations
     */
    public long getOperationCount() {
      return operationCount;
    }

    /**
     * Gets the thread type used for the test ("platform" or "virtual").
     *
     * @return the thread type
     */
    public String getThreadType() {
      return threadType;
    }

    /**
     * Gets the operations per second.
     *
     * @return the operations per second
     */
    public double getOperationsPerSecond() {
      return operationCount * 1000.0 / totalDurationMs;
    }

    /**
     * Gets the average duration per operation in milliseconds.
     *
     * @return the average duration per operation
     */
    public double getAverageDurationPerOperationMs() {
      return (double) totalDurationMs / operationCount;
    }

    @Override
    public String toString() {
      return String.format("%s threads: %d operations in %d ms (%.2f ops/sec, %.2f ms/op)",
          threadType, operationCount, totalDurationMs, getOperationsPerSecond(), getAverageDurationPerOperationMs());
    }
  }

  /**
   * Measures the performance of a cleanup operation using platform threads.
   *
   * @param operation the operation to measure
   * @param concurrentTasks the number of concurrent tasks to execute
   * @return the performance result
   */
  public PerformanceResult measurePlatformThreadPerformance(
      final Runnable operation,
      final int concurrentTasks) 
  {
    return measurePerformance(
        operation,
        concurrentTasks,
        () -> createPlatformThreadExecutor("cleanup-platform", concurrentTasks),
        "platform");
  }

  /**
   * Measures the performance of a cleanup operation using platform threads with default concurrency.
   *
   * @param operation the operation to measure
   * @return the performance result
   */
  public PerformanceResult measurePlatformThreadPerformance(final Runnable operation) {
    return measurePlatformThreadPerformance(operation, DEFAULT_CONCURRENT_TASKS);
  }

  /**
   * Measures the performance of a cleanup operation using virtual threads.
   *
   * @param operation the operation to measure
   * @param concurrentTasks the number of concurrent tasks to execute
   * @return the performance result
   */
  public PerformanceResult measureVirtualThreadPerformance(
      final Runnable operation,
      final int concurrentTasks) 
  {
    assumeVirtualThreadsEnabled();
    return measurePerformance(
        operation,
        concurrentTasks,
        () -> createVirtualThreadExecutor("cleanup-virtual"),
        "virtual");
  }

  /**
   * Measures the performance of a cleanup operation using virtual threads with default concurrency.
   *
   * @param operation the operation to measure
   * @return the performance result
   */
  public PerformanceResult measureVirtualThreadPerformance(final Runnable operation) {
    return measureVirtualThreadPerformance(operation, DEFAULT_CONCURRENT_TASKS);
  }

  /**
   * Compares the performance of platform threads vs virtual threads for a cleanup operation.
   *
   * @param operation the operation to compare
   * @param concurrentTasks the number of concurrent tasks to execute
   * @return a list containing both performance results (platform first, virtual second)
   */
  public List<PerformanceResult> compareThreadPerformance(
      final Runnable operation,
      final int concurrentTasks) 
  {
    assumeVirtualThreadsEnabled();
    List<PerformanceResult> results = new ArrayList<>();
    
    // Run platform threads first
    results.add(measurePlatformThreadPerformance(operation, concurrentTasks));
    
    // Then run virtual threads
    results.add(measureVirtualThreadPerformance(operation, concurrentTasks));
    
    // Log the comparison
    log.info("Performance comparison:");
    results.forEach(result -> log.info("  " + result));
    
    double speedup = results.get(0).getAverageDurationPerOperationMs() / 
                     results.get(1).getAverageDurationPerOperationMs();
    log.info("  Virtual thread speedup: {:.2f}x", speedup);
    
    return results;
  }

  /**
   * Compares the performance of platform threads vs virtual threads with default concurrency.
   *
   * @param operation the operation to compare
   * @return a list containing both performance results (platform first, virtual second)
   */
  public List<PerformanceResult> compareThreadPerformance(final Runnable operation) {
    return compareThreadPerformance(operation, DEFAULT_CONCURRENT_TASKS);
  }

  /**
   * Executes a cleanup operation concurrently using the specified executor service.
   *
   * @param operation the operation to execute
   * @param concurrentTasks the number of concurrent tasks to execute
   * @param executorSupplier supplier for the executor service
   * @param timeoutSeconds maximum time to wait for completion in seconds
   * @return true if all tasks completed successfully, false if timeout occurred
   * @throws Exception if an error occurs during execution
   */
  public boolean executeCleanupConcurrently(
      final Runnable operation,
      final int concurrentTasks,
      final Supplier<ExecutorService> executorSupplier,
      final int timeoutSeconds) throws Exception 
  {
    ExecutorService executor = executorSupplier.get();
    try {
      CountDownLatch latch = new CountDownLatch(concurrentTasks);
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < concurrentTasks; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            operation.run();
          } finally {
            latch.countDown();
          }
        }, executor);
        futures.add(future);
      }
      
      // Wait for completion or timeout
      boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
      
      // Check for exceptions
      if (completed) {
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      }
      
      return completed;
    } finally {
      executor.shutdown();
      executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
    }
  }

  /**
   * Executes a cleanup operation concurrently using platform threads.
   *
   * @param operation the operation to execute
   * @param concurrentTasks the number of concurrent tasks to execute
   * @param timeoutSeconds maximum time to wait for completion in seconds
   * @return true if all tasks completed successfully, false if timeout occurred
   * @throws Exception if an error occurs during execution
   */
  public boolean executeCleanupWithPlatformThreads(
      final Runnable operation,
      final int concurrentTasks,
      final int timeoutSeconds) throws Exception 
  {
    return executeCleanupConcurrently(
        operation,
        concurrentTasks,
        () -> createPlatformThreadExecutor("cleanup-platform", concurrentTasks),
        timeoutSeconds);
  }

  /**
   * Executes a cleanup operation concurrently using platform threads with default timeout.
   *
   * @param operation the operation to execute
   * @param concurrentTasks the number of concurrent tasks to execute
   * @return true if all tasks completed successfully, false if timeout occurred
   * @throws Exception if an error occurs during execution
   */
  public boolean executeCleanupWithPlatformThreads(
      final Runnable operation,
      final int concurrentTasks) throws Exception 
  {
    return executeCleanupWithPlatformThreads(operation, concurrentTasks, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Executes a cleanup operation concurrently using virtual threads.
   *
   * @param operation the operation to execute
   * @param concurrentTasks the number of concurrent tasks to execute
   * @param timeoutSeconds maximum time to wait for completion in seconds
   * @return true if all tasks completed successfully, false if timeout occurred
   * @throws Exception if an error occurs during execution
   */
  public boolean executeCleanupWithVirtualThreads(
      final Runnable operation,
      final int concurrentTasks,
      final int timeoutSeconds) throws Exception 
  {
    assumeVirtualThreadsEnabled();
    return executeCleanupConcurrently(
        operation,
        concurrentTasks,
        () -> createVirtualThreadExecutor("cleanup-virtual"),
        timeoutSeconds);
  }

  /**
   * Executes a cleanup operation concurrently using virtual threads with default timeout.
   *
   * @param operation the operation to execute
   * @param concurrentTasks the number of concurrent tasks to execute
   * @return true if all tasks completed successfully, false if timeout occurred
   * @throws Exception if an error occurs during execution
   */
  public boolean executeCleanupWithVirtualThreads(
      final Runnable operation,
      final int concurrentTasks) throws Exception 
  {
    return executeCleanupWithVirtualThreads(operation, concurrentTasks, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Internal method to measure performance of an operation using the specified executor.
   *
   * @param operation the operation to measure
   * @param concurrentTasks the number of concurrent tasks to execute
   * @param executorSupplier supplier for the executor service
   * @param threadType the type of threads being used (for reporting)
   * @return the performance result
   */
  private PerformanceResult measurePerformance(
      final Runnable operation,
      final int concurrentTasks,
      final Supplier<ExecutorService> executorSupplier,
      final String threadType) 
  {
    ExecutorService executor = executorSupplier.get();
    try {
      // Create tasks
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int i = 0; i < concurrentTasks; i++) {
        tasks.add(() -> {
          operation.run();
          return null;
        });
      }
      
      // Measure execution time
      long startTime = System.currentTimeMillis();
      executor.invokeAll(tasks);
      long endTime = System.currentTimeMillis();
      
      return new PerformanceResult(endTime - startTime, concurrentTasks, threadType);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Performance measurement interrupted", e);
    } finally {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          log.warn("Executor did not terminate in the specified time");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while waiting for executor shutdown", e);
      }
    }
  }
}