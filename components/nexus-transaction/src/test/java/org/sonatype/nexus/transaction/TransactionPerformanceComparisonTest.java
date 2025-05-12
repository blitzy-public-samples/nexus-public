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
package org.sonatype.nexus.transaction;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

/**
 * Performance comparison test between platform threads and virtual threads for transaction operations.
 * 
 * This test quantifies the benefits of Java 21 Virtual Threads for transaction processing and helps
 * optimize configurations for production environments.
 * 
 * @since 3.60
 */
public class TransactionPerformanceComparisonTest
    extends TestSupport
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  private static final int MAX_CONCURRENCY = 1000;
  private static final int OPERATIONS_PER_THREAD = 100;
  private static final int RETRY_PROBABILITY_PERCENT = 10;
  private static final int MAX_RETRIES = 3;
  
  @TempDir
  File tempDir;
  
  private Random random;
  private File resultsDir;
  
  @BeforeEach
  void setUp() throws IOException {
    random = new Random(42); // Fixed seed for reproducibility
    resultsDir = new File(tempDir, "performance-results");
    resultsDir.mkdirs();
  }
  
  @AfterEach
  void tearDown() {
    log.info("Performance test results available in: {}", resultsDir.getAbsolutePath());
  }
  
  /**
   * Tests transaction throughput under varying concurrency levels, comparing platform threads vs virtual threads.
   * 
   * This test demonstrates the scalability advantages of virtual threads when handling many concurrent transactions.
   */
  @Test
  void testTransactionThroughputScalability(TestInfo testInfo) throws Exception {
    // Concurrency levels to test
    int[] concurrencyLevels = {1, 10, 50, 100, 250, 500, 1000};
    
    Map<String, Map<Integer, PerformanceResult>> results = new HashMap<>();
    results.put("platform", new HashMap<>());
    results.put("virtual", new HashMap<>());
    
    // Run tests with platform threads
    for (int concurrency : concurrencyLevels) {
      PerformanceResult result = measureTransactionThroughput(
          Thread.ofPlatform().factory(),
          concurrency,
          "Platform Threads");
      results.get("platform").put(concurrency, result);
    }
    
    // Run tests with virtual threads
    for (int concurrency : concurrencyLevels) {
      PerformanceResult result = measureTransactionThroughput(
          Thread.ofVirtual().factory(),
          concurrency,
          "Virtual Threads");
      results.get("virtual").put(concurrency, result);
    }
    
    // Generate CSV report
    generateCsvReport(results, "transaction-throughput", testInfo);
    
    // Verify that virtual threads perform better at high concurrency
    int highConcurrency = concurrencyLevels[concurrencyLevels.length - 1];
    PerformanceResult platformResult = results.get("platform").get(highConcurrency);
    PerformanceResult virtualResult = results.get("virtual").get(highConcurrency);
    
    log.info("Platform threads throughput at {} concurrency: {} ops/sec", 
        highConcurrency, platformResult.getOperationsPerSecond());
    log.info("Virtual threads throughput at {} concurrency: {} ops/sec", 
        highConcurrency, virtualResult.getOperationsPerSecond());
    
    // At high concurrency, virtual threads should have better throughput
    assertThat("Virtual threads should have higher throughput at high concurrency",
        virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond()));
  }
  
  /**
   * Tests transaction latency under varying concurrency levels, comparing platform threads vs virtual threads.
   * 
   * This test demonstrates the latency advantages of virtual threads when handling many concurrent transactions.
   */
  @Test
  void testTransactionLatencyUnderLoad(TestInfo testInfo) throws Exception {
    // Concurrency levels to test
    int[] concurrencyLevels = {1, 10, 50, 100, 250, 500, 1000};
    
    Map<String, Map<Integer, PerformanceResult>> results = new HashMap<>();
    results.put("platform", new HashMap<>());
    results.put("virtual", new HashMap<>());
    
    // Run tests with platform threads
    for (int concurrency : concurrencyLevels) {
      PerformanceResult result = measureTransactionLatency(
          Thread.ofPlatform().factory(),
          concurrency,
          "Platform Threads");
      results.get("platform").put(concurrency, result);
    }
    
    // Run tests with virtual threads
    for (int concurrency : concurrencyLevels) {
      PerformanceResult result = measureTransactionLatency(
          Thread.ofVirtual().factory(),
          concurrency,
          "Virtual Threads");
      results.get("virtual").put(concurrency, result);
    }
    
    // Generate CSV report
    generateCsvReport(results, "transaction-latency", testInfo);
    
    // Verify that virtual threads have lower latency at high concurrency
    int highConcurrency = concurrencyLevels[concurrencyLevels.length - 1];
    PerformanceResult platformResult = results.get("platform").get(highConcurrency);
    PerformanceResult virtualResult = results.get("virtual").get(highConcurrency);
    
    log.info("Platform threads P95 latency at {} concurrency: {} ms", 
        highConcurrency, platformResult.getP95LatencyMs());
    log.info("Virtual threads P95 latency at {} concurrency: {} ms", 
        highConcurrency, virtualResult.getP95LatencyMs());
    
    // At high concurrency, virtual threads should have lower latency
    assertThat("Virtual threads should have lower P95 latency at high concurrency",
        virtualResult.getP95LatencyMs(), lessThan(platformResult.getP95LatencyMs()));
  }
  
  /**
   * Tests memory utilization patterns for both thread types under varying loads.
   * 
   * This test demonstrates the memory efficiency of virtual threads compared to platform threads.
   */
  @Test
  void testMemoryUtilizationPatterns(TestInfo testInfo) throws Exception {
    // Concurrency levels to test
    int[] concurrencyLevels = {10, 100, 500, 1000};
    
    Map<String, Map<Integer, Long>> memoryResults = new HashMap<>();
    memoryResults.put("platform", new HashMap<>());
    memoryResults.put("virtual", new HashMap<>());
    
    // Measure memory usage with platform threads
    for (int concurrency : concurrencyLevels) {
      long memoryUsed = measureMemoryUtilization(
          Thread.ofPlatform().factory(),
          concurrency,
          "Platform Threads");
      memoryResults.get("platform").put(concurrency, memoryUsed);
    }
    
    // Measure memory usage with virtual threads
    for (int concurrency : concurrencyLevels) {
      long memoryUsed = measureMemoryUtilization(
          Thread.ofVirtual().factory(),
          concurrency,
          "Virtual Threads");
      memoryResults.get("virtual").put(concurrency, memoryUsed);
    }
    
    // Generate CSV report for memory usage
    try (PrintWriter writer = new PrintWriter(new FileWriter(new File(resultsDir, "memory-utilization.csv")))) {
      writer.println("Concurrency,Platform Threads Memory (KB),Virtual Threads Memory (KB)");
      
      for (int concurrency : concurrencyLevels) {
        writer.println(format("%d,%d,%d",
            concurrency,
            memoryResults.get("platform").get(concurrency) / 1024,
            memoryResults.get("virtual").get(concurrency) / 1024));
      }
    }
    
    // Verify that virtual threads use less memory at high concurrency
    int highConcurrency = concurrencyLevels[concurrencyLevels.length - 1];
    long platformMemory = memoryResults.get("platform").get(highConcurrency);
    long virtualMemory = memoryResults.get("virtual").get(highConcurrency);
    
    log.info("Platform threads memory usage at {} concurrency: {} KB", 
        highConcurrency, platformMemory / 1024);
    log.info("Virtual threads memory usage at {} concurrency: {} KB", 
        highConcurrency, virtualMemory / 1024);
    
    // At high concurrency, virtual threads should use significantly less memory
    assertThat("Virtual threads should use less memory at high concurrency",
        virtualMemory, lessThan(platformMemory / 2)); // At least 50% less memory
  }
  
  /**
   * Tests transaction retry performance characteristics for both thread types.
   * 
   * This test demonstrates how virtual threads handle retry scenarios more efficiently.
   */
  @ParameterizedTest
  @ValueSource(ints = {100, 500, 1000})
  void testTransactionRetryPerformance(int concurrency, TestInfo testInfo) throws Exception {
    // Run with platform threads
    PerformanceResult platformResult = measureTransactionRetryPerformance(
        Thread.ofPlatform().factory(),
        concurrency,
        "Platform Threads");
    
    // Run with virtual threads
    PerformanceResult virtualResult = measureTransactionRetryPerformance(
        Thread.ofVirtual().factory(),
        concurrency,
        "Virtual Threads");
    
    // Generate CSV report
    try (PrintWriter writer = new PrintWriter(new FileWriter(
        new File(resultsDir, "retry-performance-" + concurrency + ".csv")))) {
      writer.println("Metric,Platform Threads,Virtual Threads");
      writer.println(format("Operations Per Second,%.2f,%.2f",
          platformResult.getOperationsPerSecond(), virtualResult.getOperationsPerSecond()));
      writer.println(format("Average Latency (ms),%.2f,%.2f",
          platformResult.getAverageLatencyMs(), virtualResult.getAverageLatencyMs()));
      writer.println(format("P50 Latency (ms),%.2f,%.2f",
          platformResult.getP50LatencyMs(), virtualResult.getP50LatencyMs()));
      writer.println(format("P95 Latency (ms),%.2f,%.2f",
          platformResult.getP95LatencyMs(), virtualResult.getP95LatencyMs()));
      writer.println(format("P99 Latency (ms),%.2f,%.2f",
          platformResult.getP99LatencyMs(), virtualResult.getP99LatencyMs()));
      writer.println(format("Retry Count,%d,%d",
          platformResult.getRetryCount(), virtualResult.getRetryCount()));
    }
    
    log.info("Platform threads throughput with retries at {} concurrency: {} ops/sec", 
        concurrency, platformResult.getOperationsPerSecond());
    log.info("Virtual threads throughput with retries at {} concurrency: {} ops/sec", 
        concurrency, virtualResult.getOperationsPerSecond());
    
    // Virtual threads should handle retries more efficiently
    assertThat("Virtual threads should have higher throughput with retries",
        virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond()));
    
    assertThat("Virtual threads should have lower latency with retries",
        virtualResult.getP95LatencyMs(), lessThan(platformResult.getP95LatencyMs()));
  }
  
  /**
   * Tests high-concurrency behavior with virtual threads.
   * 
   * This test validates that virtual threads can handle extremely high concurrency levels
   * that would be impractical with platform threads.
   */
  @Test
  void testHighConcurrencyBehavior() throws Exception {
    // This test only runs with virtual threads as platform threads would likely
    // run out of memory or have severe performance degradation at these concurrency levels
    int[] highConcurrencyLevels = {1000, 5000, 10000};
    
    for (int concurrency : highConcurrencyLevels) {
      log.info("Testing virtual threads at extreme concurrency: {}", concurrency);
      
      // Use a shorter workload for these extreme tests
      PerformanceResult result = measureTransactionThroughput(
          Thread.ofVirtual().factory(),
          concurrency,
          "Virtual Threads (High Concurrency)",
          10); // Fewer operations per thread for this extreme test
      
      log.info("Virtual threads throughput at {} concurrency: {} ops/sec", 
          concurrency, result.getOperationsPerSecond());
      log.info("Virtual threads P95 latency at {} concurrency: {} ms", 
          concurrency, result.getP95LatencyMs());
      
      // Verify that operations completed successfully
      assertThat("All operations should complete successfully",
          result.getCompletedOperations(), is(concurrency * 10));
      
      // Verify that latency remains reasonable even at extreme concurrency
      assertThat("Latency should remain reasonable at high concurrency",
          result.getP95LatencyMs(), lessThanOrEqualTo(5000.0)); // 5 seconds max
    }
  }
  
  /**
   * Measures transaction throughput using the specified thread factory and concurrency level.
   */
  private PerformanceResult measureTransactionThroughput(
      ThreadFactory threadFactory,
      int concurrency,
      String threadType) throws Exception {
    return measureTransactionThroughput(threadFactory, concurrency, threadType, OPERATIONS_PER_THREAD);
  }
  
  /**
   * Measures transaction throughput using the specified thread factory, concurrency level, and operations per thread.
   */
  private PerformanceResult measureTransactionThroughput(
      ThreadFactory threadFactory,
      int concurrency,
      String threadType,
      int operationsPerThread) throws Exception {
    
    log.info("Measuring transaction throughput with {} at concurrency {}", threadType, concurrency);
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warm-up phase
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runTransactionWorkload(executor, concurrency, operationsPerThread, false);
      }
      
      // Measurement phase
      PerformanceResult result = new PerformanceResult();
      
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        PerformanceResult iterationResult = runTransactionWorkload(executor, concurrency, operationsPerThread, false);
        result.merge(iterationResult);
      }
      
      result.setThreadType(threadType);
      result.setConcurrencyLevel(concurrency);
      return result;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Measures transaction latency using the specified thread factory and concurrency level.
   */
  private PerformanceResult measureTransactionLatency(
      ThreadFactory threadFactory,
      int concurrency,
      String threadType) throws Exception {
    
    log.info("Measuring transaction latency with {} at concurrency {}", threadType, concurrency);
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warm-up phase
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runTransactionWorkload(executor, concurrency, OPERATIONS_PER_THREAD, false);
      }
      
      // Measurement phase
      PerformanceResult result = new PerformanceResult();
      
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        PerformanceResult iterationResult = runTransactionWorkload(executor, concurrency, OPERATIONS_PER_THREAD, false);
        result.merge(iterationResult);
      }
      
      result.setThreadType(threadType);
      result.setConcurrencyLevel(concurrency);
      return result;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Measures memory utilization using the specified thread factory and concurrency level.
   */
  private long measureMemoryUtilization(
      ThreadFactory threadFactory,
      int concurrency,
      String threadType) throws Exception {
    
    log.info("Measuring memory utilization with {} at concurrency {}", threadType, concurrency);
    
    // Force garbage collection before measurement
    System.gc();
    Thread.sleep(1000);
    
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Create and start threads but make them wait
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(concurrency);
      
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for signal to start
            simulateTransaction(false); // Run a single transaction
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Allow time for thread creation
      Thread.sleep(1000);
      
      // Force garbage collection again
      System.gc();
      Thread.sleep(1000);
      
      // Measure memory with all threads created
      long memoryDuring = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      
      // Let the transactions complete
      startLatch.countDown();
      completionLatch.await();
      
      return memoryDuring - memoryBefore;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Measures transaction retry performance using the specified thread factory and concurrency level.
   */
  private PerformanceResult measureTransactionRetryPerformance(
      ThreadFactory threadFactory,
      int concurrency,
      String threadType) throws Exception {
    
    log.info("Measuring transaction retry performance with {} at concurrency {}", threadType, concurrency);
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warm-up phase
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runTransactionWorkload(executor, concurrency, OPERATIONS_PER_THREAD, true);
      }
      
      // Measurement phase
      PerformanceResult result = new PerformanceResult();
      
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        PerformanceResult iterationResult = runTransactionWorkload(executor, concurrency, OPERATIONS_PER_THREAD, true);
        result.merge(iterationResult);
      }
      
      result.setThreadType(threadType);
      result.setConcurrencyLevel(concurrency);
      return result;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Runs a transaction workload with the specified parameters.
   */
  private PerformanceResult runTransactionWorkload(
      ExecutorService executor,
      int concurrency,
      int operationsPerThread,
      boolean withRetries) throws Exception {
    
    CountDownLatch completionLatch = new CountDownLatch(concurrency);
    AtomicInteger completedOperations = new AtomicInteger(0);
    AtomicInteger retryCount = new AtomicInteger(0);
    List<CompletableFuture<List<Long>>> futures = new ArrayList<>();
    
    long startTime = System.nanoTime();
    
    // Submit tasks
    for (int i = 0; i < concurrency; i++) {
      CompletableFuture<List<Long>> future = CompletableFuture.supplyAsync(() -> {
        try {
          List<Long> latencies = new ArrayList<>();
          
          for (int j = 0; j < operationsPerThread; j++) {
            long operationStart = System.nanoTime();
            int retries = simulateTransaction(withRetries);
            long operationEnd = System.nanoTime();
            
            latencies.add(NANOSECONDS.toMillis(operationEnd - operationStart));
            completedOperations.incrementAndGet();
            retryCount.addAndGet(retries);
          }
          
          return latencies;
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          completionLatch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for completion
    completionLatch.await();
    long endTime = System.nanoTime();
    
    // Calculate duration in seconds
    double durationSeconds = NANOSECONDS.toMillis(endTime - startTime) / 1000.0;
    
    // Collect all latencies
    List<Long> allLatencies = new ArrayList<>();
    for (CompletableFuture<List<Long>> future : futures) {
      allLatencies.addAll(future.get());
    }
    
    // Sort latencies for percentile calculations
    allLatencies.sort(Long::compare);
    
    // Calculate metrics
    double operationsPerSecond = completedOperations.get() / durationSeconds;
    double averageLatency = allLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
    
    // Calculate percentiles
    long p50Latency = percentile(allLatencies, 50);
    long p95Latency = percentile(allLatencies, 95);
    long p99Latency = percentile(allLatencies, 99);
    
    // Create result
    PerformanceResult result = new PerformanceResult();
    result.setOperationsPerSecond(operationsPerSecond);
    result.setAverageLatencyMs(averageLatency);
    result.setP50LatencyMs(p50Latency);
    result.setP95LatencyMs(p95Latency);
    result.setP99LatencyMs(p99Latency);
    result.setCompletedOperations(completedOperations.get());
    result.setRetryCount(retryCount.get());
    
    return result;
  }
  
  /**
   * Simulates a transaction with optional retries.
   * 
   * @param withRetries whether to simulate transaction retries
   * @return the number of retries performed
   */
  private int simulateTransaction(boolean withRetries) {
    int retries = 0;
    boolean success = false;
    MockTransaction transaction = new MockTransaction();
    
    while (!success) {
      try {
        // Begin transaction
        transaction.begin();
        
        // Simulate transaction work
        simulateTransactionWork();
        
        // Simulate random failure with retry
        if (withRetries && shouldRetry() && retries < MAX_RETRIES) {
          retries++;
          throw new TransientException("Simulated transient error");
        }
        
        // Commit transaction
        transaction.commit();
        success = true;
      } catch (TransientException e) {
        // Rollback transaction
        transaction.rollback();
        
        // Check if retry is allowed
        if (!transaction.allowRetry(e)) {
          throw new RuntimeException("Retry not allowed", e);
        }
      } catch (Exception e) {
        // Rollback transaction
        transaction.rollback();
        throw new RuntimeException("Transaction failed", e);
      } finally {
        // End transaction
        transaction.end();
      }
    }
    
    return retries;
  }
  
  /**
   * Simulates work done within a transaction.
   */
  private void simulateTransactionWork() {
    // Simulate CPU work
    int iterations = 1000 + random.nextInt(1000);
    double result = 0;
    for (int i = 0; i < iterations; i++) {
      result += Math.sin(i) * Math.cos(i);
    }
    
    // Simulate I/O work (e.g., database access)
    try {
      Thread.sleep(5 + random.nextInt(10));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Determines if a transaction should be retried based on the configured probability.
   */
  private boolean shouldRetry() {
    return random.nextInt(100) < RETRY_PROBABILITY_PERCENT;
  }
  
  /**
   * Calculates the specified percentile from a sorted list of values.
   */
  private long percentile(List<Long> sortedValues, int percentile) {
    if (sortedValues.isEmpty()) {
      return 0;
    }
    
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
  }
  
  /**
   * Generates a CSV report from the performance results.
   */
  private void generateCsvReport(
      Map<String, Map<Integer, PerformanceResult>> results,
      String reportName,
      TestInfo testInfo) throws IOException {
    
    File reportFile = new File(resultsDir, reportName + ".csv");
    
    try (PrintWriter writer = new PrintWriter(new FileWriter(reportFile))) {
      // Write header
      writer.println("Concurrency,Platform Threads Ops/Sec,Virtual Threads Ops/Sec," +
          "Platform Threads Avg Latency (ms),Virtual Threads Avg Latency (ms)," +
          "Platform Threads P95 Latency (ms),Virtual Threads P95 Latency (ms)");
      
      // Write data rows
      for (int concurrency : results.get("platform").keySet()) {
        PerformanceResult platformResult = results.get("platform").get(concurrency);
        PerformanceResult virtualResult = results.get("virtual").get(concurrency);
        
        writer.println(format("%d,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
            concurrency,
            platformResult.getOperationsPerSecond(),
            virtualResult.getOperationsPerSecond(),
            platformResult.getAverageLatencyMs(),
            virtualResult.getAverageLatencyMs(),
            platformResult.getP95LatencyMs(),
            virtualResult.getP95LatencyMs()));
      }
    }
    
    log.info("Generated report: {}", reportFile.getAbsolutePath());
  }
  
  /**
   * Mock implementation of Transaction for performance testing.
   */
  private static class MockTransaction extends TransactionSupport {
    private final AtomicLong transactionId = new AtomicLong(0);
    
    @Override
    protected void doBegin() {
      // Simulate transaction initialization
      transactionId.set(System.nanoTime());
    }
    
    @Override
    protected void doCommit() {
      // Simulate transaction commit
    }
    
    @Override
    protected void doRollback() {
      // Simulate transaction rollback
    }
  }
  
  /**
   * Exception representing a transient error that can be retried.
   */
  private static class TransientException extends RuntimeException {
    public TransientException(String message) {
      super(message);
    }
  }
  
  /**
   * Class to hold performance test results.
   */
  private static class PerformanceResult {
    private String threadType;
    private int concurrencyLevel;
    private double operationsPerSecond;
    private double averageLatencyMs;
    private long p50LatencyMs;
    private long p95LatencyMs;
    private long p99LatencyMs;
    private int completedOperations;
    private int retryCount;
    
    public void merge(PerformanceResult other) {
      // Weighted average for operations per second
      int totalOps = this.completedOperations + other.completedOperations;
      if (totalOps > 0) {
        this.operationsPerSecond = (
            (this.operationsPerSecond * this.completedOperations) +
            (other.operationsPerSecond * other.completedOperations)
        ) / totalOps;
      }
      
      // Weighted average for latencies
      if (totalOps > 0) {
        this.averageLatencyMs = (
            (this.averageLatencyMs * this.completedOperations) +
            (other.averageLatencyMs * other.completedOperations)
        ) / totalOps;
      }
      
      // Take max of percentiles for conservative estimate
      this.p50LatencyMs = Math.max(this.p50LatencyMs, other.p50LatencyMs);
      this.p95LatencyMs = Math.max(this.p95LatencyMs, other.p95LatencyMs);
      this.p99LatencyMs = Math.max(this.p99LatencyMs, other.p99LatencyMs);
      
      // Sum completed operations and retries
      this.completedOperations += other.completedOperations;
      this.retryCount += other.retryCount;
    }
    
    // Getters and setters
    public String getThreadType() {
      return threadType;
    }
    
    public void setThreadType(String threadType) {
      this.threadType = threadType;
    }
    
    public int getConcurrencyLevel() {
      return concurrencyLevel;
    }
    
    public void setConcurrencyLevel(int concurrencyLevel) {
      this.concurrencyLevel = concurrencyLevel;
    }
    
    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }
    
    public void setOperationsPerSecond(double operationsPerSecond) {
      this.operationsPerSecond = operationsPerSecond;
    }
    
    public double getAverageLatencyMs() {
      return averageLatencyMs;
    }
    
    public void setAverageLatencyMs(double averageLatencyMs) {
      this.averageLatencyMs = averageLatencyMs;
    }
    
    public long getP50LatencyMs() {
      return p50LatencyMs;
    }
    
    public void setP50LatencyMs(long p50LatencyMs) {
      this.p50LatencyMs = p50LatencyMs;
    }
    
    public long getP95LatencyMs() {
      return p95LatencyMs;
    }
    
    public void setP95LatencyMs(long p95LatencyMs) {
      this.p95LatencyMs = p95LatencyMs;
    }
    
    public long getP99LatencyMs() {
      return p99LatencyMs;
    }
    
    public void setP99LatencyMs(long p99LatencyMs) {
      this.p99LatencyMs = p99LatencyMs;
    }
    
    public int getCompletedOperations() {
      return completedOperations;
    }
    
    public void setCompletedOperations(int completedOperations) {
      this.completedOperations = completedOperations;
    }
    
    public int getRetryCount() {
      return retryCount;
    }
    
    public void setRetryCount(int retryCount) {
      this.retryCount = retryCount;
    }
  }
}