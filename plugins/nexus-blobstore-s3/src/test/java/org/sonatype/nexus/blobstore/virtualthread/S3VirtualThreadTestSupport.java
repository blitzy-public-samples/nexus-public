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
package org.sonatype.nexus.blobstore.virtualthread;

import java.time.Duration;
import java.util.concurrent.ForkJoinPool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test support class for S3BlobStore virtual thread testing.
 * 
 * <p>Provides utilities for creating thread pools (both platform and virtual),
 * measuring performance, and executing concurrent S3 blob operations.</p>
 * 
 * <p>This class is designed to be extended by test classes that need to compare
 * the performance of platform threads vs virtual threads for S3 operations.</p>
 * 
 * <p>Virtual threads are lightweight threads introduced in Java 21 that are managed by the JVM
 * rather than the operating system. They are particularly well-suited for I/O-bound operations
 * like those performed by S3BlobStore, as they allow for high concurrency without the overhead
 * of traditional platform threads.</p>
 * 
 * <p>To enable virtual thread tests, set the system property {@code test.virtual.threads=true}.</p>
 * 
 * @since 3.60
 */
public class S3VirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(S3VirtualThreadTestSupport.class);
  
  /**
   * System property to enable/disable virtual thread tests.
   */
  public static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "test.virtual.threads";
  
  /**
   * Default number of threads to use in platform thread pool.
   */
  public static final int DEFAULT_PLATFORM_THREAD_COUNT = 50;
  
  /**
   * Default number of operations to execute concurrently for performance tests.
   */
  public static final int DEFAULT_CONCURRENT_OPERATIONS = 100;
  
  /**
   * Default timeout for operations in seconds.
   */
  public static final int DEFAULT_TIMEOUT_SECONDS = 60;
  
  /**
   * Checks if virtual thread tests are enabled via system property.
   * 
   * @return true if virtual thread tests are enabled
   */
  public static boolean isVirtualThreadsEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_ENABLED_PROPERTY);
  }
  
  /**
   * Creates a platform thread factory with the specified name prefix.
   * 
   * @param namePrefix prefix for thread names
   * @return a platform thread factory
   */
  public static ThreadFactory platformThreadFactory(String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = new Thread(r);
      thread.setName(namePrefix + "-" + counter.incrementAndGet());
      return thread;
    };
  }
  
  /**
   * Creates a virtual thread factory with the specified name prefix.
   * 
   * @param namePrefix prefix for thread names
   * @return a virtual thread factory
   */
  public static ThreadFactory virtualThreadFactory(String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> Thread.ofVirtual()
        .name(namePrefix + "-" + counter.incrementAndGet())
        .unstarted(r);
  }
  
  /**
   * Creates a fixed-size platform thread pool with the specified thread count and name prefix.
   * 
   * @param threadCount number of threads in the pool
   * @param namePrefix prefix for thread names
   * @return a fixed-size platform thread pool
   */
  public static ExecutorService newPlatformThreadPool(int threadCount, String namePrefix) {
    return Executors.newFixedThreadPool(threadCount, platformThreadFactory(namePrefix));
  }
  
  /**
   * Creates a fixed-size platform thread pool with the default thread count and specified name prefix.
   * 
   * @param namePrefix prefix for thread names
   * @return a fixed-size platform thread pool
   */
  public static ExecutorService newPlatformThreadPool(String namePrefix) {
    return newPlatformThreadPool(DEFAULT_PLATFORM_THREAD_COUNT, namePrefix);
  }
  
  /**
   * Creates a virtual thread per task executor with the specified name prefix.
   * 
   * @param namePrefix prefix for thread names
   * @return a virtual thread per task executor
   */
  public static ExecutorService newVirtualThreadPool(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }
  
  /**
   * Creates a virtual thread executor using the built-in factory method.
   * This is the recommended way to create virtual threads in Java 21.
   * 
   * @return a virtual thread per task executor
   */
  public static ExecutorService newVirtualThreadPerTaskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Performance measurement result containing execution time and operations per second.
   */
  public static class PerformanceResult {
    private final long durationMillis;
    private final int operationCount;
    private final double operationsPerSecond;
    
    public PerformanceResult(long durationMillis, int operationCount) {
      this.durationMillis = durationMillis;
      this.operationCount = operationCount;
      this.operationsPerSecond = operationCount / (durationMillis / 1000.0);
    }
    
    public long getDurationMillis() {
      return durationMillis;
    }
    
    public int getOperationCount() {
      return operationCount;
    }
    
    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }
    
    @Override
    public String toString() {
      return String.format("%d operations in %d ms (%.2f ops/sec)", 
          operationCount, durationMillis, operationsPerSecond);
    }
  }
  
  /**
   * Executes the specified operation concurrently using the provided executor service
   * and measures performance.
   * 
   * @param <T> the type of result returned by the operation
   * @param executor the executor service to use
   * @param operation the operation to execute concurrently
   * @param operationCount the number of concurrent operations to execute
   * @return a performance measurement result
   * @throws Exception if any operation fails
   */
  public <T> PerformanceResult measureConcurrentPerformance(
      ExecutorService executor,
      Callable<T> operation,
      int operationCount) throws Exception {
    
    List<Future<T>> futures = new ArrayList<>(operationCount);
    
    // Warm-up phase - execute a few operations to ensure JIT compilation
    for (int i = 0; i < Math.min(5, operationCount); i++) {
      operation.call();
    }
    
    // Actual measurement
    long startTime = System.currentTimeMillis();
    
    // Submit all operations
    for (int i = 0; i < operationCount; i++) {
      futures.add(executor.submit(operation));
    }
    
    // Wait for all operations to complete
    for (Future<T> future : futures) {
      try {
        future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (Exception e) {
        log.error("Operation failed: {}", e.getMessage(), e);
        throw e;
      }
    }
    
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    return new PerformanceResult(duration, operationCount);
  }
  
  /**
   * Executes the specified operation concurrently using both platform and virtual thread pools
   * and compares their performance.
   * 
   * @param <T> the type of result returned by the operation
   * @param operation the operation to execute concurrently
   * @param operationCount the number of concurrent operations to execute
   * @param platformThreadCount the number of platform threads to use
   * @return a pair of performance measurement results (platform, virtual)
   * @throws Exception if any operation fails
   */
  public <T> PerformanceComparison<T> compareThreadPoolPerformance(
      Callable<T> operation,
      int operationCount,
      int platformThreadCount) throws Exception {
    
    if (!isVirtualThreadsEnabled()) {
      log.info("Virtual threads are disabled. Skipping performance comparison.");
      ExecutorService platformExecutor = newPlatformThreadPool(platformThreadCount, "platform-test");
      try {
        PerformanceResult platformResult = 
            measureConcurrentPerformance(platformExecutor, operation, operationCount);
        log.info("Platform thread performance: {}", platformResult);
        return new PerformanceComparison<>(platformResult, platformResult, 0.0);
      } finally {
        shutdownExecutor(platformExecutor);
      }
    }
    
    // Create thread pools
    ExecutorService platformExecutor = newPlatformThreadPool(platformThreadCount, "platform-test");
    ExecutorService virtualExecutor = newVirtualThreadPerTaskExecutor();
    
    try {
      // Measure platform thread performance
      log.info("Measuring platform thread performance with {} threads for {} operations", 
          platformThreadCount, operationCount);
      PerformanceResult platformResult = 
          measureConcurrentPerformance(platformExecutor, operation, operationCount);
      log.info("Platform thread performance: {}", platformResult);
      
      // Measure virtual thread performance
      log.info("Measuring virtual thread performance for {} operations", operationCount);
      PerformanceResult virtualResult = 
          measureConcurrentPerformance(virtualExecutor, operation, operationCount);
      log.info("Virtual thread performance: {}", virtualResult);
      
      // Calculate improvement percentage
      double improvementPercent = ((virtualResult.getOperationsPerSecond() / 
          platformResult.getOperationsPerSecond()) - 1) * 100;
      
      log.info("Virtual threads {} performance by {}%", 
          improvementPercent >= 0 ? "improved" : "reduced",
          String.format("%.2f", Math.abs(improvementPercent)));
      
      return new PerformanceComparison<>(platformResult, virtualResult, improvementPercent);
    } finally {
      // Shutdown thread pools
      shutdownExecutor(platformExecutor);
      shutdownExecutor(virtualExecutor);
    }
  }
  
  /**
   * Executes the specified operation concurrently using both platform and virtual thread pools
   * with default settings and compares their performance.
   * 
   * @param <T> the type of result returned by the operation
   * @param operation the operation to execute concurrently
   * @return a pair of performance measurement results (platform, virtual)
   * @throws Exception if any operation fails
   */
  public <T> PerformanceComparison<T> compareThreadPoolPerformance(Callable<T> operation) throws Exception {
    return compareThreadPoolPerformance(operation, DEFAULT_CONCURRENT_OPERATIONS, DEFAULT_PLATFORM_THREAD_COUNT);
  }
  
  /**
   * Performance comparison result containing performance measurements for both platform and virtual threads.
   * 
   * @param <T> the type of result returned by the operations
   */
  public static class PerformanceComparison<T> {
    private final PerformanceResult platformResult;
    private final PerformanceResult virtualResult;
    private final double improvementPercent;
    
    public PerformanceComparison(
        PerformanceResult platformResult, 
        PerformanceResult virtualResult,
        double improvementPercent) {
      this.platformResult = platformResult;
      this.virtualResult = virtualResult;
      this.improvementPercent = improvementPercent;
    }
    
    public PerformanceResult getPlatformResult() {
      return platformResult;
    }
    
    public PerformanceResult getVirtualResult() {
      return virtualResult;
    }
    
    public double getImprovementPercent() {
      return improvementPercent;
    }
    
    public boolean isVirtualFaster() {
      return improvementPercent > 0;
    }
  }
  
  /**
   * Executes a list of operations concurrently using the provided executor service.
   * 
   * @param <T> the type of result returned by the operations
   * @param executor the executor service to use
   * @param operations the list of operations to execute concurrently
   * @return a list of results from the operations
   * @throws Exception if any operation fails
   */
  public <T> List<T> executeConcurrently(
      ExecutorService executor,
      List<Callable<T>> operations) throws Exception {
    
    List<Future<T>> futures = new ArrayList<>(operations.size());
    
    // Submit all operations
    for (Callable<T> operation : operations) {
      futures.add(executor.submit(operation));
    }
    
    // Collect results
    List<T> results = new ArrayList<>(operations.size());
    for (Future<T> future : futures) {
      try {
        results.add(future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      } catch (Exception e) {
        log.error("Operation failed: {}", e.getMessage(), e);
        throw e;
      }
    }
    
    return results;
  }
  
  /**
   * Executes a list of operations concurrently using a virtual thread pool.
   * 
   * @param <T> the type of result returned by the operations
   * @param operations the list of operations to execute concurrently
   * @return a list of results from the operations
   * @throws Exception if any operation fails
   */
  public <T> List<T> executeWithVirtualThreads(List<Callable<T>> operations) throws Exception {
    if (!isVirtualThreadsEnabled()) {
      log.info("Virtual threads are disabled. Using platform threads instead.");
      return executeWithPlatformThreads(operations);
    }
    
    ExecutorService executor = newVirtualThreadPerTaskExecutor();
    try {
      return executeConcurrently(executor, operations);
    } finally {
      shutdownExecutor(executor);
    }
  }
  
  /**
   * Executes a list of operations concurrently using a platform thread pool.
   * 
   * @param <T> the type of result returned by the operations
   * @param operations the list of operations to execute concurrently
   * @param threadCount the number of platform threads to use
   * @return a list of results from the operations
   * @throws Exception if any operation fails
   */
  public <T> List<T> executeWithPlatformThreads(
      List<Callable<T>> operations, 
      int threadCount) throws Exception {
    
    ExecutorService executor = newPlatformThreadPool(threadCount, "platform-exec");
    try {
      return executeConcurrently(executor, operations);
    } finally {
      shutdownExecutor(executor);
    }
  }
  
  /**
   * Executes a list of operations concurrently using a platform thread pool with default thread count.
   * 
   * @param <T> the type of result returned by the operations
   * @param operations the list of operations to execute concurrently
   * @return a list of results from the operations
   * @throws Exception if any operation fails
   */
  public <T> List<T> executeWithPlatformThreads(List<Callable<T>> operations) throws Exception {
    return executeWithPlatformThreads(operations, DEFAULT_PLATFORM_THREAD_COUNT);
  }
  
  /**
   * Measures the execution time of a supplier function.
   * 
   * @param <T> the type of result returned by the supplier
   * @param name a descriptive name for the operation being measured
   * @param supplier the supplier function to measure
   * @return the result of the supplier function
   */
  public <T> T measureExecutionTime(String name, Supplier<T> supplier) {
    long startTime = System.currentTimeMillis();
    T result = supplier.get();
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    log.info("{} completed in {} ms", name, duration);
    
    return result;
  }
  
  /**
   * Gets the current number of available processors for the JVM.
   * This is useful for determining the optimal number of platform threads.
   * 
   * @return the number of available processors
   */
  public static int getAvailableProcessors() {
    return Runtime.getRuntime().availableProcessors();
  }
  
  /**
   * Gets the current parallelism level of the common ForkJoinPool.
   * This is typically equal to the number of available processors.
   * 
   * @return the parallelism level of the common ForkJoinPool
   */
  public static int getCommonPoolParallelism() {
    return ForkJoinPool.getCommonPoolParallelism();
  }
  
  /**
   * Safely shuts down an executor service with a timeout.
   * 
   * @param executor the executor service to shut down
   */
  private void shutdownExecutor(ExecutorService executor) {
    executor.shutdown();
    try {
      if (!executor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow();
        if (!executor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          log.warn("Executor did not terminate");
        }
      }
    } catch (InterruptedException e) {
      executor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
}