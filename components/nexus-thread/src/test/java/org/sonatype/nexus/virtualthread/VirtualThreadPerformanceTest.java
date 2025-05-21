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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.io.DirectoryHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Performance benchmark comparing Java 21 Virtual Threads against traditional platform threads
 * across various operations in the nexus-thread module. The test measures throughput, latency,
 * memory utilization, and thread pinning across different concurrency levels.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadPerformanceTest
    extends TestSupport
{
  private static final int[] CONCURRENCY_LEVELS = {100, 1_000, 10_000, 100_000};
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASUREMENT_ITERATIONS = 5;
  private static final int IO_OPERATION_SIZE_BYTES = 1024;
  private static final int IO_BUFFER_SIZE = 8192;
  private static final long THREAD_PINNING_THRESHOLD_MS = 100;
  
  private Path tempDir;
  private final Map<String, List<BenchmarkResult>> benchmarkResults = new ConcurrentHashMap<>();
  
  @Before
  public void setup() throws IOException {
    tempDir = Files.createTempDirectory("virtual-thread-test");
  }
  
  @After
  public void cleanup() throws IOException {
    DirectoryHelper.deleteIfExists(tempDir.toFile());
  }
  
  /**
   * Compares the performance of Virtual Threads vs Platform Threads for I/O-bound operations.
   * This test simulates file operations that are typically I/O-bound and should benefit
   * significantly from Virtual Threads.
   */
  @Test
  public void compareIOBoundOperations() throws Exception {
    log.info("Starting I/O-bound operations benchmark");
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      // Skip extremely high concurrency levels for platform threads to avoid resource exhaustion
      if (concurrencyLevel > 10_000) {
        log.info("Skipping platform thread test at concurrency level {} to avoid resource exhaustion", concurrencyLevel);
        continue;
      }
      
      log.info("Testing at concurrency level: {}", concurrencyLevel);
      
      // Run with platform threads
      runBenchmark("io-platform-" + concurrencyLevel, concurrencyLevel, false, this::performIOOperation);
      
      // Run with virtual threads
      runBenchmark("io-virtual-" + concurrencyLevel, concurrencyLevel, true, this::performIOOperation);
    }
    
    generateReport("IO-Bound Operations");
  }
  
  /**
   * Compares the performance of Virtual Threads vs Platform Threads for coordination operations.
   * This test simulates operations that involve coordination between threads, such as
   * waiting for other threads to complete work.
   */
  @Test
  public void compareCoordinationOperations() throws Exception {
    log.info("Starting coordination operations benchmark");
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      // Skip extremely high concurrency levels for platform threads to avoid resource exhaustion
      if (concurrencyLevel > 10_000) {
        log.info("Skipping platform thread test at concurrency level {} to avoid resource exhaustion", concurrencyLevel);
        continue;
      }
      
      log.info("Testing at concurrency level: {}", concurrencyLevel);
      
      // Run with platform threads
      runBenchmark("coord-platform-" + concurrencyLevel, concurrencyLevel, false, this::performCoordinationOperation);
      
      // Run with virtual threads
      runBenchmark("coord-virtual-" + concurrencyLevel, concurrencyLevel, true, this::performCoordinationOperation);
    }
    
    generateReport("Coordination Operations");
  }
  
  /**
   * Compares the performance of Virtual Threads vs Platform Threads for mixed workloads.
   * This test simulates a mix of I/O-bound and coordination operations to represent
   * real-world scenarios in Nexus Repository Manager.
   */
  @Test
  public void compareMixedWorkloads() throws Exception {
    log.info("Starting mixed workload benchmark");
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      // Skip extremely high concurrency levels for platform threads to avoid resource exhaustion
      if (concurrencyLevel > 10_000) {
        log.info("Skipping platform thread test at concurrency level {} to avoid resource exhaustion", concurrencyLevel);
        continue;
      }
      
      log.info("Testing at concurrency level: {}", concurrencyLevel);
      
      // Run with platform threads
      runBenchmark("mixed-platform-" + concurrencyLevel, concurrencyLevel, false, this::performMixedOperation);
      
      // Run with virtual threads
      runBenchmark("mixed-virtual-" + concurrencyLevel, concurrencyLevel, true, this::performMixedOperation);
    }
    
    generateReport("Mixed Workload Operations");
  }
  
  /**
   * Tests for thread pinning issues with Virtual Threads. Thread pinning occurs when a Virtual Thread
   * cannot be unmounted from its carrier thread, negating the benefits of Virtual Threads.
   */
  @Test
  public void detectThreadPinning() throws Exception {
    log.info("Starting thread pinning detection test");
    
    int concurrencyLevel = 1000;
    AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(concurrencyLevel);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks that might cause pinning
      for (int i = 0; i < concurrencyLevel; i++) {
        executor.submit(() -> {
          try {
            Instant start = Instant.now();
            
            // Perform a synchronized operation that might cause pinning
            synchronized (this) {
              // Simulate some work inside a synchronized block
              try {
                Thread.sleep(50);
              }
              catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
            
            // Check if the operation took longer than expected, indicating possible pinning
            Duration duration = Duration.between(start, Instant.now());
            if (duration.toMillis() > THREAD_PINNING_THRESHOLD_MS) {
              pinnedThreadCount.incrementAndGet();
              log.warn("Possible thread pinning detected: {} ms", duration.toMillis());
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, SECONDS);
    }
    
    log.info("Thread pinning test completed. Detected {} potential pinned threads out of {}",
        pinnedThreadCount.get(), concurrencyLevel);
    
    // Assert that pinning is minimal
    assertThat("Thread pinning should be minimal",
        (double) pinnedThreadCount.get() / concurrencyLevel, lessThan(0.05)); // Less than 5% pinning
  }
  
  /**
   * Runs a benchmark with the specified parameters and operation.
   *
   * @param benchmarkName    the name of the benchmark for reporting
   * @param concurrencyLevel the number of concurrent threads to use
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @param operation        the operation to benchmark
   */
  private void runBenchmark(
      String benchmarkName,
      int concurrencyLevel,
      boolean useVirtualThreads,
      Supplier<Boolean> operation) throws Exception
  {
    log.info("Running benchmark: {} with {} threads (virtual: {})",
        benchmarkName, concurrencyLevel, useVirtualThreads);
    
    List<BenchmarkResult> results = new ArrayList<>();
    
    // Warmup iterations
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      log.info("Warmup iteration {}/{}", i + 1, WARMUP_ITERATIONS);
      runIteration(benchmarkName, concurrencyLevel, useVirtualThreads, operation, null);
    }
    
    // Measurement iterations
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
      log.info("Measurement iteration {}/{}", i + 1, MEASUREMENT_ITERATIONS);
      BenchmarkResult result = runIteration(benchmarkName, concurrencyLevel, useVirtualThreads, operation, results);
      results.add(result);
    }
    
    // Store results for reporting
    benchmarkResults.put(benchmarkName, results);
    
    // Log summary statistics
    double avgThroughput = results.stream()
        .mapToDouble(BenchmarkResult::getThroughput)
        .average()
        .orElse(0.0);
    
    double avgLatency = results.stream()
        .mapToDouble(BenchmarkResult::getAverageLatency)
        .average()
        .orElse(0.0);
    
    log.info("Benchmark {} completed. Avg throughput: {:.2f} ops/sec, Avg latency: {:.2f} ms",
        benchmarkName, avgThroughput, avgLatency);
  }
  
  /**
   * Runs a single iteration of the benchmark.
   *
   * @param benchmarkName    the name of the benchmark
   * @param concurrencyLevel the number of concurrent threads
   * @param useVirtualThreads whether to use virtual threads
   * @param operation        the operation to perform
   * @param results          the list to add results to (null for warmup)
   * @return the benchmark result
   */
  private BenchmarkResult runIteration(
      String benchmarkName,
      int concurrencyLevel,
      boolean useVirtualThreads,
      Supplier<Boolean> operation,
      List<BenchmarkResult> results) throws Exception
  {
    // Create appropriate executor based on thread type
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        createPlatformThreadExecutor(concurrencyLevel);
    
    CountDownLatch latch = new CountDownLatch(concurrencyLevel * OPERATIONS_PER_THREAD);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalLatency = new AtomicLong(0);
    
    // Record memory usage before test
    long memoryBefore = getUsedMemory();
    
    // Record start time
    Instant startTime = Instant.now();
    
    try {
      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < concurrencyLevel; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            try {
              Instant operationStart = Instant.now();
              boolean success = operation.get();
              long latency = Duration.between(operationStart, Instant.now()).toMillis();
              
              totalLatency.addAndGet(latency);
              
              if (success) {
                successCount.incrementAndGet();
              }
              else {
                errorCount.incrementAndGet();
              }
            }
            catch (Exception e) {
              log.error("Error in benchmark operation", e);
              errorCount.incrementAndGet();
            }
            finally {
              latch.countDown();
            }
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(5, TimeUnit.MINUTES);
      if (!completed) {
        log.warn("Benchmark timed out before all operations completed");
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
    
    // Calculate metrics
    int totalOperations = successCount.get() + errorCount.get();
    double throughput = totalOperations > 0 ? (double) totalOperations / (durationMs / 1000.0) : 0;
    double averageLatency = totalOperations > 0 ? (double) totalLatency.get() / totalOperations : 0;
    
    // Create and return result
    BenchmarkResult result = new BenchmarkResult(
        benchmarkName,
        concurrencyLevel,
        useVirtualThreads,
        durationMs,
        successCount.get(),
        errorCount.get(),
        throughput,
        averageLatency,
        memoryUsed
    );
    
    if (results != null) {
      log.info("Iteration result: {}", result);
    }
    
    return result;
  }
  
  /**
   * Creates a platform thread executor with the specified number of threads.
   *
   * @param threadCount the number of threads in the pool
   * @return the executor service
   */
  private ExecutorService createPlatformThreadExecutor(int threadCount) {
    ThreadFactory factory = Thread.ofPlatform().factory();
    return Executors.newFixedThreadPool(threadCount, factory);
  }
  
  /**
   * Performs an I/O-bound operation by writing and reading a file.
   *
   * @return true if the operation was successful
   */
  private boolean performIOOperation() {
    try {
      // Create a unique filename for this thread
      String threadName = Thread.currentThread().getName();
      File file = tempDir.resolve("io-test-" + threadName + "-" + System.nanoTime() + ".tmp").toFile();
      
      // Write data to file
      try (FileOutputStream fos = new FileOutputStream(file)) {
        byte[] data = new byte[IO_OPERATION_SIZE_BYTES];
        // Fill with some pattern
        for (int i = 0; i < data.length; i++) {
          data[i] = (byte) (i % 256);
        }
        fos.write(data);
        fos.flush();
      }
      
      // Read data back
      byte[] readData = Files.readAllBytes(file.toPath());
      
      // Clean up
      Files.deleteIfExists(file.toPath());
      
      return readData.length == IO_OPERATION_SIZE_BYTES;
    }
    catch (IOException e) {
      log.error("I/O operation failed", e);
      return false;
    }
  }
  
  /**
   * Performs a coordination operation by waiting for other threads.
   *
   * @return true if the operation was successful
   */
  private boolean performCoordinationOperation() {
    try {
      // Create a small group of threads that need to coordinate
      int groupSize = 5;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch doneLatch = new CountDownLatch(groupSize);
      
      // Create a small executor for this coordination group
      ExecutorService groupExecutor = Thread.currentThread() instanceof VirtualThread ?
          Executors.newVirtualThreadPerTaskExecutor() :
          ForkJoinPool.commonPool();
      
      try {
        // Start coordination tasks
        for (int i = 0; i < groupSize; i++) {
          final int taskId = i;
          groupExecutor.submit(() -> {
            try {
              // Wait for all tasks to be ready
              startLatch.await();
              
              // Simulate some work
              Thread.sleep(10 + (taskId * 5));
              
              return true;
            }
            catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return false;
            }
            finally {
              doneLatch.countDown();
            }
          });
        }
        
        // Signal all tasks to start
        startLatch.countDown();
        
        // Wait for all tasks to complete
        return doneLatch.await(1, SECONDS);
      }
      finally {
        groupExecutor.shutdown();
      }
    }
    catch (Exception e) {
      log.error("Coordination operation failed", e);
      return false;
    }
  }
  
  /**
   * Performs a mixed operation that combines I/O and coordination.
   *
   * @return true if the operation was successful
   */
  private boolean performMixedOperation() {
    try {
      // First do some I/O
      boolean ioResult = performIOOperation();
      
      // Then do some coordination
      boolean coordResult = performCoordinationOperation();
      
      return ioResult && coordResult;
    }
    catch (Exception e) {
      log.error("Mixed operation failed", e);
      return false;
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
   * Generates a performance report from the benchmark results.
   *
   * @param title the title of the report
   */
  private void generateReport(String title) {
    StringBuilder report = new StringBuilder();
    report.append("# ").append(title).append(" Performance Report\n\n");
    report.append("## Summary\n\n");
    report.append("| Benchmark | Threads | Type | Throughput (ops/sec) | Avg Latency (ms) | Memory Used (MB) |\n");
    report.append("|-----------|---------|------|---------------------|------------------|-------------------|\n");
    
    // Group results by concurrency level
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      String platformKey = title.toLowerCase().split(" ")[0] + "-platform-" + concurrencyLevel;
      String virtualKey = title.toLowerCase().split(" ")[0] + "-virtual-" + concurrencyLevel;
      
      List<BenchmarkResult> platformResults = benchmarkResults.get(platformKey);
      List<BenchmarkResult> virtualResults = benchmarkResults.get(virtualKey);
      
      if (platformResults != null && !platformResults.isEmpty()) {
        BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
        report.append(format("| %s | %d | Platform | %.2f | %.2f | %.2f |\n",
            title, concurrencyLevel, avgPlatform.getThroughput(),
            avgPlatform.getAverageLatency(), avgPlatform.getMemoryUsedMB()));
      }
      
      if (virtualResults != null && !virtualResults.isEmpty()) {
        BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
        report.append(format("| %s | %d | Virtual | %.2f | %.2f | %.2f |\n",
            title, concurrencyLevel, avgVirtual.getThroughput(),
            avgVirtual.getAverageLatency(), avgVirtual.getMemoryUsedMB()));
      }
      
      // Add comparison if both results exist
      if (platformResults != null && !platformResults.isEmpty() &&
          virtualResults != null && !virtualResults.isEmpty()) {
        BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
        BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
        
        double throughputImprovement = calculateImprovement(avgVirtual.getThroughput(), avgPlatform.getThroughput());
        double latencyImprovement = calculateImprovement(avgPlatform.getAverageLatency(), avgVirtual.getAverageLatency());
        double memoryImprovement = calculateImprovement(avgPlatform.getMemoryUsedMB(), avgVirtual.getMemoryUsedMB());
        
        report.append(format("| %s | %d | Improvement | %.2f%% | %.2f%% | %.2f%% |\n",
            title, concurrencyLevel, throughputImprovement, latencyImprovement, memoryImprovement));
        report.append("|-----------|---------|------|---------------------|------------------|-------------------|\n");
      }
    }
    
    report.append("\n## Detailed Results\n\n");
    
    // Add detailed results for each benchmark
    for (Map.Entry<String, List<BenchmarkResult>> entry : benchmarkResults.entrySet()) {
      String benchmarkName = entry.getKey();
      List<BenchmarkResult> results = entry.getValue();
      
      report.append("### ").append(benchmarkName).append("\n\n");
      report.append("| Iteration | Duration (ms) | Success | Errors | Throughput (ops/sec) | Avg Latency (ms) | Memory Used (MB) |\n");
      report.append("|-----------|---------------|---------|--------|---------------------|------------------|-------------------|\n");
      
      for (int i = 0; i < results.size(); i++) {
        BenchmarkResult result = results.get(i);
        report.append(format("| %d | %d | %d | %d | %.2f | %.2f | %.2f |\n",
            i + 1, result.getDurationMs(), result.getSuccessCount(), result.getErrorCount(),
            result.getThroughput(), result.getAverageLatency(), result.getMemoryUsedMB()));
      }
      
      report.append("\n");
    }
    
    // Add observations and recommendations
    report.append("## Observations and Recommendations\n\n");
    report.append("### Key Findings\n\n");
    
    // Analyze results to provide insights
    boolean virtualThreadsBetter = analyzeIfVirtualThreadsPerformBetter();
    boolean scalingIssues = analyzeIfScalingIssues();
    boolean memoryEfficient = analyzeIfMemoryEfficient();
    
    if (virtualThreadsBetter) {
      report.append("- Virtual Threads show better performance than Platform Threads, especially at higher concurrency levels\n");
    }
    else {
      report.append("- Virtual Threads do not show significant performance improvements over Platform Threads in the tested scenarios\n");
    }
    
    if (scalingIssues) {
      report.append("- Performance scaling issues detected at higher concurrency levels\n");
    }
    else {
      report.append("- Good performance scaling observed across tested concurrency levels\n");
    }
    
    if (memoryEfficient) {
      report.append("- Virtual Threads demonstrate significant memory efficiency compared to Platform Threads\n");
    }
    else {
      report.append("- Memory usage patterns are similar between Virtual and Platform Threads\n");
    }
    
    report.append("\n### Recommendations\n\n");
    
    if (virtualThreadsBetter) {
      report.append("- Consider adopting Virtual Threads for I/O-bound and coordination-heavy operations\n");
      report.append("- Prioritize migration of components with high thread contention to Virtual Threads\n");
    }
    
    if (scalingIssues) {
      report.append("- Investigate potential bottlenecks in the system that limit scaling at higher concurrency levels\n");
      report.append("- Consider implementing throttling mechanisms to prevent resource exhaustion\n");
    }
    
    if (memoryEfficient) {
      report.append("- Leverage Virtual Threads to reduce memory footprint in high-concurrency scenarios\n");
    }
    
    // Write report to file
    try {
      Path reportPath = Path.of("target/virtual-thread-performance-report.md");
      Files.write(reportPath, report.toString().getBytes(StandardCharsets.UTF_8));
      log.info("Performance report written to {}", reportPath.toAbsolutePath());
    }
    catch (IOException e) {
      log.error("Failed to write performance report", e);
    }
  }
  
  /**
   * Calculates the average result from a list of benchmark results.
   *
   * @param results the list of results
   * @return the average result
   */
  private BenchmarkResult calculateAverageResult(List<BenchmarkResult> results) {
    if (results == null || results.isEmpty()) {
      return null;
    }
    
    BenchmarkResult first = results.get(0);
    double avgDuration = results.stream().mapToLong(BenchmarkResult::getDurationMs).average().orElse(0);
    double avgSuccess = results.stream().mapToInt(BenchmarkResult::getSuccessCount).average().orElse(0);
    double avgError = results.stream().mapToInt(BenchmarkResult::getErrorCount).average().orElse(0);
    double avgThroughput = results.stream().mapToDouble(BenchmarkResult::getThroughput).average().orElse(0);
    double avgLatency = results.stream().mapToDouble(BenchmarkResult::getAverageLatency).average().orElse(0);
    double avgMemory = results.stream().mapToLong(BenchmarkResult::getMemoryUsed).average().orElse(0);
    
    return new BenchmarkResult(
        first.getBenchmarkName(),
        first.getConcurrencyLevel(),
        first.isVirtualThreads(),
        (long) avgDuration,
        (int) avgSuccess,
        (int) avgError,
        avgThroughput,
        avgLatency,
        (long) avgMemory
    );
  }
  
  /**
   * Calculates the percentage improvement between two values.
   *
   * @param newValue the new value
   * @param oldValue the old value
   * @return the percentage improvement
   */
  private double calculateImprovement(double newValue, double oldValue) {
    if (oldValue == 0) {
      return 0;
    }
    return ((newValue - oldValue) / oldValue) * 100;
  }
  
  /**
   * Analyzes if Virtual Threads perform better than Platform Threads.
   *
   * @return true if Virtual Threads perform better
   */
  private boolean analyzeIfVirtualThreadsPerformBetter() {
    int betterCount = 0;
    int totalComparisons = 0;
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      for (String type : new String[]{"io", "coord", "mixed"}) {
        String platformKey = type + "-platform-" + concurrencyLevel;
        String virtualKey = type + "-virtual-" + concurrencyLevel;
        
        List<BenchmarkResult> platformResults = benchmarkResults.get(platformKey);
        List<BenchmarkResult> virtualResults = benchmarkResults.get(virtualKey);
        
        if (platformResults != null && !platformResults.isEmpty() &&
            virtualResults != null && !virtualResults.isEmpty()) {
          BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
          BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
          
          totalComparisons++;
          if (avgVirtual.getThroughput() > avgPlatform.getThroughput()) {
            betterCount++;
          }
        }
      }
    }
    
    return totalComparisons > 0 && ((double) betterCount / totalComparisons) > 0.5;
  }
  
  /**
   * Analyzes if there are scaling issues at higher concurrency levels.
   *
   * @return true if scaling issues are detected
   */
  private boolean analyzeIfScalingIssues() {
    for (String type : new String[]{"io", "coord", "mixed"}) {
      for (String threadType : new String[]{"platform", "virtual"}) {
        double prevThroughputPerThread = 0;
        
        for (int concurrencyLevel : CONCURRENCY_LEVELS) {
          String key = type + "-" + threadType + "-" + concurrencyLevel;
          List<BenchmarkResult> results = benchmarkResults.get(key);
          
          if (results != null && !results.isEmpty()) {
            BenchmarkResult avg = calculateAverageResult(results);
            double throughputPerThread = avg.getThroughput() / concurrencyLevel;
            
            if (prevThroughputPerThread > 0 && throughputPerThread < prevThroughputPerThread * 0.5) {
              // More than 50% drop in throughput per thread indicates scaling issues
              return true;
            }
            
            prevThroughputPerThread = throughputPerThread;
          }
        }
      }
    }
    
    return false;
  }
  
  /**
   * Analyzes if Virtual Threads are more memory efficient than Platform Threads.
   *
   * @return true if Virtual Threads are more memory efficient
   */
  private boolean analyzeIfMemoryEfficient() {
    int efficientCount = 0;
    int totalComparisons = 0;
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      for (String type : new String[]{"io", "coord", "mixed"}) {
        String platformKey = type + "-platform-" + concurrencyLevel;
        String virtualKey = type + "-virtual-" + concurrencyLevel;
        
        List<BenchmarkResult> platformResults = benchmarkResults.get(platformKey);
        List<BenchmarkResult> virtualResults = benchmarkResults.get(virtualKey);
        
        if (platformResults != null && !platformResults.isEmpty() &&
            virtualResults != null && !virtualResults.isEmpty()) {
          BenchmarkResult avgPlatform = calculateAverageResult(platformResults);
          BenchmarkResult avgVirtual = calculateAverageResult(virtualResults);
          
          totalComparisons++;
          if (avgVirtual.getMemoryUsed() < avgPlatform.getMemoryUsed()) {
            efficientCount++;
          }
        }
      }
    }
    
    return totalComparisons > 0 && ((double) efficientCount / totalComparisons) > 0.5;
  }
  
  /**
   * Class representing the result of a benchmark run.
   */
  private static class BenchmarkResult
  {
    private final String benchmarkName;
    private final int concurrencyLevel;
    private final boolean virtualThreads;
    private final long durationMs;
    private final int successCount;
    private final int errorCount;
    private final double throughput;
    private final double averageLatency;
    private final long memoryUsed;
    
    public BenchmarkResult(
        String benchmarkName,
        int concurrencyLevel,
        boolean virtualThreads,
        long durationMs,
        int successCount,
        int errorCount,
        double throughput,
        double averageLatency,
        long memoryUsed)
    {
      this.benchmarkName = benchmarkName;
      this.concurrencyLevel = concurrencyLevel;
      this.virtualThreads = virtualThreads;
      this.durationMs = durationMs;
      this.successCount = successCount;
      this.errorCount = errorCount;
      this.throughput = throughput;
      this.averageLatency = averageLatency;
      this.memoryUsed = memoryUsed;
    }
    
    public String getBenchmarkName() {
      return benchmarkName;
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
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getAverageLatency() {
      return averageLatency;
    }
    
    public long getMemoryUsed() {
      return memoryUsed;
    }
    
    public double getMemoryUsedMB() {
      return memoryUsed / (1024.0 * 1024.0);
    }
    
    @Override
    public String toString() {
      return String.format(
          "%s (threads: %d, virtual: %s) - Duration: %d ms, Success: %d, Errors: %d, " +
              "Throughput: %.2f ops/sec, Avg Latency: %.2f ms, Memory: %.2f MB",
          benchmarkName, concurrencyLevel, virtualThreads, durationMs, successCount, errorCount,
          throughput, averageLatency, getMemoryUsedMB());
    }
  }
}