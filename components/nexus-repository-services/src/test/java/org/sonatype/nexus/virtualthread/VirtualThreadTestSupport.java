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
package org.sonatype.nexus.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.After;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

/**
 * Base support class for Virtual Thread testing in Nexus Repository Services.
 * <p>
 * This class provides utilities for creating virtual thread factories, executing concurrent tasks,
 * detecting thread pinning issues, and comparing performance between platform and virtual threads.
 * <p>
 * Tests that extend this class should be annotated with {@code @Category(VirtualThreadTestGroup.class)}
 * to allow selective execution in CI pipelines.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public abstract class VirtualThreadTestSupport
    extends TestSupport
{
  /**
   * Default timeout for concurrent operations in seconds.
   */
  protected static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * List of executor services to be shut down after each test.
   */
  private final List<ExecutorService> executorServices = new ArrayList<>();

  /**
   * Cleans up any executor services created during the test.
   */
  @After
  public void shutdownExecutors() {
    executorServices.forEach(ExecutorService::shutdownNow);
    executorServices.clear();
  }

  /**
   * Creates a virtual thread factory.
   *
   * @return a thread factory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates a platform thread factory.
   *
   * @return a thread factory that creates platform threads
   */
  protected ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().factory();
  }

  /**
   * Creates a virtual thread per task executor service.
   *
   * @return an executor service that creates a new virtual thread for each task
   */
  protected ExecutorService createVirtualThreadExecutor() {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    executorServices.add(executor);
    return executor;
  }

  /**
   * Creates a fixed thread pool executor service with platform threads.
   *
   * @param nThreads the number of threads in the pool
   * @return a fixed thread pool executor service
   */
  protected ExecutorService createPlatformThreadExecutor(int nThreads) {
    ExecutorService executor = Executors.newFixedThreadPool(nThreads, createPlatformThreadFactory());
    executorServices.add(executor);
    return executor;
  }

  /**
   * Executes the given task concurrently using the specified number of threads.
   *
   * @param executor the executor service to use
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   * @throws TimeoutException if the execution times out
   */
  protected void executeConcurrently(ExecutorService executor, Runnable task, int concurrency)
      throws InterruptedException, ExecutionException, TimeoutException {
    executeConcurrently(executor, task, concurrency, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Executes the given task concurrently using the specified number of threads with a timeout.
   *
   * @param executor the executor service to use
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   * @throws TimeoutException if the execution times out
   */
  protected void executeConcurrently(ExecutorService executor, Runnable task, int concurrency, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    List<Future<?>> futures = new ArrayList<>(concurrency);
    for (int i = 0; i < concurrency; i++) {
      futures.add(executor.submit(task));
    }
    
    for (Future<?> future : futures) {
      future.get(timeout, unit);
    }
  }

  /**
   * Executes the given callable concurrently using the specified number of threads.
   *
   * @param <T> the type of the callable's result
   * @param executor the executor service to use
   * @param callable the callable to execute
   * @param concurrency the number of concurrent executions
   * @return a list of results from each callable execution
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   * @throws TimeoutException if the execution times out
   */
  protected <T> List<T> executeConcurrently(ExecutorService executor, Callable<T> callable, int concurrency)
      throws InterruptedException, ExecutionException, TimeoutException {
    return executeConcurrently(executor, callable, concurrency, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Executes the given callable concurrently using the specified number of threads with a timeout.
   *
   * @param <T> the type of the callable's result
   * @param executor the executor service to use
   * @param callable the callable to execute
   * @param concurrency the number of concurrent executions
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return a list of results from each callable execution
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   * @throws TimeoutException if the execution times out
   */
  protected <T> List<T> executeConcurrently(ExecutorService executor, Callable<T> callable, int concurrency, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    List<Future<T>> futures = new ArrayList<>(concurrency);
    for (int i = 0; i < concurrency; i++) {
      futures.add(executor.submit(callable));
    }
    
    List<T> results = new ArrayList<>(concurrency);
    for (Future<T> future : futures) {
      results.add(future.get(timeout, unit));
    }
    
    return results;
  }

  /**
   * Detects if thread pinning occurs when executing the given task.
   * <p>
   * This method uses a heuristic approach to detect potential thread pinning by monitoring
   * if a virtual thread blocks for an extended period while executing a task.
   *
   * @param task the task to check for thread pinning
   * @param timeoutMillis the maximum time to wait for task completion in milliseconds
   * @return true if thread pinning is detected, false otherwise
   */
  protected boolean detectThreadPinning(Runnable task, long timeoutMillis) {
    AtomicBoolean completed = new AtomicBoolean(false);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a virtual thread to run the task
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        task.run();
        completed.set(true);
      }
      catch (Exception e) {
        log.error("Error in virtual thread task", e);
      }
    });
    
    // Create a monitoring thread to check if the virtual thread is pinned
    Thread monitorThread = Thread.ofPlatform().start(() -> {
      try {
        // Wait for a short period to allow the virtual thread to start
        Thread.sleep(100);
        
        // Check if the virtual thread is still running but not making progress
        long startTime = System.currentTimeMillis();
        while (!completed.get() && System.currentTimeMillis() - startTime < timeoutMillis) {
          // If the thread is alive but not making progress for a significant time, it might be pinned
          if (virtualThread.isAlive()) {
            Thread.sleep(100); // Check periodically
          }
          else {
            break; // Thread completed
          }
        }
        
        // If the task didn't complete within the timeout, it might be pinned
        if (!completed.get()) {
          log.warn("Potential thread pinning detected: Virtual thread blocked for {} ms", timeoutMillis);
          pinningDetected.set(true);
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    try {
      monitorThread.join(timeoutMillis + 1000); // Wait for the monitor thread to complete
      return pinningDetected.get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /**
   * Compares the performance of virtual threads versus platform threads for a given task.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param iterations the number of iterations to run
   * @return a {@link ThreadPerformanceResult} containing the performance metrics
   */
  protected ThreadPerformanceResult compareThreadPerformance(Runnable task, int concurrency, int iterations) {
    // Warm-up phase
    try {
      ExecutorService warmupExecutor = createVirtualThreadExecutor();
      for (int i = 0; i < Math.min(5, iterations / 2); i++) {
        executeConcurrently(warmupExecutor, task, Math.min(5, concurrency));
      }
      warmupExecutor.shutdown();
      warmupExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
    catch (Exception e) {
      log.warn("Warm-up phase failed", e);
    }
    
    // Measure virtual thread performance
    long virtualThreadStartTime = System.nanoTime();
    try {
      ExecutorService virtualExecutor = createVirtualThreadExecutor();
      for (int i = 0; i < iterations; i++) {
        executeConcurrently(virtualExecutor, task, concurrency);
      }
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(5, TimeUnit.MINUTES);
    }
    catch (Exception e) {
      log.error("Virtual thread performance test failed", e);
      fail("Virtual thread performance test failed: " + e.getMessage());
    }
    long virtualThreadDuration = System.nanoTime() - virtualThreadStartTime;
    
    // Measure platform thread performance
    long platformThreadStartTime = System.nanoTime();
    try {
      ExecutorService platformExecutor = createPlatformThreadExecutor(concurrency);
      for (int i = 0; i < iterations; i++) {
        executeConcurrently(platformExecutor, task, concurrency);
      }
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(5, TimeUnit.MINUTES);
    }
    catch (Exception e) {
      log.error("Platform thread performance test failed", e);
      fail("Platform thread performance test failed: " + e.getMessage());
    }
    long platformThreadDuration = System.nanoTime() - platformThreadStartTime;
    
    return new ThreadPerformanceResult(virtualThreadDuration, platformThreadDuration);
  }

  /**
   * Executes a task with a high number of concurrent virtual threads to test scalability.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions (should be high, e.g., 1000+)
   * @param timeoutSeconds the maximum time to wait for all tasks to complete
   * @throws InterruptedException if the execution is interrupted
   * @throws TimeoutException if the execution times out
   */
  protected void testVirtualThreadScalability(Runnable task, int concurrency, int timeoutSeconds)
      throws InterruptedException, TimeoutException {
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    ExecutorService executor = createVirtualThreadExecutor();
    
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        try {
          task.run();
        }
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
    if (!completed) {
      throw new TimeoutException("Virtual thread scalability test timed out after " + timeoutSeconds + " seconds");
    }
    
    assertThat("No errors should occur during virtual thread execution", errorCount.get(), is(0));
  }

  /**
   * Executes a task asynchronously using CompletableFuture with virtual threads.
   *
   * @param <T> the type of the result
   * @param supplier the supplier that produces the result
   * @return a CompletableFuture that will complete with the result
   */
  protected <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
    return CompletableFuture.supplyAsync(supplier, createVirtualThreadExecutor());
  }

  /**
   * Asserts that a task does not cause thread pinning.
   *
   * @param task the task to check for thread pinning
   * @param timeoutMillis the maximum time to wait for task completion in milliseconds
   */
  protected void assertNoPinning(Runnable task, long timeoutMillis) {
    boolean pinningDetected = detectThreadPinning(task, timeoutMillis);
    assertThat("Task should not cause thread pinning", pinningDetected, is(false));
  }

  /**
   * Asserts that virtual threads perform better than platform threads for a given task.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param iterations the number of iterations to run
   */
  protected void assertVirtualThreadsPerformBetter(Runnable task, int concurrency, int iterations) {
    ThreadPerformanceResult result = compareThreadPerformance(task, concurrency, iterations);
    log.info("Performance comparison: Virtual threads: {} ns, Platform threads: {} ns", 
        result.getVirtualThreadDuration(), result.getPlatformThreadDuration());
    
    assertThat("Virtual threads should perform better than platform threads", 
        result.getVirtualThreadDuration() < result.getPlatformThreadDuration(), is(true));
  }

  /**
   * Asserts that a task can be executed with a high number of concurrent virtual threads.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions (should be high, e.g., 1000+)
   */
  protected void assertVirtualThreadScalability(Runnable task, int concurrency) {
    try {
      testVirtualThreadScalability(task, concurrency, DEFAULT_TIMEOUT_SECONDS);
    }
    catch (Exception e) {
      fail("Virtual thread scalability test failed: " + e.getMessage());
    }
  }

  /**
   * Result class for thread performance comparison.
   */
  public static class ThreadPerformanceResult
  {
    private final long virtualThreadDuration;
    private final long platformThreadDuration;

    public ThreadPerformanceResult(long virtualThreadDuration, long platformThreadDuration) {
      this.virtualThreadDuration = virtualThreadDuration;
      this.platformThreadDuration = platformThreadDuration;
    }

    /**
     * Gets the duration of the virtual thread execution in nanoseconds.
     *
     * @return the virtual thread duration
     */
    public long getVirtualThreadDuration() {
      return virtualThreadDuration;
    }

    /**
     * Gets the duration of the platform thread execution in nanoseconds.
     *
     * @return the platform thread duration
     */
    public long getPlatformThreadDuration() {
      return platformThreadDuration;
    }

    /**
     * Calculates the performance improvement ratio of virtual threads compared to platform threads.
     *
     * @return the ratio of platform thread duration to virtual thread duration
     */
    public double getImprovementRatio() {
      return (double) platformThreadDuration / virtualThreadDuration;
    }

    /**
     * Formats the performance comparison as a human-readable string.
     *
     * @return a string representation of the performance comparison
     */
    @Override
    public String toString() {
      double improvementPercent = (getImprovementRatio() - 1.0) * 100.0;
      return String.format("Virtual threads: %.2f ms, Platform threads: %.2f ms (%.2f%% improvement)",
          virtualThreadDuration / 1_000_000.0,
          platformThreadDuration / 1_000_000.0,
          improvementPercent);
    }
  }
}