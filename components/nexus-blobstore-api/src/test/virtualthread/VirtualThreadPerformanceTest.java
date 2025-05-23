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
package org.sonatype.nexus.blobstore.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Performance comparison test between Platform Threads and Virtual Threads for BlobStore operations.
 * This test measures throughput, response times, and resource utilization under varying levels of concurrency,
 * demonstrating the scalability advantages of Virtual Threads for I/O-bound blob operations.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadPerformanceTest
{
  private static final int SMALL_CONCURRENCY = 10;
  private static final int MEDIUM_CONCURRENCY = 100;
  private static final int LARGE_CONCURRENCY = 1000;
  
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASUREMENT_ITERATIONS = 5;
  
  private static final String TEST_CONTENT = "Test blob content for performance testing";
  private static final byte[] TEST_BYTES = TEST_CONTENT.getBytes(StandardCharsets.UTF_8);
  
  @Mock
  private BlobStoreManager blobStoreManager;
  
  @Mock
  private BlobStore blobStore;
  
  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;
  
  @Mock
  private Blob blob;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  public void setUp() {
    // Configure mock BlobStore
    when(blobStoreManager.get(anyString())).thenReturn(blobStore);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreConfiguration.getName()).thenReturn("test-blob-store");
    
    // Mock blob creation
    when(blobStore.create(any(InputStream.class), anyMap(), anyString()))
        .thenAnswer(invocation -> {
          // Simulate some I/O work
          Thread.sleep(5);
          return blob;
        });
    
    // Mock blob retrieval
    when(blobStore.get(any(BlobId.class)))
        .thenAnswer(invocation -> {
          // Simulate some I/O work
          Thread.sleep(5);
          return blob;
        });
    
    // Create executors
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void tearDown() {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    try {
      if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        platformThreadExecutor.shutdownNow();
      }
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  @Test
  public void testSmallConcurrencyPerformance() throws Exception {
    PerformanceResult platformResult = runBenchmark(ThreadingModel.PLATFORM, SMALL_CONCURRENCY);
    PerformanceResult virtualResult = runBenchmark(ThreadingModel.VIRTUAL, SMALL_CONCURRENCY);
    
    System.out.println("--- Small Concurrency Results (" + SMALL_CONCURRENCY + " threads) ---");
    printResults(platformResult, virtualResult);
    
    // At small concurrency, both should perform similarly
    assertThat("Both thread models should complete successfully", 
        platformResult.getErrorCount() + virtualResult.getErrorCount(), is(0));
  }
  
  @Test
  public void testMediumConcurrencyPerformance() throws Exception {
    PerformanceResult platformResult = runBenchmark(ThreadingModel.PLATFORM, MEDIUM_CONCURRENCY);
    PerformanceResult virtualResult = runBenchmark(ThreadingModel.VIRTUAL, MEDIUM_CONCURRENCY);
    
    System.out.println("--- Medium Concurrency Results (" + MEDIUM_CONCURRENCY + " threads) ---");
    printResults(platformResult, virtualResult);
    
    // At medium concurrency, virtual threads should start showing advantages
    assertThat("Both thread models should complete successfully", 
        platformResult.getErrorCount() + virtualResult.getErrorCount(), is(0));
    
    // Virtual threads should have better or similar throughput
    assertThat("Virtual threads should have competitive throughput",
        virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond() * 0.9));
  }
  
  @Test
  public void testLargeConcurrencyPerformance() throws Exception {
    PerformanceResult platformResult = runBenchmark(ThreadingModel.PLATFORM, LARGE_CONCURRENCY);
    PerformanceResult virtualResult = runBenchmark(ThreadingModel.VIRTUAL, LARGE_CONCURRENCY);
    
    System.out.println("--- Large Concurrency Results (" + LARGE_CONCURRENCY + " threads) ---");
    printResults(platformResult, virtualResult);
    
    // At high concurrency, virtual threads should show significant advantages
    assertThat("Both thread models should complete successfully", 
        platformResult.getErrorCount() + virtualResult.getErrorCount(), is(0));
    
    // Virtual threads should have better throughput
    assertThat("Virtual threads should have better throughput at high concurrency",
        virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond() * 1.1));
    
    // Virtual threads should have lower average latency
    assertThat("Virtual threads should have lower average latency at high concurrency",
        virtualResult.getAverageLatencyMs(), lessThan(platformResult.getAverageLatencyMs() * 0.9));
  }
  
  private PerformanceResult runBenchmark(ThreadingModel threadingModel, int concurrency) throws Exception {
    // Warm-up phase
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runWorkload(threadingModel, concurrency / 10, 5);
    }
    
    // Measurement phase
    PerformanceResult result = new PerformanceResult();
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
      BenchmarkResult benchmarkResult = runWorkload(threadingModel, concurrency, OPERATIONS_PER_THREAD);
      result.addResult(benchmarkResult);
    }
    
    return result;
  }
  
  private BenchmarkResult runWorkload(ThreadingModel threadingModel, int concurrency, int operationsPerThread) 
      throws Exception 
  {
    ExecutorService executor = threadingModel == ThreadingModel.PLATFORM ? 
        platformThreadExecutor : virtualThreadExecutor;
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrency);
    
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalLatency = new AtomicLong(0);
    AtomicInteger completedOperations = new AtomicInteger(0);
    
    // Submit tasks
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          for (int j = 0; j < operationsPerThread; j++) {
            long startTime = System.nanoTime();
            
            try {
              // Perform blob operations
              String blobPath = "test/path/" + UUID.randomUUID();
              Map<String, String> headers = new HashMap<>();
              headers.put("Content-Type", "text/plain");
              
              // Create blob
              Blob createdBlob = blobStore.create(
                  new ByteArrayInputStream(TEST_BYTES),
                  headers,
                  blobPath
              );
              
              // Get blob
              Blob retrievedBlob = blobStore.get(createdBlob.getId());
              assertThat(retrievedBlob, is(notNullValue()));
              
              completedOperations.incrementAndGet();
            }
            catch (Exception e) {
              errorCount.incrementAndGet();
            }
            
            long endTime = System.nanoTime();
            totalLatency.addAndGet((endTime - startTime) / 1_000_000); // Convert to ms
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start the benchmark
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for completion
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    if (!completed) {
      errorCount.addAndGet(concurrency); // Count incomplete tasks as errors
    }
    
    int totalOperations = completedOperations.get();
    double operationsPerSecond = totalOperations / (duration / 1000.0);
    double averageLatency = totalOperations > 0 ? totalLatency.get() / (double) totalOperations : 0;
    
    return new BenchmarkResult(
        threadingModel,
        concurrency,
        duration,
        totalOperations,
        errorCount.get(),
        operationsPerSecond,
        averageLatency
    );
  }
  
  private void printResults(PerformanceResult platformResult, PerformanceResult virtualResult) {
    System.out.println("Platform Threads:");
    System.out.println("  Operations/sec: " + String.format("%.2f", platformResult.getOperationsPerSecond()));
    System.out.println("  Avg Latency (ms): " + String.format("%.2f", platformResult.getAverageLatencyMs()));
    System.out.println("  Error count: " + platformResult.getErrorCount());
    
    System.out.println("Virtual Threads:");
    System.out.println("  Operations/sec: " + String.format("%.2f", virtualResult.getOperationsPerSecond()));
    System.out.println("  Avg Latency (ms): " + String.format("%.2f", virtualResult.getAverageLatencyMs()));
    System.out.println("  Error count: " + virtualResult.getErrorCount());
    
    double throughputImprovement = (virtualResult.getOperationsPerSecond() / platformResult.getOperationsPerSecond() - 1) * 100;
    double latencyImprovement = (1 - virtualResult.getAverageLatencyMs() / platformResult.getAverageLatencyMs()) * 100;
    
    System.out.println("Comparison (Virtual vs Platform):");
    System.out.println("  Throughput improvement: " + String.format("%.2f%%", throughputImprovement));
    System.out.println("  Latency improvement: " + String.format("%.2f%%", latencyImprovement));
    System.out.println();
  }
  
  /**
   * Enum representing the threading model to use for the benchmark.
   */
  private enum ThreadingModel {
    PLATFORM,
    VIRTUAL
  }
  
  /**
   * Class to hold the results of a single benchmark run.
   */
  private static class BenchmarkResult {
    private final ThreadingModel threadingModel;
    private final int concurrency;
    private final long durationMs;
    private final int completedOperations;
    private final int errorCount;
    private final double operationsPerSecond;
    private final double averageLatencyMs;
    
    public BenchmarkResult(
        ThreadingModel threadingModel,
        int concurrency,
        long durationMs,
        int completedOperations,
        int errorCount,
        double operationsPerSecond,
        double averageLatencyMs)
    {
      this.threadingModel = threadingModel;
      this.concurrency = concurrency;
      this.durationMs = durationMs;
      this.completedOperations = completedOperations;
      this.errorCount = errorCount;
      this.operationsPerSecond = operationsPerSecond;
      this.averageLatencyMs = averageLatencyMs;
    }
    
    public ThreadingModel getThreadingModel() {
      return threadingModel;
    }
    
    public int getConcurrency() {
      return concurrency;
    }
    
    public long getDurationMs() {
      return durationMs;
    }
    
    public int getCompletedOperations() {
      return completedOperations;
    }
    
    public int getErrorCount() {
      return errorCount;
    }
    
    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }
    
    public double getAverageLatencyMs() {
      return averageLatencyMs;
    }
  }
  
  /**
   * Class to aggregate results from multiple benchmark runs.
   */
  private static class PerformanceResult {
    private int totalRuns = 0;
    private long totalDurationMs = 0;
    private int totalOperations = 0;
    private int totalErrors = 0;
    private double totalOperationsPerSecond = 0;
    private double totalLatencyMs = 0;
    
    public void addResult(BenchmarkResult result) {
      totalRuns++;
      totalDurationMs += result.getDurationMs();
      totalOperations += result.getCompletedOperations();
      totalErrors += result.getErrorCount();
      totalOperationsPerSecond += result.getOperationsPerSecond();
      totalLatencyMs += result.getAverageLatencyMs() * result.getCompletedOperations();
    }
    
    public double getOperationsPerSecond() {
      return totalRuns > 0 ? totalOperationsPerSecond / totalRuns : 0;
    }
    
    public double getAverageLatencyMs() {
      return totalOperations > 0 ? totalLatencyMs / totalOperations : 0;
    }
    
    public int getErrorCount() {
      return totalErrors;
    }
  }
}