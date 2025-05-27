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
package org.sonatype.nexus.repository.httpbridge.virtualthread;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base support class for Virtual Thread testing in the HTTP Bridge component.
 * <p>
 * Provides utilities for creating Virtual Thread executors, measuring performance metrics,
 * and executing concurrent HTTP operations. This class serves as the foundation for all
 * HTTP Bridge Virtual Thread tests, ensuring consistent test patterns and utilities.
 *
 * @since 3.60
 */
public abstract class HttpBridgeVirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(HttpBridgeVirtualThreadTestSupport.class);

  /**
   * System property to enable/disable virtual thread tests.
   */
  protected static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "test.virtual.threads";

  /**
   * Default number of concurrent operations to execute in tests.
   */
  protected static final int DEFAULT_CONCURRENCY = 1000;

  /**
   * Default timeout for test operations in seconds.
   */
  protected static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Executor service for running test operations.
   */
  private ExecutorService executorService;

  /**
   * Setup method to initialize resources before each test.
   */
  @BeforeEach
  public void setUp() throws Exception {
    // Default to platform threads, subclasses can override to use virtual threads
    executorService = createPlatformThreadExecutor();
  }

  /**
   * Teardown method to clean up resources after each test.
   */
  @AfterEach
  public void tearDown() throws Exception {
    if (executorService != null && !executorService.isShutdown()) {
      executorService.shutdown();
      executorService.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Creates an executor service using platform threads with a fixed thread pool size.
   *
   * @param threadPoolSize the size of the thread pool
   * @return an executor service using platform threads
   */
  protected ExecutorService createPlatformThreadExecutor(int threadPoolSize) {
    return Executors.newFixedThreadPool(threadPoolSize, createPlatformThreadFactory("test-platform-thread"));
  }

  /**
   * Creates an executor service using platform threads with a default thread pool size.
   *
   * @return an executor service using platform threads
   */
  protected ExecutorService createPlatformThreadExecutor() {
    return createPlatformThreadExecutor(Runtime.getRuntime().availableProcessors());
  }

  /**
   * Creates an executor service using virtual threads.
   * <p>
   * This executor creates a new virtual thread for each submitted task, which is ideal for
   * I/O-bound operations like HTTP requests.
   *
   * @return an executor service using virtual threads
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates a thread factory for platform threads with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return a thread factory for platform threads
   */
  protected ThreadFactory createPlatformThreadFactory(final String namePrefix) {
    AtomicInteger threadCounter = new AtomicInteger(1);
    return r -> {
      Thread thread = new Thread(r);
      thread.setName(namePrefix + "-" + threadCounter.getAndIncrement());
      thread.setDaemon(true);
      return thread;
    };
  }

  /**
   * Checks if virtual thread tests are enabled via system property.
   *
   * @return true if virtual thread tests are enabled, false otherwise
   */
  protected boolean isVirtualThreadsEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_ENABLED_PROPERTY);
  }

  /**
   * Executes the given task concurrently with the specified level of parallelism.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param <T> the return type of the task
   * @return a list of futures representing the pending completion of the tasks
   */
  protected <T> List<Future<T>> executeConcurrently(Callable<T> task, int concurrency) {
    List<Future<T>> futures = new ArrayList<>(concurrency);
    for (int i = 0; i < concurrency; i++) {
      futures.add(executorService.submit(task));
    }
    return futures;
  }

  /**
   * Executes the given task concurrently with the default level of parallelism.
   *
   * @param task the task to execute
   * @param <T> the return type of the task
   * @return a list of futures representing the pending completion of the tasks
   */
  protected <T> List<Future<T>> executeConcurrently(Callable<T> task) {
    return executeConcurrently(task, DEFAULT_CONCURRENCY);
  }

  /**
   * Executes the given task asynchronously using CompletableFuture.
   *
   * @param supplier the supplier to execute
   * @param <T> the return type of the supplier
   * @return a CompletableFuture representing the pending completion of the task
   */
  protected <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
    return CompletableFuture.supplyAsync(supplier, executorService);
  }

  /**
   * Measures the execution time of the given task.
   *
   * @param task the task to measure
   * @param <T> the return type of the task
   * @return a tuple containing the result and the execution time in milliseconds
   * @throws Exception if the task throws an exception
   */
  protected <T> ExecutionResult<T> measureExecutionTime(Callable<T> task) throws Exception {
    long startTime = System.nanoTime();
    T result = task.call();
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    return new ExecutionResult<>(result, durationMs);
  }

  /**
   * Measures the throughput of the given task executed concurrently.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param <T> the return type of the task
   * @return a ThroughputResult containing the throughput metrics
   * @throws Exception if an error occurs during execution
   */
  protected <T> ThroughputResult<T> measureThroughput(Callable<T> task, int concurrency) throws Exception {
    long startTime = System.nanoTime();
    List<Future<T>> futures = executeConcurrently(task, concurrency);
    List<T> results = new ArrayList<>(concurrency);
    List<Long> latencies = new ArrayList<>(concurrency);
    
    for (Future<T> future : futures) {
      try {
        results.add(future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      catch (ExecutionException e) {
        log.error("Error executing task", e.getCause());
        throw new RuntimeException("Error executing task", e.getCause());
      }
    }
    
    long endTime = System.nanoTime();
    long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    double operationsPerSecond = (double) concurrency / (totalDurationMs / 1000.0);
    
    return new ThroughputResult<>(results, totalDurationMs, operationsPerSecond, latencies);
  }

  /**
   * Measures the throughput of the given task executed concurrently with the default level of parallelism.
   *
   * @param task the task to execute
   * @param <T> the return type of the task
   * @return a ThroughputResult containing the throughput metrics
   * @throws Exception if an error occurs during execution
   */
  protected <T> ThroughputResult<T> measureThroughput(Callable<T> task) throws Exception {
    return measureThroughput(task, DEFAULT_CONCURRENCY);
  }

  /**
   * Compares the performance of the given task executed with platform threads and virtual threads.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent executions
   * @param <T> the return type of the task
   * @return a PerformanceComparisonResult containing the comparison metrics
   * @throws Exception if an error occurs during execution
   */
  protected <T> PerformanceComparisonResult<T> compareThreadPerformance(Callable<T> task, int concurrency) throws Exception {
    // Measure platform thread performance
    ExecutorService platformExecutor = createPlatformThreadExecutor();
    ExecutorService originalExecutor = this.executorService;
    this.executorService = platformExecutor;
    ThroughputResult<T> platformResult = measureThroughput(task, concurrency);
    platformExecutor.shutdown();
    platformExecutor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Measure virtual thread performance
    ExecutorService virtualExecutor = createVirtualThreadExecutor();
    this.executorService = virtualExecutor;
    ThroughputResult<T> virtualResult = measureThroughput(task, concurrency);
    virtualExecutor.shutdown();
    virtualExecutor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Restore original executor
    this.executorService = originalExecutor;
    
    return new PerformanceComparisonResult<>(platformResult, virtualResult);
  }

  /**
   * Executes an HTTP operation with retry logic.
   *
   * @param operation the HTTP operation to execute
   * @param maxRetries the maximum number of retries
   * @param <T> the return type of the operation
   * @return the result of the operation
   * @throws IOException if an I/O error occurs
   */
  protected <T> T executeWithRetry(Callable<T> operation, int maxRetries) throws IOException {
    int retries = 0;
    while (true) {
      try {
        return operation.call();
      }
      catch (IOException e) {
        if (retries >= maxRetries) {
          throw e;
        }
        retries++;
        log.warn("Retrying operation after error: {}", e.getMessage());
        try {
          Thread.sleep(Duration.ofSeconds(1).toMillis());
        }
        catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted during retry", ie);
        }
      }
      catch (Exception e) {
        throw new IOException("Error executing operation", e);
      }
    }
  }

  /**
   * Executes an HTTP operation with default retry logic.
   *
   * @param operation the HTTP operation to execute
   * @param <T> the return type of the operation
   * @return the result of the operation
   * @throws IOException if an I/O error occurs
   */
  protected <T> T executeWithRetry(Callable<T> operation) throws IOException {
    return executeWithRetry(operation, 3);
  }

  /**
   * Result of a task execution with timing information.
   *
   * @param <T> the type of the result
   */
  protected static class ExecutionResult<T> {
    private final T result;
    private final long durationMs;

    public ExecutionResult(T result, long durationMs) {
      this.result = result;
      this.durationMs = durationMs;
    }

    public T getResult() {
      return result;
    }

    public long getDurationMs() {
      return durationMs;
    }
  }

  /**
   * Result of a throughput measurement.
   *
   * @param <T> the type of the results
   */
  protected static class ThroughputResult<T> {
    private final List<T> results;
    private final long totalDurationMs;
    private final double operationsPerSecond;
    private final List<Long> latencies;

    public ThroughputResult(List<T> results, long totalDurationMs, double operationsPerSecond, List<Long> latencies) {
      this.results = results;
      this.totalDurationMs = totalDurationMs;
      this.operationsPerSecond = operationsPerSecond;
      this.latencies = latencies;
    }

    public List<T> getResults() {
      return results;
    }

    public long getTotalDurationMs() {
      return totalDurationMs;
    }

    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }

    public List<Long> getLatencies() {
      return latencies;
    }
  }

  /**
   * Result of a performance comparison between platform threads and virtual threads.
   *
   * @param <T> the type of the results
   */
  protected static class PerformanceComparisonResult<T> {
    private final ThroughputResult<T> platformResult;
    private final ThroughputResult<T> virtualResult;

    public PerformanceComparisonResult(ThroughputResult<T> platformResult, ThroughputResult<T> virtualResult) {
      this.platformResult = platformResult;
      this.virtualResult = virtualResult;
    }

    public ThroughputResult<T> getPlatformResult() {
      return platformResult;
    }

    public ThroughputResult<T> getVirtualResult() {
      return virtualResult;
    }

    /**
     * Calculates the throughput improvement factor of virtual threads over platform threads.
     *
     * @return the throughput improvement factor (> 1.0 means virtual threads are faster)
     */
    public double getThroughputImprovementFactor() {
      return virtualResult.getOperationsPerSecond() / platformResult.getOperationsPerSecond();
    }

    /**
     * Logs a summary of the performance comparison.
     */
    public void logSummary() {
      log.info("Performance Comparison Summary:");
      log.info("  Platform Threads:");
      log.info("    Total Duration: {} ms", platformResult.getTotalDurationMs());
      log.info("    Operations/sec: {}", String.format("%.2f", platformResult.getOperationsPerSecond()));
      log.info("  Virtual Threads:");
      log.info("    Total Duration: {} ms", virtualResult.getTotalDurationMs());
      log.info("    Operations/sec: {}", String.format("%.2f", virtualResult.getOperationsPerSecond()));
      log.info("  Improvement Factor: {}x", String.format("%.2f", getThroughputImprovementFactor()));
    }
  }
}