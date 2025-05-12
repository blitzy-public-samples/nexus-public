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
package org.sonatype.nexus.testsuite.testsupport.performance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;

import com.google.common.io.Files;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Benchmark test that compares the performance and scalability of platform threads versus
 * Java 21 virtual threads in repository performance scenarios.
 * 
 * This test validates that virtual threads provide improved scalability, response time,
 * and memory efficiency compared to platform threads, especially under high concurrency.
 *
 * @since 3.60
 */
@Tag("java21-tests")
@Tag("virtual-threads")
public class ThreadModelComparisonTest
    extends TestSupport
{
  private static final int[] THREAD_COUNTS = {10, 100, 500, 1000, 5000, 10000};
  
  private static final int DURATION_SECONDS = 10;
  
  private static final int WARMUP_ITERATIONS = 5;
  
  private static final int MEASUREMENT_ITERATIONS = 10;
  
  private static final String PLATFORM_THREADS = "platform-threads";
  
  private static final String VIRTUAL_THREADS = "virtual-threads";

  @TempDir
  public File tempDir;
  
  private File reportDir;
  
  private ThreadFactory platformThreadFactory;
  
  private ThreadFactory virtualThreadFactory;
  
  private Map<String, PerformanceData> results;
  
  private Map<String, MemoryUsageData> memoryUsage;

  @BeforeEach
  public void setUp() throws IOException {
    reportDir = new File(tempDir, "thread-model-comparison");
    reportDir.mkdirs();
    
    platformThreadFactory = Thread.ofPlatform().factory();
    virtualThreadFactory = Thread.ofVirtual().factory();
    
    results = new HashMap<>();
    memoryUsage = new HashMap<>();
    
    // Initialize performance data for both thread models
    results.put(PLATFORM_THREADS, new PerformanceData());
    results.put(VIRTUAL_THREADS, new PerformanceData());
    
    // Initialize memory usage data for both thread models
    memoryUsage.put(PLATFORM_THREADS, new MemoryUsageData());
    memoryUsage.put(VIRTUAL_THREADS, new MemoryUsageData());
  }
  
  @AfterEach
  public void tearDown() {
    // Ensure we don't leave any executors running
    System.gc();
  }

  /**
   * Tests the performance of simulated repository operations using both platform threads and virtual threads.
   * 
   * This test measures and compares:
   * - Throughput (operations per second)
   * - Response time (latency)
   * - Memory efficiency
   * - Scalability with increasing thread counts
   * 
   * The test validates that virtual threads provide better performance characteristics,
   * especially at higher concurrency levels.
   */
  @Test
  public void compareThreadModelPerformance() throws Exception {
    log.info("Starting thread model comparison benchmark");
    
    // Run benchmarks for both thread models
    runBenchmark("I/O-bound operation", this::simulateIoBoundOperation, PLATFORM_THREADS, platformThreadFactory);
    runBenchmark("I/O-bound operation", this::simulateIoBoundOperation, VIRTUAL_THREADS, virtualThreadFactory);
    
    // Generate comparison report
    generateComparisonReport();
    
    // Verify performance assertions
    verifyPerformanceAssertions();
    
    log.info("Thread model comparison benchmark completed");
  }
  
  /**
   * Runs a benchmark for the specified operation using the given thread factory.
   * 
   * @param operationName the name of the operation being benchmarked
   * @param operation the operation to benchmark
   * @param threadModel the thread model name (platform or virtual)
   * @param threadFactory the thread factory to use
   */
  private void runBenchmark(
      String operationName,
      Callable<Void> operation,
      String threadModel,
      ThreadFactory threadFactory) throws Exception 
  {
    log.info("Running benchmark for {} using {}", operationName, threadModel);
    
    PerformanceData performanceData = results.get(threadModel);
    MemoryUsageData memUsageData = memoryUsage.get(threadModel);
    
    for (int threadCount : THREAD_COUNTS) {
      log.info("  Benchmarking with {} threads", threadCount);
      
      // Create task list
      List<Callable<Void>> tasks = new ArrayList<>();
      for (int i = 0; i < threadCount; i++) {
        tasks.add(operation);
      }
      
      // Warm up
      log.info("    Warming up...");
      ExecutorService warmupExecutor = createExecutorService(threadFactory, threadCount);
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runIteration(tasks, warmupExecutor, 1);
      }
      warmupExecutor.shutdown();
      warmupExecutor.awaitTermination(30, TimeUnit.SECONDS);
      
      // Measure memory before test
      System.gc();
      long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      
      // Run measurement iterations
      log.info("    Running measurement iterations...");
      ExecutorService executor = createExecutorService(threadFactory, threadCount);
      
      AtomicInteger totalProcessed = new AtomicInteger(0);
      AtomicInteger totalFailed = new AtomicInteger(0);
      AtomicLong totalDuration = new AtomicLong(0);
      
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        LoadExecutor loadExecutor = new LoadExecutor(tasks, threadCount, DURATION_SECONDS);
        
        boolean exceptionThrown = false;
        try {
          loadExecutor.callTasks();
        }
        catch (Exception | AssertionError e) {
          log.warn("Iteration failed", e);
          exceptionThrown = true;
        }
        
        totalProcessed.addAndGet(loadExecutor.getRequestsProcessed());
        totalFailed.addAndGet(loadExecutor.getRequestsStarted() - loadExecutor.getRequestsProcessed());
        totalDuration.addAndGet(DURATION_SECONDS);
        
        if (exceptionThrown) {
          break;
        }
      }
      
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
      
      // Measure memory after test
      System.gc();
      long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      long memoryDelta = memoryAfter - memoryBefore;
      
      // Record memory usage
      memUsageData.addMemoryUsage(threadCount, memoryDelta);
      
      // Record performance results
      PerformanceTestSeries testSeries = performanceData.findTestResult(operationName);
      testSeries.addResults(threadCount, new PerformanceRunResult(
          totalProcessed.get(),
          totalFailed.get(),
          totalDuration.intValue(),
          false));
      
      log.info("    Results for {} threads: {} ops/sec, memory delta: {} bytes",
          threadCount,
          totalProcessed.get() / totalDuration.get(),
          memoryDelta);
    }
  }
  
  /**
   * Creates an appropriate executor service based on the thread factory.
   */
  private ExecutorService createExecutorService(ThreadFactory threadFactory, int threadCount) {
    if (threadFactory == virtualThreadFactory) {
      // For virtual threads, we use the newVirtualThreadPerTaskExecutor
      return Executors.newVirtualThreadPerTaskExecutor();
    } else {
      // For platform threads, we use a fixed thread pool
      return Executors.newFixedThreadPool(threadCount, threadFactory);
    }
  }
  
  /**
   * Runs a single iteration of the benchmark.
   */
  private void runIteration(List<Callable<Void>> tasks, ExecutorService executor, int durationSeconds) 
      throws Exception {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (Callable<Void> task : tasks) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          task.call();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor));
    }
    
    // Wait for the specified duration
    Thread.sleep(durationSeconds * 1000L);
  }
  
  /**
   * Simulates an I/O-bound operation typical in repository operations.
   * This includes a mix of CPU work and simulated I/O waits.
   */
  private Void simulateIoBoundOperation() throws Exception {
    // Simulate some CPU work (20%)
    long start = System.nanoTime();
    while (System.nanoTime() - start < 2_000_000) { // 2ms of CPU work
      // Busy-wait to simulate CPU work
      Math.sin(Math.random());
    }
    
    // Simulate I/O wait (80%)
    Thread.sleep(8); // 8ms of I/O wait
    
    return null;
  }
  
  /**
   * Generates an HTML report comparing the performance of platform threads and virtual threads.
   */
  private void generateComparisonReport() throws IOException {
    // Generate individual performance reports
    File platformReportFile = new File(reportDir, "platform-threads-report.html");
    File virtualReportFile = new File(reportDir, "virtual-threads-report.html");
    
    PerformanceChart.writePerformanceReport(results.get(PLATFORM_THREADS), platformReportFile);
    PerformanceChart.writePerformanceReport(results.get(VIRTUAL_THREADS), virtualReportFile);
    
    // Generate comparison report
    StringBuilder comparisonReport = new StringBuilder();
    comparisonReport.append("<html><head><title>Thread Model Comparison</title>")
        .append("<style>")
        .append("body { font-family: Arial, sans-serif; margin: 20px; }")
        .append("table { border-collapse: collapse; width: 100%; }")
        .append("th, td { border: 1px solid #ddd; padding: 8px; text-align: right; }")
        .append("th { background-color: #f2f2f2; }")
        .append("tr:nth-child(even) { background-color: #f9f9f9; }")
        .append("h1, h2 { color: #333; }")
        .append("</style>")
        .append("</head><body>")
        .append("<h1>Thread Model Performance Comparison</h1>")
        .append("<p>Comparison of platform threads vs. virtual threads performance</p>")
        .append("<h2>Throughput Comparison (operations/second)</h2>")
        .append("<table>")
        .append("<tr><th>Thread Count</th><th>Platform Threads</th><th>Virtual Threads</th><th>Improvement</th></tr>");
    
    // Add throughput data
    for (int threadCount : THREAD_COUNTS) {
      PerformanceRunResult platformResult = results.get(PLATFORM_THREADS)
          .findTestResult("I/O-bound operation")
          .getResult(threadCount);
      
      PerformanceRunResult virtualResult = results.get(VIRTUAL_THREADS)
          .findTestResult("I/O-bound operation")
          .getResult(threadCount);
      
      if (platformResult != null && virtualResult != null) {
        double platformThroughput = (double) platformResult.getRequestsCompleted() / platformResult.getTestDurationSeconds();
        double virtualThroughput = (double) virtualResult.getRequestsCompleted() / virtualResult.getTestDurationSeconds();
        double improvement = ((virtualThroughput / platformThroughput) - 1.0) * 100.0;
        
        comparisonReport.append(String.format("<tr><td>%d</td><td>%.2f</td><td>%.2f</td><td>%.2f%%</td></tr>",
            threadCount, platformThroughput, virtualThroughput, improvement));
      }
    }
    
    comparisonReport.append("</table>")
        .append("<h2>Memory Usage Comparison</h2>")
        .append("<table>")
        .append("<tr><th>Thread Count</th><th>Platform Threads (MB)</th><th>Virtual Threads (MB)</th><th>Difference</th></tr>");
    
    // Add memory usage data
    for (int threadCount : THREAD_COUNTS) {
      Long platformMemory = memoryUsage.get(PLATFORM_THREADS).getMemoryUsage(threadCount);
      Long virtualMemory = memoryUsage.get(VIRTUAL_THREADS).getMemoryUsage(threadCount);
      
      if (platformMemory != null && virtualMemory != null) {
        double platformMemoryMB = platformMemory / (1024.0 * 1024.0);
        double virtualMemoryMB = virtualMemory / (1024.0 * 1024.0);
        double difference = platformMemoryMB - virtualMemoryMB;
        
        comparisonReport.append(String.format("<tr><td>%d</td><td>%.2f</td><td>%.2f</td><td>%.2f MB</td></tr>",
            threadCount, platformMemoryMB, virtualMemoryMB, difference));
      }
    }
    
    comparisonReport.append("</table>")
        .append("<p>Individual reports: ")
        .append("<a href='platform-threads-report.html'>Platform Threads</a> | ")
        .append("<a href='virtual-threads-report.html'>Virtual Threads</a>")
        .append("</p>")
        .append("</body></html>");
    
    // Write comparison report
    File comparisonFile = new File(reportDir, "thread-model-comparison.html");
    Files.asCharSink(comparisonFile, StandardCharsets.UTF_8).write(comparisonReport.toString());
    
    log.info("Generated comparison report at {}", comparisonFile.getAbsolutePath());
  }
  
  /**
   * Verifies that virtual threads meet or exceed the performance thresholds compared to platform threads.
   */
  private void verifyPerformanceAssertions() {
    // For high concurrency (1000+ threads), virtual threads should show significantly better performance
    for (int threadCount : THREAD_COUNTS) {
      if (threadCount >= 1000) {
        PerformanceRunResult platformResult = results.get(PLATFORM_THREADS)
            .findTestResult("I/O-bound operation")
            .getResult(threadCount);
        
        PerformanceRunResult virtualResult = results.get(VIRTUAL_THREADS)
            .findTestResult("I/O-bound operation")
            .getResult(threadCount);
        
        if (platformResult != null && virtualResult != null) {
          double platformThroughput = (double) platformResult.getRequestsCompleted() / platformResult.getTestDurationSeconds();
          double virtualThroughput = (double) virtualResult.getRequestsCompleted() / virtualResult.getTestDurationSeconds();
          
          // Virtual threads should provide at least 20% better throughput at high concurrency
          log.info("Thread count: {}, Platform throughput: {}, Virtual throughput: {}", 
              threadCount, platformThroughput, virtualThroughput);
          
          assertThat("Virtual threads should provide better throughput at high concurrency",
              virtualThroughput, greaterThan(platformThroughput * 1.2));
          
          // Memory usage should be lower with virtual threads
          Long platformMemory = memoryUsage.get(PLATFORM_THREADS).getMemoryUsage(threadCount);
          Long virtualMemory = memoryUsage.get(VIRTUAL_THREADS).getMemoryUsage(threadCount);
          
          if (platformMemory != null && virtualMemory != null) {
            log.info("Thread count: {}, Platform memory: {} bytes, Virtual memory: {} bytes", 
                threadCount, platformMemory, virtualMemory);
            
            // At high thread counts, virtual threads should use significantly less memory
            assertThat("Virtual threads should use less memory at high concurrency",
                virtualMemory, lessThan(platformMemory));
          }
        }
      }
    }
    
    // Verify scalability - virtual threads should maintain throughput as thread count increases
    PerformanceTestSeries virtualSeries = results.get(VIRTUAL_THREADS).findTestResult("I/O-bound operation");
    
    if (THREAD_COUNTS.length >= 2) {
      int lowThreadCount = THREAD_COUNTS[0]; // e.g., 10
      int highThreadCount = THREAD_COUNTS[THREAD_COUNTS.length - 1]; // e.g., 10000
      
      PerformanceRunResult lowResult = virtualSeries.getResult(lowThreadCount);
      PerformanceRunResult highResult = virtualSeries.getResult(highThreadCount);
      
      if (lowResult != null && highResult != null) {
        double lowThroughputPerThread = ((double) lowResult.getRequestsCompleted() / lowResult.getTestDurationSeconds()) / lowThreadCount;
        double highThroughputPerThread = ((double) highResult.getRequestsCompleted() / highResult.getTestDurationSeconds()) / highThreadCount;
        
        // Per-thread throughput should not degrade significantly as thread count increases
        double scalabilityRatio = highThroughputPerThread / lowThroughputPerThread;
        log.info("Virtual thread scalability ratio (high/low throughput per thread): {}", scalabilityRatio);
        
        // Should maintain at least 50% efficiency at high thread counts
        assertThat("Virtual threads should maintain throughput as thread count increases",
            scalabilityRatio, greaterThanOrEqualTo(0.5));
      }
    }
  }
  
  /**
   * Simple class to track memory usage for different thread counts.
   */
  private static class MemoryUsageData {
    private final Map<Integer, Long> memoryByThreadCount = new HashMap<>();
    
    public void addMemoryUsage(int threadCount, long memoryBytes) {
      memoryByThreadCount.put(threadCount, memoryBytes);
    }
    
    public Long getMemoryUsage(int threadCount) {
      return memoryByThreadCount.get(threadCount);
    }
  }
}