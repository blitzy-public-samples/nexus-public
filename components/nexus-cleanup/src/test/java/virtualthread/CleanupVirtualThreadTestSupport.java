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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.experimental.categories.Category;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Support class for virtual thread testing in the cleanup component. Provides utilities for creating
 * and comparing platform and virtual thread executors, measuring performance metrics, and running
 * cleanup operations concurrently with different thread types.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class CleanupVirtualThreadTestSupport
    extends TestSupport
{
  /**
   * Default number of threads to use for platform thread executors.
   */
  private static final int DEFAULT_PLATFORM_THREAD_COUNT = 100;

  /**
   * Default timeout for waiting for operations to complete.
   */
  private static final int DEFAULT_TIMEOUT_SECONDS = 60;

  /**
   * System property that controls whether virtual threads are enabled for tests.
   */
  private static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "test.virtual.threads";

  /**
   * Executor services created during tests that need to be shut down.
   */
  private final List<ExecutorService> executorServices = new ArrayList<>();

  @Before
  public void setupVirtualThreadSupport() {
    log.info("Virtual thread test support initialized. Virtual threads enabled: {}", isVirtualThreadsEnabled());
  }

  @After
  public void shutdownExecutors() {
    // Shutdown all executor services created during the test
    executorServices.forEach(executor -> {
      try {
        executor.shutdown();
        if (!executor.awaitTermination(5, SECONDS)) {
          log.warn("Executor did not terminate in the allotted time. Forcing shutdown.");
          executor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while waiting for executor shutdown", e);
      }
    });
    executorServices.clear();
  }

  /**
   * Checks if virtual threads are enabled for testing.
   *
   * @return true if virtual threads are enabled
   */
  public boolean isVirtualThreadsEnabled() {
    return Boolean.parseBoolean(System.getProperty(VIRTUAL_THREADS_ENABLED_PROPERTY, "false"));
  }

  /**
   * Creates a thread factory for virtual threads if enabled, otherwise for platform threads.
   *
   * @return the thread factory
   */
  public ThreadFactory createThreadFactory() {
    return isVirtualThreadsEnabled() ? createVirtualThreadFactory() : createPlatformThreadFactory();
  }

  /**
   * Creates a thread factory for virtual threads.
   *
   * @return the virtual thread factory
   */
  public ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().name("cleanup-virtual-", 0).factory();
  }

  /**
   * Creates a thread factory for platform threads.
   *
   * @return the platform thread factory
   */
  public ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().name("cleanup-platform-", 0).factory();
  }

  /**
   * Creates an executor service using virtual threads if enabled, otherwise using platform threads.
   *
   * @return the executor service
   */
  public ExecutorService createExecutorService() {
    return createExecutorService(DEFAULT_PLATFORM_THREAD_COUNT);
  }

  /**
   * Creates an executor service using virtual threads if enabled, otherwise using platform threads
   * with the specified thread count.
   *
   * @param platformThreadCount the number of platform threads to use if virtual threads are disabled
   * @return the executor service
   */
  public ExecutorService createExecutorService(int platformThreadCount) {
    ExecutorService executor = isVirtualThreadsEnabled() ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(platformThreadCount, createPlatformThreadFactory());
    
    // Track the executor for cleanup
    executorServices.add(executor);
    
    return executor;
  }

  /**
   * Executes a cleanup operation with the specified concurrency level and measures performance.
   *
   * @param operation the cleanup operation to execute
   * @param concurrencyLevel the number of concurrent operations to run
   * @param useVirtualThreads whether to use virtual threads
   * @return the performance result
   */
  public PerformanceResult executeWithConcurrency(
      Supplier<Boolean> operation,
      int concurrencyLevel,
      boolean useVirtualThreads) throws Exception
  {
    return executeWithConcurrency(operation, concurrencyLevel, useVirtualThreads, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Executes a cleanup operation with the specified concurrency level and measures performance.
   *
   * @param operation the cleanup operation to execute
   * @param concurrencyLevel the number of concurrent operations to run
   * @param useVirtualThreads whether to use virtual threads
   * @param timeoutSeconds the maximum time to wait for operations to complete
   * @return the performance result
   */
  public PerformanceResult executeWithConcurrency(
      Supplier<Boolean> operation,
      int concurrencyLevel,
      boolean useVirtualThreads,
      int timeoutSeconds) throws Exception
  {
    log.info("Executing cleanup operation with concurrency level {} using {} threads",
        concurrencyLevel, useVirtualThreads ? "virtual" : "platform");

    // Create appropriate executor based on thread type
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(concurrencyLevel, createPlatformThreadFactory());

    executorServices.add(executor);

    CountDownLatch latch = new CountDownLatch(concurrencyLevel);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Record memory usage before test
    long memoryBefore = getUsedMemory();

    // Record start time
    Instant startTime = Instant.now();

    try {
      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();

      for (int i = 0; i < concurrencyLevel; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            boolean success = operation.get();
            if (success) {
              successCount.incrementAndGet();
            }
            else {
              errorCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in cleanup operation", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }, executor);

        futures.add(future);
      }

      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
      if (!completed) {
        log.warn("Operation timed out before all tasks completed");
      }

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
    finally {
      executor.shutdown();
    }

    // Record end time and calculate duration
    Instant endTime = Instant.now();
    long durationMs = Duration.between(startTime, endTime).toMillis();

    // Record memory usage after test
    long memoryAfter = getUsedMemory();
    long memoryUsed = memoryAfter - memoryBefore;

    // Create and return result
    return new PerformanceResult(
        concurrencyLevel,
        useVirtualThreads,
        durationMs,
        successCount.get(),
        errorCount.get(),
        memoryUsed
    );
  }

  /**
   * Compares the performance of virtual threads vs platform threads for a cleanup operation.
   *
   * @param operation the cleanup operation to execute
   * @param concurrencyLevel the number of concurrent operations to run
   * @param resultConsumer consumer for the comparison result
   */
  public void compareThreadPerformance(
      Supplier<Boolean> operation,
      int concurrencyLevel,
      Consumer<ThreadComparisonResult> resultConsumer) throws Exception
  {
    // Skip if virtual threads are not enabled
    if (!isVirtualThreadsEnabled()) {
      log.info("Skipping thread performance comparison because virtual threads are not enabled");
      return;
    }

    log.info("Comparing thread performance for cleanup operation at concurrency level {}", concurrencyLevel);

    // Run with platform threads
    PerformanceResult platformResult = executeWithConcurrency(operation, concurrencyLevel, false);
    log.info("Platform thread result: {}", platformResult);

    // Run with virtual threads
    PerformanceResult virtualResult = executeWithConcurrency(operation, concurrencyLevel, true);
    log.info("Virtual thread result: {}", virtualResult);

    // Create comparison result
    ThreadComparisonResult comparisonResult = new ThreadComparisonResult(platformResult, virtualResult);
    log.info("Thread comparison result: {}", comparisonResult);

    // Provide result to consumer
    if (resultConsumer != null) {
      resultConsumer.accept(comparisonResult);
    }
  }

  /**
   * Gets the current used memory in bytes.
   *
   * @return the used memory
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  /**
   * Class representing the result of a performance measurement.
   */
  public static class PerformanceResult
  {
    private final int concurrencyLevel;
    private final boolean virtualThreads;
    private final long durationMs;
    private final int successCount;
    private final int errorCount;
    private final long memoryUsed;

    public PerformanceResult(
        int concurrencyLevel,
        boolean virtualThreads,
        long durationMs,
        int successCount,
        int errorCount,
        long memoryUsed)
    {
      this.concurrencyLevel = concurrencyLevel;
      this.virtualThreads = virtualThreads;
      this.durationMs = durationMs;
      this.successCount = successCount;
      this.errorCount = errorCount;
      this.memoryUsed = memoryUsed;
    }

    public int getConcurrencyLevel() {
      return concurrencyLevel;
    }

    public boolean isVirtualThreads() {
      return virtualThreads;
    }

    public long getDurationMs() {
      return durationMs;
    }

    public int getSuccessCount() {
      return successCount;
    }

    public int getErrorCount() {
      return errorCount;
    }

    public long getMemoryUsed() {
      return memoryUsed;
    }

    public double getMemoryUsedMB() {
      return memoryUsed / (1024.0 * 1024.0);
    }

    public double getOperationsPerSecond() {
      int totalOperations = successCount + errorCount;
      return totalOperations > 0 ? (double) totalOperations / (durationMs / 1000.0) : 0;
    }

    @Override
    public String toString() {
      return String.format(
          "Performance(threads: %d, virtual: %s) - Duration: %d ms, Success: %d, Errors: %d, " +
              "Throughput: %.2f ops/sec, Memory: %.2f MB",
          concurrencyLevel, virtualThreads, durationMs, successCount, errorCount,
          getOperationsPerSecond(), getMemoryUsedMB());
    }
  }

  /**
   * Class representing the result of a thread performance comparison.
   */
  public static class ThreadComparisonResult
  {
    private final PerformanceResult platformResult;
    private final PerformanceResult virtualResult;

    public ThreadComparisonResult(PerformanceResult platformResult, PerformanceResult virtualResult) {
      this.platformResult = platformResult;
      this.virtualResult = virtualResult;
    }

    public PerformanceResult getPlatformResult() {
      return platformResult;
    }

    public PerformanceResult getVirtualResult() {
      return virtualResult;
    }

    public double getDurationImprovement() {
      return calculateImprovement(platformResult.getDurationMs(), virtualResult.getDurationMs());
    }

    public double getThroughputImprovement() {
      return calculateImprovement(virtualResult.getOperationsPerSecond(), platformResult.getOperationsPerSecond());
    }

    public double getMemoryImprovement() {
      return calculateImprovement(platformResult.getMemoryUsed(), virtualResult.getMemoryUsed());
    }

    private double calculateImprovement(double oldValue, double newValue) {
      if (oldValue == 0) {
        return 0;
      }
      return ((newValue - oldValue) / oldValue) * 100;
    }

    @Override
    public String toString() {
      return String.format(
          "Thread Comparison - Duration: %.2f%%, Throughput: %.2f%%, Memory: %.2f%%",
          getDurationImprovement(), getThroughputImprovement(), getMemoryImprovement());
    }
  }
}