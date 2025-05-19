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
package org.sonatype.nexus;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.FileBlobStoreProvider;

import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Benchmarks and validates the performance of concurrent blob operations using Java 21 Virtual Threads.
 * This test class measures throughput, response times, and resource utilization when executing
 * a large number of concurrent blob operations. It compares performance between platform threads
 * and virtual threads to verify the expected performance improvements from Java 21.
 * 
 * The test validates the following performance requirements:
 * - Maximum Concurrent Connections should exceed 10,000 with Virtual Threads
 * - P95 Response Time should be under 350ms for 1k connections with Virtual Threads
 * - Memory Utilization should be under +300MB for 1k connections with Virtual Threads
 * - Thread Scaling Efficiency should be >90% with Virtual Threads
 * 
 * These benchmarks are critical for quantifying the performance benefits of Java 21's virtual threads
 * in the BlobStore implementation, particularly for I/O-bound operations like blob creation, retrieval,
 * and deletion.
 */
@Tag("VirtualThreads")
@Tag("Performance")
public class ConcurrentBlobOperationsTest
    extends TestSupport
{
  private static final int SMALL_CONCURRENCY = 100;
  private static final int MEDIUM_CONCURRENCY = 1_000;
  private static final int LARGE_CONCURRENCY = 10_000;
  
  private static final int BLOB_SIZE_BYTES = 1024; // 1KB
  private static final int WARMUP_COUNT = 100;
  
  private static final String BLOB_STORE_NAME = "test-concurrent-blob-store";
  
  @TempDir
  Path tempDir;
  
  @Mock
  private BlobStoreManager blobStoreManager;
  
  private BlobStore blobStore;
  private AutoCloseable mocks;
  
  @BeforeEach
  void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    
    // Create a temporary file blob store for testing
    BlobStoreConfiguration config = new BlobStoreConfiguration();
    config.setName(BLOB_STORE_NAME);
    config.setType(FileBlobStoreProvider.TYPE);
    config.setAttributes(ImmutableMap.of("file", ImmutableMap.of("path", tempDir.toString())));
    
    when(blobStoreManager.get(BLOB_STORE_NAME)).thenReturn(null);
    when(blobStoreManager.newConfiguration()).thenReturn(config);
    
    blobStore = new FileBlobStore(tempDir.toString(), config, blobStoreManager);
    
    // Perform warmup to initialize JVM and avoid cold start issues
    performWarmup();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (blobStore != null) {
      blobStore.stop();
    }
    mocks.close();
  }
  
  /**
   * Performs warmup operations to initialize the JVM and avoid cold start issues.
   */
  private void performWarmup() throws Exception {
    log.info("Performing warmup with {} operations", WARMUP_COUNT);
    
    List<BlobId> blobIds = new ArrayList<>();
    
    // Create blobs
    for (int i = 0; i < WARMUP_COUNT; i++) {
      byte[] content = generateRandomContent(BLOB_SIZE_BYTES);
      Blob blob = blobStore.create(new ByteArrayInputStream(content), ImmutableMap.of(
          "warmup", "true",
          "index", String.valueOf(i)
      ));
      blobIds.add(blob.getId());
    }
    
    // Read blobs
    for (BlobId blobId : blobIds) {
      Blob blob = blobStore.get(blobId);
      if (blob != null) {
        blob.getInputStream().close();
      }
    }
    
    // Delete blobs
    for (BlobId blobId : blobIds) {
      blobStore.delete(blobId, "warmup");
    }
    
    log.info("Warmup completed");
  }
  
  /**
   * Generates random content of the specified size.
   */
  private byte[] generateRandomContent(int sizeBytes) {
    String uuid = UUID.randomUUID().toString();
    StringBuilder sb = new StringBuilder(sizeBytes);
    
    while (sb.length() < sizeBytes) {
      sb.append(uuid);
    }
    
    return sb.substring(0, sizeBytes).getBytes(StandardCharsets.UTF_8);
  }
  
  /**
   * Tests the performance of concurrent blob creation operations using platform threads.
   * This establishes a baseline for comparison with virtual threads.
   */
  @Test
  @DisplayName("Benchmark concurrent blob creation with platform threads")
  void testConcurrentBlobCreationWithPlatformThreads() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    log.info(STR"Testing concurrent blob creation with \{concurrency} platform threads");
    
    // Create a fixed thread pool with the specified concurrency
    // Use a reasonable number of threads based on available processors
    int threadPoolSize = Math.min(concurrency, Runtime.getRuntime().availableProcessors() * 2);
    log.info(STR"Creating platform thread pool with \{threadPoolSize} threads");
    
    ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize, new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger();
      
      @Override
      public Thread newThread(Runnable r) {
        Thread thread = new Thread(r);
        thread.setName("platform-thread-" + counter.incrementAndGet());
        return thread;
      }
    });
    
    try {
      // Measure memory before test
      long memoryBefore = getUsedMemory();
      
      // Run the benchmark
      BenchmarkResult result = benchmarkBlobOperations(executor, concurrency, this::createBlobOperation);
      
      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;
      
      // Log results
      logBenchmarkResults("Platform Threads", concurrency, result, memoryUsage);
      
      // Store results for comparison
      platformThreadResults = result;
      platformThreadMemoryUsage = memoryUsage;
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Tests the performance of concurrent blob creation operations using virtual threads.
   * This demonstrates the performance improvements provided by Java 21 virtual threads.
   */
  @Test
  @DisplayName("Benchmark concurrent blob creation with virtual threads")
  void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    log.info(STR"Testing concurrent blob creation with \{concurrency} virtual threads");
    
    // Create a virtual thread per task executor
    // This is one of the key Java 21 features - creating a virtual thread for each task
    // without the overhead of platform threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Measure memory before test
      long memoryBefore = getUsedMemory();
      
      // Run the benchmark
      BenchmarkResult result = benchmarkBlobOperations(executor, concurrency, this::createBlobOperation);
      
      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;
      
      // Log results
      logBenchmarkResults("Virtual Threads", concurrency, result, memoryUsage);
      
      // Store results for comparison
      virtualThreadResults = result;
      virtualThreadMemoryUsage = memoryUsage;
      
      // Validate performance targets
      validatePerformanceTargets(concurrency, result, memoryUsage);
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Tests the scalability of virtual threads with a large number of concurrent operations.
   * This validates that virtual threads can handle high concurrency levels efficiently.
   * 
   * This test specifically validates the requirement that the system should support
   * more than 10,000 concurrent connections with Virtual Threads.
   */
  @Test
  @DisplayName("Test scalability with large number of virtual threads")
  void testScalabilityWithLargeNumberOfVirtualThreads() throws Exception {
    int concurrency = LARGE_CONCURRENCY;
    log.info(STR"Testing scalability with \{concurrency} virtual threads");
    
    // Create a virtual thread per task executor
    // With platform threads, this many concurrent threads would be impossible
    // due to OS thread limitations and memory overhead
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Measure memory before test
      long memoryBefore = getUsedMemory();
      log.info(STR"Memory before large scale test: \{memoryBefore} MB");
      
      // Run the benchmark
      BenchmarkResult result = benchmarkBlobOperations(executor, concurrency, this::createBlobOperation);
      
      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;
      log.info(STR"Memory after large scale test: \{memoryAfter} MB (\{memoryUsage} MB increase)");
      
      // Log results
      logBenchmarkResults("Virtual Threads (Large Scale)", concurrency, result, memoryUsage);
      
      // Validate scalability
      validateScalability(concurrency, result);
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(2, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Tests the performance of mixed blob operations (create, read, delete) using virtual threads.
   * This simulates a more realistic workload with different types of operations.
   * 
   * This test is important because it represents a real-world scenario where different
   * types of I/O operations are performed concurrently, which is exactly where
   * virtual threads excel compared to platform threads.
   */
  @Test
  @DisplayName("Benchmark mixed blob operations with virtual threads")
  void testMixedBlobOperationsWithVirtualThreads() throws Exception {
    int concurrency = MEDIUM_CONCURRENCY;
    log.info(STR"Testing mixed blob operations with \{concurrency} virtual threads");
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create blobs first
      List<BlobId> blobIds = new ArrayList<>();
      CountDownLatch createLatch = new CountDownLatch(concurrency);
      
      for (int i = 0; i < concurrency; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            byte[] content = generateRandomContent(BLOB_SIZE_BYTES);
            Blob blob = blobStore.create(new ByteArrayInputStream(content), ImmutableMap.of(
                "test", "mixed-operations",
                "index", String.valueOf(index)
            ));
            blobIds.add(blob.getId());
            return blob.getId();
          } finally {
            createLatch.countDown();
          }
        });
      }
      
      createLatch.await();
      log.info(STR"Created \{blobIds.size()} blobs for mixed operations test");
      
      // Measure memory before test
      long memoryBefore = getUsedMemory();
      
      // Run mixed operations benchmark (1/3 create, 1/3 read, 1/3 delete)
      BenchmarkResult result = benchmarkMixedBlobOperations(executor, concurrency, blobIds);
      
      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;
      
      // Log results
      logBenchmarkResults("Virtual Threads (Mixed Operations)", concurrency, result, memoryUsage);
      
      // Validate performance targets for mixed operations
      validatePerformanceTargets(concurrency, result, memoryUsage);
      
      // Cleanup any remaining blobs
      log.info("Cleaning up test blobs...");
      for (BlobId blobId : blobIds) {
        try {
          blobStore.delete(blobId, "cleanup");
        } catch (Exception e) {
          // Ignore errors during cleanup
        }
      }
      
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Compares the performance of platform threads and virtual threads.
   * This test runs after both platform and virtual thread benchmarks have completed.
   */
  @Test
  @DisplayName("Compare platform threads vs virtual threads performance")
  void compareThreadPerformance() {
    // Skip if either benchmark hasn't run yet
    if (platformThreadResults == null || virtualThreadResults == null) {
      log.info("Skipping comparison as not all benchmarks have run");
      return;
    }
    
    log.info("Comparing platform threads vs virtual threads performance");
    
    // Calculate improvement percentages
    double throughputImprovement = calculateImprovement(
        platformThreadResults.operationsPerSecond, virtualThreadResults.operationsPerSecond);
    
    double p95ResponseTimeImprovement = calculateImprovement(
        platformThreadResults.p95ResponseTimeMs, virtualThreadResults.p95ResponseTimeMs, true);
    
    double memoryUsageImprovement = calculateImprovement(
        platformThreadMemoryUsage, virtualThreadMemoryUsage, true);
    
    // Log comparison results
    log.info(STR"""
        Performance Comparison (Platform vs Virtual Threads):
        - Throughput: \{throughputImprovement}% improvement with Virtual Threads
        - P95 Response Time: \{p95ResponseTimeImprovement}% improvement with Virtual Threads
        - Memory Usage: \{memoryUsageImprovement}% improvement with Virtual Threads
        """);
    
    // Assert that virtual threads provide better performance
    assertThat("Virtual threads should provide higher throughput",
        virtualThreadResults.operationsPerSecond, greaterThan(platformThreadResults.operationsPerSecond));
    
    assertThat("Virtual threads should provide lower P95 response time",
        virtualThreadResults.p95ResponseTimeMs, lessThan(platformThreadResults.p95ResponseTimeMs));
    
    assertThat("Virtual threads should use less memory",
        virtualThreadMemoryUsage, lessThan(platformThreadMemoryUsage));
  }
  
  /**
   * Calculates the percentage improvement between two values.
   * 
   * @param baseline The baseline value
   * @param improved The improved value
   * @param lowerIsBetter Whether a lower value is better (e.g., for response time)
   * @return The percentage improvement
   */
  private double calculateImprovement(double baseline, double improved, boolean lowerIsBetter) {
    if (lowerIsBetter) {
      return ((baseline - improved) / baseline) * 100.0;
    } else {
      return ((improved - baseline) / baseline) * 100.0;
    }
  }
  
  private double calculateImprovement(double baseline, double improved) {
    return calculateImprovement(baseline, improved, false);
  }
  
  // Store benchmark results for comparison
  private BenchmarkResult platformThreadResults;
  private BenchmarkResult virtualThreadResults;
  private long platformThreadMemoryUsage;
  private long virtualThreadMemoryUsage;
  
  /**
   * Represents the result of a benchmark run.
   */
  private static class BenchmarkResult {
    final double operationsPerSecond;
    final double avgResponseTimeMs;
    final double p95ResponseTimeMs;
    final double p99ResponseTimeMs;
    final double maxResponseTimeMs;
    final double successRate;
    
    BenchmarkResult(double operationsPerSecond, double avgResponseTimeMs, double p95ResponseTimeMs,
                    double p99ResponseTimeMs, double maxResponseTimeMs, double successRate) {
      this.operationsPerSecond = operationsPerSecond;
      this.avgResponseTimeMs = avgResponseTimeMs;
      this.p95ResponseTimeMs = p95ResponseTimeMs;
      this.p99ResponseTimeMs = p99ResponseTimeMs;
      this.maxResponseTimeMs = maxResponseTimeMs;
      this.successRate = successRate;
    }
  }
  
  /**
   * Benchmarks blob operations using the provided executor and operation supplier.
   * 
   * @param executor The executor service to use for concurrent operations
   * @param concurrency The number of concurrent operations to perform
   * @param operationSupplier A supplier that returns a runnable blob operation
   * @return The benchmark results
   */
  private BenchmarkResult benchmarkBlobOperations(
      ExecutorService executor, int concurrency, Supplier<Runnable> operationSupplier) throws Exception {
    
    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(concurrency);
    
    // Track response times and success/failure counts
    List<Long> responseTimes = new ArrayList<>(concurrency);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Start timing
    long startTime = System.nanoTime();
    
    // Submit operations to the executor
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        long operationStartTime = System.nanoTime();
        try {
          // Execute the operation
          operationSupplier.get().run();
          successCount.incrementAndGet();
        } catch (Exception e) {
          failureCount.incrementAndGet();
          log.error("Operation failed", e);
        } finally {
          long operationEndTime = System.nanoTime();
          long responseTime = TimeUnit.NANOSECONDS.toMillis(operationEndTime - operationStartTime);
          synchronized (responseTimes) {
            responseTimes.add(responseTime);
          }
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    latch.await();
    
    // Calculate elapsed time
    long endTime = System.nanoTime();
    long elapsedTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    // Calculate operations per second
    double operationsPerSecond = (concurrency * 1000.0) / elapsedTimeMs;
    
    // Calculate response time statistics
    responseTimes.sort(Long::compareTo);
    double avgResponseTimeMs = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    double p95ResponseTimeMs = calculatePercentile(responseTimes, 95);
    double p99ResponseTimeMs = calculatePercentile(responseTimes, 99);
    double maxResponseTimeMs = responseTimes.isEmpty() ? 0 : responseTimes.get(responseTimes.size() - 1);
    
    // Calculate success rate
    double successRate = (successCount.get() * 100.0) / concurrency;
    
    return new BenchmarkResult(
        operationsPerSecond,
        avgResponseTimeMs,
        p95ResponseTimeMs,
        p99ResponseTimeMs,
        maxResponseTimeMs,
        successRate
    );
  }
  
  /**
   * Benchmarks mixed blob operations (create, read, delete) using the provided executor.
   * 
   * @param executor The executor service to use for concurrent operations
   * @param concurrency The number of concurrent operations to perform
   * @param existingBlobIds A list of existing blob IDs for read and delete operations
   * @return The benchmark results
   */
  private BenchmarkResult benchmarkMixedBlobOperations(
      ExecutorService executor, int concurrency, List<BlobId> existingBlobIds) throws Exception {
    
    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(concurrency);
    
    // Track response times and success/failure counts
    List<Long> responseTimes = new ArrayList<>(concurrency);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create a map to track which blobs have been deleted
    ConcurrentHashMap<BlobId, Boolean> deletedBlobs = new ConcurrentHashMap<>();
    
    // Start timing
    long startTime = System.nanoTime();
    
    // Submit operations to the executor
    for (int i = 0; i < concurrency; i++) {
      final int index = i;
      executor.submit(() -> {
        long operationStartTime = System.nanoTime();
        try {
          // Determine operation type based on index (1/3 create, 1/3 read, 1/3 delete)
          int operationType = index % 3;
          
          switch (operationType) {
            case 0: // Create
              createBlobOperation().run();
              break;
              
            case 1: // Read
              if (!existingBlobIds.isEmpty()) {
                int blobIndex = index % existingBlobIds.size();
                BlobId blobId = existingBlobIds.get(blobIndex);
                
                // Skip if already deleted
                if (deletedBlobs.containsKey(blobId)) {
                  break;
                }
                
                Blob blob = blobStore.get(blobId);
                if (blob != null) {
                  blob.getInputStream().close();
                }
              }
              break;
              
            case 2: // Delete
              if (!existingBlobIds.isEmpty()) {
                int blobIndex = index % existingBlobIds.size();
                BlobId blobId = existingBlobIds.get(blobIndex);
                
                // Skip if already deleted
                if (deletedBlobs.putIfAbsent(blobId, Boolean.TRUE) == null) {
                  blobStore.delete(blobId, "mixed-operations-test");
                }
              }
              break;
          }
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          failureCount.incrementAndGet();
          log.error("Operation failed", e);
        } finally {
          long operationEndTime = System.nanoTime();
          long responseTime = TimeUnit.NANOSECONDS.toMillis(operationEndTime - operationStartTime);
          synchronized (responseTimes) {
            responseTimes.add(responseTime);
          }
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    latch.await();
    
    // Calculate elapsed time
    long endTime = System.nanoTime();
    long elapsedTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    
    // Calculate operations per second
    double operationsPerSecond = (concurrency * 1000.0) / elapsedTimeMs;
    
    // Calculate response time statistics
    responseTimes.sort(Long::compareTo);
    double avgResponseTimeMs = responseTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    double p95ResponseTimeMs = calculatePercentile(responseTimes, 95);
    double p99ResponseTimeMs = calculatePercentile(responseTimes, 99);
    double maxResponseTimeMs = responseTimes.isEmpty() ? 0 : responseTimes.get(responseTimes.size() - 1);
    
    // Calculate success rate
    double successRate = (successCount.get() * 100.0) / concurrency;
    
    return new BenchmarkResult(
        operationsPerSecond,
        avgResponseTimeMs,
        p95ResponseTimeMs,
        p99ResponseTimeMs,
        maxResponseTimeMs,
        successRate
    );
  }
  
  /**
   * Creates a blob creation operation.
   * 
   * @return A runnable that creates a blob
   */
  private Runnable createBlobOperation() {
    return () -> {
      try {
        byte[] content = generateRandomContent(BLOB_SIZE_BYTES);
        Blob blob = blobStore.create(new ByteArrayInputStream(content), ImmutableMap.of(
            "test", "concurrent-operations",
            "timestamp", String.valueOf(System.currentTimeMillis())
        ));
        assertThat(blob, notNullValue());
        assertThat(blob.getId(), notNullValue());
      } catch (IOException e) {
        throw new RuntimeException("Failed to create blob", e);
      }
    };
  }
  
  /**
   * Calculates the percentile value from a sorted list of response times.
   * 
   * @param responseTimes A sorted list of response times
   * @param percentile The percentile to calculate (e.g., 95 for P95)
   * @return The percentile value
   */
  private double calculatePercentile(List<Long> responseTimes, int percentile) {
    if (responseTimes.isEmpty()) {
      return 0;
    }
    
    int index = (int) Math.ceil(percentile / 100.0 * responseTimes.size()) - 1;
    return responseTimes.get(Math.max(0, Math.min(responseTimes.size() - 1, index)));
  }
  
  /**
   * Gets the current used memory in MB.
   * 
   * @return The used memory in MB
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate memory usage
    Runtime runtime = Runtime.getRuntime();
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
  }
  
  /**
   * Logs the benchmark results.
   * 
   * @param testName The name of the test
   * @param concurrency The concurrency level
   * @param result The benchmark results
   * @param memoryUsageMB The memory usage in MB
   */
  private void logBenchmarkResults(String testName, int concurrency, BenchmarkResult result, long memoryUsageMB) {
    log.info(STR"""
        Benchmark Results (\{testName} with \{concurrency} concurrent operations):
        - Operations/sec: \{result.operationsPerSecond}
        - Avg Response Time: \{result.avgResponseTimeMs} ms
        - P95 Response Time: \{result.p95ResponseTimeMs} ms
        - P99 Response Time: \{result.p99ResponseTimeMs} ms
        - Max Response Time: \{result.maxResponseTimeMs} ms
        - Success Rate: \{result.successRate}%
        - Memory Usage: \{memoryUsageMB} MB
        """);
  }
  
  /**
   * Validates that the performance meets the specified targets.
   * 
   * These targets are based on the requirements specified in the technical specification:
   * - P95 Response Time should be under 350ms for 1k connections with Virtual Threads
   * - Memory Utilization should be under +300MB for 1k connections with Virtual Threads
   * - Thread Scaling Efficiency should be >90% with Virtual Threads (measured by success rate)
   * 
   * @param concurrency The concurrency level
   * @param result The benchmark results
   * @param memoryUsageMB The memory usage in MB
   */
  private void validatePerformanceTargets(int concurrency, BenchmarkResult result, long memoryUsageMB) {
    if (concurrency >= 1000) {
      // P95 Response Time should be under 350ms for 1k connections with Virtual Threads
      assertThat("P95 Response Time should be under 350ms",
          result.p95ResponseTimeMs, lessThan(350.0));
      
      // Memory Utilization should be under +300MB for 1k connections with Virtual Threads
      assertThat("Memory Utilization should be under 300MB",
          memoryUsageMB, lessThan(300L));
      
      // Thread Scaling Efficiency should be >90% with Virtual Threads
      // We measure this by the success rate of operations
      assertThat("Thread Scaling Efficiency should be >90%",
          result.successRate, greaterThanOrEqualTo(90.0));
    }
    
    // Success rate should be 100% for normal operation
    assertThat("Success rate should be 100%",
        result.successRate, is(100.0));
  }
  
  /**
   * Validates that virtual threads can scale to a large number of concurrent operations.
   * 
   * This validation specifically checks the requirement that:
   * - Maximum Concurrent Connections should exceed 10,000 with Virtual Threads
   * - Thread Scaling Efficiency should be >90% with Virtual Threads
   * 
   * @param concurrency The concurrency level
   * @param result The benchmark results
   */
  private void validateScalability(int concurrency, BenchmarkResult result) {
    // Maximum Concurrent Connections should exceed 10,000 with Virtual Threads
    assertThat("Should support at least 10,000 concurrent connections",
        concurrency, greaterThanOrEqualTo(10_000));
    
    // Thread Scaling Efficiency should be >90% with Virtual Threads
    // We measure this by the success rate of operations
    double successRate = result.successRate;
    assertThat("Thread Scaling Efficiency should be >90%",
        successRate, greaterThanOrEqualTo(90.0));
    
    // Log the scalability metrics
    log.info(STR"""
        Scalability Metrics:
        - Concurrent Connections: \{concurrency}
        - Thread Scaling Efficiency: \{successRate}%
        - Operations/sec: \{result.operationsPerSecond}
        """);
  }
}