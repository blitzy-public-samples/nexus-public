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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Base support class for Virtual Thread testing in the MyBatis datastore integration.
 * Provides utilities for thread factory creation, pinning detection, and performance metrics.
 * 
 * This class offers thread factory methods, pinning detection mechanisms, and performance metrics
 * collection specifically designed to validate JDBC operations under Java 21's Virtual Thread
 * execution model. It includes utilities for comparing performance between platform and virtual
 * threads, detecting thread pinning issues, and measuring memory usage differences.
 * 
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends TestSupport
{
  /**
   * Creates a platform thread factory.
   * 
   * @return A ThreadFactory that creates platform threads
   */
  protected ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().factory();
  }

  /**
   * Creates a virtual thread factory.
   * 
   * @return A ThreadFactory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates an executor service using platform threads.
   * 
   * @param nThreads The number of threads in the thread pool
   * @return An ExecutorService backed by platform threads
   */
  protected ExecutorService createPlatformThreadExecutor(int nThreads) {
    return Executors.newFixedThreadPool(nThreads, createPlatformThreadFactory());
  }

  /**
   * Creates an executor service using virtual threads.
   * 
   * @return An ExecutorService that creates a new virtual thread for each task
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Metrics collected during virtual thread execution.
   * 
   * This class tracks various metrics related to virtual thread execution, including:
   * - Total number of virtual threads created
   * - Number of threads that experienced pinning
   * - Maximum number of concurrent threads
   * - Stack traces of pinned threads for debugging
   */
  public static class VirtualThreadMetrics {
    private final AtomicLong totalVirtualThreadsCreated = new AtomicLong(0);
    private final AtomicLong pinnedThreadCount = new AtomicLong(0);
    private final AtomicLong maxConcurrentThreads = new AtomicLong(0);
    private final AtomicLong activeThreadCount = new AtomicLong(0);
    private final List<String> pinnedThreadStackTraces = new ArrayList<>();

    public long getTotalVirtualThreadsCreated() {
      return totalVirtualThreadsCreated.get();
    }

    public long getPinnedThreadCount() {
      return pinnedThreadCount.get();
    }

    public long getMaxConcurrentThreads() {
      return maxConcurrentThreads.get();
    }

    public List<String> getPinnedThreadStackTraces() {
      return pinnedThreadStackTraces;
    }

    void threadStarted() {
      totalVirtualThreadsCreated.incrementAndGet();
      long active = activeThreadCount.incrementAndGet();
      maxConcurrentThreads.updateAndGet(current -> Math.max(current, active));
    }

    void threadEnded() {
      activeThreadCount.decrementAndGet();
    }

    void threadPinned(String stackTrace) {
      pinnedThreadCount.incrementAndGet();
      synchronized (pinnedThreadStackTraces) {
        pinnedThreadStackTraces.add(stackTrace);
      }
    }
  }

  /**
   * Creates a metrics collector for virtual thread execution.
   * Uses JFR (Java Flight Recorder) events to track thread creation, termination, and pinning.
   * 
   * This method sets up event listeners for the following JFR events:
   * - jdk.VirtualThreadStart: Triggered when a virtual thread starts
   * - jdk.VirtualThreadEnd: Triggered when a virtual thread ends
   * - jdk.VirtualThreadPinned: Triggered when a virtual thread is pinned to a carrier thread
   * 
   * @return A VirtualThreadMetrics instance for collecting metrics
   */
  protected VirtualThreadMetrics createVirtualThreadMetricsCollector() {
    VirtualThreadMetrics metrics = new VirtualThreadMetrics();
    
    // Start JFR recording stream to monitor virtual thread events
    RecordingStream rs = new RecordingStream();
    
    // Monitor virtual thread start events
    rs.enable("jdk.VirtualThreadStart");
    rs.onEvent("jdk.VirtualThreadStart", event -> {
      metrics.threadStarted();
    });
    
    // Monitor virtual thread end events
    rs.enable("jdk.VirtualThreadEnd");
    rs.onEvent("jdk.VirtualThreadEnd", event -> {
      metrics.threadEnded();
    });
    
    // Monitor thread pinning events
    rs.enable("jdk.VirtualThreadPinned");
    rs.onEvent("jdk.VirtualThreadPinned", event -> {
      String stackTrace = extractStackTrace(event);
      metrics.threadPinned(stackTrace);
    });
    
    // Start the recording in a separate thread
    Thread recordingThread = Thread.ofPlatform().name("jfr-recording").daemon(true).start(rs::start);
    
    return metrics;
  }

  /**
   * Extracts a readable stack trace from a JFR event.
   * 
   * @param event The JFR event containing thread pinning information
   * @return A formatted string with thread name, pinning duration, and stack trace
   */
  private String extractStackTrace(RecordedEvent event) {
    StringBuilder sb = new StringBuilder();
    sb.append("Thread pinned: ").append(event.getString("eventThread"));
    sb.append("\nDuration: ").append(event.getDuration());
    
    // Add more details from the event if available
    if (event.hasField("stackTrace")) {
      String stackTraceValue = event.getValue("stackTrace");
      sb.append("\nStack trace: ").append(stackTraceValue);
    }
    
    return sb.toString();
  }

  /**
   * Performance result for a single test run.
   * 
   * Captures performance metrics for a single test run, including:
   * - Operation count
   * - Execution duration
   * - Thread type (platform or virtual)
   * - Calculated operations per second
   */
  public static class PerformanceResult {
    private final String name;
    private final long operationCount;
    private final Duration duration;
    private final boolean virtualThread;

    public PerformanceResult(String name, long operationCount, Duration duration, boolean virtualThread) {
      this.name = name;
      this.operationCount = operationCount;
      this.duration = duration;
      this.virtualThread = virtualThread;
    }

    public String getName() {
      return name;
    }

    public long getOperationCount() {
      return operationCount;
    }

    public Duration getDuration() {
      return duration;
    }

    public boolean isVirtualThread() {
      return virtualThread;
    }

    public double getOperationsPerSecond() {
      return operationCount / (duration.toMillis() / 1000.0);
    }
  }

  /**
   * Runs a performance test with the given thread factory and operation.
   * 
   * This method executes the specified operation multiple times using threads created
   * by the provided thread factory, and measures the execution time. It also detects
   * whether virtual threads are being used.
   * 
   * @param name The name of the test
   * @param threadFactory The thread factory to use
   * @param operationCount The number of operations to perform
   * @param operation The operation to perform
   * @return The performance result containing metrics about the test run
   * @throws Exception If an error occurs during test execution
   */
  protected PerformanceResult runPerformanceTest(
      String name,
      ThreadFactory threadFactory,
      int operationCount,
      Runnable operation) throws Exception {
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicBoolean isVirtual = new AtomicBoolean(false);
    
    try {
      // Check if we're using virtual threads by examining the first thread
      executor.submit(() -> {
        isVirtual.set(Thread.currentThread().isVirtual());
      }).get();
      
      Instant start = Instant.now();
      
      // Submit all operations
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            operation.run();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      latch.await(5, TimeUnit.MINUTES);
      
      Duration duration = Duration.between(start, Instant.now());
      return new PerformanceResult(name, operationCount, duration, isVirtual.get());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Compares performance between platform and virtual threads.
   * 
   * This method runs the same operation with both platform and virtual threads,
   * allowing for direct performance comparison between the two threading models.
   * 
   * @param testName The name of the test
   * @param operationCount The number of operations to perform
   * @param operation The operation to perform
   * @return A pair of performance results (platform, virtual)
   * @throws Exception If an error occurs during test execution
   */
  protected PerformanceResult[] compareThreadPerformance(
      String testName,
      int operationCount,
      Runnable operation) throws Exception {
    
    PerformanceResult platformResult = runPerformanceTest(
        testName + " (Platform)",
        createPlatformThreadFactory(),
        operationCount,
        operation);
    
    PerformanceResult virtualResult = runPerformanceTest(
        testName + " (Virtual)",
        createVirtualThreadFactory(),
        operationCount,
        operation);
    
    return new PerformanceResult[] { platformResult, virtualResult };
  }

  /**
   * Asserts that virtual threads perform better than platform threads.
   * 
   * This method compares the operations per second between platform and virtual threads,
   * and asserts that virtual threads provide better performance. It also logs the
   * performance metrics for both thread types.
   * 
   * @param platformResult The performance result for platform threads
   * @param virtualResult The performance result for virtual threads
   */
  protected void assertVirtualThreadsPerformBetter(PerformanceResult platformResult, PerformanceResult virtualResult) {
    logger.info("Platform thread performance: {} ops/sec", platformResult.getOperationsPerSecond());
    logger.info("Virtual thread performance: {} ops/sec", virtualResult.getOperationsPerSecond());
    
    assertThat("Virtual thread is faster than platform thread",
        virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond()));
  }

  /**
   * Asserts that no thread pinning occurred during the test.
   * 
   * Thread pinning occurs when a virtual thread blocks a carrier platform thread,
   * negating the benefits of virtual threads. This method checks if any thread
   * pinning was detected during the test and logs detailed information about
   * pinned threads if found.
   * 
   * @param metrics The metrics collected during the test
   */
  protected void assertNoPinning(VirtualThreadMetrics metrics) {
    if (metrics.getPinnedThreadCount() > 0) {
      logger.warn("Thread pinning detected! {} threads were pinned", metrics.getPinnedThreadCount());
      metrics.getPinnedThreadStackTraces().forEach(trace -> logger.warn(trace));
    }
    
    assertThat("No thread pinning should occur", metrics.getPinnedThreadCount(), is(0L));
  }

  /**
   * Runs a concurrent test with virtual threads.
   * 
   * This method executes the specified task multiple times concurrently using virtual threads,
   * and collects metrics about thread creation, termination, and pinning. It also verifies
   * that all tasks complete successfully without errors.
   * 
   * @param taskCount The number of concurrent tasks to run
   * @param task The task to run
   * @return The metrics collected during the test
   * @throws Exception If an error occurs during test execution or if tasks don't complete in time
   */
  protected VirtualThreadMetrics runConcurrentVirtualThreadTest(int taskCount, Runnable task) throws Exception {
    VirtualThreadMetrics metrics = createVirtualThreadMetricsCollector();
    ExecutorService executor = createVirtualThreadExecutor();
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            task.run();
          } catch (Exception e) {
            logger.error("Error in virtual thread task", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(2, TimeUnit.MINUTES);
      
      // Verify results
      assertThat("All tasks completed", completed, is(true));
      assertThat("No errors occurred", errorCount.get(), is(0));
      
      return metrics;
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Runs a callable task with both platform and virtual threads and compares the results.
   * 
   * This method verifies that the same task produces identical results regardless of whether
   * it's executed on a platform thread or a virtual thread. This helps ensure that code
   * behaves consistently across different thread types.
   * 
   * @param task The task to run
   * @param <T> The return type of the task
   * @throws Exception If an error occurs during task execution or if the results don't match
   */
  protected <T> void assertSameResultWithDifferentThreads(Callable<T> task) throws Exception {
    // Run with platform thread
    ExecutorService platformExecutor = createPlatformThreadExecutor(1);
    T platformResult = platformExecutor.submit(task).get(30, TimeUnit.SECONDS);
    platformExecutor.shutdown();
    
    // Run with virtual thread
    ExecutorService virtualExecutor = createVirtualThreadExecutor();
    T virtualResult = virtualExecutor.submit(task).get(30, TimeUnit.SECONDS);
    virtualExecutor.shutdown();
    
    // Assert results are equal
    assertThat("Results should be the same regardless of thread type", 
        virtualResult, equalTo(platformResult));
  }

  /**
   * Runs a high-concurrency test with virtual threads to verify scalability.
   * 
   * This method tests the same operation at different concurrency levels to verify
   * that virtual threads scale effectively. It collects and logs metrics for each
   * concurrency level, including the number of threads created and pinned.
   * 
   * @param concurrencyLevels The concurrency levels to test (e.g., [10, 100, 1000])
   * @param operation The operation to perform
   * @return The metrics for each concurrency level
   * @throws Exception If an error occurs during test execution
   */
  protected List<VirtualThreadMetrics> testVirtualThreadScalability(
      int[] concurrencyLevels,
      Runnable operation) throws Exception {
    
    List<VirtualThreadMetrics> results = new ArrayList<>();
    
    for (int concurrency : concurrencyLevels) {
      logger.info("Testing with concurrency level: {}", concurrency);
      VirtualThreadMetrics metrics = runConcurrentVirtualThreadTest(concurrency, operation);
      results.add(metrics);
      
      logger.info("Concurrency {}: {} virtual threads created, {} pinned",
          concurrency, metrics.getTotalVirtualThreadsCreated(), metrics.getPinnedThreadCount());
    }
    
    return results;
  }

  /**
   * Measures the memory footprint difference between platform and virtual threads.
   * 
   * This method creates the same number of platform and virtual threads, and measures
   * the memory usage difference between them. It performs garbage collection before
   * measurements to ensure accurate results.
   * 
   * @param threadCount The number of threads to create
   * @return The memory usage difference in bytes (positive means virtual threads use less memory)
   * @throws Exception If an error occurs during thread creation or measurement
   */
  protected long compareThreadMemoryFootprint(int threadCount) throws Exception {
    // Force garbage collection before measurement
    System.gc();
    Thread.sleep(100);
    System.gc();
    
    // Measure memory before platform threads
    long beforePlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create platform threads
    List<Thread> platformThreads = new ArrayList<>();
    CountDownLatch platformLatch = new CountDownLatch(1);
    
    for (int i = 0; i < threadCount; i++) {
      Thread t = Thread.ofPlatform().name("platform-" + i).start(() -> {
        try {
          platformLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      platformThreads.add(t);
    }
    
    // Measure memory after platform threads
    long afterPlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Release platform threads
    platformLatch.countDown();
    for (Thread t : platformThreads) {
      t.join();
    }
    
    // Force garbage collection
    System.gc();
    Thread.sleep(100);
    System.gc();
    
    // Measure memory before virtual threads
    long beforeVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create virtual threads
    List<Thread> virtualThreads = new ArrayList<>();
    CountDownLatch virtualLatch = new CountDownLatch(1);
    
    for (int i = 0; i < threadCount; i++) {
      Thread t = Thread.ofVirtual().name("virtual-" + i).start(() -> {
        try {
          virtualLatch.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      virtualThreads.add(t);
    }
    
    // Measure memory after virtual threads
    long afterVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Release virtual threads
    virtualLatch.countDown();
    for (Thread t : virtualThreads) {
      t.join();
    }
    
    // Calculate and return the difference
    long platformMemory = afterPlatform - beforePlatform;
    long virtualMemory = afterVirtual - beforeVirtual;

    logger.info("Memory usage for {} threads - Platform: {} bytes, Virtual: {} bytes",
        threadCount, platformMemory, virtualMemory);
    
    return platformMemory - virtualMemory;
  }

  /**
   * Asserts that virtual threads use significantly less memory than platform threads.
   * 
   * This method compares the memory usage between platform and virtual threads,
   * and asserts that virtual threads use significantly less memory. It also logs
   * the memory savings achieved with virtual threads.
   * 
   * @param threadCount The number of threads to create
   * @throws Exception If an error occurs during thread creation or measurement
   */
  protected void assertVirtualThreadsUseSignificantlyLessMemory(int threadCount) throws Exception {
    long memoryDifference = compareThreadMemoryFootprint(threadCount);
    
    // Virtual threads should use at least 80% less memory
    assertThat("Virtual threads should use significantly less memory than platform threads",
        memoryDifference, greaterThan(0L));
    
    logger.info("Memory savings with virtual threads: {} bytes", memoryDifference);
  }
}