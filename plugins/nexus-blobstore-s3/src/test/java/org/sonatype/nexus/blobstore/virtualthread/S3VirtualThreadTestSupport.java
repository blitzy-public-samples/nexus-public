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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;

import static java.util.concurrent.TimeUnit.*;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CONTENT_TYPE_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_IP_HEADER;

/**
 * Test support class for S3BlobStore with Java 21 Virtual Threads.
 * <p>
 * Provides utilities for testing S3BlobStore operations with both platform threads and virtual threads,
 * allowing for performance comparison and validation of concurrent behavior.
 * </p>
 * <p>
 * This class is designed to be used in tests that validate the behavior and performance of S3BlobStore
 * when using Java 21 Virtual Threads for I/O-bound operations.
 * </p>
 *
 * @since 3.60
 */
public class S3VirtualThreadTestSupport
{
  /**
   * Default number of operations to perform in concurrent tests.
   */
  public static final int DEFAULT_OPERATION_COUNT = 100;

  /**
   * Default timeout for concurrent operations in seconds.
   */
  public static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Default content size for test blobs in bytes.
   */
  public static final int DEFAULT_CONTENT_SIZE = 1024; // 1KB

  /**
   * System property to enable virtual thread testing.
   */
  public static final String VIRTUAL_THREAD_PROPERTY = "test.virtual.threads";

  /**
   * Performance metrics collected during test execution.
   */
  public static class PerformanceMetrics
  {
    private final AtomicLong totalOperations = new AtomicLong(0);
    private final AtomicLong totalDurationMs = new AtomicLong(0);
    private final AtomicLong minDurationMs = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxDurationMs = new AtomicLong(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private final Map<Long, Long> durationHistogram = new ConcurrentHashMap<>();

    /**
     * Records the duration of a single operation.
     *
     * @param durationMs Duration of the operation in milliseconds
     */
    public void recordOperation(long durationMs) {
      totalOperations.incrementAndGet();
      totalDurationMs.addAndGet(durationMs);
      minDurationMs.updateAndGet(current -> Math.min(current, durationMs));
      maxDurationMs.updateAndGet(current -> Math.max(current, durationMs));
      durationHistogram.compute(durationMs, (k, v) -> v == null ? 1L : v + 1L);
    }

    /**
     * Records an error that occurred during an operation.
     */
    public void recordError() {
      errorCount.incrementAndGet();
    }

    /**
     * Gets the total number of operations performed.
     *
     * @return Total operation count
     */
    public long getTotalOperations() {
      return totalOperations.get();
    }

    /**
     * Gets the total duration of all operations in milliseconds.
     *
     * @return Total duration in milliseconds
     */
    public long getTotalDurationMs() {
      return totalDurationMs.get();
    }

    /**
     * Gets the minimum operation duration in milliseconds.
     *
     * @return Minimum duration in milliseconds
     */
    public long getMinDurationMs() {
      return minDurationMs.get() == Long.MAX_VALUE ? 0 : minDurationMs.get();
    }

    /**
     * Gets the maximum operation duration in milliseconds.
     *
     * @return Maximum duration in milliseconds
     */
    public long getMaxDurationMs() {
      return maxDurationMs.get();
    }

    /**
     * Gets the average operation duration in milliseconds.
     *
     * @return Average duration in milliseconds, or 0 if no operations were performed
     */
    public double getAverageDurationMs() {
      return totalOperations.get() > 0 ? (double) totalDurationMs.get() / totalOperations.get() : 0;
    }

    /**
     * Gets the number of errors that occurred during operations.
     *
     * @return Error count
     */
    public int getErrorCount() {
      return errorCount.get();
    }

    /**
     * Gets the operations per second (throughput).
     *
     * @return Operations per second, or 0 if total duration is 0
     */
    public double getOperationsPerSecond() {
      return totalDurationMs.get() > 0 ? 
          (double) totalOperations.get() / (totalDurationMs.get() / 1000.0) : 0;
    }

    /**
     * Gets the percentile duration in milliseconds.
     *
     * @param percentile Percentile to calculate (0-100)
     * @return Duration at the specified percentile in milliseconds
     */
    public long getPercentileDurationMs(double percentile) {
      if (percentile < 0 || percentile > 100) {
        throw new IllegalArgumentException("Percentile must be between 0 and 100");
      }

      if (totalOperations.get() == 0) {
        return 0;
      }

      // Convert histogram to sorted list of durations with counts
      List<Map.Entry<Long, Long>> sortedDurations = new ArrayList<>(durationHistogram.entrySet());
      sortedDurations.sort(Map.Entry.comparingByKey());

      // Calculate the index for the percentile
      long targetIndex = Math.round(totalOperations.get() * percentile / 100.0);
      if (targetIndex == 0) {
        targetIndex = 1; // Ensure we return at least the minimum value
      }

      // Find the duration at the percentile
      long currentIndex = 0;
      for (Map.Entry<Long, Long> entry : sortedDurations) {
        currentIndex += entry.getValue();
        if (currentIndex >= targetIndex) {
          return entry.getKey();
        }
      }

      // Fallback to max if something went wrong
      return maxDurationMs.get();
    }

    @Override
    public String toString() {
      return String.format(
          "Operations: %d, Avg: %.2fms, Min: %dms, Max: %dms, P95: %dms, P99: %dms, Errors: %d, Throughput: %.2f ops/sec",
          getTotalOperations(),
          getAverageDurationMs(),
          getMinDurationMs(),
          getMaxDurationMs(),
          getPercentileDurationMs(95),
          getPercentileDurationMs(99),
          getErrorCount(),
          getOperationsPerSecond());
    }
  }

