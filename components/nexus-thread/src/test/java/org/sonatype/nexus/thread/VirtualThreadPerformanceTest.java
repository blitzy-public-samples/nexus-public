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
package org.sonatype.nexus.thread;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Performance benchmark test comparing platform threads with virtual threads across different workloads and concurrency levels.
 * Measures throughput, memory usage, and latency to validate the scalability benefits of virtual threads for I/O-bound operations.
 *
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadPerformanceTest
    extends TestSupport
{
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASUREMENT_ITERATIONS = 5;
  private static final int MAX_CONCURRENT_OPERATIONS = 1000;
  private static final int OPERATIONS_PER_TEST = 5000;
  private static final int HTTP_TIMEOUT_SECONDS = 10;
  private static final String TEST_URL = "https://httpbin.org/delay/0.2"; // 200ms delay
  
  private HttpClient httpClient;
  private HttpRequest httpRequest;
  
  @Before
  public void setUp() throws Exception {
    httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
        .build();
    
    httpRequest = HttpRequest.newBuilder()
        .uri(URI.create(TEST_URL))
        .timeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
        .GET()
        .build();
  }
  
  @After
  public void tearDown() {
    httpClient = null;
    httpRequest = null;
  }
  
  /**
   * Benchmark comparing throughput between platform threads and virtual threads with increasing concurrency.
   * This test measures operations per second for both thread models across different concurrency levels.
   */
  @Test
  public void testThroughputComparison() throws Exception {
    log.info("Starting throughput comparison benchmark");
    
    // Create thread factories for both thread types
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Concurrency levels to test
    int[] concurrencyLevels = {10, 50, 100, 250, 500, 1000};
    
    // Results storage
    Map<String, List<Double>> throughputResults = new ConcurrentHashMap<>();
    throughputResults.put("platform", new ArrayList<>());
    throughputResults.put("virtual", new ArrayList<>());
    
    // Run benchmarks for each concurrency level
    for (int concurrency : concurrencyLevels) {
      log.info("Testing concurrency level: {}", concurrency);
      
      // Benchmark platform threads
      double platformThroughput = benchmarkThroughput(platformThreadFactory, concurrency);
      throughputResults.get("platform").add(platformThroughput);
      log.info("Platform thread throughput at concurrency {}: {} ops/sec", concurrency, platformThroughput);
      
      // Benchmark virtual threads
      double virtualThroughput = benchmarkThroughput(virtualThreadFactory, concurrency);
      throughputResults.get("virtual").add(virtualThroughput);
      log.info("Virtual thread throughput at concurrency {}: {} ops/sec", concurrency, virtualThroughput);
      
      // At higher concurrency levels, virtual threads should outperform platform threads
      if (concurrency >= 100) {
        assertThat("Virtual threads should have higher throughput at high concurrency",
            virtualThroughput, greaterThan(platformThroughput));
      }
    }
    
    // Generate report
    generateThroughputReport(concurrencyLevels, throughputResults);
  }
  
  /**
   * Benchmark comparing memory usage between platform threads and virtual threads under load.
   * This test measures the memory footprint of both thread models with increasing thread counts.
   */
  @Test
  public void testMemoryUsageComparison() throws Exception {
    log.info("Starting memory usage comparison benchmark");
    
    // Thread counts to test
    int[] threadCounts = {100, 500, 1000};
    
    // Results storage
    Map<String, List<Long>> memoryResults = new ConcurrentHashMap<>();
    memoryResults.put("platform", new ArrayList<>());
    memoryResults.put("virtual", new ArrayList<>());
    
    for (int threadCount : threadCounts) {
      log.info("Testing memory usage with {} threads", threadCount);
      
      // Measure platform threads memory usage
      long platformMemory = measureMemoryUsage(() -> createPlatformThreads(threadCount));
      memoryResults.get("platform").add(platformMemory);
      log.info("Platform threads memory usage with {} threads: {} KB", threadCount, platformMemory / 1024);
      
      // Measure virtual threads memory usage
      long virtualMemory = measureMemoryUsage(() -> createVirtualThreads(threadCount));
      memoryResults.get("virtual").add(virtualMemory);
      log.info("Virtual threads memory usage with {} threads: {} KB", threadCount, virtualMemory / 1024);
      
      // Virtual threads should use significantly less memory
      assertThat("Virtual threads should use less memory", virtualMemory, lessThan(platformMemory / 2));
    }
    
    // Generate report
    generateMemoryReport(threadCounts, memoryResults);
  }
  
  /**
   * Benchmark comparing latency between platform threads and virtual threads for I/O-bound operations.
   * This test measures response time percentiles (p50, p95, p99) for both thread models.
   */
  @Test
  public void testLatencyComparison() throws Exception {
    log.info("Starting latency comparison benchmark");
    
    // Create thread factories for both thread types
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Concurrency level for latency test
    int concurrency = 500;
    
    // Run latency benchmark for platform threads
    Map<String, Long> platformLatencies = benchmarkLatency(platformThreadFactory, concurrency);
    log.info("Platform thread latencies at concurrency {}: p50={}ms, p95={}ms, p99={}ms", 
        concurrency, 
        platformLatencies.get("p50"), 
        platformLatencies.get("p95"), 
        platformLatencies.get("p99"));
    
    // Run latency benchmark for virtual threads
    Map<String, Long> virtualLatencies = benchmarkLatency(virtualThreadFactory, concurrency);
    log.info("Virtual thread latencies at concurrency {}: p50={}ms, p95={}ms, p99={}ms", 
        concurrency, 
        virtualLatencies.get("p50"), 
        virtualLatencies.get("p95"), 
        virtualLatencies.get("p99"));
    
    // Virtual threads should have lower tail latencies at high concurrency
    assertThat("Virtual threads should have lower p95 latency", 
        virtualLatencies.get("p95"), lessThan(platformLatencies.get("p95")));
    assertThat("Virtual threads should have lower p99 latency", 
        virtualLatencies.get("p99"), lessThan(platformLatencies.get("p99")));
    
    // Generate report
    generateLatencyReport(concurrency, platformLatencies, virtualLatencies);
  }
  
  /**
   * Measures throughput (operations per second) for a given thread factory and concurrency level.
   */
  private double benchmarkThroughput(ThreadFactory threadFactory, int concurrency) throws Exception {
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warmup phase
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runConcurrentHttpRequests(executor, Math.min(concurrency, 100), 100);
      }
      
      // Measurement phase
      List<Double> measurements = new ArrayList<>();
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        long startTime = System.nanoTime();
        runConcurrentHttpRequests(executor, concurrency, OPERATIONS_PER_TEST);
        long endTime = System.nanoTime();
        
        double durationSeconds = NANOSECONDS.toMillis(endTime - startTime) / 1000.0;
        double throughput = OPERATIONS_PER_TEST / durationSeconds;
        measurements.add(throughput);
      }
      
      // Calculate average throughput
      return measurements.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Measures memory usage for a given thread creation operation.
   */
  private long measureMemoryUsage(Runnable threadCreator) throws Exception {
    // Force garbage collection before measurement
    System.gc();
    Thread.sleep(1000);
    
    // Measure memory before thread creation
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create threads
    threadCreator.run();
    
    // Measure memory after thread creation
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Return memory difference in bytes
    return memoryAfter - memoryBefore;
  }
  
  /**
   * Creates and starts the specified number of platform threads that block on a latch.
   */
  private void createPlatformThreads(int count) {
    CountDownLatch latch = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < count; i++) {
      Thread thread = new Thread(() -> {
        try {
          latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      thread.start();
      threads.add(thread);
    }
    
    // Release threads
    latch.countDown();
    
    // Wait for threads to finish
    for (Thread thread : threads) {
      try {
        thread.join(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Creates and starts the specified number of virtual threads that block on a latch.
   */
  private void createVirtualThreads(int count) {
    CountDownLatch latch = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < count; i++) {
      Thread thread = Thread.ofVirtual().start(() -> {
        try {
          latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      threads.add(thread);
    }
    
    // Release threads
    latch.countDown();
    
    // Wait for threads to finish
    for (Thread thread : threads) {
      try {
        thread.join(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Measures latency percentiles for a given thread factory and concurrency level.
   */
  private Map<String, Long> benchmarkLatency(ThreadFactory threadFactory, int concurrency) throws Exception {
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warmup phase
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runConcurrentHttpRequests(executor, Math.min(concurrency, 100), 100);
      }
      
      // Measurement phase - collect all latencies
      List<Long> allLatencies = new ArrayList<>();
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        List<Long> latencies = measureHttpRequestLatencies(executor, concurrency, 200);
        allLatencies.addAll(latencies);
      }
      
      // Sort latencies for percentile calculation
      allLatencies.sort(Long::compare);
      
      // Calculate percentiles
      Map<String, Long> percentiles = new ConcurrentHashMap<>();
      percentiles.put("p50", calculatePercentile(allLatencies, 50));
      percentiles.put("p95", calculatePercentile(allLatencies, 95));
      percentiles.put("p99", calculatePercentile(allLatencies, 99));
      
      return percentiles;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Runs the specified number of concurrent HTTP requests using the provided executor.
   */
  private void runConcurrentHttpRequests(ExecutorService executor, int concurrency, int totalOperations) 
      throws Exception {
    CountDownLatch latch = new CountDownLatch(totalOperations);
    AtomicInteger activeCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    // Submit tasks
    for (int i = 0; i < totalOperations; i++) {
      while (activeCount.get() >= concurrency) {
        // Wait until concurrency drops below limit
        Thread.sleep(1);
      }
      
      activeCount.incrementAndGet();
      executor.submit(() -> {
        try {
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
          completedCount.incrementAndGet();
        } catch (Exception e) {
          log.error("HTTP request failed", e);
        } finally {
          activeCount.decrementAndGet();
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    latch.await();
    
    // Verify all operations completed successfully
    assertThat("All operations should complete successfully", 
        completedCount.get(), is(totalOperations));
  }
  
  /**
   * Measures latencies for HTTP requests using the provided executor.
   */
  private List<Long> measureHttpRequestLatencies(ExecutorService executor, int concurrency, int totalOperations) 
      throws Exception {
    List<Long> latencies = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(totalOperations);
    AtomicInteger activeCount = new AtomicInteger(0);
    
    // Submit tasks
    for (int i = 0; i < totalOperations; i++) {
      while (activeCount.get() >= concurrency) {
        // Wait until concurrency drops below limit
        Thread.sleep(1);
      }
      
      activeCount.incrementAndGet();
      executor.submit(() -> {
        try {
          long startTime = System.nanoTime();
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
          long endTime = System.nanoTime();
          
          // Record latency in milliseconds
          long latencyMs = NANOSECONDS.toMillis(endTime - startTime);
          synchronized (latencies) {
            latencies.add(latencyMs);
          }
        } catch (Exception e) {
          log.error("HTTP request failed", e);
        } finally {
          activeCount.decrementAndGet();
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    latch.await();
    
    return latencies;
  }
  
  /**
   * Calculates the specified percentile from a sorted list of values.
   */
  private long calculatePercentile(List<Long> sortedValues, int percentile) {
    if (sortedValues.isEmpty()) {
      return 0;
    }
    
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
  }
  
  /**
   * Generates a throughput comparison report.
   */
  private void generateThroughputReport(int[] concurrencyLevels, Map<String, List<Double>> results) {
    StringBuilder report = new StringBuilder();
    report.append("\nTHROUGHPUT COMPARISON REPORT\n");
    report.append("============================\n\n");
    report.append(String.format("%-15s %-15s %-15s %-15s\n", "Concurrency", "Platform (ops/s)", "Virtual (ops/s)", "Improvement"));
    report.append(String.format("%-15s %-15s %-15s %-15s\n", "-----------", "---------------", "-------------", "-----------"));
    
    List<Double> platformResults = results.get("platform");
    List<Double> virtualResults = results.get("virtual");
    
    for (int i = 0; i < concurrencyLevels.length; i++) {
      double platformThroughput = platformResults.get(i);
      double virtualThroughput = virtualResults.get(i);
      double improvement = ((virtualThroughput / platformThroughput) - 1) * 100;
      
      report.append(String.format("%-15d %-15.2f %-15.2f %-15.2f%%\n", 
          concurrencyLevels[i], platformThroughput, virtualThroughput, improvement));
    }
    
    log.info(report.toString());
    
    // Save report to file
    try {
      Path reportPath = Path.of("target/virtual-thread-throughput-report.txt");
      Files.createDirectories(reportPath.getParent());
      Files.writeString(reportPath, report.toString());
      log.info("Throughput report saved to {}", reportPath.toAbsolutePath());
    } catch (IOException e) {
      log.error("Failed to save throughput report", e);
    }
  }
  
  /**
   * Generates a memory usage comparison report.
   */
  private void generateMemoryReport(int[] threadCounts, Map<String, List<Long>> results) {
    StringBuilder report = new StringBuilder();
    report.append("\nMEMORY USAGE COMPARISON REPORT\n");
    report.append("============================\n\n");
    report.append(String.format("%-15s %-20s %-20s %-15s\n", "Thread Count", "Platform (KB)", "Virtual (KB)", "Reduction"));
    report.append(String.format("%-15s %-20s %-20s %-15s\n", "-----------", "------------", "-----------", "---------"));
    
    List<Long> platformResults = results.get("platform");
    List<Long> virtualResults = results.get("virtual");
    
    for (int i = 0; i < threadCounts.length; i++) {
      long platformMemory = platformResults.get(i) / 1024; // Convert to KB
      long virtualMemory = virtualResults.get(i) / 1024; // Convert to KB
      double reduction = ((double) (platformMemory - virtualMemory) / platformMemory) * 100;
      
      report.append(String.format("%-15d %-20d %-20d %-15.2f%%\n", 
          threadCounts[i], platformMemory, virtualMemory, reduction));
    }
    
    log.info(report.toString());
    
    // Save report to file
    try {
      Path reportPath = Path.of("target/virtual-thread-memory-report.txt");
      Files.createDirectories(reportPath.getParent());
      Files.writeString(reportPath, report.toString());
      log.info("Memory report saved to {}", reportPath.toAbsolutePath());
    } catch (IOException e) {
      log.error("Failed to save memory report", e);
    }
  }
  
  /**
   * Generates a latency comparison report.
   */
  private void generateLatencyReport(int concurrency, Map<String, Long> platformLatencies, Map<String, Long> virtualLatencies) {
    StringBuilder report = new StringBuilder();
    report.append("\nLATENCY COMPARISON REPORT\n");
    report.append("=========================\n\n");
    report.append(String.format("Concurrency level: %d\n\n", concurrency));
    report.append(String.format("%-10s %-15s %-15s %-15s\n", "Percentile", "Platform (ms)", "Virtual (ms)", "Improvement"));
    report.append(String.format("%-10s %-15s %-15s %-15s\n", "----------", "------------", "-----------", "-----------"));
    
    String[] percentiles = {"p50", "p95", "p99"};
    
    for (String percentile : percentiles) {
      long platformLatency = platformLatencies.get(percentile);
      long virtualLatency = virtualLatencies.get(percentile);
      double improvement = ((double) (platformLatency - virtualLatency) / platformLatency) * 100;
      
      report.append(String.format("%-10s %-15d %-15d %-15.2f%%\n", 
          percentile, platformLatency, virtualLatency, improvement));
    }
    
    log.info(report.toString());
    
    // Save report to file
    try {
      Path reportPath = Path.of("target/virtual-thread-latency-report.txt");
      Files.createDirectories(reportPath.getParent());
      Files.writeString(reportPath, report.toString());
      log.info("Latency report saved to {}", reportPath.toAbsolutePath());
    } catch (IOException e) {
      log.error("Failed to save latency report", e);
    }
  }
}