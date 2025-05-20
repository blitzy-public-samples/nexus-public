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
package org.sonatype.virtualthread;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base support class for testing Java 21 Virtual Thread functionality in repository services.
 * 
 * <p>Provides utilities for creating and managing virtual threads, measuring performance,
 * detecting thread pinning, and executing concurrent operations.</p>
 * 
 * <p>This class is categorized with both {@link Java21TestGroup} and {@link VirtualThreadTestGroup}
 * to enable selective test execution in CI/CD pipelines.</p>
 * 
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public abstract class VirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);
  
  /**
   * Default timeout for waiting on concurrent operations to complete.
   */
  protected static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
  
  /**
   * Default number of concurrent tasks to run in performance tests.
   */
  protected static final int DEFAULT_CONCURRENCY = 1000;
  
  /**
   * Checks if the current JVM supports virtual threads.
   * 
   * <p>This method verifies that the JVM is Java 21 or newer and has virtual thread support.</p>
   * 
   * @return true if virtual threads are supported
   */
  protected boolean isVirtualThreadSupported() {
    try {
      // Try to create a virtual thread factory to verify support
      Thread.ofVirtual().factory();
      return true;
    }
    catch (UnsupportedOperationException | NoSuchMethodError e) {
      log.warn("Virtual threads are not supported in this JVM", e);
      return false;
    }
  }
  
  /**
   * Ensures that virtual threads are supported before running tests.
   * 
   * <p>Tests that extend this class will be skipped if virtual threads are not supported.</p>
   */
  @BeforeEach
  public void ensureVirtualThreadSupport() {
    Assumptions.assumeTrue(isVirtualThreadSupported(), "Virtual threads are not supported in this JVM");
  }
  
  /**
   * Creates a thread factory that produces virtual threads.
   * 
   * @return a thread factory that creates virtual threads
   */
  protected ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }
  
  /**
   * Creates a thread factory that produces platform threads.
   * 
   * @return a thread factory that creates platform threads
   */
  protected ThreadFactory platformThreadFactory() {
    return Thread.ofPlatform().factory();
  }
  
  /**
   * Creates an executor service that creates a new virtual thread for each task.
   * 
   * @return an executor service using virtual threads
   */
  protected ExecutorService virtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates an executor service that creates a fixed number of platform threads.
   * 
   * @param nThreads the number of threads in the pool
   * @return an executor service using platform threads
   */
  protected ExecutorService platformThreadExecutor(int nThreads) {
    return Executors.newFixedThreadPool(nThreads);
  }
  
  /**
   * Executes a task multiple times concurrently using virtual threads and measures the execution time.
   * 
   * @param <T> the type of result produced by the task
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @return a performance result containing timing information
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   */
  protected <T> PerformanceResult<T> executeWithVirtualThreads(Callable<T> task, int concurrency) 
      throws InterruptedException, ExecutionException {
    return executeWithExecutor(virtualThreadExecutor(), task, concurrency);
  }
  
  /**
   * Executes a task multiple times concurrently using platform threads and measures the execution time.
   * 
   * @param <T> the type of result produced by the task
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param threadPoolSize the size of the thread pool to use
   * @return a performance result containing timing information
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   */
  protected <T> PerformanceResult<T> executeWithPlatformThreads(Callable<T> task, int concurrency, int threadPoolSize) 
      throws InterruptedException, ExecutionException {
    return executeWithExecutor(platformThreadExecutor(threadPoolSize), task, concurrency);
  }
  
  /**
   * Executes a task multiple times concurrently using the provided executor service and measures the execution time.
   * 
   * @param <T> the type of result produced by the task
   * @param executor the executor service to use
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @return a performance result containing timing information
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   */
  protected <T> PerformanceResult<T> executeWithExecutor(ExecutorService executor, Callable<T> task, int concurrency) 
      throws InterruptedException, ExecutionException {
    long startTime = System.nanoTime();
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Future<T>> futures = new ArrayList<>(concurrency);
    
    try {
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        futures.add(executor.submit(task));
      }
      
      // Collect results
      List<T> results = new ArrayList<>(concurrency);
      for (Future<T> future : futures) {
        try {
          results.add(future.get());
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Task execution failed", e);
        }
      }
      
      long endTime = System.nanoTime();
      Duration duration = Duration.ofNanos(endTime - startTime);
      
      return new PerformanceResult<>(results, duration, errorCount.get());
    } 
    finally {
      if (!executor.isShutdown()) {
        executor.shutdown();
        executor.awaitTermination(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
  }
  
  /**
   * Executes a task asynchronously using CompletableFuture with virtual threads.
   * 
   * @param <T> the type of result produced by the task
   * @param supplier the supplier that produces the result
   * @return a CompletableFuture that will complete with the result
   */
  protected <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
    return CompletableFuture.supplyAsync(supplier, virtualThreadExecutor());
  }
  
  /**
   * Runs multiple tasks concurrently and waits for all to complete.
   * 
   * @param tasks the tasks to run
   * @throws InterruptedException if the execution is interrupted
   */
  protected void runConcurrently(Runnable... tasks) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(tasks.length);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try (ExecutorService executor = virtualThreadExecutor()) {
      for (Runnable task : tasks) {
        executor.submit(() -> {
          try {
            task.run();
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Task execution failed", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      if (!latch.await(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new InterruptedException("Timeout waiting for tasks to complete");
      }
      
      if (errorCount.get() > 0) {
        throw new RuntimeException("One or more tasks failed: " + errorCount.get());
      }
    }
  }
  
  /**
   * Detects if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread
   */
  protected boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Detects if thread pinning is occurring by analyzing the stack trace.
   * 
   * <p>Thread pinning occurs when a virtual thread is forced to stay mounted on its carrier thread,
   * typically due to synchronized blocks, native methods, or certain blocking operations.</p>
   * 
   * @return true if thread pinning is detected
   */
  protected boolean isThreadPinned() {
    if (!isVirtualThread()) {
      return false;
    }
    
    // Check for common causes of thread pinning in the stack trace
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (StackTraceElement element : stackTrace) {
      String className = element.getClassName();
      String methodName = element.getMethodName();
      
      // Check for synchronized methods (often contain "$sync" in the method name)
      if (methodName.contains("$sync")) {
        log.warn("Thread pinning detected: synchronized method at {}:{}", className, methodName);
        return true;
      }
      
      // Check for native methods
      if (element.isNativeMethod()) {
        log.warn("Thread pinning detected: native method at {}:{}", className, methodName);
        return true;
      }
      
      // Check for synchronized blocks
      if (className.contains("$Proxy") || methodName.equals("monitorEnter") || methodName.equals("monitorExit")) {
        log.warn("Thread pinning detected: synchronized block at {}:{}", className, methodName);
        return true;
      }
    }
    
    return false;
  }
  
  /**
   * Gets a detailed report of thread pinning causes if detected.
   * 
   * @return a string containing the pinning report, or null if no pinning is detected
   */
  protected String getThreadPinningReport() {
    if (!isVirtualThread() || !isThreadPinned()) {
      return null;
    }
    
    StringBuilder report = new StringBuilder("Thread Pinning Report:\n");
    report.append("Thread: ").append(Thread.currentThread()).append("\n");
    report.append("Stack Trace:\n");
    
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (int i = 0; i < stackTrace.length; i++) {
      StackTraceElement element = stackTrace[i];
      report.append("  at ").append(element.getClassName())
          .append(".").append(element.getMethodName())
          .append("(").append(element.getFileName())
          .append(":").append(element.getLineNumber())
          .append(")")
          .append(element.isNativeMethod() ? " [native]" : "")
          .append("\n");
    }
    
    return report.toString();
  }
  
  /**
   * Enables JDK's built-in thread pinning detection.
   * 
   * <p>This method sets the system property to enable detailed thread pinning detection.</p>
   * 
   * @param mode the detection mode: "short" for basic info, "full" for detailed stack traces
   */
  protected void enableThreadPinningDetection(String mode) {
    System.setProperty("jdk.tracePinnedThreads", mode);
    log.info("Enabled thread pinning detection with mode: {}", mode);
  }
  
  /**
   * Enables JDK's built-in thread pinning detection with full stack traces.
   */
  protected void enableThreadPinningDetection() {
    enableThreadPinningDetection("full");
  }
  
  /**
   * Disables JDK's built-in thread pinning detection.
   */
  protected void disableThreadPinningDetection() {
    System.clearProperty("jdk.tracePinnedThreads");
    log.info("Disabled thread pinning detection");
  }
  
  /**
   * Configures the virtual thread scheduler parallelism.
   * 
   * <p>This method sets the system property to control the number of carrier threads
   * used by the virtual thread scheduler.</p>
   * 
   * @param parallelism the number of carrier threads to use
   */
  protected void setVirtualThreadParallelism(int parallelism) {
    System.setProperty("jdk.virtualThreadScheduler.parallelism", String.valueOf(parallelism));
    log.info("Set virtual thread scheduler parallelism to: {}", parallelism);
  }
  
  /**
   * Configures the maximum pool size for the virtual thread scheduler.
   * 
   * <p>This method sets the system property to control the maximum number of carrier threads
   * that can be created by the virtual thread scheduler.</p>
   * 
   * @param maxPoolSize the maximum number of carrier threads
   */
  protected void setVirtualThreadMaxPoolSize(int maxPoolSize) {
    System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", String.valueOf(maxPoolSize));
    log.info("Set virtual thread scheduler max pool size to: {}", maxPoolSize);
  }
  
  /**
   * Result class for performance measurements.
   *
   * @param <T> the type of result produced by the measured task
   */
  /**
   * Compares the performance of virtual threads vs platform threads for a given task.
   * 
   * @param <T> the type of result produced by the task
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param platformThreadPoolSize the size of the platform thread pool to use
   * @return a comparison result containing performance metrics for both thread types
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if any task execution fails
   */
  protected <T> ThreadPerformanceComparison<T> compareThreadPerformance(
      Callable<T> task, int concurrency, int platformThreadPoolSize) 
      throws InterruptedException, ExecutionException {
    
    log.info("Running performance comparison with concurrency={}, platformThreadPoolSize={}", 
        concurrency, platformThreadPoolSize);
    
    // Run with virtual threads
    log.info("Executing with virtual threads...");
    PerformanceResult<T> virtualResult = executeWithVirtualThreads(task, concurrency);
    
    // Run with platform threads
    log.info("Executing with platform threads...");
    PerformanceResult<T> platformResult = executeWithPlatformThreads(task, concurrency, platformThreadPoolSize);
    
    // Calculate improvement ratio
    double throughputImprovement = virtualResult.getThroughput() / Math.max(0.001, platformResult.getThroughput());
    double durationImprovement = (double) platformResult.getDuration().toMillis() / 
        Math.max(1, virtualResult.getDuration().toMillis());
    
    log.info("Performance comparison complete:");
    log.info("  Virtual threads:  {} ops/sec, {} ms total, {} ms avg", 
        String.format("%.2f", virtualResult.getThroughput()),
        virtualResult.getDuration().toMillis(),
        virtualResult.getAverageDuration().toMillis());
    log.info("  Platform threads: {} ops/sec, {} ms total, {} ms avg", 
        String.format("%.2f", platformResult.getThroughput()),
        platformResult.getDuration().toMillis(),
        platformResult.getAverageDuration().toMillis());
    log.info("  Improvement: {}x throughput, {}x faster execution", 
        String.format("%.2f", throughputImprovement),
        String.format("%.2f", durationImprovement));
    
    return new ThreadPerformanceComparison<>(virtualResult, platformResult);
  }
  
  /**
   * Result class for performance measurements.
   *
   * @param <T> the type of result produced by the measured task
   */
  protected static class PerformanceResult<T> {
    private final List<T> results;
    private final Duration duration;
    private final int errorCount;
    private final Runtime runtime = Runtime.getRuntime();
    private final long memoryUsedBytes;
    
    public PerformanceResult(List<T> results, Duration duration, int errorCount) {
      this.results = results;
      this.duration = duration;
      this.errorCount = errorCount;
      
      // Capture memory usage
      System.gc(); // Request garbage collection to get more accurate memory usage
      this.memoryUsedBytes = runtime.totalMemory() - runtime.freeMemory();
    }
    
    /**
     * Gets the results of the task executions.
     * 
     * @return the list of results
     */
    public List<T> getResults() {
      return results;
    }
    
    /**
     * Gets the total execution time.
     * 
     * @return the duration of the execution
     */
    public Duration getDuration() {
      return duration;
    }
    
    /**
     * Gets the number of tasks that failed.
     * 
     * @return the error count
     */
    public int getErrorCount() {
      return errorCount;
    }
    
    /**
     * Gets the memory used during execution in bytes.
     * 
     * @return the memory used in bytes
     */
    public long getMemoryUsedBytes() {
      return memoryUsedBytes;
    }
    
    /**
     * Gets the memory used during execution in megabytes.
     * 
     * @return the memory used in megabytes
     */
    public double getMemoryUsedMB() {
      return memoryUsedBytes / (1024.0 * 1024.0);
    }
    
    /**
     * Calculates the average execution time per task.
     * 
     * @return the average duration per task
     */
    public Duration getAverageDuration() {
      return Duration.ofNanos(duration.toNanos() / Math.max(1, results.size()));
    }
    
    /**
     * Calculates the throughput in operations per second.
     * 
     * @return the throughput
     */
    public double getThroughput() {
      double seconds = duration.toNanos() / 1_000_000_000.0;
      return results.size() / Math.max(0.001, seconds);
    }
  }
  
  /**
   * Comparison result for virtual threads vs platform threads performance.
   *
   * @param <T> the type of result produced by the measured task
   */
  protected static class ThreadPerformanceComparison<T> {
    private final PerformanceResult<T> virtualThreadResult;
    private final PerformanceResult<T> platformThreadResult;
    
    public ThreadPerformanceComparison(
        PerformanceResult<T> virtualThreadResult, 
        PerformanceResult<T> platformThreadResult) {
      this.virtualThreadResult = virtualThreadResult;
      this.platformThreadResult = platformThreadResult;
    }
    
    /**
     * Gets the performance result for virtual threads.
     * 
     * @return the virtual thread performance result
     */
    public PerformanceResult<T> getVirtualThreadResult() {
      return virtualThreadResult;
    }
    
    /**
     * Gets the performance result for platform threads.
     * 
     * @return the platform thread performance result
     */
    public PerformanceResult<T> getPlatformThreadResult() {
      return platformThreadResult;
    }
    
    /**
     * Calculates the throughput improvement ratio of virtual threads compared to platform threads.
     * 
     * @return the throughput improvement ratio (values > 1 indicate virtual threads are faster)
     */
    public double getThroughputImprovement() {
      return virtualThreadResult.getThroughput() / Math.max(0.001, platformThreadResult.getThroughput());
    }
    
    /**
     * Calculates the execution time improvement ratio of virtual threads compared to platform threads.
     * 
     * @return the execution time improvement ratio (values > 1 indicate virtual threads are faster)
     */
    public double getExecutionTimeImprovement() {
      return (double) platformThreadResult.getDuration().toMillis() / 
          Math.max(1, virtualThreadResult.getDuration().toMillis());
    }
    
    /**
     * Calculates the memory usage improvement ratio of virtual threads compared to platform threads.
     * 
     * @return the memory usage improvement ratio (values > 1 indicate virtual threads use less memory)
     */
    public double getMemoryUsageImprovement() {
      return (double) platformThreadResult.getMemoryUsedBytes() / 
          Math.max(1, virtualThreadResult.getMemoryUsedBytes());
    }
  }
}