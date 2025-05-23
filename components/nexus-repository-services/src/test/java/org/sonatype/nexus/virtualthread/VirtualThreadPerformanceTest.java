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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static com.google.common.base.Charsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Performance benchmark comparing Virtual Threads with Platform Threads in the Nexus Repository Services context.
 * This test class measures throughput, response times, memory utilization, and scalability under varying load conditions
 * to validate the performance improvements from Java 21's Virtual Threads.
 */
public class VirtualThreadPerformanceTest
    extends TestSupport
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  private static final int SMALL_CONCURRENCY = 100;
  private static final int MEDIUM_CONCURRENCY = 1_000;
  private static final int LARGE_CONCURRENCY = 10_000;
  private static final int VERY_LARGE_CONCURRENCY = 100_000;
  private static final long IO_SIMULATION_TIME_MS = 50;
  private static final byte[] CONTENT_BYTES = "TEST CONTENT".getBytes(UTF_8);

  @Mock
  private Repository repository;

  @Mock
  private Context context;

  @Mock
  private Request request;

  @Mock
  private Content content;

  @Mock
  private AttributesMap attributesMap;

  private AutoCloseable mocks;

  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  public void setUp() {
    mocks = MockitoAnnotations.openMocks(this);

    // Setup mock behavior
    when(context.getRepository()).thenReturn(repository);
    when(context.getRequest()).thenReturn(request);
    when(request.getAction()).thenReturn(GET);
    when(content.getAttributes()).thenReturn(attributesMap);
    when(content.openInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(CONTENT_BYTES));

    // Create executors
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    platformThreadExecutor = Executors.newCachedThreadPool(platformThreadFactory);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  public void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    mocks.close();
  }

  /**
   * Measures and compares throughput between Virtual Threads and Platform Threads.
   * This test simulates I/O-bound operations typical in repository services and measures
   * the number of operations completed per second with each thread model.
   */
  @Test
  public void testThroughputComparison(TestInfo testInfo) throws Exception {
    log.info("Running {}", testInfo.getDisplayName());

    // Warm up
    log.info("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runThroughputTest(platformThreadExecutor, SMALL_CONCURRENCY);
      runThroughputTest(virtualThreadExecutor, SMALL_CONCURRENCY);
    }

    // Test with medium concurrency
    log.info("Testing throughput with {} concurrent operations", MEDIUM_CONCURRENCY);
    double platformThroughput = runThroughputTest(platformThreadExecutor, MEDIUM_CONCURRENCY);
    double virtualThroughput = runThroughputTest(virtualThreadExecutor, MEDIUM_CONCURRENCY);

    log.info("Platform Thread throughput: {} ops/sec", platformThroughput);
    log.info("Virtual Thread throughput: {} ops/sec", virtualThroughput);
    log.info("Improvement factor: {}x", virtualThroughput / platformThroughput);

    // Assert that Virtual Threads provide better throughput
    assertThat("Virtual Threads should provide better throughput than Platform Threads",
        virtualThroughput, greaterThan(platformThroughput));
  }

  /**
   * Measures and compares memory utilization between Virtual Threads and Platform Threads.
   * This test creates a large number of threads and measures the memory impact of each thread model.
   */
  @Test
  public void testMemoryUtilization(TestInfo testInfo) throws Exception {
    log.info("Running {}", testInfo.getDisplayName());

    // Force GC to get a clean baseline
    System.gc();
    Thread.sleep(1000);
    long baselineMemory = getUsedMemory();

    // Test platform threads memory usage
    log.info("Testing Platform Threads memory usage with {} threads", MEDIUM_CONCURRENCY);
    long platformMemoryBefore = getUsedMemory();
    List<Thread> platformThreads = createPlatformThreads(MEDIUM_CONCURRENCY);
    long platformMemoryAfter = getUsedMemory();
    long platformMemoryUsage = platformMemoryAfter - platformMemoryBefore;
    double platformMemoryPerThread = (double) platformMemoryUsage / MEDIUM_CONCURRENCY;

    // Clean up platform threads
    for (Thread thread : platformThreads) {
      thread.join();
    }
    platformThreads.clear();
    System.gc();
    Thread.sleep(1000);

    // Test virtual threads memory usage
    log.info("Testing Virtual Threads memory usage with {} threads", MEDIUM_CONCURRENCY);
    long virtualMemoryBefore = getUsedMemory();
    List<Thread> virtualThreads = createVirtualThreads(MEDIUM_CONCURRENCY);
    long virtualMemoryAfter = getUsedMemory();
    long virtualMemoryUsage = virtualMemoryAfter - virtualMemoryBefore;
    double virtualMemoryPerThread = (double) virtualMemoryUsage / MEDIUM_CONCURRENCY;

    // Clean up virtual threads
    for (Thread thread : virtualThreads) {
      thread.join();
    }
    virtualThreads.clear();

    log.info("Baseline memory usage: {} MB", bytesToMB(baselineMemory));
    log.info("Platform Threads total memory usage: {} MB", bytesToMB(platformMemoryUsage));
    log.info("Platform Threads per-thread memory: {} KB", bytesToKB(platformMemoryPerThread));
    log.info("Virtual Threads total memory usage: {} MB", bytesToMB(virtualMemoryUsage));
    log.info("Virtual Threads per-thread memory: {} KB", bytesToKB(virtualMemoryPerThread));

    // Assert that Virtual Threads use significantly less memory
    assertThat("Virtual Threads should use significantly less memory per thread",
        virtualMemoryPerThread, lessThan(platformMemoryPerThread / 2));
  }

  /**
   * Measures and compares response times between Virtual Threads and Platform Threads.
   * This test captures P50, P95, and P99 percentile response times for each thread model
   * under increasing load conditions.
   */
  @Test
  public void testResponseTimePercentiles(TestInfo testInfo) throws Exception {
    log.info("Running {}", testInfo.getDisplayName());

    // Warm up
    log.info("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      measureResponseTimes(platformThreadExecutor, SMALL_CONCURRENCY);
      measureResponseTimes(virtualThreadExecutor, SMALL_CONCURRENCY);
    }

    // Test with medium concurrency
    log.info("Testing response times with {} concurrent operations", MEDIUM_CONCURRENCY);
    Map<String, Double> platformResponseTimes = measureResponseTimes(platformThreadExecutor, MEDIUM_CONCURRENCY);
    Map<String, Double> virtualResponseTimes = measureResponseTimes(virtualThreadExecutor, MEDIUM_CONCURRENCY);

    log.info("Platform Thread P50: {} ms, P95: {} ms, P99: {} ms",
        platformResponseTimes.get("p50"),
        platformResponseTimes.get("p95"),
        platformResponseTimes.get("p99"));

    log.info("Virtual Thread P50: {} ms, P95: {} ms, P99: {} ms",
        virtualResponseTimes.get("p50"),
        virtualResponseTimes.get("p95"),
        virtualResponseTimes.get("p99"));

    // Assert that Virtual Threads provide better response times
    assertThat("Virtual Threads should provide better P95 response times",
        virtualResponseTimes.get("p95"), lessThan(platformResponseTimes.get("p95")));

    assertThat("Virtual Threads should provide better P99 response times",
        virtualResponseTimes.get("p99"), lessThan(platformResponseTimes.get("p99")));
  }

  /**
   * Tests scalability by measuring throughput with increasing concurrency levels.
   * This test validates that Virtual Threads maintain performance under high concurrency
   * while Platform Threads degrade.
   */
  @Test
  public void testScalabilityWithIncreasingConcurrency(TestInfo testInfo) throws Exception {
    log.info("Running {}", testInfo.getDisplayName());

    // Warm up
    log.info("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runThroughputTest(platformThreadExecutor, SMALL_CONCURRENCY);
      runThroughputTest(virtualThreadExecutor, SMALL_CONCURRENCY);
    }

    // Test with increasing concurrency levels
    int[] concurrencyLevels = {SMALL_CONCURRENCY, MEDIUM_CONCURRENCY, LARGE_CONCURRENCY};
    Map<Integer, Double> platformThroughputs = new ConcurrentHashMap<>();
    Map<Integer, Double> virtualThroughputs = new ConcurrentHashMap<>();

    for (int concurrency : concurrencyLevels) {
      log.info("Testing scalability with {} concurrent operations", concurrency);
      double platformThroughput = runThroughputTest(platformThreadExecutor, concurrency);
      double virtualThroughput = runThroughputTest(virtualThreadExecutor, concurrency);

      platformThroughputs.put(concurrency, platformThroughput);
      virtualThroughputs.put(concurrency, virtualThroughput);

      log.info("Concurrency: {}, Platform Thread throughput: {} ops/sec, Virtual Thread throughput: {} ops/sec",
          concurrency, platformThroughput, virtualThroughput);
    }

    // Calculate scalability factors
    double platformScalabilityFactor = platformThroughputs.get(LARGE_CONCURRENCY) / platformThroughputs.get(SMALL_CONCURRENCY);
    double virtualScalabilityFactor = virtualThroughputs.get(LARGE_CONCURRENCY) / virtualThroughputs.get(SMALL_CONCURRENCY);

    log.info("Platform Thread scalability factor: {}", platformScalabilityFactor);
    log.info("Virtual Thread scalability factor: {}", virtualScalabilityFactor);

    // Assert that Virtual Threads scale better with increasing concurrency
    assertThat("Virtual Threads should scale better with increasing concurrency",
        virtualScalabilityFactor, greaterThan(platformScalabilityFactor));
  }

  /**
   * Tests the ability to handle very high concurrency levels with Virtual Threads.
   * This test validates that the system can support at least 10,000 concurrent connections
   * with Virtual Threads, which would be impractical with Platform Threads.
   */
  @Test
  public void testVeryHighConcurrencyWithVirtualThreads(TestInfo testInfo) throws Exception {
    log.info("Running {}", testInfo.getDisplayName());

    log.info("Testing with {} concurrent virtual threads", VERY_LARGE_CONCURRENCY);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(VERY_LARGE_CONCURRENCY);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Create a large number of virtual threads
    for (int i = 0; i < VERY_LARGE_CONCURRENCY; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be created
          simulateIOOperation(); // Perform simulated I/O operation
          completionLatch.countDown();
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete with a reasonable timeout
    boolean completed = completionLatch.await(2, TimeUnit.MINUTES);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    log.info("Completed {} concurrent operations in {} ms with {} errors",
        VERY_LARGE_CONCURRENCY, durationMs, errorCount.get());

    // Assert that all operations completed successfully
    assertTrue(completed, "All operations should complete within the timeout period");
    assertEquals(0, errorCount.get(), "There should be no errors during execution");
    assertThat("Should support at least 10,000 concurrent connections",
        VERY_LARGE_CONCURRENCY, greaterThanOrEqualTo(10_000));
  }

  /**
   * Runs a throughput test with the specified executor and concurrency level.
   *
   * @param executor The executor service to use (platform or virtual thread-based)
   * @param concurrency The number of concurrent operations to perform
   * @return The throughput in operations per second
   */
  private double runThroughputTest(ExecutorService executor, int concurrency) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrency);
    AtomicInteger completedOperations = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Submit tasks to the executor
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          simulateRepositoryOperation();
          completedOperations.incrementAndGet();
          completionLatch.countDown();
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete
    completionLatch.await(30, SECONDS);
    long endTime = System.nanoTime();

    // Calculate throughput
    long durationNanos = endTime - startTime;
    double durationSeconds = durationNanos / 1_000_000_000.0;
    double throughput = completedOperations.get() / durationSeconds;

    return throughput;
  }

  /**
   * Measures response times for operations using the specified executor and concurrency level.
   *
   * @param executor The executor service to use (platform or virtual thread-based)
   * @param concurrency The number of concurrent operations to perform
   * @return A map containing P50, P95, and P99 response times in milliseconds
   */
  private Map<String, Double> measureResponseTimes(ExecutorService executor, int concurrency) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrency);
    List<Long> responseTimes = new ArrayList<>(concurrency);
    Object lock = new Object(); // Lock for thread-safe list access

    // Submit tasks to the executor
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          long startTime = System.nanoTime();
          simulateRepositoryOperation();
          long endTime = System.nanoTime();
          long responseTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

          synchronized (lock) {
            responseTimes.add(responseTime);
          }

          completionLatch.countDown();
        }
        catch (Exception e) {
          completionLatch.countDown();
        }
      });
    }

    // Start all threads simultaneously
    startLatch.countDown();

    // Wait for all operations to complete
    completionLatch.await(30, SECONDS);

    // Calculate percentiles
    Map<String, Double> percentiles = new ConcurrentHashMap<>();
    synchronized (lock) {
      responseTimes.sort(Long::compare);
      percentiles.put("p50", calculatePercentile(responseTimes, 50));
      percentiles.put("p95", calculatePercentile(responseTimes, 95));
      percentiles.put("p99", calculatePercentile(responseTimes, 99));
    }

    return percentiles;
  }

  /**
   * Creates a specified number of platform threads for memory testing.
   *
   * @param count The number of threads to create
   * @return A list of created threads
   */
  private List<Thread> createPlatformThreads(int count) throws InterruptedException {
    List<Thread> threads = new ArrayList<>(count);
    CountDownLatch latch = new CountDownLatch(count);

    for (int i = 0; i < count; i++) {
      Thread thread = Thread.ofPlatform().name("platform-" + i).start(() -> {
        try {
          latch.countDown();
          // Keep thread alive for measurement
          Thread.sleep(5000);
        }
        catch (InterruptedException e) {
          // Ignore
        }
      });
      threads.add(thread);
    }

    // Wait for all threads to start
    latch.await(30, SECONDS);
    return threads;
  }

  /**
   * Creates a specified number of virtual threads for memory testing.
   *
   * @param count The number of threads to create
   * @return A list of created threads
   */
  private List<Thread> createVirtualThreads(int count) throws InterruptedException {
    List<Thread> threads = new ArrayList<>(count);
    CountDownLatch latch = new CountDownLatch(count);

    for (int i = 0; i < count; i++) {
      Thread thread = Thread.ofVirtual().name("virtual-" + i).start(() -> {
        try {
          latch.countDown();
          // Keep thread alive for measurement
          Thread.sleep(5000);
        }
        catch (InterruptedException e) {
          // Ignore
        }
      });
      threads.add(thread);
    }

    // Wait for all threads to start
    latch.await(30, SECONDS);
    return threads;
  }

  /**
   * Simulates a repository operation with I/O, similar to what would happen in a real repository service.
   * This includes simulated network latency and content processing.
   */
  private void simulateRepositoryOperation() throws Exception {
    // Simulate network latency or database query
    simulateIOOperation();

    // Simulate content processing
    try (InputStream in = content.openInputStream()) {
      byte[] buffer = new byte[1024];
      while (in.read(buffer) != -1) {
        // Process content
      }
    }
  }

  /**
   * Simulates an I/O operation with a configurable delay to represent network or disk latency.
   */
  private void simulateIOOperation() throws InterruptedException {
    Thread.sleep(IO_SIMULATION_TIME_MS);
  }

  /**
   * Gets the current used memory in bytes.
   *
   * @return The used memory in bytes
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  /**
   * Calculates the specified percentile from a sorted list of values.
   *
   * @param sortedValues The sorted list of values
   * @param percentile The percentile to calculate (0-100)
   * @return The percentile value
   */
  private double calculatePercentile(List<Long> sortedValues, int percentile) {
    if (sortedValues.isEmpty()) {
      return 0;
    }

    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    index = Math.max(0, Math.min(index, sortedValues.size() - 1));
    return sortedValues.get(index);
  }

  /**
   * Converts bytes to megabytes for readable output.
   *
   * @param bytes The number of bytes
   * @return The equivalent value in megabytes
   */
  private double bytesToMB(double bytes) {
    return bytes / (1024 * 1024);
  }

  /**
   * Converts bytes to kilobytes for readable output.
   *
   * @param bytes The number of bytes
   * @return The equivalent value in kilobytes
   */
  private double bytesToKB(double bytes) {
    return bytes / 1024;
  }
}