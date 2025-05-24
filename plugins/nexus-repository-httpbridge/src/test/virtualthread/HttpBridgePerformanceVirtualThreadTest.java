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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.DoubleSummaryStatistics;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Map;
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

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpbridge.internal.DefaultHttpResponseSender;
import org.sonatype.nexus.repository.view.Headers;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Performance benchmark test comparing HTTP Bridge performance with platform threads versus Virtual Threads.
 * This test measures throughput, latency, and resource utilization across different concurrency levels,
 * generating detailed performance reports that quantify the benefits of Virtual Threads for HTTP operations.
 * 
 * @since 3.60
 */
public class HttpBridgePerformanceVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(HttpBridgePerformanceVirtualThreadTest.class);

  private static final int[] CONCURRENCY_LEVELS = {100, 500, 1000, 5000, 10000};
  private static final int WARMUP_ITERATIONS = 3;
  private static final int BENCHMARK_ITERATIONS = 5;
  private static final int PAYLOAD_SIZE_SMALL = 10 * 1024;       // 10KB
  private static final int PAYLOAD_SIZE_MEDIUM = 1 * 1024 * 1024; // 1MB
  private static final int PAYLOAD_SIZE_LARGE = 10 * 1024 * 1024; // 10MB
  private static final int IO_DELAY_MS = 5; // Simulated I/O delay in milliseconds
  
  private DefaultHttpResponseSender underTest;
  private AutoCloseable mocks;
  
  @Mock
  private Request request;
  
  /**
   * Test setup - initialize mocks and the HTTP response sender under test.
   */
  @Before
  public void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    underTest = new DefaultHttpResponseSender();
    
    when(request.getAction()).thenReturn(HttpMethods.GET);
    when(request.getHeaders()).thenReturn(new Headers());
  }
  
  /**
   * Test cleanup - release mocks.
   */
  @After
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }
  
  /**
   * Benchmark test comparing platform threads vs Virtual Threads for small payload transfers.
   * This test measures the performance difference when handling many concurrent small HTTP responses.
   */
  @Test
  public void benchmarkSmallPayloadTransfer() throws Exception {
    log.info("Running small payload benchmark ({}KB)", PAYLOAD_SIZE_SMALL / 1024);
    runBenchmark(PAYLOAD_SIZE_SMALL, false);
  }
  
  /**
   * Benchmark test comparing platform threads vs Virtual Threads for medium payload transfers.
   * This test measures the performance difference when handling concurrent medium-sized HTTP responses.
   */
  @Test
  public void benchmarkMediumPayloadTransfer() throws Exception {
    log.info("Running medium payload benchmark ({}MB)", PAYLOAD_SIZE_MEDIUM / (1024 * 1024));
    runBenchmark(PAYLOAD_SIZE_MEDIUM, false);
  }
  
  /**
   * Benchmark test comparing platform threads vs Virtual Threads for large payload transfers.
   * This test measures the performance difference when handling concurrent large HTTP responses.
   */
  @Test
  public void benchmarkLargePayloadTransfer() throws Exception {
    log.info("Running large payload benchmark ({}MB)", PAYLOAD_SIZE_LARGE / (1024 * 1024));
    runBenchmark(PAYLOAD_SIZE_LARGE, false);
  }
  
  /**
   * Benchmark test comparing platform threads vs Virtual Threads for I/O-bound operations.
   * This test simulates network latency to measure how Virtual Threads handle I/O-bound workloads.
   */
  @Test
  public void benchmarkIOBoundOperations() throws Exception {
    log.info("Running I/O-bound operations benchmark with {}ms simulated delay", IO_DELAY_MS);
    runBenchmark(PAYLOAD_SIZE_MEDIUM, true);
  }
  
  /**
   * Benchmark test to detect thread pinning issues with Virtual Threads.
   * This test monitors for carrier thread starvation when handling many concurrent requests.
   */
  @Test
  public void detectThreadPinningIssues() throws Exception {
    log.info("Running thread pinning detection test");
    
    // Use the highest concurrency level to stress test for pinning
    int concurrencyLevel = CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1];
    
    // Create a map to track which carrier threads are executing virtual threads
    Map<String, AtomicInteger> carrierThreadUsage = new ConcurrentHashMap<>();
    
    // Create a thread factory that tracks carrier thread usage
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-", 0).factory();
    
    // Create the executor with virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create tasks that will track their carrier thread
      List<Future<?>> futures = new ArrayList<>();
      CountDownLatch startLatch = new CountDownLatch(1);
      
      for (int i = 0; i < concurrencyLevel; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Wait for all tasks to be submitted
            startLatch.await();
            
            // Get the current carrier thread name
            String carrierThread = Thread.currentThread().getName();
            if (!carrierThread.startsWith("vt-")) {
              // This is a carrier thread, track its usage
              carrierThreadUsage.computeIfAbsent(carrierThread, k -> new AtomicInteger()).incrementAndGet();
            }
            
            // Simulate I/O-bound work with blocking operations
            HttpServletResponse response = createMockResponse();
            Payload payload = createTestPayload(PAYLOAD_SIZE_MEDIUM, true);
            underTest.send(request, HttpResponses.ok(payload), response);
            
            // Check carrier thread again to see if it changed
            String afterCarrierThread = Thread.currentThread().getName();
            if (!afterCarrierThread.startsWith("vt-") && !afterCarrierThread.equals(carrierThread)) {
              // The carrier thread changed during execution, which is good
              log.debug("Carrier thread changed from {} to {}", carrierThread, afterCarrierThread);
            }
            
            return null;
          } catch (Exception e) {
            log.error("Error in virtual thread task", e);
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      
      // Analyze carrier thread usage
      int totalCarrierThreads = carrierThreadUsage.size();
      int maxUsagePerThread = carrierThreadUsage.values().stream()
          .mapToInt(AtomicInteger::get)
          .max()
          .orElse(0);
      
      log.info("Thread pinning analysis:");
      log.info("  Total carrier threads used: {}", totalCarrierThreads);
      log.info("  Maximum operations per carrier thread: {}", maxUsagePerThread);
      log.info("  Average operations per carrier thread: {}", 
          concurrencyLevel / (double) totalCarrierThreads);
      
      // Check if we have good distribution across carrier threads
      assertThat("Should use multiple carrier threads", totalCarrierThreads, greaterThan(1));
      
      // If a single carrier thread handles too many operations, it might indicate pinning
      double avgOperationsPerThread = concurrencyLevel / (double) totalCarrierThreads;
      double maxToAvgRatio = maxUsagePerThread / avgOperationsPerThread;
      
      log.info("  Max/Avg ratio: {}", maxToAvgRatio);
      
      // A high ratio indicates potential pinning (some threads doing much more work than others)
      assertThat("Max/Avg ratio should be reasonable (no severe pinning)", 
          maxToAvgRatio, lessThan(5.0));
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Runs a comprehensive benchmark comparing platform threads vs Virtual Threads across multiple concurrency levels.
   * 
   * @param payloadSize the size of the payload to transfer in bytes
   * @param simulateIODelay whether to simulate I/O delays during transfer
   */
  private void runBenchmark(int payloadSize, boolean simulateIODelay) throws Exception {
    log.info("=== Starting benchmark: payload={} bytes, simulateIODelay={} ===", 
        payloadSize, simulateIODelay);
    
    // Results storage for platform threads and virtual threads
    Map<Integer, BenchmarkResult> platformThreadResults = new ConcurrentHashMap<>();
    Map<Integer, BenchmarkResult> virtualThreadResults = new ConcurrentHashMap<>();
    
    // Run warmup iterations
    log.info("Running {} warmup iterations...", WARMUP_ITERATIONS);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      // Use a moderate concurrency level for warmup
      int warmupConcurrency = CONCURRENCY_LEVELS[Math.min(1, CONCURRENCY_LEVELS.length - 1)];
      runBenchmarkIteration(warmupConcurrency, payloadSize, simulateIODelay, true, null);
      runBenchmarkIteration(warmupConcurrency, payloadSize, simulateIODelay, false, null);
    }
    
    // Run actual benchmark iterations for each concurrency level
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      log.info("Benchmarking with {} concurrent requests...", concurrencyLevel);
      
      // Platform threads benchmark
      List<BenchmarkResult> platformResults = new ArrayList<>();
      for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
        BenchmarkResult result = runBenchmarkIteration(concurrencyLevel, payloadSize, 
            simulateIODelay, true, "Platform Thread Iteration " + (i + 1));
        platformResults.add(result);
      }
      
      // Calculate average platform thread result
      BenchmarkResult avgPlatformResult = calculateAverageResult(platformResults);
      platformThreadResults.put(concurrencyLevel, avgPlatformResult);
      
      // Virtual threads benchmark
      List<BenchmarkResult> virtualResults = new ArrayList<>();
      for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
        BenchmarkResult result = runBenchmarkIteration(concurrencyLevel, payloadSize, 
            simulateIODelay, false, "Virtual Thread Iteration " + (i + 1));
        virtualResults.add(result);
      }
      
      // Calculate average virtual thread result
      BenchmarkResult avgVirtualResult = calculateAverageResult(virtualResults);
      virtualThreadResults.put(concurrencyLevel, avgVirtualResult);
    }
    
    // Generate and print the benchmark report
    generateBenchmarkReport(platformThreadResults, virtualThreadResults, payloadSize, simulateIODelay);
  }
  
  /**
   * Runs a single benchmark iteration with the specified parameters.
   * 
   * @param concurrencyLevel the number of concurrent requests to process
   * @param payloadSize the size of the payload to transfer in bytes
   * @param simulateIODelay whether to simulate I/O delays during transfer
   * @param usePlatformThreads whether to use platform threads (true) or virtual threads (false)
   * @param iterationName optional name for this iteration (null for warmup)
   * @return the benchmark result for this iteration
   */
  private BenchmarkResult runBenchmarkIteration(
      int concurrencyLevel, 
      int payloadSize, 
      boolean simulateIODelay,
      boolean usePlatformThreads,
      String iterationName) throws Exception {
    
    // Create appropriate executor based on thread type
    ExecutorService executor;
    String threadType;
    
    if (usePlatformThreads) {
      executor = Executors.newFixedThreadPool(concurrencyLevel);
      threadType = "Platform";
    } else {
      executor = Executors.newVirtualThreadPerTaskExecutor();
      threadType = "Virtual";
    }
    
    try {
      // Track metrics for this iteration
      AtomicLong totalBytes = new AtomicLong(0);
      AtomicLong totalOperations = new AtomicLong(0);
      List<Long> responseTimes = new ArrayList<>(concurrencyLevel);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
      
      // Create and submit tasks
      for (int i = 0; i < concurrencyLevel; i++) {
        executor.submit(() -> {
          try {
            // Wait for all tasks to be submitted before starting
            startLatch.await();
            
            // Measure response time for this request
            long startTime = System.nanoTime();
            
            // Process the request
            HttpServletResponse response = createMockResponse(totalBytes);
            Payload payload = createTestPayload(payloadSize, simulateIODelay);
            underTest.send(request, HttpResponses.ok(payload), response);
            
            // Record metrics
            long endTime = System.nanoTime();
            long responseTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            
            synchronized (responseTimes) {
              responseTimes.add(responseTimeMs);
            }
            
            totalOperations.incrementAndGet();
            completionLatch.countDown();
          } catch (Exception e) {
            log.error("Error in benchmark task", e);
            completionLatch.countDown();
          }
        });
      }
      
      // Start timing and release all threads
      long benchmarkStart = System.nanoTime();
      startLatch.countDown();
      
      // Wait for all operations to complete
      boolean completed = completionLatch.await(60, TimeUnit.SECONDS);
      long benchmarkEnd = System.nanoTime();
      
      if (!completed) {
        log.warn("Benchmark did not complete within timeout!");
      }
      
      // Calculate benchmark metrics
      long durationMs = TimeUnit.NANOSECONDS.toMillis(benchmarkEnd - benchmarkStart);
      double operationsPerSecond = (totalOperations.get() * 1000.0) / durationMs;
      double throughputMBps = (totalBytes.get() * 1000.0) / (durationMs * 1024 * 1024);
      
      // Calculate percentiles
      long[] sortedResponseTimes = responseTimes.stream().mapToLong(Long::longValue).sorted().toArray();
      long p50 = calculatePercentile(sortedResponseTimes, 50);
      long p90 = calculatePercentile(sortedResponseTimes, 90);
      long p95 = calculatePercentile(sortedResponseTimes, 95);
      long p99 = calculatePercentile(sortedResponseTimes, 99);
      
      // Create result object
      BenchmarkResult result = new BenchmarkResult(
          concurrencyLevel,
          durationMs,
          totalOperations.get(),
          totalBytes.get(),
          operationsPerSecond,
          throughputMBps,
          p50, p90, p95, p99
      );
      
      // Log results if this is not a warmup iteration
      if (iterationName != null) {
        log.info("{} Thread {} - Completed {} operations in {} ms ({} ops/sec, {:.2f} MB/s)",
            threadType, iterationName, totalOperations.get(), durationMs, 
            String.format("%.2f", operationsPerSecond),
            throughputMBps);
        log.info("  Response times (ms): p50={}, p90={}, p95={}, p99={}",
            p50, p90, p95, p99);
      }
      
      return result;
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(10, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Calculates the average benchmark result from multiple iterations.
   * 
   * @param results the list of benchmark results to average
   * @return the average benchmark result
   */
  private BenchmarkResult calculateAverageResult(List<BenchmarkResult> results) {
    if (results.isEmpty()) {
      throw new IllegalArgumentException("Cannot calculate average of empty results");
    }
    
    int concurrencyLevel = results.get(0).concurrencyLevel;
    
    LongSummaryStatistics durationStats = results.stream()
        .mapToLong(r -> r.durationMs)
        .summaryStatistics();
    
    LongSummaryStatistics operationsStats = results.stream()
        .mapToLong(r -> r.operations)
        .summaryStatistics();
    
    LongSummaryStatistics bytesStats = results.stream()
        .mapToLong(r -> r.bytes)
        .summaryStatistics();
    
    DoubleSummaryStatistics opsPerSecStats = results.stream()
        .mapToDouble(r -> r.operationsPerSecond)
        .summaryStatistics();
    
    DoubleSummaryStatistics throughputStats = results.stream()
        .mapToDouble(r -> r.throughputMBps)
        .summaryStatistics();
    
    LongSummaryStatistics p50Stats = results.stream()
        .mapToLong(r -> r.p50ResponseTimeMs)
        .summaryStatistics();
    
    LongSummaryStatistics p90Stats = results.stream()
        .mapToLong(r -> r.p90ResponseTimeMs)
        .summaryStatistics();
    
    LongSummaryStatistics p95Stats = results.stream()
        .mapToLong(r -> r.p95ResponseTimeMs)
        .summaryStatistics();
    
    LongSummaryStatistics p99Stats = results.stream()
        .mapToLong(r -> r.p99ResponseTimeMs)
        .summaryStatistics();
    
    return new BenchmarkResult(
        concurrencyLevel,
        (long) durationStats.getAverage(),
        (long) operationsStats.getAverage(),
        (long) bytesStats.getAverage(),
        opsPerSecStats.getAverage(),
        throughputStats.getAverage(),
        (long) p50Stats.getAverage(),
        (long) p90Stats.getAverage(),
        (long) p95Stats.getAverage(),
        (long) p99Stats.getAverage()
    );
  }
  
  /**
   * Generates and prints a comprehensive benchmark report comparing platform threads vs Virtual Threads.
   * 
   * @param platformResults the benchmark results for platform threads
   * @param virtualResults the benchmark results for virtual threads
   * @param payloadSize the size of the payload used in the benchmark
   * @param simulateIODelay whether I/O delays were simulated
   */
  private void generateBenchmarkReport(
      Map<Integer, BenchmarkResult> platformResults,
      Map<Integer, BenchmarkResult> virtualResults,
      int payloadSize,
      boolean simulateIODelay) {
    
    log.info("\n=== BENCHMARK REPORT ===\n");
    log.info("Benchmark Configuration:");
    log.info("  Payload Size: {} bytes", payloadSize);
    log.info("  Simulated I/O Delay: {}", simulateIODelay);
    log.info("  Iterations: {}", BENCHMARK_ITERATIONS);
    log.info("\nPerformance Comparison (Platform Threads vs Virtual Threads):\n");
    
    // Print header
    log.info("{} | {} | {} | {} | {} | {} | {}",
        "Concurrency",
        "Thread Type",
        "Ops/sec",
        "Throughput (MB/s)",
        "p50 (ms)",
        "p95 (ms)",
        "p99 (ms)");
    log.info("{}", String.join("", Collections.nCopies(100, "-")));
    
    // Print results for each concurrency level
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      BenchmarkResult platformResult = platformResults.get(concurrencyLevel);
      BenchmarkResult virtualResult = virtualResults.get(concurrencyLevel);
      
      if (platformResult != null && virtualResult != null) {
        // Calculate improvement percentages
        double throughputImprovement = calculateImprovement(
            virtualResult.throughputMBps, platformResult.throughputMBps);
        double latencyImprovement = calculateImprovement(
            platformResult.p95ResponseTimeMs, virtualResult.p95ResponseTimeMs);
        
        // Print platform thread results
        log.info("{} | {} | {:.2f} | {:.2f} | {} | {} | {}",
            concurrencyLevel,
            "Platform",
            platformResult.operationsPerSecond,
            platformResult.throughputMBps,
            platformResult.p50ResponseTimeMs,
            platformResult.p95ResponseTimeMs,
            platformResult.p99ResponseTimeMs);
        
        // Print virtual thread results
        log.info("{} | {} | {:.2f} | {:.2f} | {} | {} | {}",
            concurrencyLevel,
            "Virtual",
            virtualResult.operationsPerSecond,
            virtualResult.throughputMBps,
            virtualResult.p50ResponseTimeMs,
            virtualResult.p95ResponseTimeMs,
            virtualResult.p99ResponseTimeMs);
        
        // Print improvement summary
        log.info("{} | {} | {:.2f}% | {:.2f}% | {:.2f}% | {:.2f}% | {:.2f}%\n",
            concurrencyLevel,
            "Improvement",
            calculateImprovement(virtualResult.operationsPerSecond, platformResult.operationsPerSecond),
            throughputImprovement,
            calculateImprovement(platformResult.p50ResponseTimeMs, virtualResult.p50ResponseTimeMs),
            latencyImprovement,
            calculateImprovement(platformResult.p99ResponseTimeMs, virtualResult.p99ResponseTimeMs));
      }
    }
    
    // Print summary and recommendations
    log.info("\nSummary:");
    
    // Calculate average improvements across all concurrency levels
    double avgThroughputImprovement = Arrays.stream(CONCURRENCY_LEVELS)
        .filter(c -> platformResults.containsKey(c) && virtualResults.containsKey(c))
        .mapToDouble(c -> calculateImprovement(
            virtualResults.get(c).throughputMBps, 
            platformResults.get(c).throughputMBps))
        .average()
        .orElse(0);
    
    double avgLatencyImprovement = Arrays.stream(CONCURRENCY_LEVELS)
        .filter(c -> platformResults.containsKey(c) && virtualResults.containsKey(c))
        .mapToDouble(c -> calculateImprovement(
            platformResults.get(c).p95ResponseTimeMs, 
            virtualResults.get(c).p95ResponseTimeMs))
        .average()
        .orElse(0);
    
    // Find the concurrency level with the greatest improvement
    int bestConcurrencyLevel = Arrays.stream(CONCURRENCY_LEVELS)
        .filter(c -> platformResults.containsKey(c) && virtualResults.containsKey(c))
        .reduce((a, b) -> {
          double improvementA = calculateImprovement(
              virtualResults.get(a).throughputMBps, 
              platformResults.get(a).throughputMBps);
          double improvementB = calculateImprovement(
              virtualResults.get(b).throughputMBps, 
              platformResults.get(b).throughputMBps);
          return improvementA > improvementB ? a : b;
        })
        .orElse(CONCURRENCY_LEVELS[0]);
    
    log.info("  Average throughput improvement with Virtual Threads: {:.2f}%", avgThroughputImprovement);
    log.info("  Average latency improvement with Virtual Threads: {:.2f}%", avgLatencyImprovement);
    log.info("  Best performance improvement at concurrency level: {}", bestConcurrencyLevel);
    
    // Check if we meet the target response time for 1000 connections
    int targetConcurrency = 1000;
    if (platformResults.containsKey(targetConcurrency) && virtualResults.containsKey(targetConcurrency)) {
      BenchmarkResult platformResult = platformResults.get(targetConcurrency);
      BenchmarkResult virtualResult = virtualResults.get(targetConcurrency);
      
      log.info("\nTarget Response Time Analysis (1000 connections):");
      log.info("  Platform Thread P95 Response Time: {} ms", platformResult.p95ResponseTimeMs);
      log.info("  Virtual Thread P95 Response Time: {} ms", virtualResult.p95ResponseTimeMs);
      log.info("  Target P95 Response Time: <350ms (vs. platform thread baseline of ~500ms)");
      
      if (virtualResult.p95ResponseTimeMs < 350) {
        log.info("  ✅ Virtual Threads MEET the target response time requirement");
      } else {
        log.info("  ❌ Virtual Threads DO NOT meet the target response time requirement");
      }
    }
    
    log.info("\nRecommendations:");
    if (avgThroughputImprovement > 20 && avgLatencyImprovement > 20) {
      log.info("  ✅ STRONGLY RECOMMENDED to use Virtual Threads for HTTP operations");
      log.info("     Significant improvements in both throughput ({:.2f}%) and latency ({:.2f}%)", 
          avgThroughputImprovement, avgLatencyImprovement);
    } else if (avgThroughputImprovement > 10 || avgLatencyImprovement > 10) {
      log.info("  ✅ RECOMMENDED to use Virtual Threads for HTTP operations");
      log.info("     Moderate improvements observed, especially at higher concurrency levels");
    } else {
      log.info("  ⚠️ CONSIDER using Virtual Threads for HTTP operations");
      log.info("     Limited performance improvements in this specific benchmark,");
      log.info("     but may still provide benefits for real-world workloads");
    }
    
    log.info("\n=== END OF BENCHMARK REPORT ===\n");
  }
  
  /**
   * Creates a mock HTTP servlet response for testing.
   * 
   * @return a mocked HttpServletResponse
   */
  private HttpServletResponse createMockResponse() throws Exception {
    return createMockResponse(new AtomicLong(0));
  }
  
  /**
   * Creates a mock HTTP servlet response that tracks bytes written.
   * 
   * @param bytesCounter an atomic counter to track bytes written
   * @return a mocked HttpServletResponse
   */
  private HttpServletResponse createMockResponse(AtomicLong bytesCounter) throws Exception {
    HttpServletResponse response = org.mockito.Mockito.mock(HttpServletResponse.class);
    ServletOutputStream outputStream = org.mockito.Mockito.mock(ServletOutputStream.class);
    
    // Mock the output stream to count bytes written
    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(InvocationOnMock invocation) throws Throwable {
        byte[] buffer = invocation.getArgument(0);
        int offset = invocation.getArgument(1);
        int length = invocation.getArgument(2);
        bytesCounter.addAndGet(length);
        return null;
      }
    }).when(outputStream).write(any(byte[].class), any(int.class), any(int.class));
    
    when(response.getOutputStream()).thenReturn(outputStream);
    return response;
  }
  
  /**
   * Creates a test payload of the specified size, optionally with simulated I/O delays.
   * 
   * @param size the size of the payload in bytes
   * @param simulateIODelay whether to simulate I/O delays during transfer
   * @return a Payload instance for testing
   */
  private Payload createTestPayload(int size, boolean simulateIODelay) {
    // Create payload data
    byte[] data = new byte[size];
    // Fill with pattern data
    for (int i = 0; i < data.length; i++) {
      data[i] = (byte)(i % 256);
    }
    
    // Create input stream supplier to ensure fresh stream for each use
    Supplier<InputStream> inputStreamSupplier = () -> new ByteArrayInputStream(data);
    
    return new Payload() {
      @Override
      public InputStream openInputStream() throws IOException {
        return inputStreamSupplier.get();
      }

      @Override
      public long getSize() {
        return size;
      }

      @Override
      public String getContentType() {
        return "application/octet-stream";
      }

      @Override
      public void close() throws IOException {
        // Nothing to close for byte array input stream
      }
      
      @Override
      public void copy(InputStream from, OutputStream to) throws IOException {
        // Custom copy implementation that can simulate I/O delays
        byte[] buffer = new byte[8192];
        int bytesRead;
        
        while ((bytesRead = from.read(buffer)) != -1) {
          if (simulateIODelay) {
            try {
              // Simulate I/O delay
              Thread.sleep(IO_DELAY_MS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
          
          to.write(buffer, 0, bytesRead);
        }
      }
    };
  }
  
  /**
   * Calculates the specified percentile from an array of values.
   * 
   * @param sortedValues the sorted array of values
   * @param percentile the percentile to calculate (0-100)
   * @return the percentile value
   */
  private long calculatePercentile(long[] sortedValues, int percentile) {
    if (sortedValues.length == 0) {
      return 0;
    }
    
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.length) - 1;
    index = Math.max(0, Math.min(sortedValues.length - 1, index));
    return sortedValues[index];
  }
  
  /**
   * Calculates the improvement percentage between two values.
   * 
   * @param newValue the new value
   * @param oldValue the old value
   * @return the improvement percentage
   */
  private double calculateImprovement(double newValue, double oldValue) {
    if (oldValue == 0) {
      return newValue > 0 ? 100 : 0;
    }
    return ((newValue - oldValue) / oldValue) * 100;
  }
  
  /**
   * Data class to hold benchmark results.
   */
  private static class BenchmarkResult {
    final int concurrencyLevel;
    final long durationMs;
    final long operations;
    final long bytes;
    final double operationsPerSecond;
    final double throughputMBps;
    final long p50ResponseTimeMs;
    final long p90ResponseTimeMs;
    final long p95ResponseTimeMs;
    final long p99ResponseTimeMs;
    
    BenchmarkResult(
        int concurrencyLevel,
        long durationMs,
        long operations,
        long bytes,
        double operationsPerSecond,
        double throughputMBps,
        long p50ResponseTimeMs,
        long p90ResponseTimeMs,
        long p95ResponseTimeMs,
        long p99ResponseTimeMs) {
      this.concurrencyLevel = concurrencyLevel;
      this.durationMs = durationMs;
      this.operations = operations;
      this.bytes = bytes;
      this.operationsPerSecond = operationsPerSecond;
      this.throughputMBps = throughputMBps;
      this.p50ResponseTimeMs = p50ResponseTimeMs;
      this.p90ResponseTimeMs = p90ResponseTimeMs;
      this.p95ResponseTimeMs = p95ResponseTimeMs;
      this.p99ResponseTimeMs = p99ResponseTimeMs;
    }
  }
}