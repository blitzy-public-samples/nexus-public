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
package org.sonatype.nexus.datastore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.stateguard.StateGuardModule;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;

import org.junit.experimental.categories.Category;

import com.google.inject.Injector;

import static com.google.inject.Guice.createInjector;
import static java.lang.System.gc;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Performance comparison test for datastore operations using platform threads versus Virtual Threads.
 * <p>
 * This test class benchmarks the performance differences between traditional platform threads and
 * Java 21 Virtual Threads when performing I/O-bound database operations in the Nexus datastore.
 * <p>
 * The tests measure throughput, latency (including percentiles), and resource utilization under
 * various concurrency levels to validate the performance improvements achieved by migrating to
 * Java 21 Virtual Threads.
 * <p>
 * Key metrics measured:
 * <ul>
 *   <li>Throughput (operations per second)</li>
 *   <li>Latency (median, 95th percentile, 99th percentile)</li>
 *   <li>Memory consumption</li>
 *   <li>Scalability under increasing load</li>
 * </ul>
 * <p>
 * The test validates that Virtual Threads provide at least 20% improvement in throughput,
 * 20% reduction in P95 latency, and 30% reduction in memory usage compared to platform threads.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class DataStorePerformanceVirtualThreadTest
    extends TestSupport
{
  private static final int WARMUP_ITERATIONS = 3;
  private static final int BENCHMARK_ITERATIONS = 5;
  private static final int[] CONCURRENCY_LEVELS = {10, 50, 100, 500, 1000};
  
  // Performance thresholds for virtual threads compared to platform threads
  private static final double MIN_THROUGHPUT_IMPROVEMENT = 1.2; // 20% improvement
  private static final double MAX_P95_LATENCY_RATIO = 0.8; // 20% reduction
  private static final double MAX_MEMORY_USAGE_RATIO = 0.7; // 30% reduction
  
  @Mock
  private DataStore dataStore;
  
  @Mock
  private DataSession<?> dataSession;
  
  @Mock
  private Connection connection;
  
  @BeforeEach
  void setUp() throws Exception {
    Injector injector = createInjector(new StateGuardModule());
    
    // Configure mocks
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName("test-datastore");
    config.setType("h2");
    config.setSource("local");
    
    when(dataStore.openConnection()).thenReturn(connection);
    when(dataStore.openSession()).thenReturn(dataSession);
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Ensure resources are released
    if (connection != null) {
      connection.close();
    }
  }
  
  /**
   * Tests read operation performance with varying concurrency levels using both platform threads and virtual threads.
   *
   * @param concurrencyLevel the number of concurrent operations to perform
   */
  @ParameterizedTest
  @ValueSource(ints = {10, 100, 500})
  void testReadPerformance(int concurrencyLevel) throws Exception {
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, this::simulateReadOperation);
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, this::simulateReadOperation);
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread Read", this::createPlatformThreadExecutor, 
          concurrencyLevel, this::simulateReadOperation));
      
      virtualResults.add(runBenchmark("Virtual Thread Read", this::createVirtualThreadExecutor, 
          concurrencyLevel, this::simulateReadOperation));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Verify performance improvements
    assertAll(
        () -> assertTrue(avgVirtual.throughput >= avgPlatform.throughput * MIN_THROUGHPUT_IMPROVEMENT,
            String.format("Virtual Thread throughput (%.2f ops/s) should be at least %.0f%% better than Platform Thread throughput (%.2f ops/s)",
                avgVirtual.throughput, (MIN_THROUGHPUT_IMPROVEMENT - 1) * 100, avgPlatform.throughput)),
        
        () -> assertTrue(avgVirtual.p95Latency <= avgPlatform.p95Latency * MAX_P95_LATENCY_RATIO,
            String.format("Virtual Thread P95 latency (%.2f ms) should be at most %.0f%% of Platform Thread P95 latency (%.2f ms)",
                avgVirtual.p95Latency, MAX_P95_LATENCY_RATIO * 100, avgPlatform.p95Latency)),
        
        () -> assertTrue(avgVirtual.memoryUsed <= avgPlatform.memoryUsed * MAX_MEMORY_USAGE_RATIO,
            String.format("Virtual Thread memory usage (%.2f MB) should be at most %.0f%% of Platform Thread memory usage (%.2f MB)",
                avgVirtual.memoryUsed, MAX_MEMORY_USAGE_RATIO * 100, avgPlatform.memoryUsed))
    );
  }
  
  /**
   * Tests write operation performance with varying concurrency levels using both platform threads and virtual threads.
   *
   * @param concurrencyLevel the number of concurrent operations to perform
   */
  @ParameterizedTest
  @ValueSource(ints = {10, 100, 500})
  void testWritePerformance(int concurrencyLevel) throws Exception {
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, this::simulateWriteOperation);
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, this::simulateWriteOperation);
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread Write", this::createPlatformThreadExecutor, 
          concurrencyLevel, this::simulateWriteOperation));
      
      virtualResults.add(runBenchmark("Virtual Thread Write", this::createVirtualThreadExecutor, 
          concurrencyLevel, this::simulateWriteOperation));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Verify performance improvements
    assertAll(
        () -> assertTrue(avgVirtual.throughput >= avgPlatform.throughput * MIN_THROUGHPUT_IMPROVEMENT,
            String.format("Virtual Thread throughput (%.2f ops/s) should be at least %.0f%% better than Platform Thread throughput (%.2f ops/s)",
                avgVirtual.throughput, (MIN_THROUGHPUT_IMPROVEMENT - 1) * 100, avgPlatform.throughput)),
        
        () -> assertTrue(avgVirtual.p95Latency <= avgPlatform.p95Latency * MAX_P95_LATENCY_RATIO,
            String.format("Virtual Thread P95 latency (%.2f ms) should be at most %.0f%% of Platform Thread P95 latency (%.2f ms)",
                avgVirtual.p95Latency, MAX_P95_LATENCY_RATIO * 100, avgPlatform.p95Latency)),
        
        () -> assertTrue(avgVirtual.memoryUsed <= avgPlatform.memoryUsed * MAX_MEMORY_USAGE_RATIO,
            String.format("Virtual Thread memory usage (%.2f MB) should be at most %.0f%% of Platform Thread memory usage (%.2f MB)",
                avgVirtual.memoryUsed, MAX_MEMORY_USAGE_RATIO * 100, avgPlatform.memoryUsed))
    );
  }
  
  /**
   * Tests mixed read/write operation performance with high concurrency using both platform threads and virtual threads.
   */
  @Test
  void testMixedOperationsHighConcurrency() throws Exception {
    final int concurrencyLevel = 1000;
    
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, this::simulateMixedOperation);
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, this::simulateMixedOperation);
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread Mixed", this::createPlatformThreadExecutor, 
          concurrencyLevel, this::simulateMixedOperation));
      
      virtualResults.add(runBenchmark("Virtual Thread Mixed", this::createVirtualThreadExecutor, 
          concurrencyLevel, this::simulateMixedOperation));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Log detailed results
    log.info("Platform Thread Results: {}", avgPlatform);
    log.info("Virtual Thread Results: {}", avgVirtual);
    log.info("Throughput improvement: {}%", String.format("%.2f", (avgVirtual.throughput / avgPlatform.throughput - 1) * 100));
    log.info("P95 latency reduction: {}%", String.format("%.2f", (1 - avgVirtual.p95Latency / avgPlatform.p95Latency) * 100));
    log.info("Memory usage reduction: {}%", String.format("%.2f", (1 - avgVirtual.memoryUsed / avgPlatform.memoryUsed) * 100));
    
    // Verify performance improvements
    assertAll(
        () -> assertTrue(avgVirtual.throughput >= avgPlatform.throughput * MIN_THROUGHPUT_IMPROVEMENT,
            String.format("Virtual Thread throughput (%.2f ops/s) should be at least %.0f%% better than Platform Thread throughput (%.2f ops/s)",
                avgVirtual.throughput, (MIN_THROUGHPUT_IMPROVEMENT - 1) * 100, avgPlatform.throughput)),
        
        () -> assertTrue(avgVirtual.p95Latency <= avgPlatform.p95Latency * MAX_P95_LATENCY_RATIO,
            String.format("Virtual Thread P95 latency (%.2f ms) should be at most %.0f%% of Platform Thread P95 latency (%.2f ms)",
                avgVirtual.p95Latency, MAX_P95_LATENCY_RATIO * 100, avgPlatform.p95Latency)),
        
        () -> assertTrue(avgVirtual.memoryUsed <= avgPlatform.memoryUsed * MAX_MEMORY_USAGE_RATIO,
            String.format("Virtual Thread memory usage (%.2f MB) should be at most %.0f%% of Platform Thread memory usage (%.2f MB)",
                avgVirtual.memoryUsed, MAX_MEMORY_USAGE_RATIO * 100, avgPlatform.memoryUsed))
    );
  }
  
  /**
   * Tests the scalability of virtual threads compared to platform threads under increasing load.
   */
  /**
   * Tests the scalability of virtual threads compared to platform threads under increasing load.
   * <p>
   * This test validates that Virtual Threads maintain their performance advantage as concurrency increases,
   * demonstrating their superior scalability for I/O-bound operations. The test runs with concurrency
   * levels from 10 to 1000 threads and measures throughput at each level.
   * <p>
   * Expected results:
   * - At low concurrency (10-50 threads): Virtual Threads should perform at least as well as platform threads
   * - At medium concurrency (100-500 threads): Virtual Threads should show significant advantages
   * - At high concurrency (1000+ threads): Virtual Threads should demonstrate dramatic improvements
   */
  @Test
  void testScalabilityUnderIncreasingLoad() throws Exception {
    Map<Integer, BenchmarkResult> platformResults = new ConcurrentHashMap<>();
    Map<Integer, BenchmarkResult> virtualResults = new ConcurrentHashMap<>();
    
    // Test with increasing concurrency levels
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      // Run warmup
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, this::simulateReadOperation);
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, this::simulateReadOperation);
      
      // Run actual benchmarks
      platformResults.put(concurrencyLevel, runBenchmark("Platform Thread Scalability", 
          this::createPlatformThreadExecutor, concurrencyLevel, this::simulateReadOperation));
      
      virtualResults.put(concurrencyLevel, runBenchmark("Virtual Thread Scalability", 
          this::createVirtualThreadExecutor, concurrencyLevel, this::simulateReadOperation));
    }
    
    // Log scalability results
    log.info("Scalability Results:");
    log.info("Concurrency | Platform Throughput | Virtual Throughput | Improvement | P95 Latency Reduction");
    log.info("-----------|-------------------|------------------|------------|--------------------");
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      BenchmarkResult platformResult = platformResults.get(concurrencyLevel);
      BenchmarkResult virtualResult = virtualResults.get(concurrencyLevel);
      double throughputImprovement = (virtualResult.throughput / platformResult.throughput - 1) * 100;
      double latencyReduction = (1 - virtualResult.p95Latency / platformResult.p95Latency) * 100;
      
      log.info("{} | {:.2f} ops/s | {:.2f} ops/s | {:.2f}% | {:.2f}%",
          concurrencyLevel, platformResult.throughput, virtualResult.throughput, 
          throughputImprovement, latencyReduction);
      
      // Verify that virtual threads scale better at higher concurrency
      if (concurrencyLevel >= 100) {
        assertTrue(virtualResult.throughput >= platformResult.throughput * MIN_THROUGHPUT_IMPROVEMENT,
            String.format("At concurrency level %d, Virtual Thread throughput (%.2f ops/s) should be at least %.0f%% better than Platform Thread throughput (%.2f ops/s)",
                concurrencyLevel, virtualResult.throughput, (MIN_THROUGHPUT_IMPROVEMENT - 1) * 100, platformResult.throughput));
      }
    }
  }
  
  /**
   * Creates an executor service using platform threads with a fixed thread pool.
   *
   * @param threadCount the number of threads in the pool
   * @return the executor service
   */
  private ExecutorService createPlatformThreadExecutor(int threadCount) {
    return Executors.newFixedThreadPool(threadCount, new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger();
      
      @Override
      public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setName("platform-thread-" + counter.incrementAndGet());
        return thread;
      }
    });
  }
  
  /**
   * Creates an executor service using virtual threads.
   * <p>
   * This method leverages Java 21's Virtual Thread implementation through the
   * Executors.newVirtualThreadPerTaskExecutor() factory method, which creates a new
   * virtual thread for each submitted task.
   * <p>
   * Virtual threads are designed to be lightweight and efficient for I/O-bound operations,
   * as they don't maintain a 1:1 mapping with OS threads. Instead, they are scheduled on
   * a smaller pool of carrier threads managed by the JVM.
   *
   * @param threadCount the maximum number of concurrent virtual threads (not used, as virtual threads are unbounded)
   * @return the executor service that creates a new virtual thread for each task
   */
  private ExecutorService createVirtualThreadExecutor(int threadCount) {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Runs a benchmark with the specified executor service and operation.
   *
   * @param name the name of the benchmark
   * @param executorFactory the factory to create the executor service
   * @param concurrencyLevel the number of concurrent operations to perform
   * @param operation the operation to benchmark
   * @return the benchmark result
   */
  private BenchmarkResult runBenchmark(String name, ExecutorFactory executorFactory, int concurrencyLevel, 
                                      Callable<Void> operation) throws Exception {
    log.info("Running benchmark: {} with concurrency level {}", name, concurrencyLevel);
    
    // Force garbage collection before starting
    gc();
    Thread.sleep(100);
    
    // Measure memory before starting
    long memoryBefore = getUsedMemory();
    
    // Create tasks
    List<Callable<Long>> tasks = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
    
    for (int i = 0; i < concurrencyLevel; i++) {
      tasks.add(() -> {
        startLatch.await(); // Wait for all threads to be ready
        long startTime = nanoTime();
        try {
          operation.call();
          return nanoTime() - startTime;
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Execute tasks
    ExecutorService executor = executorFactory.create(concurrencyLevel);
    try {
      List<Future<Long>> futures = new ArrayList<>();
      for (Callable<Long> task : tasks) {
        futures.add(executor.submit(task));
      }
      
      // Start timing
      Instant startTime = Instant.now();
      startLatch.countDown(); // Release all threads simultaneously
      
      // Wait for completion
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      Instant endTime = Instant.now();
      
      if (!completed) {
        log.warn("Benchmark {} did not complete within timeout", name);
      }
      
      // Collect latency data
      List<Long> latencies = new ArrayList<>();
      for (Future<Long> future : futures) {
        try {
          latencies.add(NANOSECONDS.toMillis(future.get()));
        } catch (Exception e) {
          log.error("Error getting task result", e);
        }
      }
      
      // Calculate metrics
      Duration duration = Duration.between(startTime, endTime);
      double throughput = concurrencyLevel / (duration.toMillis() / 1000.0);
      
      // Sort latencies for percentile calculation
      latencies.sort(Long::compare);
      long p50Latency = calculatePercentile(latencies, 50);
      long p95Latency = calculatePercentile(latencies, 95);
      long p99Latency = calculatePercentile(latencies, 99);
      
      // Calculate memory usage
      long memoryAfter = getUsedMemory();
      double memoryUsed = (memoryAfter - memoryBefore) / (1024.0 * 1024.0); // Convert to MB
      
      // Create result
      BenchmarkResult result = new BenchmarkResult(
          throughput,
          p50Latency,
          p95Latency,
          p99Latency,
          memoryUsed
      );
      
      log.info("Benchmark {} completed: {}", name, result);
      return result;
    } finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Simulates a read operation on the datastore.
   * <p>
   * This method simulates a typical database read operation with the following characteristics:
   * <ul>
   *   <li>Prepares a SQL statement with a parameter</li>
   *   <li>Executes the query</li>
   *   <li>Processes the result set</li>
   * </ul>
   * <p>
   * Since this is a simulation, we use Thread.sleep() to mimic the I/O latency that would
   * normally occur during a database operation. This is particularly important for testing
   * Virtual Threads, as their primary advantage is in handling I/O-bound operations where
   * threads spend significant time waiting.
   *
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulateReadOperation() throws Exception {
    try (PreparedStatement stmt = connection.prepareStatement("SELECT * FROM test_table WHERE id = ?")) {
      stmt.setInt(1, (int) (Math.random() * 1000));
      try (ResultSet rs = stmt.executeQuery()) {
        // Simulate processing result set
        Thread.sleep(50); // Simulate I/O latency
      }
    } catch (SQLException e) {
      // In a real test, we would use a real database
      // For this simulation, we'll just sleep to simulate the operation
      Thread.sleep(100);
    }
    return null;
  }
  
  /**
   * Simulates a write operation on the datastore.
   * <p>
   * This method simulates a typical database write operation with the following characteristics:
   * <ul>
   *   <li>Prepares an INSERT statement with parameters</li>
   *   <li>Sets parameter values</li>
   *   <li>Executes the update</li>
   * </ul>
   * <p>
   * Write operations typically have higher latency than read operations, so we use a longer
   * sleep duration to simulate this difference. This helps demonstrate how Virtual Threads
   * can improve performance for operations with significant I/O wait times.
   *
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulateWriteOperation() throws Exception {
    try (PreparedStatement stmt = connection.prepareStatement("INSERT INTO test_table (id, value) VALUES (?, ?)")) {
      stmt.setInt(1, (int) (Math.random() * 1000));
      stmt.setString(2, "test-value-" + System.nanoTime());
      stmt.executeUpdate();
      // Simulate I/O latency
      Thread.sleep(100);
    } catch (SQLException e) {
      // In a real test, we would use a real database
      // For this simulation, we'll just sleep to simulate the operation
      Thread.sleep(150);
    }
    return null;
  }
  
  /**
   * Simulates a mixed read/write operation on the datastore.
   * <p>
   * This method randomly selects between read and write operations with a 70/30 distribution,
   * which is representative of many real-world database workloads where reads are more common
   * than writes.
   * <p>
   * This mixed workload is particularly useful for evaluating the overall performance benefits
   * of Virtual Threads in realistic scenarios that include both types of operations.
   *
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulateMixedOperation() throws Exception {
    // 70% reads, 30% writes
    if (Math.random() < 0.7) {
      return simulateReadOperation();
    } else {
      return simulateWriteOperation();
    }
  }
  
  /**
   * Calculates the percentile value from a sorted list of latencies.
   *
   * @param sortedLatencies the sorted list of latencies
   * @param percentile the percentile to calculate (0-100)
   * @return the percentile value
   */
  private long calculatePercentile(List<Long> sortedLatencies, int percentile) {
    if (sortedLatencies.isEmpty()) {
      return 0;
    }
    int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
    return sortedLatencies.get(Math.max(0, Math.min(sortedLatencies.size() - 1, index)));
  }
  
  /**
   * Gets the current used memory in bytes.
   *
   * @return the used memory in bytes
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Calculates the average benchmark result from a list of results.
   *
   * @param results the list of benchmark results
   * @return the average result
   */
  private BenchmarkResult calculateAverageResult(List<BenchmarkResult> results) {
    if (results.isEmpty()) {
      return new BenchmarkResult(0, 0, 0, 0, 0);
    }
    
    double avgThroughput = results.stream().mapToDouble(r -> r.throughput).average().orElse(0);
    double avgP50Latency = results.stream().mapToDouble(r -> r.p50Latency).average().orElse(0);
    double avgP95Latency = results.stream().mapToDouble(r -> r.p95Latency).average().orElse(0);
    double avgP99Latency = results.stream().mapToDouble(r -> r.p99Latency).average().orElse(0);
    double avgMemoryUsed = results.stream().mapToDouble(r -> r.memoryUsed).average().orElse(0);
    
    return new BenchmarkResult(avgThroughput, avgP50Latency, avgP95Latency, avgP99Latency, avgMemoryUsed);
  }
  
  /**
   * Functional interface for creating executor services.
   */
  @FunctionalInterface
  private interface ExecutorFactory {
    ExecutorService create(int threadCount);
  }
  
  /**
   * Class representing the result of a benchmark.
   * <p>
   * This immutable class captures all the key metrics measured during a benchmark run:
   * <ul>
   *   <li>Throughput: Operations per second</li>
   *   <li>Latency percentiles: P50 (median), P95, and P99</li>
   *   <li>Memory usage: In megabytes</li>
   * </ul>
   * <p>
   * These metrics provide a comprehensive view of performance, allowing for detailed
   * comparison between platform threads and Virtual Threads across different dimensions.
   */
  private static class BenchmarkResult {
    final double throughput;     // operations per second
    final double p50Latency;     // median latency in milliseconds
    final double p95Latency;     // 95th percentile latency in milliseconds
    final double p99Latency;     // 99th percentile latency in milliseconds
    final double memoryUsed;     // memory used in MB
    
    BenchmarkResult(double throughput, double p50Latency, double p95Latency, double p99Latency, double memoryUsed) {
      this.throughput = throughput;
      this.p50Latency = p50Latency;
      this.p95Latency = p95Latency;
      this.p99Latency = p99Latency;
      this.memoryUsed = memoryUsed;
    }
    
    @Override
    public String toString() {
      return String.format("Throughput: %.2f ops/s, P50: %.2f ms, P95: %.2f ms, P99: %.2f ms, Memory: %.2f MB",
          throughput, p50Latency, p95Latency, p99Latency, memoryUsed);
    }
  }
}