  /**
   * Creates a platform thread factory.
   *
   * @param namePrefix Prefix for thread names
   * @return Platform thread factory
   */
  public static ThreadFactory createPlatformThreadFactory(String namePrefix) {
    AtomicInteger threadCount = new AtomicInteger(1);
    return r -> {
      Thread thread = Thread.ofPlatform()
              .name(namePrefix + "-" + threadCount.getAndIncrement())
              .unstarted(r);
      thread.setDaemon(true);
      return thread;
    };
  }


  /**
   * Creates a virtual thread factory.
   *
   * @param namePrefix Prefix for thread names
   * @return Virtual thread factory
   */
  public static ThreadFactory createVirtualThreadFactory(String namePrefix) {
    AtomicInteger threadCount = new AtomicInteger(1);
    return r -> {
        return Thread.ofPlatform()
              .name(namePrefix + "-" + threadCount.getAndIncrement())
              .unstarted(r);
    };
  }


  /**
   * Creates an executor service using platform threads.
   *
   * @param threadCount Number of threads in the pool
   * @param namePrefix Prefix for thread names
   * @return Executor service with platform threads
   */
  public static ExecutorService createPlatformThreadExecutor(int threadCount, String namePrefix) {
    return Executors.newFixedThreadPool(threadCount, createPlatformThreadFactory(namePrefix));
  }

  /**
   * Creates an executor service using virtual threads.
   *
   * @param namePrefix Prefix for thread names
   * @return Executor service with virtual threads
   */
  public static ExecutorService createVirtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(createVirtualThreadFactory(namePrefix));
  }

  /**
   * Creates an executor service based on the test.virtual.threads system property.
   * If the property is set to true, a virtual thread executor is created.
   * Otherwise, a platform thread executor is created.
   *
   * @param threadCount Number of threads for platform thread executor (ignored for virtual threads)
   * @param namePrefix Prefix for thread names
   * @return Executor service with either platform or virtual threads
   */
  public static ExecutorService createExecutorService(int threadCount, String namePrefix) {
    boolean useVirtualThreads = Boolean.parseBoolean(System.getProperty(VIRTUAL_THREAD_PROPERTY, "false"));
    return useVirtualThreads ?
        createVirtualThreadExecutor(namePrefix) :
        createPlatformThreadExecutor(threadCount, namePrefix);
  }

