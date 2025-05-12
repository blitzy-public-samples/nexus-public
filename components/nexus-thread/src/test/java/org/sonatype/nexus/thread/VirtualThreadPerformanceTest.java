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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Performance benchmark test comparing platform threads with virtual threads across different workloads and concurrency levels.
 * 
 * @since 3.60
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class VirtualThreadPerformanceTest
    extends TestSupport
{
  private static final String TEST_URL = "https://httpbin.org/delay/0.2";
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASUREMENT_ITERATIONS = 5;
  private static final int[] CONCURRENCY_LEVELS = {10, 50, 100, 500, 1000};
  
  private HttpClient httpClient;
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    // Create platform thread executor with a fixed thread pool
    platformThreadExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors() * 2,
        new NexusThreadFactory("platform-thread-test", "platform-thread-test")
    );
    
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Benchmark test comparing throughput between platform threads and virtual threads
   * with increasing concurrent operations.
   */
  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void testThroughputComparison() throws Exception {
    log.info("Starting throughput comparison test");
    
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      log.info("Testing with concurrency level: {}", concurrencyLevel);
      
      // Warm up
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runBenchmark(platformThreadExecutor, concurrencyLevel, "Platform Thread Warmup");
        runBenchmark(virtualThreadExecutor, concurrencyLevel, "Virtual Thread Warmup");
      }
      
      // Measure platform threads
      List<BenchmarkResult> platformResults = new ArrayList<>();
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        platformResults.add(runBenchmark(platformThreadExecutor, concurrencyLevel, "Platform Thread"));
      }
      
      // Measure virtual threads
      List<BenchmarkResult> virtualResults = new ArrayList<>();
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        virtualResults.add(runBenchmark(virtualThreadExecutor, concurrencyLevel, "Virtual Thread"));
      }
      
      // Calculate averages
      BenchmarkResult avgPlatform = calculateAverage(platformResults);
      BenchmarkResult avgVirtual = calculateAverage(virtualResults);
      
      // Log results
      log.info("Concurrency Level: {}", concurrencyLevel);
      log.info("Platform Thread Avg: {} ops/sec, {} ms latency, {} MB memory", 
          avgPlatform.throughput, avgPlatform.avgLatency, avgPlatform.memoryUsageMB);
      log.info("Virtual Thread Avg: {} ops/sec, {} ms latency, {} MB memory", 
          avgVirtual.throughput, avgVirtual.avgLatency, avgVirtual.memoryUsageMB);
      
      // At higher concurrency levels, virtual threads should show better performance
      if (concurrencyLevel >= 500) {
        assertThat("Virtual threads should have higher throughput at high concurrency",
            avgVirtual.throughput, greaterThan(avgPlatform.throughput));
        assertThat("Virtual threads should have lower latency at high concurrency",
            avgVirtual.avgLatency, lessThan(avgPlatform.avgLatency));
        assertThat("Virtual threads should use less memory at high concurrency",
            avgVirtual.memoryUsageMB, lessThan(avgPlatform.memoryUsageMB));
      }
    }
  }
  
  /**
   * Test memory consumption patterns under load for both thread models.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void testMemoryConsumptionUnderLoad() throws Exception {
    log.info("Starting memory consumption test");
    
    int concurrencyLevel = 1000; // High concurrency to stress memory usage
    
    // Measure platform threads memory usage
    System.gc(); // Request garbage collection before test
    long beforePlatformMem = getUsedMemory();
    runBenchmark(platformThreadExecutor, concurrencyLevel, "Platform Thread Memory");
    long afterPlatformMem = getUsedMemory();
    double platformMemoryUsage = (afterPlatformMem - beforePlatformMem) / (1024.0 * 1024.0);
    
    // Measure virtual threads memory usage
    System.gc(); // Request garbage collection before test
    long beforeVirtualMem = getUsedMemory();
    runBenchmark(virtualThreadExecutor, concurrencyLevel, "Virtual Thread Memory");
    long afterVirtualMem = getUsedMemory();
    double virtualMemoryUsage = (afterVirtualMem - beforeVirtualMem) / (1024.0 * 1024.0);
    
    log.info("Platform Thread Memory Usage: {} MB", platformMemoryUsage);
    log.info("Virtual Thread Memory Usage: {} MB", virtualMemoryUsage);
    
    // Virtual threads should use significantly less memory
    assertThat("Virtual threads should use less memory", 
        virtualMemoryUsage, lessThan(platformMemoryUsage * 0.7)); // At least 30% less memory
  }
  
  /**
   * Test I/O-bound workload performance differences between thread models.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void testIOBoundWorkloadPerformance() throws Exception {
    log.info("Starting I/O-bound workload performance test");
    
    int concurrencyLevel = 200;
    
    // Measure platform threads
    BenchmarkResult platformResult = runIOBoundBenchmark(platformThreadExecutor, concurrencyLevel, "Platform Thread IO");
    
    // Measure virtual threads
    BenchmarkResult virtualResult = runIOBoundBenchmark(virtualThreadExecutor, concurrencyLevel, "Virtual Thread IO");
    
    log.info("Platform Thread I/O: {} ops/sec, {} ms latency", platformResult.throughput, platformResult.avgLatency);
    log.info("Virtual Thread I/O: {} ops/sec, {} ms latency", virtualResult.throughput, virtualResult.avgLatency);
    
    // Virtual threads should perform better for I/O-bound operations
    assertThat("Virtual threads should have higher throughput for I/O-bound operations",
        virtualResult.throughput, greaterThan(platformResult.throughput));
    assertThat("Virtual threads should have lower latency for I/O-bound operations",
        virtualResult.avgLatency, lessThan(platformResult.avgLatency));
  }
  
  /**
   * Run a benchmark with the specified executor and concurrency level.
   */
  private BenchmarkResult runBenchmark(ExecutorService executor, int concurrencyLevel, String label) throws Exception {
    long startTime = System.currentTimeMillis();
    long startMemory = getUsedMemory();
    AtomicInteger completedTasks = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Long> latencies = new ArrayList<>();
    
    // Create and submit tasks
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrencyLevel; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          long taskStart = System.currentTimeMillis();
          simulateIOOperation();
          long taskEnd = System.currentTimeMillis();
          synchronized (latencies) {
            latencies.add(taskEnd - taskStart);
          }
          completedTasks.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in {}: {}", label, e.getMessage(), e);
        }
      }, executor));
    }
    
    // Wait for all tasks to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    long endTime = System.currentTimeMillis();
    long endMemory = getUsedMemory();
    
    // Calculate metrics
    double elapsedSeconds = (endTime - startTime) / 1000.0;
    double throughput = completedTasks.get() / elapsedSeconds;
    double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    double memoryUsageMB = (endMemory - startMemory) / (1024.0 * 1024.0);
    
    log.debug("{}: Completed {} tasks in {:.2f} seconds ({:.2f} ops/sec), avg latency: {:.2f} ms, memory: {:.2f} MB",
        label, completedTasks.get(), elapsedSeconds, throughput, avgLatency, memoryUsageMB);
    
    return new BenchmarkResult(throughput, avgLatency, memoryUsageMB, errorCount.get());
  }
  
  /**
   * Run an I/O-bound benchmark with the specified executor and concurrency level.
   */
  private BenchmarkResult runIOBoundBenchmark(ExecutorService executor, int concurrencyLevel, String label) throws Exception {
    long startTime = System.currentTimeMillis();
    AtomicInteger completedTasks = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<Long> latencies = new ArrayList<>();
    
    // Create and submit tasks
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrencyLevel; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          long taskStart = System.currentTimeMillis();
          // Perform actual HTTP request
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TEST_URL))
              .GET()
              .build();
          
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          assertThat("HTTP response should be successful", response.statusCode(), is(200));
          assertThat("HTTP response body should not be null", response.body(), notNullValue());
          
          long taskEnd = System.currentTimeMillis();
          synchronized (latencies) {
            latencies.add(taskEnd - taskStart);
          }
          completedTasks.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in {}: {}", label, e.getMessage(), e);
        }
      }, executor));
    }
    
    // Wait for all tasks to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    long endTime = System.currentTimeMillis();
    
    // Calculate metrics
    double elapsedSeconds = (endTime - startTime) / 1000.0;
    double throughput = completedTasks.get() / elapsedSeconds;
    double avgLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
    
    log.debug("{}: Completed {} tasks in {:.2f} seconds ({:.2f} ops/sec), avg latency: {:.2f} ms",
        label, completedTasks.get(), elapsedSeconds, throughput, avgLatency);
    
    return new BenchmarkResult(throughput, avgLatency, 0, errorCount.get());
  }
  
  /**
   * Simulate an I/O operation by sleeping for a short time.
   */
  private void simulateIOOperation() throws InterruptedException {
    // Simulate I/O latency (e.g., network call, disk access)
    Thread.sleep(50);
  }
  
  /**
   * Get the currently used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Calculate the average of multiple benchmark results.
   */
  private BenchmarkResult calculateAverage(List<BenchmarkResult> results) {
    double avgThroughput = results.stream().mapToDouble(r -> r.throughput).average().orElse(0);
    double avgLatency = results.stream().mapToDouble(r -> r.avgLatency).average().orElse(0);
    double avgMemory = results.stream().mapToDouble(r -> r.memoryUsageMB).average().orElse(0);
    int totalErrors = results.stream().mapToInt(r -> r.errorCount).sum();
    
    return new BenchmarkResult(avgThroughput, avgLatency, avgMemory, totalErrors);
  }
  
  /**
   * Class to hold benchmark result metrics.
   */
  private static class BenchmarkResult {
    final double throughput;     // Operations per second
    final double avgLatency;     // Average latency in milliseconds
    final double memoryUsageMB;  // Memory usage in MB
    final int errorCount;        // Number of errors
    
    BenchmarkResult(double throughput, double avgLatency, double memoryUsageMB, int errorCount) {
      this.throughput = throughput;
      this.avgLatency = avgLatency;
      this.memoryUsageMB = memoryUsageMB;
      this.errorCount = errorCount;
    }
  }
}