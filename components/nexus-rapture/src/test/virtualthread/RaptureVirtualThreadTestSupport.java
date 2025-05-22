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
package org.sonatype.nexus.rapture.virtualthread;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base support class for virtual thread testing in the nexus-rapture component.
 * <p>
 * This class provides utilities for creating virtual thread executors, measuring performance metrics,
 * comparing platform threads vs. virtual threads, and detecting thread pinning issues.
 * <p>
 * Virtual threads are a Java 21 feature that enables high-throughput concurrent applications by providing
 * lightweight threads that are managed by the JVM rather than the operating system.
 *
 * @since 3.60
 */
public abstract class RaptureVirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(RaptureVirtualThreadTestSupport.class);

  /**
   * System property to enable virtual thread testing.
   */
  public static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "test.virtual.threads";

  /**
   * Default timeout for operations in seconds.
   */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Checks if virtual thread testing is enabled via system property.
   *
   * @return true if virtual thread testing is enabled
   */
  public static boolean isVirtualThreadsEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_ENABLED_PROPERTY);
  }

  /**
   * Skip the test if virtual threads are not enabled.
   */
  public static void skipIfVirtualThreadsDisabled() {
    Assumptions.assumeTrue(isVirtualThreadsEnabled(), 
        "Test skipped because virtual threads are not enabled. Enable with -D" + VIRTUAL_THREADS_ENABLED_PROPERTY + "=true");
  }

  /**
   * Creates a platform thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return a platform thread factory
   */
  public static ThreadFactory platformThreadFactory(String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = Thread.ofPlatform().factory().newThread(r);
      thread.setName(namePrefix + "-" + counter.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Creates a virtual thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return a virtual thread factory
   */
  public static ThreadFactory virtualThreadFactory(String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = Thread.ofVirtual().factory().newThread(r);
      thread.setName(namePrefix + "-" + counter.incrementAndGet());
      return thread;
    };
  }

  /**
   * Creates an executor service using platform threads.
   *
   * @param namePrefix the prefix for thread names
   * @param nThreads the number of threads in the pool
   * @return an executor service using platform threads
   */
  public static ExecutorService platformThreadExecutor(String namePrefix, int nThreads) {
    return Executors.newFixedThreadPool(nThreads, platformThreadFactory(namePrefix));
  }

  /**
   * Creates an executor service using virtual threads.
   *
   * @param namePrefix the prefix for thread names
   * @return an executor service using virtual threads
   */
  public static ExecutorService virtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }

  /**
   * Creates an appropriate executor service based on whether virtual threads are enabled.
   *
   * @param namePrefix the prefix for thread names
   * @param nThreads the number of threads to use if platform threads are selected
   * @return an executor service using either virtual or platform threads
   */
  public static ExecutorService createExecutor(String namePrefix, int nThreads) {
    return isVirtualThreadsEnabled() ?
        virtualThreadExecutor(namePrefix) :
        platformThreadExecutor(namePrefix, nThreads);
  }

  /**
   * Performance metrics for an operation.
   */
  public static class PerformanceMetrics {
    private final long totalOperations;
    private final long totalDurationMs;
    private final long maxDurationMs;
    private final long minDurationMs;
    private final double operationsPerSecond;
    private final double avgDurationMs;
    private final long errorCount;

    public PerformanceMetrics(long totalOperations, long totalDurationMs, long maxDurationMs, 
                             long minDurationMs, long errorCount) {
      this.totalOperations = totalOperations;
      this.totalDurationMs = totalDurationMs;
      this.maxDurationMs = maxDurationMs;
      this.minDurationMs = minDurationMs;
      this.errorCount = errorCount;
      this.operationsPerSecond = totalOperations > 0 ? 
          (double) totalOperations / (totalDurationMs / 1000.0) : 0;
      this.avgDurationMs = totalOperations > 0 ? 
          (double) totalDurationMs / totalOperations : 0;
    }

    public long getTotalOperations() {
      return totalOperations;
    }

    public long getTotalDurationMs() {
      return totalDurationMs;
    }

    public long getMaxDurationMs() {
      return maxDurationMs;
    }

    public long getMinDurationMs() {
      return minDurationMs;
    }

    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }

    public double getAvgDurationMs() {
      return avgDurationMs;
    }

    public long getErrorCount() {
      return errorCount;
    }

    @Override
    public String toString() {
      return String.format(
          "PerformanceMetrics{totalOperations=%d, totalDurationMs=%d, maxDurationMs=%d, minDurationMs=%d, " +
          "operationsPerSecond=%.2f, avgDurationMs=%.2f, errorCount=%d}",
          totalOperations, totalDurationMs, maxDurationMs, minDurationMs, 
          operationsPerSecond, avgDurationMs, errorCount);
    }
  }

  /**
   * Executes an operation concurrently with the specified number of threads and measures performance.
   *
   * @param operation the operation to execute
   * @param concurrency the number of concurrent executions
   * @param iterations the number of iterations per thread
   * @param namePrefix the prefix for thread names
   * @return performance metrics for the operation
   */
  public static PerformanceMetrics measureConcurrentPerformance(
      Runnable operation,
      int concurrency,
      int iterations,
      String namePrefix) {
    
    return measureConcurrentPerformance(
        () -> {
          operation.run();
          return null;
        },
        concurrency,
        iterations,
        namePrefix);
  }

  /**
   * Executes an operation concurrently with the specified number of threads and measures performance.
   *
   * @param <T> the type of result returned by the operation
   * @param operation the operation to execute
   * @param concurrency the number of concurrent executions
   * @param iterations the number of iterations per thread
   * @param namePrefix the prefix for thread names
   * @return performance metrics for the operation
   */
  public static <T> PerformanceMetrics measureConcurrentPerformance(
      Callable<T> operation,
      int concurrency,
      int iterations,
      String namePrefix) {

    ExecutorService executor = createExecutor(namePrefix, concurrency);
    CountDownLatch latch = new CountDownLatch(concurrency * iterations);
    AtomicLong totalDuration = new AtomicLong(0);
    AtomicLong maxDuration = new AtomicLong(0);
    AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);
    AtomicInteger errorCount = new AtomicInteger(0);

    long startTime = System.currentTimeMillis();

    try {
      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < concurrency; i++) {
        for (int j = 0; j < iterations; j++) {
          CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            try {
              long operationStart = System.currentTimeMillis();
              operation.call();
              long operationDuration = System.currentTimeMillis() - operationStart;
              
              totalDuration.addAndGet(operationDuration);
              updateMax(maxDuration, operationDuration);
              updateMin(minDuration, operationDuration);
            }
            catch (Exception e) {
              log.error("Error executing operation", e);
              errorCount.incrementAndGet();
            }
            finally {
              latch.countDown();
            }
          }, executor);
          futures.add(future);
        }
      }

      // Wait for completion
      boolean completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      if (!completed) {
        log.warn("Timeout waiting for operations to complete");
      }

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
          .join();

    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.error("Interrupted while waiting for operations to complete", e);
    }
    finally {
      executor.shutdown();
    }

    long totalTime = System.currentTimeMillis() - startTime;
    long totalOps = concurrency * iterations - errorCount.get();
    long minDurationValue = minDuration.get() == Long.MAX_VALUE ? 0 : minDuration.get();

    return new PerformanceMetrics(
        totalOps,
        totalDuration.get(),
        maxDuration.get(),
        minDurationValue,
        errorCount.get());
  }

  /**
   * Compares the performance of an operation using both platform and virtual threads.
   *
   * @param operation the operation to execute
   * @param concurrency the number of concurrent executions
   * @param iterations the number of iterations per thread
   * @param namePrefix the prefix for thread names
   * @return a pair of performance metrics for platform and virtual threads
   */
  public static ThreadPerformanceComparison compareThreadPerformance(
      Runnable operation,
      int concurrency,
      int iterations,
      String namePrefix) {

    // Skip if virtual threads are not enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Virtual threads not enabled, skipping comparison");
      return new ThreadPerformanceComparison(null, null);
    }

    // Force the system property to false temporarily for platform thread test
    String originalProperty = System.getProperty(VIRTUAL_THREADS_ENABLED_PROPERTY);
    System.setProperty(VIRTUAL_THREADS_ENABLED_PROPERTY, "false");

    log.info("Measuring platform thread performance...");
    PerformanceMetrics platformMetrics = measureConcurrentPerformance(
        operation, concurrency, iterations, namePrefix + "-platform");
    log.info("Platform thread performance: {}", platformMetrics);

    // Restore the system property for virtual thread test
    System.setProperty(VIRTUAL_THREADS_ENABLED_PROPERTY, "true");

    log.info("Measuring virtual thread performance...");
    PerformanceMetrics virtualMetrics = measureConcurrentPerformance(
        operation, concurrency, iterations, namePrefix + "-virtual");
    log.info("Virtual thread performance: {}", virtualMetrics);

    // Restore original property value
    if (originalProperty != null) {
      System.setProperty(VIRTUAL_THREADS_ENABLED_PROPERTY, originalProperty);
    }
    else {
      System.clearProperty(VIRTUAL_THREADS_ENABLED_PROPERTY);
    }

    return new ThreadPerformanceComparison(platformMetrics, virtualMetrics);
  }

  /**
   * Executes an operation with a timeout and returns the result.
   *
   * @param <T> the type of result returned by the operation
   * @param operation the operation to execute
   * @param timeout the timeout duration
   * @return the result of the operation
   * @throws RuntimeException if the operation times out or throws an exception
   */
  public static <T> T executeWithTimeout(Supplier<T> operation, Duration timeout) {
    ExecutorService executor = isVirtualThreadsEnabled() ?
        virtualThreadExecutor("timeout-executor") :
        platformThreadExecutor("timeout-executor", 1);

    try {
      return CompletableFuture
          .supplyAsync(operation, executor)
          .orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
          .join();
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Executes an operation with a timeout.
   *
   * @param operation the operation to execute
   * @param timeout the timeout duration
   * @throws RuntimeException if the operation times out or throws an exception
   */
  public static void executeWithTimeout(Runnable operation, Duration timeout) {
    executeWithTimeout(() -> {
      operation.run();
      return null;
    }, timeout);
  }

  /**
   * Detects if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Detects if thread pinning is occurring in the current thread.
   * Thread pinning happens when a virtual thread is pinned to its carrier thread,
   * which can happen during synchronized blocks or when calling native methods.
   *
   * @return true if thread pinning is detected
   */
  public static boolean isThreadPinned() {
    if (!isVirtualThread()) {
      return false; // Only virtual threads can be pinned
    }
    
    // This is a heuristic approach - in a real implementation, you would use
    // JDK internal APIs or JFR events to detect pinning accurately
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (StackTraceElement element : stackTrace) {
      // Look for indicators of pinning in the stack trace
      if (element.getMethodName().contains("synchronized") ||
          element.getClassName().contains("native")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Container for comparing performance between platform and virtual threads.
   */
  public static class ThreadPerformanceComparison {
    private final PerformanceMetrics platformMetrics;
    private final PerformanceMetrics virtualMetrics;

    public ThreadPerformanceComparison(PerformanceMetrics platformMetrics, PerformanceMetrics virtualMetrics) {
      this.platformMetrics = platformMetrics;
      this.virtualMetrics = virtualMetrics;
    }

    public PerformanceMetrics getPlatformMetrics() {
      return platformMetrics;
    }

    public PerformanceMetrics getVirtualMetrics() {
      return virtualMetrics;
    }

    /**
     * Calculates the throughput improvement ratio of virtual threads compared to platform threads.
     *
     * @return the ratio of virtual thread throughput to platform thread throughput, or 0 if metrics are not available
     */
    public double getThroughputImprovement() {
      if (platformMetrics == null || virtualMetrics == null || platformMetrics.getOperationsPerSecond() == 0) {
        return 0;
      }
      return virtualMetrics.getOperationsPerSecond() / platformMetrics.getOperationsPerSecond();
    }

    /**
     * Calculates the latency improvement ratio of virtual threads compared to platform threads.
     * Lower values are better for latency, so the ratio is inverted.
     *
     * @return the ratio of platform thread latency to virtual thread latency, or 0 if metrics are not available
     */
    public double getLatencyImprovement() {
      if (platformMetrics == null || virtualMetrics == null || virtualMetrics.getAvgDurationMs() == 0) {
        return 0;
      }
      return platformMetrics.getAvgDurationMs() / virtualMetrics.getAvgDurationMs();
    }

    @Override
    public String toString() {
      if (platformMetrics == null || virtualMetrics == null) {
        return "ThreadPerformanceComparison{comparison not available}";
      }
      
      return String.format(
          "ThreadPerformanceComparison{\n" +
          "  Platform: %s\n" +
          "  Virtual: %s\n" +
          "  Throughput improvement: %.2fx\n" +
          "  Latency improvement: %.2fx\n" +
          "}",
          platformMetrics, virtualMetrics, getThroughputImprovement(), getLatencyImprovement());
    }
  }

  // Helper methods

  private static void updateMax(AtomicLong maxValue, long newValue) {
    long currentMax;
    do {
      currentMax = maxValue.get();
      if (newValue <= currentMax) {
        break;
      }
    } while (!maxValue.compareAndSet(currentMax, newValue));
  }

  private static void updateMin(AtomicLong minValue, long newValue) {
    long currentMin;
    do {
      currentMin = minValue.get();
      if (newValue >= currentMin) {
        break;
      }
    } while (!minValue.compareAndSet(currentMin, newValue));
  }
}