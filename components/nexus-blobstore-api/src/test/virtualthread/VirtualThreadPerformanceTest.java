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

package org.sonatype.nexus.blobstore.api;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;
import org.sonatype.nexus.blobstore.api.VirtualThreadFriendly;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Performance comparison test between Platform Threads and Virtual Threads for BlobStore operations.
 * <p>
 * This test measures throughput, response times, and resource utilization under varying levels of concurrency,
 * demonstrating the scalability advantages of Virtual Threads for I/O-bound blob operations while ensuring
 * functional correctness is maintained.
 *
 * @since 3.60
 */
public class VirtualThreadPerformanceTest
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadPerformanceTest.class);

  private static final String BLOB_CONTENT = "This is test blob content for performance testing";
  private static final int WARMUP_ITERATIONS = 10;
  private static final int TEST_ITERATIONS = 100;
  private static final int[] CONCURRENCY_LEVELS = {10, 100, 1000};

  private TestBlobStore blobStore;

  @Before
  public void setUp() {
    blobStore = new TestBlobStore();
  }

  @After
  public void tearDown() {
    blobStore = null;
  }

  /**
   * Tests the performance difference between Platform Threads and Virtual Threads
   * when performing concurrent blob operations at different concurrency levels.
   * <p>
   * This test validates that Virtual Threads provide better scalability at high concurrency
   * levels compared to Platform Threads, particularly for I/O-bound operations.
   */
  @Test
  public void testThreadModelPerformanceComparison() throws Exception {
    // Configure thread factories
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();

    // Run tests at different concurrency levels
    for (int concurrency : CONCURRENCY_LEVELS) {
      log.info("Testing with concurrency level: {}", concurrency);

      // Test with platform threads
      PerformanceResult platformResult = runBenchmark("Platform Threads", platformThreadFactory, concurrency);
      log.info("Platform Thread Results: {}", platformResult);

      // Test with virtual threads
      PerformanceResult virtualResult = runBenchmark("Virtual Threads", virtualThreadFactory, concurrency);
      log.info("Virtual Thread Results: {}", virtualResult);

      // Verify both thread models completed successfully
      assertThat("Platform thread operations completed successfully", 
          platformResult.getErrorCount(), is(0));
      assertThat("Virtual thread operations completed successfully", 
          virtualResult.getErrorCount(), is(0));

      // At higher concurrency levels, virtual threads should show better performance
      if (concurrency >= 100) {
        assertThat("Virtual threads should have higher throughput at high concurrency",
            virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond()));
        
        assertThat("Virtual threads should have lower average latency at high concurrency",
            virtualResult.getAverageLatencyMs(), lessThan(platformResult.getAverageLatencyMs()));
      }

      // Print comparison summary
      double throughputImprovement = (virtualResult.getOperationsPerSecond() / platformResult.getOperationsPerSecond() - 1) * 100;
      double latencyImprovement = (1 - virtualResult.getAverageLatencyMs() / platformResult.getAverageLatencyMs()) * 100;
      
      log.info("Concurrency {}: Virtual Threads throughput improvement: {}%, latency improvement: {}%",
          concurrency, String.format("%.2f", throughputImprovement), String.format("%.2f", latencyImprovement));
    }
  }

  /**
   * Runs a performance benchmark using the specified thread factory and concurrency level.
   *
   * @param name the name of the benchmark for reporting
   * @param threadFactory the thread factory to use (platform or virtual)
   * @param concurrency the number of concurrent operations to perform
   * @return performance metrics from the benchmark
   */
  @VirtualThreadFriendly
  private PerformanceResult runBenchmark(String name, ThreadFactory threadFactory, int concurrency) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    try {
      // Warm up
      runOperations(executor, WARMUP_ITERATIONS, concurrency);

      // Actual test
      long startTime = System.nanoTime();
      OperationStats stats = runOperations(executor, TEST_ITERATIONS, concurrency);
      long endTime = System.nanoTime();
      long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

      // Calculate metrics
      double operationsPerSecond = (double) (TEST_ITERATIONS * concurrency) / (durationMs / 1000.0);
      double averageLatencyMs = stats.getTotalLatencyMs() / (double) (TEST_ITERATIONS * concurrency);

      return new PerformanceResult(
          name,
          concurrency,
          operationsPerSecond,
          averageLatencyMs,
          stats.getErrorCount()
      );
    } finally {
      executor.shutdown();
      executor.awaitTermination(30, TimeUnit.SECONDS);
    }
  }

  /**
   * Runs a set of blob operations concurrently using the provided executor.
   *
   * @param executor the executor service to use for concurrent operations
   * @param iterations the number of iterations to perform
   * @param concurrency the number of concurrent operations per iteration
   * @return statistics about the operations performed
   */
  @VirtualThreadFriendly
  private OperationStats runOperations(
      ExecutorService executor,
      int iterations,
      int concurrency) throws Exception {
    AtomicLong totalLatencyMs = new AtomicLong(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    for (int i = 0; i < iterations; i++) {
      CountDownLatch latch = new CountDownLatch(concurrency);

      // Submit concurrent operations
      for (int j = 0; j < concurrency; j++) {
        executor.submit(() -> {
          try {
            // Measure latency of a complete blob operation cycle
            long startTime = System.nanoTime();
            
            // Create a blob
            Blob blob = createTestBlob();
            assertThat("Blob should be created successfully", blob, notNullValue());
            BlobId blobId = blob.getId();
            
            // Get the blob
            Blob retrievedBlob = blobStore.get(blobId);
            assertThat("Blob should be retrieved successfully", retrievedBlob, notNullValue());
            
            // Delete the blob
            boolean deleted = blobStore.delete(blobId, "Test cleanup");
            assertThat("Blob should be deleted successfully", deleted, is(true));
            
            long endTime = System.nanoTime();
            long latencyMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            totalLatencyMs.addAndGet(latencyMs);
          } catch (Exception e) {
            log.error("Error during blob operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations in this iteration to complete
      latch.await();
    }

    return new OperationStats(totalLatencyMs.get(), errorCount.get());
  }

  /**
   * Creates a test blob with random content for performance testing.
   *
   * @return the created blob
   */
  @VirtualThreadFriendly
  private Blob createTestBlob() {
    Map<String, String> headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, "test-blob-" + UUID.randomUUID());
    headers.put(BlobStore.CREATED_BY_HEADER, "virtual-thread-test");
    headers.put(BlobStore.CONTENT_TYPE_HEADER, "text/plain");

    InputStream inputStream = new ByteArrayInputStream(BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));
    return blobStore.create(inputStream, headers);
  }

  /**
   * Simple in-memory BlobStore implementation for performance testing.
   * <p>
   * This implementation simulates I/O operations with controlled delays to represent
   * real-world scenarios while maintaining predictable behavior for testing.
   */
  private static class TestBlobStore implements BlobStore {
    private final Map<BlobId, Blob> blobs = new HashMap<>();
    private final BlobStoreConfiguration configuration;

    public TestBlobStore() {
      this.configuration = mock(BlobStoreConfiguration.class);
      when(configuration.getName()).thenReturn("test-blobstore");
      when(configuration.isWritable()).thenReturn(true);
    }

    @Override
    @VirtualThreadFriendly
    public Blob create(InputStream blobData, Map<String, String> headers) {
      // Simulate I/O delay
      simulateIoDelay();
      
      BlobId blobId = new BlobId(UUID.randomUUID().toString());
      TestBlob blob = new TestBlob(blobId, headers);
      blobs.put(blobId, blob);
      return blob;
    }

    @Override
    @VirtualThreadFriendly
    public Blob get(BlobId blobId) {
      // Simulate I/O delay
      simulateIoDelay();
      
      return blobs.get(blobId);
    }

    @Override
    @VirtualThreadFriendly
    public boolean delete(BlobId blobId, String reason) {
      // Simulate I/O delay
      simulateIoDelay();
      
      return blobs.remove(blobId) != null;
    }

    @Override
    public BlobStoreConfiguration getBlobStoreConfiguration() {
      return configuration;
    }

    @Override
    public void start() {
      // No-op for test implementation
    }

    @Override
    public void stop() {
      // No-op for test implementation
    }

    @Override
    public boolean isStarted() {
      return true;
    }

    /**
     * Simulates an I/O delay that would typically occur in a real BlobStore implementation.
     * This helps model the behavior of I/O-bound operations where Virtual Threads excel.
     */
    @VirtualThreadFriendly
    private void simulateIoDelay() {
      try {
        // Simulate variable I/O latency between 5-15ms
        Thread.sleep((long) (5 + Math.random() * 10));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    // Stub implementations of other BlobStore methods
    @Override public Blob create(InputStream blobData, Map<String, String> headers, BlobId blobId) { return null; }
    @Override public Blob get(BlobId blobId, boolean includeDeleted) { return get(blobId); }
    @Override public boolean exists(BlobId blobId) { return blobs.containsKey(blobId); }
    @Override public boolean bytesExists(BlobId blobId) { return exists(blobId); }
    @Override public boolean isBlobEmpty(BlobId blobId) { return false; }
    @Override public boolean deleteHard(BlobId blobId) { return delete(blobId, "hard delete"); }
    @Override public <B extends BlobStore> BlobStoreMetricsService<B> getMetricsService() { return null; }
    @Override public BlobStoreMetrics getMetrics() { return null; }
    @Override public Map<OperationType, OperationMetrics> getOperationMetricsByType() { return null; }
    @Override public Map<OperationType, OperationMetrics> getOperationMetricsDelta() { return null; }
    @Override public void clearOperationMetrics() { }
    @Override public void compact(BlobStoreUsageChecker inUseChecker) { }
    @Override public void deleteTempFiles(Integer daysOlderThan) { }
    @Override public void init(BlobStoreConfiguration configuration) { }
    @Override public void remove() { }
    @Override public Stream<BlobId> getBlobIdStream() { return null; }
    @Override public Stream<BlobId> getBlobIdUpdatedSinceStream(Duration duration) { return null; }
    @Override public PaginatedResult<BlobId> getBlobIdUpdatedSinceStream(String prefix, OffsetDateTime fromDateTime, OffsetDateTime toDateTime, String continuationToken, int pageSize) { return null; }
    @Override public Stream<BlobId> getDirectPathBlobIdStream(String prefix) { return null; }
    @Override public BlobAttributes getBlobAttributes(BlobId blobId) { return null; }
    @Override public void setBlobAttributes(BlobId blobId, BlobAttributes blobAttributes) { }
    @Override public boolean undelete(BlobStoreUsageChecker inUseChecker, BlobId blobId, BlobAttributes attributes, boolean isDryRun) { return false; }
    @Override public boolean isStorageAvailable() { return true; }
    @Override public boolean isEmpty() { return blobs.isEmpty(); }
    @Override public void shutdown() { }
    @Override public RawObjectAccess getRawObjectAccess() { return null; }
    @Override public BlobSession<?> openSession() { return null; }
    @Override public Blob copy(BlobId blobId, Map<String, String> headers) { return null; }
    @Override public void createBlobAttributes(BlobId blobId, Map<String, String> headers, BlobMetrics blobMetrics) { }
    @Override public BlobAttributes createBlobAttributesInstance(BlobId blobId, Map<String, String> headers, BlobMetrics metrics) { return null; }
  }

  /**
   * Simple Blob implementation for testing purposes.
   */
  private static class TestBlob implements Blob {
    private final BlobId id;
    private final Map<String, String> headers;

    public TestBlob(BlobId id, Map<String, String> headers) {
      this.id = id;
      this.headers = new HashMap<>(headers);
    }

    @Override
    public BlobId getId() {
      return id;
    }

    @Override
    public Map<String, String> getHeaders() {
      return headers;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(BLOB_CONTENT.getBytes(StandardCharsets.UTF_8));
    }

    // Stub implementations of other Blob methods
    @Override public long getMetrics() { return BLOB_CONTENT.length(); }
    @Override public boolean isDeleted() { return false; }
  }

  /**
   * Holds statistics about blob operations performed during a benchmark run.
   */
  private static class OperationStats {
    private final long totalLatencyMs;
    private final int errorCount;

    public OperationStats(long totalLatencyMs, int errorCount) {
      this.totalLatencyMs = totalLatencyMs;
      this.errorCount = errorCount;
    }

    public long getTotalLatencyMs() {
      return totalLatencyMs;
    }

    public int getErrorCount() {
      return errorCount;
    }
  }

  /**
   * Represents the results of a performance benchmark run.
   */
  private static class PerformanceResult {
    private final String name;
    private final int concurrencyLevel;
    private final double operationsPerSecond;
    private final double averageLatencyMs;
    private final int errorCount;

    public PerformanceResult(
        String name,
        int concurrencyLevel,
        double operationsPerSecond,
        double averageLatencyMs,
        int errorCount) {
      this.name = name;
      this.concurrencyLevel = concurrencyLevel;
      this.operationsPerSecond = operationsPerSecond;
      this.averageLatencyMs = averageLatencyMs;
      this.errorCount = errorCount;
    }

    public String getName() {
      return name;
    }

    public int getConcurrencyLevel() {
      return concurrencyLevel;
    }

    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }

    public double getAverageLatencyMs() {
      return averageLatencyMs;
    }

    public int getErrorCount() {
      return errorCount;
    }

    @Override
    public String toString() {
      return String.format("%s (concurrency=%d): %.2f ops/sec, %.2f ms avg latency, %d errors",
          name, concurrencyLevel, operationsPerSecond, averageLatencyMs, errorCount);
    }
  }
}