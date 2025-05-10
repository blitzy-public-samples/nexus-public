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
package org.sonatype.nexus.blobstore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import org.sonatype.nexus.blobstore.file.FileBlobStoreConfigurationBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Performance and scalability tests for BlobStore operations under high concurrency,
 * comparing platform threads and virtual threads.
 *
 * @since 3.60
 */
public class ConcurrentBlobStoreVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int BLOB_SIZE = 1024; // 1KB
  private static final int WARMUP_ITERATIONS = 5;
  private static final int TEST_ITERATIONS = 10;
  private static final int TIMEOUT_SECONDS = 60;
  
  @org.junit.Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  
  private BlobStore blobStore;
  private BlobId testBlobId;
  private byte[] testData;
  
  @Before
  public void setUp() throws Exception {
    // Create a real file-based blob store for testing
    BlobStoreConfiguration config = FileBlobStoreConfigurationBuilder.create("test")
        .withPath(temporaryFolder.newFolder().toPath())
        .build();
    
    blobStore = new FileBlobStore(
        config,
        temporaryFolder.newFolder().toPath(),
        new BlobStoreMetricsStoreImpl(),
        new PerformanceLogger());
    
    blobStore.start();
    
    // Create test data
    testData = new byte[BLOB_SIZE];
    for (int i = 0; i < BLOB_SIZE; i++) {
      testData[i] = (byte) (i % 256);
    }
    
    // Create a test blob that will be used for read tests
    Map<String, String> headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, "test-blob");
    headers.put(BlobStore.CREATED_BY_HEADER, "test");
    
    try (InputStream inputStream = new ByteArrayInputStream(testData)) {
      Blob blob = blobStore.create(inputStream, headers);
      testBlobId = blob.getId();
    }
  }
  
  @After
  public void tearDown() throws Exception {
    if (blobStore != null) {
      blobStore.stop();
    }
  }
  
  /**
   * Tests concurrent blob creation using platform threads.
   */
  @Test
  public void testConcurrentBlobCreationWithPlatformThreads() throws Exception {
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    PerformanceResult result = measureBlobCreationPerformance(platformThreadFactory, "platform");
    
    log.info("Platform Thread Results: {}", result);
    
    // Basic validation that the test completed successfully
    assertThat(result.getErrorCount(), is(0));
    assertThat(result.getCompletedOperations(), is(CONCURRENT_THREADS));
  }
  
  /**
   * Tests concurrent blob creation using virtual threads.
   */
  @Test
  public void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    PerformanceResult result = measureBlobCreationPerformance(virtualThreadFactory, "virtual");
    
    log.info("Virtual Thread Results: {}", result);
    
    // Basic validation that the test completed successfully
    assertThat(result.getErrorCount(), is(0));
    assertThat(result.getCompletedOperations(), is(CONCURRENT_THREADS));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for blob creation.
   */
  @Test
  public void testCompareThreadTypesForBlobCreation() throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      measureBlobCreationPerformance(Thread.ofPlatform().factory(), "platform-warmup");
      measureBlobCreationPerformance(Thread.ofVirtual().factory(), "virtual-warmup");
    }
    
    // Actual test
    PerformanceResult platformResult = measureBlobCreationPerformance(Thread.ofPlatform().factory(), "platform");
    PerformanceResult virtualResult = measureBlobCreationPerformance(Thread.ofVirtual().factory(), "virtual");
    
    log.info("Platform Thread Results: {}", platformResult);
    log.info("Virtual Thread Results: {}", virtualResult);
    
    // Verify both completed successfully
    assertThat(platformResult.getErrorCount(), is(0));
    assertThat(virtualResult.getErrorCount(), is(0));
    
    // Verify virtual threads have better or equal performance
    assertThat("Virtual threads should have lower or equal average time", 
        virtualResult.getAverageTimeMs(), lessThan(platformResult.getAverageTimeMs() * 1.1)); // Allow 10% margin
    
    // Verify memory efficiency - virtual threads should use less memory per thread
    assertThat("Virtual threads should use less memory", 
        virtualResult.getMemoryUsedBytes(), lessThan(platformResult.getMemoryUsedBytes()));
  }
  
  /**
   * Tests concurrent blob reading using platform threads.
   */
  @Test
  public void testConcurrentBlobReadWithPlatformThreads() throws Exception {
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    PerformanceResult result = measureBlobReadPerformance(platformThreadFactory, "platform");
    
    log.info("Platform Thread Read Results: {}", result);
    
    // Basic validation that the test completed successfully
    assertThat(result.getErrorCount(), is(0));
    assertThat(result.getCompletedOperations(), is(CONCURRENT_THREADS));
  }
  
  /**
   * Tests concurrent blob reading using virtual threads.
   */
  @Test
  public void testConcurrentBlobReadWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    PerformanceResult result = measureBlobReadPerformance(virtualThreadFactory, "virtual");
    
    log.info("Virtual Thread Read Results: {}", result);
    
    // Basic validation that the test completed successfully
    assertThat(result.getErrorCount(), is(0));
    assertThat(result.getCompletedOperations(), is(CONCURRENT_THREADS));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for blob reading.
   */
  @Test
  public void testCompareThreadTypesForBlobReading() throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      measureBlobReadPerformance(Thread.ofPlatform().factory(), "platform-warmup");
      measureBlobReadPerformance(Thread.ofVirtual().factory(), "virtual-warmup");
    }
    
    // Actual test
    PerformanceResult platformResult = measureBlobReadPerformance(Thread.ofPlatform().factory(), "platform");
    PerformanceResult virtualResult = measureBlobReadPerformance(Thread.ofVirtual().factory(), "virtual");
    
    log.info("Platform Thread Read Results: {}", platformResult);
    log.info("Virtual Thread Read Results: {}", virtualResult);
    
    // Verify both completed successfully
    assertThat(platformResult.getErrorCount(), is(0));
    assertThat(virtualResult.getErrorCount(), is(0));
    
    // Verify virtual threads have better or equal performance for reads
    assertThat("Virtual threads should have lower or equal average read time", 
        virtualResult.getAverageTimeMs(), lessThan(platformResult.getAverageTimeMs() * 1.1)); // Allow 10% margin
    
    // Verify memory efficiency - virtual threads should use less memory per thread
    assertThat("Virtual threads should use less memory for reads", 
        virtualResult.getMemoryUsedBytes(), lessThan(platformResult.getMemoryUsedBytes()));
  }
  
  /**
   * Tests mixed workload (50% reads, 50% writes) with platform threads.
   */
  @Test
  public void testMixedWorkloadWithPlatformThreads() throws Exception {
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    PerformanceResult result = measureMixedWorkloadPerformance(platformThreadFactory, "platform");
    
    log.info("Platform Thread Mixed Workload Results: {}", result);
    
    // Basic validation that the test completed successfully
    assertThat(result.getErrorCount(), is(0));
    assertThat(result.getCompletedOperations(), is(CONCURRENT_THREADS));
  }
  
  /**
   * Tests mixed workload (50% reads, 50% writes) with virtual threads.
   */
  @Test
  public void testMixedWorkloadWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    PerformanceResult result = measureMixedWorkloadPerformance(virtualThreadFactory, "virtual");
    
    log.info("Virtual Thread Mixed Workload Results: {}", result);
    
    // Basic validation that the test completed successfully
    assertThat(result.getErrorCount(), is(0));
    assertThat(result.getCompletedOperations(), is(CONCURRENT_THREADS));
  }
  
  /**
   * Compares performance between platform threads and virtual threads for mixed workload.
   */
  @Test
  public void testCompareThreadTypesForMixedWorkload() throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      measureMixedWorkloadPerformance(Thread.ofPlatform().factory(), "platform-warmup");
      measureMixedWorkloadPerformance(Thread.ofVirtual().factory(), "virtual-warmup");
    }
    
    // Actual test
    PerformanceResult platformResult = measureMixedWorkloadPerformance(Thread.ofPlatform().factory(), "platform");
    PerformanceResult virtualResult = measureMixedWorkloadPerformance(Thread.ofVirtual().factory(), "virtual");
    
    log.info("Platform Thread Mixed Workload Results: {}", platformResult);
    log.info("Virtual Thread Mixed Workload Results: {}", virtualResult);
    
    // Verify both completed successfully
    assertThat(platformResult.getErrorCount(), is(0));
    assertThat(virtualResult.getErrorCount(), is(0));
    
    // Verify virtual threads have better or equal performance for mixed workload
    assertThat("Virtual threads should have lower or equal average time for mixed workload", 
        virtualResult.getAverageTimeMs(), lessThan(platformResult.getAverageTimeMs() * 1.1)); // Allow 10% margin
    
    // Verify memory efficiency - virtual threads should use less memory per thread
    assertThat("Virtual threads should use less memory for mixed workload", 
        virtualResult.getMemoryUsedBytes(), lessThan(platformResult.getMemoryUsedBytes()));
  }
  
  /**
   * Tests extreme concurrency (10x normal) with virtual threads to verify scalability.
   */
  @Test
  public void testExtremeConcurrencyWithVirtualThreads() throws Exception {
    final int extremeConcurrency = CONCURRENT_THREADS * 10; // 10,000 threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    CountDownLatch latch = new CountDownLatch(extremeConcurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger completedCount = new AtomicInteger(0);
    
    // Create executor with virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Record memory before test
      long memoryBefore = getUsedMemory();
      Instant startTime = Instant.now();
      
      // Submit tasks
      for (int i = 0; i < extremeConcurrency; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Simple read operation
            Blob blob = blobStore.get(testBlobId);
            if (blob != null) {
              completedCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            log.error("Error in extreme concurrency test task {}", taskId, e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS); // Double timeout for extreme test
      
      // Calculate metrics
      Duration duration = Duration.between(startTime, Instant.now());
      long memoryAfter = getUsedMemory();
      long memoryUsed = memoryAfter - memoryBefore;
      
      log.info("Extreme Concurrency Test Results:");
      log.info("  Completed: {} (of {})", completedCount.get(), extremeConcurrency);
      log.info("  Errors: {}", errorCount.get());
      log.info("  Duration: {} ms", duration.toMillis());
      log.info("  Memory used: {} bytes", memoryUsed);
      log.info("  Threads per second: {}", extremeConcurrency * 1000.0 / duration.toMillis());
      
      // Verify test completed successfully
      assertThat("Test should complete within timeout", completed, is(true));
      assertThat("All operations should complete successfully", completedCount.get(), is(extremeConcurrency));
      assertThat("No errors should occur", errorCount.get(), is(0));
      
      // Verify reasonable memory usage (less than 10MB per 1000 threads)
      long memoryPer1000Threads = memoryUsed * 1000 / extremeConcurrency;
      log.info("  Memory per 1000 threads: {} bytes", memoryPer1000Threads);
      assertThat("Memory usage should be reasonable", memoryPer1000Threads, lessThan(10_000_000L)); // 10MB
    }
  }
  
  /**
   * Measures performance of concurrent blob creation operations.
   */
  private PerformanceResult measureBlobCreationPerformance(ThreadFactory threadFactory, String threadType) throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalTimeMs = new AtomicLong(0);
    List<Long> responseTimes = new ArrayList<>(CONCURRENT_THREADS);
    
    // Create executor with the provided thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Record memory before test
      long memoryBefore = getUsedMemory();
      Instant startTime = Instant.now();
      
      // Submit blob creation tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int taskId = i;
        executor.submit(() -> {
          Instant taskStart = Instant.now();
          try {
            Map<String, String> headers = new HashMap<>();
            headers.put(BlobStore.BLOB_NAME_HEADER, "test-blob-" + threadType + "-" + taskId);
            headers.put(BlobStore.CREATED_BY_HEADER, "test");
            
            try (InputStream inputStream = new ByteArrayInputStream(testData)) {
              Blob blob = blobStore.create(inputStream, headers);
              assertThat(blob, notNullValue());
              assertThat(blob.getId(), notNullValue());
            }
          }
          catch (Exception e) {
            log.error("Error in blob creation task {}", taskId, e);
            errorCount.incrementAndGet();
          }
          finally {
            Duration taskDuration = Duration.between(taskStart, Instant.now());
            totalTimeMs.addAndGet(taskDuration.toMillis());
            synchronized (responseTimes) {
              responseTimes.add(taskDuration.toMillis());
            }
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Calculate metrics
      Duration duration = Duration.between(startTime, Instant.now());
      long memoryAfter = getUsedMemory();
      long memoryUsed = memoryAfter - memoryBefore;
      
      return new PerformanceResult(
          threadType,
          CONCURRENT_THREADS,
          errorCount.get(),
          CONCURRENT_THREADS - errorCount.get(),
          duration.toMillis(),
          totalTimeMs.get() / Math.max(1, CONCURRENT_THREADS - errorCount.get()),
          memoryUsed,
          responseTimes,
          completed
      );
    }
  }
  
  /**
   * Measures performance of concurrent blob read operations.
   */
  private PerformanceResult measureBlobReadPerformance(ThreadFactory threadFactory, String threadType) throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalTimeMs = new AtomicLong(0);
    List<Long> responseTimes = new ArrayList<>(CONCURRENT_THREADS);
    
    // Create executor with the provided thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Record memory before test
      long memoryBefore = getUsedMemory();
      Instant startTime = Instant.now();
      
      // Submit blob read tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int taskId = i;
        executor.submit(() -> {
          Instant taskStart = Instant.now();
          try {
            Blob blob = blobStore.get(testBlobId);
            assertThat(blob, notNullValue());
            
            // Read the blob content
            try (InputStream inputStream = blob.getInputStream()) {
              byte[] buffer = new byte[1024];
              int bytesRead;
              while ((bytesRead = inputStream.read(buffer)) != -1) {
                // Just consume the data
              }
            }
          }
          catch (Exception e) {
            log.error("Error in blob read task {}", taskId, e);
            errorCount.incrementAndGet();
          }
          finally {
            Duration taskDuration = Duration.between(taskStart, Instant.now());
            totalTimeMs.addAndGet(taskDuration.toMillis());
            synchronized (responseTimes) {
              responseTimes.add(taskDuration.toMillis());
            }
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Calculate metrics
      Duration duration = Duration.between(startTime, Instant.now());
      long memoryAfter = getUsedMemory();
      long memoryUsed = memoryAfter - memoryBefore;
      
      return new PerformanceResult(
          threadType,
          CONCURRENT_THREADS,
          errorCount.get(),
          CONCURRENT_THREADS - errorCount.get(),
          duration.toMillis(),
          totalTimeMs.get() / Math.max(1, CONCURRENT_THREADS - errorCount.get()),
          memoryUsed,
          responseTimes,
          completed
      );
    }
  }
  
  /**
   * Measures performance of a mixed workload (50% reads, 50% writes).
   */
  private PerformanceResult measureMixedWorkloadPerformance(ThreadFactory threadFactory, String threadType) throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong totalTimeMs = new AtomicLong(0);
    List<Long> responseTimes = new ArrayList<>(CONCURRENT_THREADS);
    
    // Create executor with the provided thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Record memory before test
      long memoryBefore = getUsedMemory();
      Instant startTime = Instant.now();
      
      // Submit mixed workload tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int taskId = i;
        final boolean isRead = (i % 2 == 0); // Alternate between read and write
        
        executor.submit(() -> {
          Instant taskStart = Instant.now();
          try {
            if (isRead) {
              // Read operation
              Blob blob = blobStore.get(testBlobId);
              assertThat(blob, notNullValue());
              
              // Read the blob content
              try (InputStream inputStream = blob.getInputStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                  // Just consume the data
                }
              }
            }
            else {
              // Write operation
              Map<String, String> headers = new HashMap<>();
              headers.put(BlobStore.BLOB_NAME_HEADER, "test-blob-" + threadType + "-" + taskId);
              headers.put(BlobStore.CREATED_BY_HEADER, "test");
              
              try (InputStream inputStream = new ByteArrayInputStream(testData)) {
                Blob blob = blobStore.create(inputStream, headers);
                assertThat(blob, notNullValue());
                assertThat(blob.getId(), notNullValue());
              }
            }
          }
          catch (Exception e) {
            log.error("Error in mixed workload task {}", taskId, e);
            errorCount.incrementAndGet();
          }
          finally {
            Duration taskDuration = Duration.between(taskStart, Instant.now());
            totalTimeMs.addAndGet(taskDuration.toMillis());
            synchronized (responseTimes) {
              responseTimes.add(taskDuration.toMillis());
            }
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Calculate metrics
      Duration duration = Duration.between(startTime, Instant.now());
      long memoryAfter = getUsedMemory();
      long memoryUsed = memoryAfter - memoryBefore;
      
      return new PerformanceResult(
          threadType,
          CONCURRENT_THREADS,
          errorCount.get(),
          CONCURRENT_THREADS - errorCount.get(),
          duration.toMillis(),
          totalTimeMs.get() / Math.max(1, CONCURRENT_THREADS - errorCount.get()),
          memoryUsed,
          responseTimes,
          completed
      );
    }
  }
  
  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Class to hold performance test results.
   */
  private static class PerformanceResult {
    private final String threadType;
    private final int totalOperations;
    private final int errorCount;
    private final int completedOperations;
    private final long totalTimeMs;
    private final long averageTimeMs;
    private final long memoryUsedBytes;
    private final List<Long> responseTimes;
    private final boolean completed;
    
    public PerformanceResult(String threadType, int totalOperations, int errorCount, int completedOperations,
                            long totalTimeMs, long averageTimeMs, long memoryUsedBytes, 
                            List<Long> responseTimes, boolean completed) {
      this.threadType = threadType;
      this.totalOperations = totalOperations;
      this.errorCount = errorCount;
      this.completedOperations = completedOperations;
      this.totalTimeMs = totalTimeMs;
      this.averageTimeMs = averageTimeMs;
      this.memoryUsedBytes = memoryUsedBytes;
      this.responseTimes = new ArrayList<>(responseTimes);
      this.completed = completed;
    }
    
    public String getThreadType() {
      return threadType;
    }
    
    public int getTotalOperations() {
      return totalOperations;
    }
    
    public int getErrorCount() {
      return errorCount;
    }
    
    public int getCompletedOperations() {
      return completedOperations;
    }
    
    public long getTotalTimeMs() {
      return totalTimeMs;
    }
    
    public long getAverageTimeMs() {
      return averageTimeMs;
    }
    
    public long getMemoryUsedBytes() {
      return memoryUsedBytes;
    }
    
    public List<Long> getResponseTimes() {
      return responseTimes;
    }
    
    public boolean isCompleted() {
      return completed;
    }
    
    public long getP95ResponseTime() {
      if (responseTimes.isEmpty()) {
        return 0;
      }
      
      List<Long> sortedTimes = new ArrayList<>(responseTimes);
      sortedTimes.sort(Long::compareTo);
      int index = (int) Math.ceil(sortedTimes.size() * 0.95) - 1;
      return sortedTimes.get(Math.max(0, index));
    }
    
    @Override
    public String toString() {
      return String.format(
          "%s threads: %d ops, %d errors, %.2f ops/sec, avg %.2f ms, p95 %d ms, memory %d bytes",
          threadType,
          completedOperations,
          errorCount,
          completedOperations * 1000.0 / totalTimeMs,
          averageTimeMs,
          getP95ResponseTime(),
          memoryUsedBytes
      );
    }
  }
}