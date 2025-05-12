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
package org.sonatype.nexus.repository;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import com.google.common.base.Stopwatch;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Support class for testing repository operations with virtual threads.
 * Provides utilities for creating virtual threads, detecting thread pinning,
 * and comparing performance between platform and virtual threads.
 * 
 * This class is designed to help test and validate the behavior of repository
 * operations when using Java 21's virtual threads, particularly for I/O-bound
 * operations like network requests and file system access.
 *
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends TestSupport
{
  /**
   * Creates a virtual thread factory for use in tests.
   *
   * @return a ThreadFactory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates a platform thread factory for use in tests.
   *
   * @return a ThreadFactory that creates platform threads
   */
  public static ThreadFactory platformThreadFactory() {
    return Thread.ofPlatform().factory();
  }

  /**
   * Creates an executor service that uses virtual threads.
   *
   * @return an ExecutorService that creates a new virtual thread for each task
   */
  public static ExecutorService virtualThreadExecutor() {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory());
  }

  /**
   * Creates an executor service that uses platform threads.
   *
   * @param nThreads the number of threads in the pool
   * @return an ExecutorService with a fixed number of platform threads
   */
  public static ExecutorService platformThreadExecutor(int nThreads) {
    return Executors.newFixedThreadPool(nThreads, platformThreadFactory());
  }

  /**
   * Detects if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Executes a task with both virtual and platform threads and compares their performance.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param concurrency the number of concurrent threads
   * @return a PerformanceResult containing the comparison results
   * @throws Exception if an error occurs during execution
   */
  public static PerformanceResult compareThreadPerformance(
      Runnable task,
      int iterations,
      int concurrency) throws Exception
  {
    // Measure platform thread performance
    long platformStart = System.nanoTime();
    runConcurrentTasks(task, iterations, concurrency, platformThreadExecutor(concurrency));
    long platformDuration = System.nanoTime() - platformStart;

    // Measure virtual thread performance
    long virtualStart = System.nanoTime();
    runConcurrentTasks(task, iterations, concurrency, virtualThreadExecutor());
    long virtualDuration = System.nanoTime() - virtualStart;

    return new PerformanceResult(platformDuration, virtualDuration);
  }

  /**
   * Runs a task concurrently using the provided executor service.
   *
   * @param task the task to run
   * @param iterations the number of iterations
   * @param concurrency the level of concurrency
   * @param executor the executor service to use
   * @throws Exception if an error occurs during execution
   */
  private static void runConcurrentTasks(
      Runnable task,
      int iterations,
      int concurrency,
      ExecutorService executor) throws Exception
  {
    try {
      CountDownLatch latch = new CountDownLatch(iterations);
      for (int i = 0; i < iterations; i++) {
        executor.submit(() -> {
          try {
            task.run();
          } finally {
            latch.countDown();
          }
        });
      }
      latch.await(5, TimeUnit.MINUTES);
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  /**
   * Detects thread pinning by running a task and checking if it causes carrier thread blocking.
   *
   * @param task the task to check for thread pinning
   * @return a ThreadPinningResult containing information about detected pinning
   * @throws Exception if an error occurs during execution
   */
  public static ThreadPinningResult detectThreadPinning(Runnable task) throws Exception {
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    AtomicReference<StackTraceElement[]> pinningStackTrace = new AtomicReference<>();
    
    // Create a virtual thread to run the task
    Thread virtualThread = Thread.ofVirtual().name("pinning-detection-thread").start(() -> {
      task.run();
    });
    
    // Wait a short time for the task to start
    Thread.sleep(100);
    
    // Check if the thread is mounted (pinned)
    StackTraceElement[] stackTrace = virtualThread.getStackTrace();
    for (StackTraceElement element : stackTrace) {
      // Look for indicators of thread pinning in the stack trace
      if (element.getClassName().contains("synchronized") || 
          element.getClassName().contains("ReentrantLock") ||
          element.getClassName().contains("native") ||
          element.getClassName().contains("java.util.concurrent.locks") ||
          element.getClassName().contains("java.io.FileInputStream") ||
          element.getClassName().contains("java.io.FileOutputStream") ||
          element.getClassName().contains("java.net.Socket")) {
        pinningDetected.set(true);
        pinningStackTrace.set(stackTrace);
        break;
      }
    }
    
    virtualThread.join(10000); // Wait for the thread to complete
    
    return new ThreadPinningResult(pinningDetected.get(), pinningStackTrace.get());
  }

  /**
   * Runs a high concurrency test with virtual threads.
   *
   * @param task the task to run
   * @param concurrency the number of concurrent threads to use
   * @return the execution time in milliseconds
   * @throws Exception if an error occurs during execution
   */
  public static long runHighConcurrencyTest(Runnable task, int concurrency) throws Exception {
    ExecutorService executor = virtualThreadExecutor();
    Stopwatch stopwatch = Stopwatch.createStarted();
    
    try {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < concurrency; i++) {
        final int taskId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(
            () -> {
              Thread.currentThread().setName("virtual-task-" + taskId);
              task.run();
            }, 
            executor
        );
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(5, TimeUnit.MINUTES)
          .join();
      
      return stopwatch.elapsed(TimeUnit.MILLISECONDS);
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  /**
   * Measures the execution time of a task using virtual threads.
   *
   * @param task the task to measure
   * @param iterations the number of iterations to run
   * @return the average execution time in milliseconds
   * @throws Exception if an error occurs during execution
   */
  public static double measureVirtualThreadPerformance(Runnable task, int iterations) throws Exception {
    ExecutorService executor = virtualThreadExecutor();
    try {
      long startTime = System.nanoTime();
      CountDownLatch latch = new CountDownLatch(iterations);
      
      for (int i = 0; i < iterations; i++) {
        executor.submit(() -> {
          try {
            task.run();
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(5, TimeUnit.MINUTES);
      long endTime = System.nanoTime();
      
      return (endTime - startTime) / (double) iterations / 1_000_000.0; // Convert to ms
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  /**
   * Executes a task with retries using virtual threads.
   *
   * @param <T> the return type of the task
   * @param task the task to execute
   * @param maxRetries the maximum number of retries
   * @param retryDelay the delay between retries
   * @return the result of the task
   * @throws Exception if the task fails after all retries
   */
  public static <T> T executeWithRetries(
      Callable<T> task,
      int maxRetries,
      Duration retryDelay) throws Exception
  {
    AtomicInteger attempts = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    while (attempts.incrementAndGet() <= maxRetries + 1) {
      try {
        return task.call();
      } catch (Exception e) {
        lastException.set(e);
        if (attempts.get() <= maxRetries) {
          Thread.sleep(retryDelay.toMillis());
        }
      }
    }
    
    throw new Exception("Failed after " + maxRetries + " retries", lastException.get());
  }
  
  /**
   * Simulates repository operations with configurable I/O latency for testing.
   * 
   * @param operationCount the number of operations to simulate
   * @param ioLatencyMs the simulated I/O latency in milliseconds
   * @param useVirtualThreads whether to use virtual threads
   * @return the total execution time in milliseconds
   * @throws Exception if an error occurs during execution
   */
  public static long simulateRepositoryOperations(
      int operationCount,
      int ioLatencyMs,
      boolean useVirtualThreads) throws Exception
  {
    Stopwatch stopwatch = Stopwatch.createStarted();
    CountDownLatch latch = new CountDownLatch(operationCount);
    
    ExecutorService executor = useVirtualThreads ? 
        virtualThreadExecutor() : 
        platformThreadExecutor(Math.min(operationCount, 200));
    
    try {
      // Submit all operations to the executor
      IntStream.range(0, operationCount).forEach(i -> {
        executor.submit(() -> {
          try {
            // Simulate I/O operation with the specified latency
            Thread.sleep(ioLatencyMs);
            return i; // Simulated result
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
          } finally {
            latch.countDown();
          }
        });
      });
      
      // Wait for all operations to complete
      latch.await(5, TimeUnit.MINUTES);
      return stopwatch.elapsed(TimeUnit.MILLISECONDS);
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  /**
   * Result class for thread performance comparison.
   */
  public static class PerformanceResult
  {
    private final long platformDurationNanos;
    private final long virtualDurationNanos;

    public PerformanceResult(long platformDurationNanos, long virtualDurationNanos) {
      this.platformDurationNanos = platformDurationNanos;
      this.virtualDurationNanos = virtualDurationNanos;
    }

    /**
     * Gets the execution time using platform threads in milliseconds.
     *
     * @return the platform thread execution time in ms
     */
    public double getPlatformDurationMs() {
      return platformDurationNanos / 1_000_000.0;
    }

    /**
     * Gets the execution time using virtual threads in milliseconds.
     *
     * @return the virtual thread execution time in ms
     */
    public double getVirtualDurationMs() {
      return virtualDurationNanos / 1_000_000.0;
    }

    /**
     * Calculates the performance improvement ratio of virtual threads compared to platform threads.
     *
     * @return the improvement ratio (values > 1 indicate virtual threads are faster)
     */
    public double getImprovementRatio() {
      return (double) platformDurationNanos / virtualDurationNanos;
    }

    /**
     * Checks if virtual threads performed better than platform threads.
     *
     * @return true if virtual threads were faster, false otherwise
     */
    public boolean isVirtualThreadsFaster() {
      return virtualDurationNanos < platformDurationNanos;
    }
  }

  /**
   * Result class for thread pinning detection.
   */
  public static class ThreadPinningResult
  {
    private final boolean pinningDetected;
    private final StackTraceElement[] pinningStackTrace;

    public ThreadPinningResult(boolean pinningDetected, StackTraceElement[] pinningStackTrace) {
      this.pinningDetected = pinningDetected;
      this.pinningStackTrace = pinningStackTrace;
    }

    /**
     * Checks if thread pinning was detected.
     *
     * @return true if pinning was detected, false otherwise
     */
    public boolean isPinningDetected() {
      return pinningDetected;
    }

    /**
     * Gets the stack trace where pinning was detected.
     *
     * @return the stack trace elements or null if no pinning was detected
     */
    public StackTraceElement[] getPinningStackTrace() {
      return pinningStackTrace;
    }

    /**
     * Gets a formatted string representation of the pinning stack trace.
     *
     * @return a formatted stack trace string or "No pinning detected"
     */
    public String getFormattedStackTrace() {
      if (!pinningDetected || pinningStackTrace == null) {
        return "No pinning detected";
      }
      
      StringBuilder sb = new StringBuilder("Thread pinning detected:\n");
      for (StackTraceElement element : pinningStackTrace) {
        sb.append("  at ").append(element).append("\n");
      }
      return sb.toString();
    }
  }

  /**
   * Simple test to verify that the virtual thread support is working correctly.
   * This test is tagged with VirtualThreadTestGroup to allow selective execution.
   */
  @Test
  @Tag(VirtualThreadTestGroup.NAME)
  public void testVirtualThreadPerformance() throws Exception {
    // A simple task that simulates I/O with sleep
    Runnable ioTask = () -> {
      try {
        Thread.sleep(10); // Simulate I/O operation
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    // Compare performance with a moderate number of iterations and concurrency
    PerformanceResult result = compareThreadPerformance(ioTask, 100, 50);
    
    // Virtual threads should be more efficient for I/O-bound operations
    assertThat("Virtual threads should be faster for I/O operations", 
        result.isVirtualThreadsFaster(), is(true));
    
    // For I/O-bound operations, virtual threads should show significant improvement
    assertThat("Virtual threads should show significant improvement for I/O operations",
        result.getImprovementRatio(), greaterThan(1.5));
    
    // Log the performance comparison results
    log.info("Platform thread duration: {} ms", result.getPlatformDurationMs());
    log.info("Virtual thread duration: {} ms", result.getVirtualDurationMs());
    log.info("Improvement ratio: {}", result.getImprovementRatio());
  }

  /**
   * Test to verify that thread pinning detection works correctly.
   * This test is tagged with VirtualThreadTestGroup to allow selective execution.
   */
  @Test
  @Tag(VirtualThreadTestGroup.NAME)
  public void testThreadPinningDetection() throws Exception {
    // A task that will cause thread pinning due to synchronized block
    Runnable pinningTask = () -> {
      Object lock = new Object();
      synchronized (lock) {
        try {
          Thread.sleep(100); // Hold the lock for a while
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    
    // A task that should not cause thread pinning
    Runnable nonPinningTask = () -> {
      try {
        Thread.sleep(100); // Simple sleep without synchronization
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    // Test pinning detection with a task that should cause pinning
    ThreadPinningResult pinningResult = detectThreadPinning(pinningTask);
    assertThat("Synchronized block should cause thread pinning", 
        pinningResult.isPinningDetected(), is(true));
    assertThat("Pinning stack trace should be available",
        pinningResult.getPinningStackTrace(), notNullValue());
    log.info("Pinning stack trace:\n{}", pinningResult.getFormattedStackTrace());
    
    // Test pinning detection with a task that should not cause pinning
    ThreadPinningResult nonPinningResult = detectThreadPinning(nonPinningTask);
    assertThat("Simple sleep should not cause thread pinning", 
        nonPinningResult.isPinningDetected(), is(false));
  }
  
  /**
   * Test to verify high concurrency with virtual threads.
   * This test is tagged with VirtualThreadTestGroup to allow selective execution.
   */
  @Test
  @Tag(VirtualThreadTestGroup.NAME)
  public void testHighConcurrencyVirtualThreads() throws Exception {
    // Counter to track task execution
    AtomicInteger counter = new AtomicInteger(0);
    
    // A simple task that increments the counter
    Runnable task = () -> {
      try {
        // Simulate some work with a short sleep
        Thread.sleep(5);
        counter.incrementAndGet();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    // Run with high concurrency (10,000 virtual threads)
    int concurrency = 10_000;
    long executionTime = runHighConcurrencyTest(task, concurrency);
    
    // Verify all tasks were executed
    assertThat("All tasks should have been executed", 
        counter.get(), is(concurrency));
    
    log.info("Executed {} concurrent tasks in {} ms using virtual threads", 
        concurrency, executionTime);
  }
  
  /**
   * Test to compare repository operation performance between platform and virtual threads.
   * This test is tagged with VirtualThreadTestGroup to allow selective execution.
   */
  @Test
  @Tag(VirtualThreadTestGroup.NAME)
  public void testRepositoryOperationsPerformance() throws Exception {
    // Simulate repository operations with moderate concurrency and I/O latency
    int operationCount = 1000;
    int ioLatencyMs = 20; // Typical network/disk I/O latency
    
    // Run with platform threads
    long platformTime = simulateRepositoryOperations(operationCount, ioLatencyMs, false);
    log.info("Repository operations with platform threads: {} ms", platformTime);
    
    // Run with virtual threads
    long virtualTime = simulateRepositoryOperations(operationCount, ioLatencyMs, true);
    log.info("Repository operations with virtual threads: {} ms", virtualTime);
    
    // Calculate improvement ratio
    double improvementRatio = (double) platformTime / virtualTime;
    log.info("Improvement ratio: {}", improvementRatio);
    
    // Virtual threads should be more efficient for I/O-bound repository operations
    assertThat("Virtual threads should be faster for repository operations",
        virtualTime, lessThan(platformTime));
    
    // For I/O-bound operations, virtual threads should show significant improvement
    assertThat("Virtual threads should show significant improvement for repository operations",
        improvementRatio, greaterThan(1.5));
  }
}