  /**
   * Executes a task multiple times concurrently using the provided executor service.
   *
   * @param executor Executor service to use
   * @param task Task to execute
   * @param count Number of times to execute the task
   * @param timeoutSeconds Timeout in seconds
   * @return Performance metrics for the operation
   * @throws InterruptedException if the operation is interrupted
   */
  public static PerformanceMetrics executeConcurrently(
      ExecutorService executor,
      Callable<?> task,
      int count,
      int timeoutSeconds) throws InterruptedException {
    PerformanceMetrics metrics = new PerformanceMetrics();
    CountDownLatch latch = new CountDownLatch(count);

    for (int i = 0; i < count; i++) {
      executor.submit(() -> {
        long startTime = System.currentTimeMillis();
        try {
          task.call();
          long duration = System.currentTimeMillis() - startTime;
          metrics.recordOperation(duration);
        }
        catch (Exception e) {
          metrics.recordError();
        }
        finally {
          latch.countDown();
        }
        return null;
      });
    }

    boolean completed = latch.await(timeoutSeconds, SECONDS);
    if (!completed) {
      metrics.recordError(); // Record timeout as an error
    }

    return metrics;
  }

  /**
   * Executes a task multiple times concurrently using both platform and virtual thread executors,
   * and returns the performance metrics for comparison.
   *
   * @param task Task to execute
   * @param count Number of times to execute the task
   * @param platformThreadCount Number of platform threads to use
   * @param timeoutSeconds Timeout in seconds
   * @return Map of executor type to performance metrics
   * @throws InterruptedException if the operation is interrupted
   */
  public static Map<String, PerformanceMetrics> compareExecutors(
      Callable<?> task,
      int count,
      int platformThreadCount,
      int timeoutSeconds) throws InterruptedException {
    Map<String, PerformanceMetrics> results = new HashMap<>();

    // Test with platform threads
    try (ExecutorService platformExecutor = createPlatformThreadExecutor(platformThreadCount, "platform-test")) {
      results.put("platform", executeConcurrently(platformExecutor, task, count, timeoutSeconds));
    }

    // Test with virtual threads
    try (ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-test")) {
      results.put("virtual", executeConcurrently(virtualExecutor, task, count, timeoutSeconds));
    }

    return results;
  }

  /**
   * Creates a test blob in the provided BlobStore.
   *
   * @param blobStore BlobStore to create the blob in
   * @param blobName Name of the blob
   * @param contentSize Size of the blob content in bytes
   * @return Created blob
   */
  public static Blob createTestBlob(BlobStore blobStore, String blobName, int contentSize) {
    byte[] content = new byte[contentSize];
    // Fill with random data
    for (int i = 0; i < contentSize; i++) {
      content[i] = (byte) (Math.random() * 256);
    }

    Map<String, String> headers = new HashMap<>();
    headers.put(BLOB_NAME_HEADER, blobName);
    headers.put(CONTENT_TYPE_HEADER, "application/octet-stream");
    headers.put(CREATED_BY_HEADER, "S3VirtualThreadTestSupport");
    headers.put(CREATED_BY_IP_HEADER, "127.0.0.1");

    return blobStore.create(new ByteArrayInputStream(content), headers);
  }

  /**
   * Creates multiple test blobs concurrently in the provided BlobStore.
   *
   * @param blobStore BlobStore to create the blobs in
   * @param count Number of blobs to create
   * @param contentSize Size of each blob in bytes
   * @param executor Executor service to use for concurrent creation
   * @return List of created blob IDs
   * @throws InterruptedException if the operation is interrupted
   */
  public static List<BlobId> createTestBlobsConcurrently(
      BlobStore blobStore,
      int count,
      int contentSize,
      ExecutorService executor) throws InterruptedException {
    List<BlobId> blobIds = new ArrayList<>(count);
    CountDownLatch latch = new CountDownLatch(count);
    List<CompletableFuture<BlobId>> futures = new ArrayList<>(count);

    for (int i = 0; i < count; i++) {
      final String blobName = "test-blob-" + UUID.randomUUID();
      CompletableFuture<BlobId> future = CompletableFuture.supplyAsync(() -> {
        try {
          Blob blob = createTestBlob(blobStore, blobName, contentSize);
          return blob.getId();
        }
        finally {
          latch.countDown();
        }
      }, executor);
      futures.add(future);
    }

    boolean completed = latch.await(DEFAULT_TIMEOUT_SECONDS, SECONDS);
    if (!completed) {
      throw new RuntimeException("Timed out waiting for blob creation");
    }

    // Collect results
    for (CompletableFuture<BlobId> future : futures) {
      try {
        BlobId blobId = future.get(1, SECONDS);
        if (blobId != null) {
          blobIds.add(blobId);
        }
      }
      catch (Exception e) {
        // Skip failed creations
      }
    }

    return blobIds;
  }

