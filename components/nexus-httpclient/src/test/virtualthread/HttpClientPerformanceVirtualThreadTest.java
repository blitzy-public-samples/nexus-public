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
package org.sonatype.nexus.httpclient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.experimental.categories.Category;

import static java.lang.System.gc;
import static java.lang.System.nanoTime;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Performance comparison test for HTTP client operations using platform threads versus Virtual Threads.
 * <p>
 * This test class benchmarks the performance differences between traditional platform threads and
 * Java 21 Virtual Threads when performing I/O-bound HTTP client operations.
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
public class HttpClientPerformanceVirtualThreadTest
    extends TestSupport
{
  private static final int SERVER_PORT = 8765;
  private static final String SERVER_HOST = "localhost";
  private static final String SERVER_URL = "http://" + SERVER_HOST + ":" + SERVER_PORT;
  
  private static final int WARMUP_ITERATIONS = 3;
  private static final int BENCHMARK_ITERATIONS = 5;
  private static final int[] CONCURRENCY_LEVELS = {10, 50, 100, 500, 1000};
  
  // Performance thresholds for virtual threads compared to platform threads
  private static final double MIN_THROUGHPUT_IMPROVEMENT = 1.2; // 20% improvement
  private static final double MAX_P95_LATENCY_RATIO = 0.8; // 20% reduction
  private static final double MAX_MEMORY_USAGE_RATIO = 0.7; // 30% reduction
  
  // Simulated server response delays
  private static final int SMALL_DELAY_MS = 50;  // For GET requests
  private static final int MEDIUM_DELAY_MS = 100; // For POST requests
  private static final int LARGE_DELAY_MS = 200;  // For complex operations
  
  private HttpServer server;
  private HttpClient virtualThreadClient;
  private HttpClient platformThreadClient;
  private final AtomicInteger requestCounter = new AtomicInteger(0);
  
  /**
   * Sets up the test HTTP server and HTTP clients before each test.
   */
  @BeforeEach
  void setUp() throws IOException {
    // Reset counter
    requestCounter.set(0);
    
    // Create and start HTTP server
    server = HttpServer.create(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 0);
    server.createContext("/get", new DelayHandler(SMALL_DELAY_MS));
    server.createContext("/post", new DelayHandler(MEDIUM_DELAY_MS));
    server.createContext("/complex", new DelayHandler(LARGE_DELAY_MS));
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    
    log.info("Started test HTTP server on {}", SERVER_URL);
    
    // Create HTTP client with virtual threads
    virtualThreadClient = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    // Create HTTP client with platform threads for comparison
    platformThreadClient = HttpClient.newBuilder()
        .executor(Executors.newFixedThreadPool(100)) // Limited to 100 platform threads
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }
  
  /**
   * Cleans up resources after each test.
   */
  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
      log.info("Stopped test HTTP server");
    }
  }
  
  /**
   * Tests GET request performance with varying concurrency levels using both platform threads and virtual threads.
   *
   * @param concurrencyLevel the number of concurrent operations to perform
   */
  @ParameterizedTest
  @ValueSource(ints = {10, 100, 500})
  void testGetRequestPerformance(int concurrencyLevel) throws Exception {
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, 
          () -> simulateGetRequest(platformThreadClient));
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, 
          () -> simulateGetRequest(virtualThreadClient));
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread GET", this::createPlatformThreadExecutor, 
          concurrencyLevel, () -> simulateGetRequest(platformThreadClient)));
      
      virtualResults.add(runBenchmark("Virtual Thread GET", this::createVirtualThreadExecutor, 
          concurrencyLevel, () -> simulateGetRequest(virtualThreadClient)));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Log detailed results
    log.info("GET Request Performance at concurrency level {}:", concurrencyLevel);
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
   * Tests POST request performance with varying concurrency levels using both platform threads and virtual threads.
   *
   * @param concurrencyLevel the number of concurrent operations to perform
   */
  @ParameterizedTest
  @ValueSource(ints = {10, 100, 500})
  void testPostRequestPerformance(int concurrencyLevel) throws Exception {
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, 
          () -> simulatePostRequest(platformThreadClient));
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, 
          () -> simulatePostRequest(virtualThreadClient));
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread POST", this::createPlatformThreadExecutor, 
          concurrencyLevel, () -> simulatePostRequest(platformThreadClient)));
      
      virtualResults.add(runBenchmark("Virtual Thread POST", this::createVirtualThreadExecutor, 
          concurrencyLevel, () -> simulatePostRequest(virtualThreadClient)));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Log detailed results
    log.info("POST Request Performance at concurrency level {}:", concurrencyLevel);
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
   * Tests complex request performance with high concurrency using both platform threads and virtual threads.
   * Complex requests involve longer server processing times, simulating more intensive operations.
   */
  @Test
  void testComplexRequestPerformance() throws Exception {
    final int concurrencyLevel = 500;
    
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, 
          () -> simulateComplexRequest(platformThreadClient));
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, 
          () -> simulateComplexRequest(virtualThreadClient));
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread Complex", this::createPlatformThreadExecutor, 
          concurrencyLevel, () -> simulateComplexRequest(platformThreadClient)));
      
      virtualResults.add(runBenchmark("Virtual Thread Complex", this::createVirtualThreadExecutor, 
          concurrencyLevel, () -> simulateComplexRequest(virtualThreadClient)));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Log detailed results
    log.info("Complex Request Performance at concurrency level {}:", concurrencyLevel);
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
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, 
          () -> simulateGetRequest(platformThreadClient));
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, 
          () -> simulateGetRequest(virtualThreadClient));
      
      // Run actual benchmarks
      platformResults.put(concurrencyLevel, runBenchmark("Platform Thread Scalability", 
          this::createPlatformThreadExecutor, concurrencyLevel, () -> simulateGetRequest(platformThreadClient)));
      
      virtualResults.put(concurrencyLevel, runBenchmark("Virtual Thread Scalability", 
          this::createVirtualThreadExecutor, concurrencyLevel, () -> simulateGetRequest(virtualThreadClient)));
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
    
    // Verify that the performance gap widens with increasing concurrency
    int lowestConcurrency = CONCURRENCY_LEVELS[0];
    int highestConcurrency = CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1];
    
    double lowConcurrencyImprovement = 
        virtualResults.get(lowestConcurrency).throughput / platformResults.get(lowestConcurrency).throughput;
    double highConcurrencyImprovement = 
        virtualResults.get(highestConcurrency).throughput / platformResults.get(highestConcurrency).throughput;
    
    assertTrue(highConcurrencyImprovement > lowConcurrencyImprovement,
        String.format("Performance improvement at high concurrency (%.2fx at %d threads) should be greater than at low concurrency (%.2fx at %d threads)",
            highConcurrencyImprovement, highestConcurrency, lowConcurrencyImprovement, lowestConcurrency));
  }
  
  /**
   * Tests the memory efficiency of virtual threads compared to platform threads under high load.
   * <p>
   * This test specifically focuses on memory consumption patterns when handling a large number
   * of concurrent HTTP connections. It validates that Virtual Threads use significantly less
   * memory than platform threads when scaling to high concurrency levels.
   */
  @Test
  void testMemoryEfficiencyUnderHighLoad() throws Exception {
    final int concurrencyLevel = 1000;
    
    // Force garbage collection before starting
    gc();
    Thread.sleep(500); // Allow GC to complete
    
    // Measure baseline memory usage
    long baselineMemory = getUsedMemory();
    log.info("Baseline memory usage: {} MB", baselineMemory / (1024 * 1024));
    
    // Run platform thread benchmark and measure memory
    gc();
    Thread.sleep(500);
    long platformMemoryBefore = getUsedMemory();
    BenchmarkResult platformResult = runBenchmark("Platform Thread Memory Test", 
        this::createPlatformThreadExecutor, concurrencyLevel, () -> simulateGetRequest(platformThreadClient));
    long platformMemoryAfter = getUsedMemory();
    long platformMemoryUsed = platformMemoryAfter - platformMemoryBefore;
    
    // Run virtual thread benchmark and measure memory
    gc();
    Thread.sleep(500);
    long virtualMemoryBefore = getUsedMemory();
    BenchmarkResult virtualResult = runBenchmark("Virtual Thread Memory Test", 
        this::createVirtualThreadExecutor, concurrencyLevel, () -> simulateGetRequest(virtualThreadClient));
    long virtualMemoryAfter = getUsedMemory();
    long virtualMemoryUsed = virtualMemoryAfter - virtualMemoryBefore;
    
    // Calculate memory usage in MB
    double platformMemoryMB = platformMemoryUsed / (1024.0 * 1024.0);
    double virtualMemoryMB = virtualMemoryUsed / (1024.0 * 1024.0);
    double memoryReductionPercent = (1 - (double)virtualMemoryUsed / platformMemoryUsed) * 100;
    
    // Log results
    log.info("Memory Efficiency Results at {} concurrent connections:", concurrencyLevel);
    log.info("Platform Thread Memory Usage: {:.2f} MB", platformMemoryMB);
    log.info("Virtual Thread Memory Usage: {:.2f} MB", virtualMemoryMB);
    log.info("Memory Reduction: {:.2f}%", memoryReductionPercent);
    
    // Verify memory efficiency
    assertTrue(virtualMemoryUsed <= platformMemoryUsed * MAX_MEMORY_USAGE_RATIO,
        String.format("Virtual Thread memory usage (%.2f MB) should be at most %.0f%% of Platform Thread memory usage (%.2f MB)",
            virtualMemoryMB, MAX_MEMORY_USAGE_RATIO * 100, platformMemoryMB));
  }
  
  /**
   * Tests the performance of mixed HTTP operations (GET, POST, complex) under high concurrency.
   * <p>
   * This test simulates a more realistic workload with a mix of different HTTP operations
   * running concurrently. It validates that Virtual Threads maintain their performance advantage
   * across diverse workloads.
   */
  @Test
  void testMixedOperationsPerformance() throws Exception {
    final int concurrencyLevel = 500;
    
    // Perform warmup to stabilize JIT compilation
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark("Platform Thread Warmup", this::createPlatformThreadExecutor, concurrencyLevel, 
          () -> simulateMixedRequest(platformThreadClient));
      runBenchmark("Virtual Thread Warmup", this::createVirtualThreadExecutor, concurrencyLevel, 
          () -> simulateMixedRequest(virtualThreadClient));
    }
    
    // Run actual benchmarks
    List<BenchmarkResult> platformResults = new ArrayList<>();
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformResults.add(runBenchmark("Platform Thread Mixed", this::createPlatformThreadExecutor, 
          concurrencyLevel, () -> simulateMixedRequest(platformThreadClient)));
      
      virtualResults.add(runBenchmark("Virtual Thread Mixed", this::createVirtualThreadExecutor, 
          concurrencyLevel, () -> simulateMixedRequest(virtualThreadClient)));
    }
    
    // Calculate average metrics
    BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
    
    // Log detailed results
    log.info("Mixed Operations Performance at concurrency level {}:", concurrencyLevel);
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
      double p50Latency = calculatePercentile(latencies, 50);
      double p95Latency = calculatePercentile(latencies, 95);
      double p99Latency = calculatePercentile(latencies, 99);
      
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
   * Simulates a GET request to the test server.
   * <p>
   * This method performs a simple HTTP GET request to the test server's /get endpoint,
   * which simulates a typical read operation with a small delay to mimic network and
   * server processing time.
   *
   * @param client the HTTP client to use for the request
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulateGetRequest(HttpClient client) throws Exception {
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(SERVER_URL + "/get?id=" + System.nanoTime()))
        .GET()
        .build();
    
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Unexpected status code: " + response.statusCode());
    }
    
    return null;
  }
  
  /**
   * Simulates a POST request to the test server.
   * <p>
   * This method performs an HTTP POST request to the test server's /post endpoint,
   * which simulates a typical write operation with a medium delay to mimic network and
   * server processing time for data submission.
   *
   * @param client the HTTP client to use for the request
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulatePostRequest(HttpClient client) throws Exception {
    String payload = String.format("{\"id\":%d,\"timestamp\":%d,\"data\":\"test-data\"}", 
        System.nanoTime(), System.currentTimeMillis());
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(SERVER_URL + "/post"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Unexpected status code: " + response.statusCode());
    }
    
    return null;
  }
  
  /**
   * Simulates a complex request to the test server.
   * <p>
   * This method performs an HTTP request to the test server's /complex endpoint,
   * which simulates a more intensive operation with a larger delay to mimic complex
   * processing on the server side.
   *
   * @param client the HTTP client to use for the request
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulateComplexRequest(HttpClient client) throws Exception {
    String payload = String.format("{\"operation\":\"complex\",\"parameters\":{\"id\":%d,\"timestamp\":%d}}", 
        System.nanoTime(), System.currentTimeMillis());
    
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(SERVER_URL + "/complex"))
        .header("Content-Type", "application/json")
        .header("X-Request-ID", String.valueOf(System.nanoTime()))
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build();
    
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Unexpected status code: " + response.statusCode());
    }
    
    return null;
  }
  
  /**
   * Simulates a mixed request to the test server.
   * <p>
   * This method randomly selects between GET, POST, and complex requests with a
   * distribution of 60% GET, 30% POST, and 10% complex operations, which is
   * representative of many real-world HTTP workloads.
   *
   * @param client the HTTP client to use for the request
   * @return null (void operation)
   * @throws Exception if an error occurs during the operation
   */
  private Void simulateMixedRequest(HttpClient client) throws Exception {
    double random = Math.random();
    
    if (random < 0.6) {
      return simulateGetRequest(client);
    } else if (random < 0.9) {
      return simulatePostRequest(client);
    } else {
      return simulateComplexRequest(client);
    }
  }
  
  /**
   * Calculates the percentile value from a sorted list of latencies.
   *
   * @param sortedLatencies the sorted list of latencies
   * @param percentile the percentile to calculate (0-100)
   * @return the percentile value
   */
  private double calculatePercentile(List<Long> sortedLatencies, int percentile) {
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
  
  /**
   * HTTP handler that introduces a configurable delay before responding, simulating a slow service.
   */
  private class DelayHandler implements HttpHandler {
    private final int delayMs;
    
    DelayHandler(int delayMs) {
      this.delayMs = delayMs;
    }
    
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      requestCounter.incrementAndGet();
      
      try {
        // Read request body if present
        String requestBody = "";
        if ("POST".equals(exchange.getRequestMethod())) {
          requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        }
        
        // Simulate processing delay
        Thread.sleep(delayMs);
        
        // Prepare response
        String response = String.format(
            "Response from %s after %dms delay. Method: %s, Query: %s, Headers: %d, Body length: %d",
            exchange.getRequestURI().getPath(),
            delayMs,
            exchange.getRequestMethod(),
            exchange.getRequestURI().getQuery(),
            exchange.getRequestHeaders().size(),
            requestBody.length());
        
        // Send response
        exchange.sendResponseHeaders(200, response.length());
        exchange.getResponseBody().write(response.getBytes());
      } catch (InterruptedException e) {
        String error = "Processing interrupted";
        exchange.sendResponseHeaders(500, error.length());
        exchange.getResponseBody().write(error.getBytes());
      } finally {
        exchange.close();
      }
    }
  }
}