  /**
   * Performs a read test on the provided BlobStore with the given blob IDs.
   *
   * @param blobStore BlobStore to read from
   * @param blobIds List of blob IDs to read
   * @param executor Executor service to use for concurrent reads
   * @return Performance metrics for the read operation
   * @throws InterruptedException if the operation is interrupted
   */
  public static PerformanceMetrics performReadTest(
      BlobStore blobStore,
      List<BlobId> blobIds,
      ExecutorService executor) throws InterruptedException {
    return executeConcurrently(executor, () -> {
      // Select a random blob ID from the list
      BlobId blobId = blobIds.get((int) (Math.random() * blobIds.size()));
      Blob blob = blobStore.get(blobId);
      if (blob == null) {
        throw new RuntimeException("Blob not found: " + blobId);
      }

      // Read the blob content
      try (InputStream is = blob.getInputStream()) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          // Just consume the data
        }
      }
      return null;
    }, blobIds.size(), DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Performs a write test on the provided BlobStore.
   *
   * @param blobStore BlobStore to write to
   * @param count Number of blobs to write
   * @param contentSize Size of each blob in bytes
   * @param executor Executor service to use for concurrent writes
   * @return Performance metrics for the write operation
   * @throws InterruptedException if the operation is interrupted
   */
  public static PerformanceMetrics performWriteTest(
      BlobStore blobStore,
      int count,
      int contentSize,
      ExecutorService executor) throws InterruptedException {
    return executeConcurrently(executor, () -> {
      String blobName = "test-blob-" + UUID.randomUUID();
      createTestBlob(blobStore, blobName, contentSize);
      return null;
    }, count, DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Performs a delete test on the provided BlobStore with the given blob IDs.
   *
   * @param blobStore BlobStore to delete from
   * @param blobIds List of blob IDs to delete
   * @param executor Executor service to use for concurrent deletes
   * @return Performance metrics for the delete operation
   * @throws InterruptedException if the operation is interrupted
   */
  public static PerformanceMetrics performDeleteTest(
      BlobStore blobStore,
      List<BlobId> blobIds,
      ExecutorService executor) throws InterruptedException {
    AtomicInteger index = new AtomicInteger(0);
    return executeConcurrently(executor, () -> {
      int i = index.getAndIncrement() % blobIds.size();
      BlobId blobId = blobIds.get(i);
      blobStore.delete(blobId, "Test deletion");
      return null;
    }, blobIds.size(), DEFAULT_TIMEOUT_SECONDS);
  }

  /**
   * Performs a comprehensive test suite on the provided BlobStore, comparing platform and virtual thread performance.
   *
   * @param blobStore BlobStore to test
   * @param operationCount Number of operations to perform for each test
   * @param contentSize Size of test blobs in bytes
   * @param platformThreadCount Number of platform threads to use
   * @return Map of test name to performance metrics comparison
   * @throws Exception if an error occurs during testing
   */
  public static Map<String, Map<String, PerformanceMetrics>> performComprehensiveTest(
      BlobStore blobStore,
      int operationCount,
      int contentSize,
      int platformThreadCount) throws Exception {
    Map<String, Map<String, PerformanceMetrics>> results = new HashMap<>();

    // Create test blobs for read and delete tests
    System.out.println("Creating test blobs for read and delete tests...");
    List<BlobId> readTestBlobIds;
    List<BlobId> deleteTestBlobIds;

    try (ExecutorService setupExecutor = createVirtualThreadExecutor("setup")) {
      readTestBlobIds = createTestBlobsConcurrently(blobStore, operationCount, contentSize, setupExecutor);
      deleteTestBlobIds = createTestBlobsConcurrently(blobStore, operationCount, contentSize, setupExecutor);
    }

    System.out.println("Created " + readTestBlobIds.size() + " blobs for read tests");
    System.out.println("Created " + deleteTestBlobIds.size() + " blobs for delete tests");

    // Write test
    System.out.println("\nPerforming write test...");
    results.put("write", compareExecutors(
        () -> {
          String blobName = "test-blob-" + UUID.randomUUID();
          createTestBlob(blobStore, blobName, contentSize);
          return null;
        },
        operationCount,
        platformThreadCount,
        DEFAULT_TIMEOUT_SECONDS));

    // Read test
    System.out.println("\nPerforming read test...");
    results.put("read", new HashMap<>());

    // Platform threads read test
    try (ExecutorService platformExecutor = createPlatformThreadExecutor(platformThreadCount, "platform-read")) {
      PerformanceMetrics platformMetrics = performReadTest(blobStore, readTestBlobIds, platformExecutor);
      results.get("read").put("platform", platformMetrics);
    }

    // Virtual threads read test
    try (ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-read")) {
      PerformanceMetrics virtualMetrics = performReadTest(blobStore, readTestBlobIds, virtualExecutor);
      results.get("read").put("virtual", virtualMetrics);
    }

    // Delete test
    System.out.println("\nPerforming delete test...");
    results.put("delete", new HashMap<>());

    // Platform threads delete test
    try (ExecutorService platformExecutor = createPlatformThreadExecutor(platformThreadCount, "platform-delete")) {
      PerformanceMetrics platformMetrics = performDeleteTest(blobStore, deleteTestBlobIds, platformExecutor);
      results.get("delete").put("platform", platformMetrics);
    }

    // Virtual threads delete test
    try (ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-delete")) {
      PerformanceMetrics virtualMetrics = performDeleteTest(blobStore, deleteTestBlobIds, virtualExecutor);
      results.get("delete").put("virtual", virtualMetrics);
    }

    // Print results
    printTestResults(results);

    return results;
  }

  /**
   * Prints the results of a comprehensive test.
   *
   * @param results Map of test name to performance metrics comparison
   */
  public static void printTestResults(Map<String, Map<String, PerformanceMetrics>> results) {
    System.out.println("\n=== Test Results ===\n");

    results.forEach((testName, metrics) -> {
      System.out.println("Test: " + testName.toUpperCase());
      PerformanceMetrics platformMetrics = metrics.get("platform");
      PerformanceMetrics virtualMetrics = metrics.get("virtual");

      System.out.println("  Platform Threads: " + platformMetrics);
      System.out.println("  Virtual Threads:  " + virtualMetrics);

      // Calculate improvement percentages
      if (platformMetrics.getTotalOperations() > 0 && virtualMetrics.getTotalOperations() > 0) {
        double throughputImprovement = ((virtualMetrics.getOperationsPerSecond() / platformMetrics.getOperationsPerSecond()) - 1) * 100;
        double latencyImprovement = ((platformMetrics.getAverageDurationMs() / virtualMetrics.getAverageDurationMs()) - 1) * 100;

        System.out.printf("  Throughput Improvement: %.2f%%\n", throughputImprovement);
        System.out.printf("  Latency Improvement:    %.2f%%\n", latencyImprovement);
      }

      System.out.println();
    });
  }

  /**
   * Measures the execution time of a task and returns the duration.
   *
   * @param task Task to measure
   * @return Duration of the task execution in milliseconds
   */
  public static long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Measures the execution time of a task that returns a result and returns both the result and duration.
   *
   * @param <T> Type of the result
   * @param supplier Supplier that produces the result
   * @return Map.Entry with the result and duration in milliseconds
   */
  public static <T> Map.Entry<T, Long> measureExecutionTime(Supplier<T> supplier) {
    long startTime = System.currentTimeMillis();
    T result = supplier.get();
    long duration = System.currentTimeMillis() - startTime;
    return Map.entry(result, duration);
  }

  /**
   * Executes a task repeatedly for a specified duration and returns performance metrics.
   *
   * @param task Task to execute
   * @param duration Duration to execute the task for
   * @param timeUnit Time unit of the duration
   * @return Performance metrics for the execution
   * @throws InterruptedException if the operation is interrupted
   */
  public static PerformanceMetrics executeForDuration(
      Callable<?> task,
      long duration,
      TimeUnit timeUnit) throws InterruptedException {
    PerformanceMetrics metrics = new PerformanceMetrics();
    long endTimeMs = System.currentTimeMillis() + timeUnit.toMillis(duration);

    while (System.currentTimeMillis() < endTimeMs) {
      long startTime = System.currentTimeMillis();
      try {
        task.call();
        long operationDuration = System.currentTimeMillis() - startTime;
        metrics.recordOperation(operationDuration);
      }
      catch (Exception e) {
        metrics.recordError();
      }
    }

    return metrics;
  }

  /**
   * Executes a task repeatedly for a specified duration using both platform and virtual thread executors,
   * and returns the performance metrics for comparison.
   *
   * @param task Task to execute
   * @param duration Duration to execute the task for
   * @param timeUnit Time unit of the duration
   * @param platformThreadCount Number of platform threads to use
   * @return Map of executor type to performance metrics
   * @throws InterruptedException if the operation is interrupted
   */
  public static Map<String, PerformanceMetrics> compareExecutorsForDuration(
      Callable<?> task,
      long duration,
      TimeUnit timeUnit,
      int platformThreadCount) throws InterruptedException {
    Map<String, PerformanceMetrics> results = new HashMap<>();

    // Test with platform threads
    try (ExecutorService platformExecutor = createPlatformThreadExecutor(platformThreadCount, "platform-duration")) {
      CountDownLatch platformLatch = new CountDownLatch(1);
      PerformanceMetrics platformMetrics = new PerformanceMetrics();
      long endTimeMs = System.currentTimeMillis() + timeUnit.toMillis(duration);

      // Start multiple tasks
      for (int i = 0; i < platformThreadCount; i++) {
        platformExecutor.submit(() -> {
          try {
            while (System.currentTimeMillis() < endTimeMs && !Thread.currentThread().isInterrupted()) {
              long startTime = System.currentTimeMillis();
              try {
                task.call();
                long operationDuration = System.currentTimeMillis() - startTime;
                platformMetrics.recordOperation(operationDuration);
              }
              catch (Exception e) {
                platformMetrics.recordError();
              }
            }
          }
          finally {
            platformLatch.countDown();
          }
          return null;
        });
      }

      platformLatch.await();
      results.put("platform", platformMetrics);
    }

    // Test with virtual threads
    try (ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-duration")) {
      PerformanceMetrics virtualMetrics = new PerformanceMetrics();
      long endTimeMs = System.currentTimeMillis() + timeUnit.toMillis(duration);
      int virtualThreadCount = platformThreadCount * 10; // Use more virtual threads to demonstrate scalability
      CountDownLatch virtualLatch = new CountDownLatch(virtualThreadCount);

      // Start multiple tasks
      for (int i = 0; i < virtualThreadCount; i++) {
        virtualExecutor.submit(() -> {
          try {
            while (System.currentTimeMillis() < endTimeMs && !Thread.currentThread().isInterrupted()) {
              long startTime = System.currentTimeMillis();
              try {
                task.call();
                long operationDuration = System.currentTimeMillis() - startTime;
                virtualMetrics.recordOperation(operationDuration);
              }
              catch (Exception e) {
                virtualMetrics.recordError();
              }
            }
          }
          finally {
            virtualLatch.countDown();
          }
          return null;
        });
      }

      virtualLatch.await();
      results.put("virtual", virtualMetrics);
    }

    return results;
  }

  /**
   * Executes a task with increasing concurrency levels and returns performance metrics for each level.
   *
   * @param task Task to execute
   * @param startConcurrency Starting concurrency level
   * @param maxConcurrency Maximum concurrency level
   * @param step Step size for increasing concurrency
   * @param iterationsPerLevel Number of iterations at each concurrency level
   * @param useVirtualThreads Whether to use virtual threads
   * @return Map of concurrency level to performance metrics
   * @throws InterruptedException if the operation is interrupted
   */
  public static Map<Integer, PerformanceMetrics> executeConcurrencyTest(
      Callable<?> task,
      int startConcurrency,
      int maxConcurrency,
      int step,
      int iterationsPerLevel,
      boolean useVirtualThreads) throws InterruptedException {
    Map<Integer, PerformanceMetrics> results = new HashMap<>();

    for (int concurrency = startConcurrency; concurrency <= maxConcurrency; concurrency += step) {
      System.out.println("Testing with concurrency level: " + concurrency);

      ExecutorService executor = useVirtualThreads ?
          createVirtualThreadExecutor("concurrency-test-" + concurrency) :
          createPlatformThreadExecutor(concurrency, "concurrency-test-" + concurrency);

      try {
        PerformanceMetrics metrics = executeConcurrently(executor, task, concurrency * iterationsPerLevel, DEFAULT_TIMEOUT_SECONDS);
        results.put(concurrency, metrics);

        System.out.println("  " + metrics);
      }
      finally {
        executor.shutdown();
        executor.awaitTermination(1, MINUTES);
      }
    }

    return results;
  }

  /**
   * Executes a task with increasing concurrency levels using both platform and virtual threads,
   * and returns performance metrics for comparison.
   *
   * @param task Task to execute
   * @param startConcurrency Starting concurrency level
   * @param maxConcurrency Maximum concurrency level
   * @param step Step size for increasing concurrency
   * @param iterationsPerLevel Number of iterations at each concurrency level
   * @return Map of executor type to map of concurrency level to performance metrics
   * @throws InterruptedException if the operation is interrupted
   */
  public static Map<String, Map<Integer, PerformanceMetrics>> compareConcurrencyLevels(
      Callable<?> task,
      int startConcurrency,
      int maxConcurrency,
      int step,
      int iterationsPerLevel) throws InterruptedException {
    Map<String, Map<Integer, PerformanceMetrics>> results = new HashMap<>();

    System.out.println("\n=== Platform Thread Concurrency Test ===\n");
    results.put("platform", executeConcurrencyTest(task, startConcurrency, maxConcurrency, step, iterationsPerLevel, false));

    System.out.println("\n=== Virtual Thread Concurrency Test ===\n");
    results.put("virtual", executeConcurrencyTest(task, startConcurrency, maxConcurrency, step, iterationsPerLevel, true));

    return results;
  }

  /**
   * Checks if virtual thread testing is enabled via the test.virtual.threads system property.
   *
   * @return true if virtual thread testing is enabled, false otherwise
   */
  public static boolean isVirtualThreadTestingEnabled() {
    return Boolean.parseBoolean(System.getProperty(VIRTUAL_THREAD_PROPERTY, "false"));
  }

  /**
   * Runs a test only if virtual thread testing is enabled.
   *
   * @param test Test to run
   * @param fallbackMessage Message to print if virtual thread testing is disabled
   */
  public static void runIfVirtualThreadsEnabled(Runnable test, String fallbackMessage) {
    if (isVirtualThreadTestingEnabled()) {
      test.run();
    }
    else {
      System.out.println(fallbackMessage);
    }
  }

  /**
   * Runs a test with a result only if virtual thread testing is enabled.
   *
   * @param <T> Type of the result
   * @param test Test to run
   * @param fallbackValue Value to return if virtual thread testing is disabled
   * @param fallbackMessage Message to print if virtual thread testing is disabled
   * @return Result of the test or fallback value
   */
  public static <T> T runIfVirtualThreadsEnabled(Supplier<T> test, T fallbackValue, String fallbackMessage) {
    if (isVirtualThreadTestingEnabled()) {
      return test.get();
    }
    else {
      System.out.println(fallbackMessage);
      return fallbackValue;
    }
  }
}