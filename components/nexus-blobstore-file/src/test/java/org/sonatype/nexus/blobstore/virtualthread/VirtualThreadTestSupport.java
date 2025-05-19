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
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;

import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.blobstore.api.BlobStore.BLOB_NAME_HEADER;
import static org.sonatype.nexus.blobstore.api.BlobStore.CREATED_BY_HEADER;

/**
 * Support class for testing BlobStore implementations with Java 21 Virtual Threads.
 * Provides utilities for creating thread factories, measuring performance, and executing
 * concurrent blob operations.
 *
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);

  /**
   * System property to enable/disable virtual thread testing.
   */
  public static final String VIRTUAL_THREADS_ENABLED_PROPERTY = "test.virtual.threads";

  /**
   * Default headers for test blobs.
   */
  public static final ImmutableMap<String, String> TEST_HEADERS = ImmutableMap.of(
      CREATED_BY_HEADER, "virtual-thread-test",
      BLOB_NAME_HEADER, "test/virtualThreadData.bin");

  /**
   * Default size for test blob content.
   */
  public static final int DEFAULT_BLOB_SIZE = 4096;

  /**
   * Default timeout for concurrent operations.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Memory MX bean for memory usage tracking.
   */
  private static final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

  /**
   * Random number generator for creating test data.
   */
  private final Random random = new Random();

  /**
   * Checks if virtual thread testing is enabled via system property.
   *
   * @return true if virtual thread testing is enabled
   */
  public static boolean isVirtualThreadsEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_ENABLED_PROPERTY);
  }

  /**
   * Creates a platform thread factory with the specified name prefix.
   *
   * @param namePrefix prefix for thread names
   * @return a platform thread factory
   */
  public ThreadFactory createPlatformThreadFactory(final String namePrefix) {
    checkNotNull(namePrefix);
    return Thread.ofPlatform().name(namePrefix + "-", 0).factory();
  }

  /**
   * Creates a virtual thread factory with the specified name prefix.
   *
   * @param namePrefix prefix for thread names
   * @return a virtual thread factory
   */
  public ThreadFactory createVirtualThreadFactory(final String namePrefix) {
    checkNotNull(namePrefix);
    return Thread.ofVirtual().name(namePrefix + "-", 0).factory();
  }

  /**
   * Creates an executor service using platform threads.
   *
   * @param namePrefix prefix for thread names
   * @param threadCount number of threads in the pool
   * @return an executor service using platform threads
   */
  public ExecutorService createPlatformThreadExecutor(final String namePrefix, final int threadCount) {
    checkNotNull(namePrefix);
    checkArgument(threadCount > 0, "Thread count must be positive");
    return Executors.newFixedThreadPool(threadCount, createPlatformThreadFactory(namePrefix));
  }

  /**
   * Creates an executor service using virtual threads.
   *
   * @param namePrefix prefix for thread names
   * @return an executor service using virtual threads
   */
  public ExecutorService createVirtualThreadExecutor(final String namePrefix) {
    checkNotNull(namePrefix);
    return Executors.newThreadPerTaskExecutor(createVirtualThreadFactory(namePrefix));
  }

  /**
   * Creates an appropriate executor service based on whether virtual threads are enabled.
   *
   * @param namePrefix prefix for thread names
   * @param threadCount number of threads in the pool (used only for platform threads)
   * @return an executor service using either virtual or platform threads
   */
  public ExecutorService createExecutor(final String namePrefix, final int threadCount) {
    if (isVirtualThreadsEnabled()) {
      log.info("Creating virtual thread executor with prefix: {}", namePrefix);
      return createVirtualThreadExecutor(namePrefix);
    }
    else {
      log.info("Creating platform thread executor with prefix: {} and {} threads", namePrefix, threadCount);
      return createPlatformThreadExecutor(namePrefix, threadCount);
    }
  }

  /**
   * Generates random byte content for test blobs.
   *
   * @param size size of the content in bytes
   * @return random byte array
   */
  public byte[] generateRandomContent(final int size) {
    byte[] content = new byte[size];
    random.nextBytes(content);
    return content;
  }

  /**
   * Creates a test blob in the specified blob store.
   *
   * @param blobStore the blob store to create the blob in
   * @param size size of the blob content in bytes
   * @param headers headers for the blob
   * @return the created blob
   */
  public Blob createBlob(final BlobStore blobStore, final int size, final Map<String, String> headers) {
    checkNotNull(blobStore);
    checkArgument(size > 0, "Blob size must be positive");
    checkNotNull(headers);

    byte[] content = generateRandomContent(size);
    return blobStore.create(new ByteArrayInputStream(content), headers);
  }

  /**
   * Creates a test blob in the specified blob store with default headers.
   *
   * @param blobStore the blob store to create the blob in
   * @param size size of the blob content in bytes
   * @return the created blob
   */
  public Blob createBlob(final BlobStore blobStore, final int size) {
    return createBlob(blobStore, size, TEST_HEADERS);
  }

  /**
   * Creates a test blob in the specified blob store with default size and headers.
   *
   * @param blobStore the blob store to create the blob in
   * @return the created blob
   */
  public Blob createBlob(final BlobStore blobStore) {
    return createBlob(blobStore, DEFAULT_BLOB_SIZE, TEST_HEADERS);
  }

  /**
   * Reads the entire content of a blob.
   *
   * @param blob the blob to read
   * @throws IOException if an I/O error occurs
   */
  public void readBlob(final Blob blob) throws IOException {
    checkNotNull(blob);

    try (InputStream is = blob.getInputStream()) {
      byte[] buffer = new byte[8192];
      while (is.read(buffer) != -1) {
        // Just read the data
      }
    }
  }

  /**
   * Executes a blob operation concurrently using the specified executor.
   *
   * @param executor the executor service to use
   * @param count the number of concurrent operations to execute
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return a list of futures for the operations
   */
  public <T> List<Future<T>> executeConcurrently(
      final ExecutorService executor,
      final int count,
      final Callable<T> operation)
  {
    checkNotNull(executor);
    checkArgument(count > 0, "Count must be positive");
    checkNotNull(operation);

    List<Future<T>> futures = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      futures.add(executor.submit(operation));
    }
    return futures;
  }

  /**
   * Executes a blob operation concurrently using the specified executor and waits for completion.
   *
   * @param executor the executor service to use
   * @param count the number of concurrent operations to execute
   * @param operation the operation to execute
   * @param timeout the maximum time to wait for completion
   * @param <T> the return type of the operation
   * @return a list of results from the operations
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public <T> List<T> executeConcurrentlyAndWait(
      final ExecutorService executor,
      final int count,
      final Callable<T> operation,
      final Duration timeout)
      throws Exception
  {
    List<Future<T>> futures = executeConcurrently(executor, count, operation);
    List<T> results = new ArrayList<>(count);

    for (Future<T> future : futures) {
      results.add(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
    }

    return results;
  }

  /**
   * Executes a blob operation concurrently using the specified executor and waits for completion
   * with the default timeout.
   *
   * @param executor the executor service to use
   * @param count the number of concurrent operations to execute
   * @param operation the operation to execute
   * @param <T> the return type of the operation
   * @return a list of results from the operations
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public <T> List<T> executeConcurrentlyAndWait(
      final ExecutorService executor,
      final int count,
      final Callable<T> operation)
      throws Exception
  {
    return executeConcurrentlyAndWait(executor, count, operation, DEFAULT_TIMEOUT);
  }

  /**
   * Creates multiple blobs concurrently in the specified blob store.
   *
   * @param blobStore the blob store to create blobs in
   * @param executor the executor service to use
   * @param count the number of blobs to create
   * @param size the size of each blob in bytes
   * @return a list of created blobs
   * @throws Exception if any blob creation fails or the timeout is exceeded
   */
  public List<Blob> createBlobsConcurrently(
      final BlobStore blobStore,
      final ExecutorService executor,
      final int count,
      final int size)
      throws Exception
  {
    checkNotNull(blobStore);
    checkNotNull(executor);
    checkArgument(count > 0, "Count must be positive");
    checkArgument(size > 0, "Size must be positive");

    return executeConcurrentlyAndWait(executor, count, () -> {
      Map<String, String> headers = ImmutableMap.of(
          CREATED_BY_HEADER, "virtual-thread-test",
          BLOB_NAME_HEADER, "test/" + UUID.randomUUID() + ".bin");
      return createBlob(blobStore, size, headers);
    });
  }

  /**
   * Reads multiple blobs concurrently from the specified blob store.
   *
   * @param blobStore the blob store to read blobs from
   * @param executor the executor service to use
   * @param blobIds the IDs of the blobs to read
   * @throws Exception if any blob read fails or the timeout is exceeded
   */
  public void readBlobsConcurrently(
      final BlobStore blobStore,
      final ExecutorService executor,
      final List<BlobId> blobIds)
      throws Exception
  {
    checkNotNull(blobStore);
    checkNotNull(executor);
    checkNotNull(blobIds);
    checkArgument(!blobIds.isEmpty(), "Blob IDs list must not be empty");

    executeConcurrentlyAndWait(executor, blobIds.size(), () -> {
      BlobId blobId = blobIds.get(random.nextInt(blobIds.size()));
      Blob blob = blobStore.get(blobId);
      if (blob != null) {
        readBlob(blob);
      }
      return null;
    });
  }

  /**
   * Deletes multiple blobs concurrently from the specified blob store.
   *
   * @param blobStore the blob store to delete blobs from
   * @param executor the executor service to use
   * @param blobIds the IDs of the blobs to delete
   * @return a list of boolean values indicating whether each blob was deleted
   * @throws Exception if any blob deletion fails or the timeout is exceeded
   */
  public List<Boolean> deleteBlobsConcurrently(
      final BlobStore blobStore,
      final ExecutorService executor,
      final List<BlobId> blobIds)
      throws Exception
  {
    checkNotNull(blobStore);
    checkNotNull(executor);
    checkNotNull(blobIds);
    checkArgument(!blobIds.isEmpty(), "Blob IDs list must not be empty");

    return executeConcurrentlyAndWait(executor, blobIds.size(), () -> {
      BlobId blobId = blobIds.get(random.nextInt(blobIds.size()));
      return blobStore.delete(blobId, "virtual-thread-test");
    });
  }

  /**
   * Measures the execution time of an operation.
   *
   * @param operation the operation to measure
   * @param <T> the return type of the operation
   * @return a pair containing the result of the operation and the execution time in milliseconds
   * @throws Exception if the operation fails
   */
  public <T> OperationResult<T> measureExecutionTime(final Callable<T> operation) throws Exception {
    checkNotNull(operation);

    long startTime = System.nanoTime();
    T result = operation.call();
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    return new OperationResult<>(result, durationMs);
  }

  /**
   * Measures the throughput of a concurrent operation.
   *
   * @param executor the executor service to use
   * @param concurrency the number of concurrent operations
   * @param operation the operation to measure
   * @param <T> the return type of the operation
   * @return a throughput result containing metrics about the operation
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public <T> ThroughputResult<T> measureThroughput(
      final ExecutorService executor,
      final int concurrency,
      final Callable<T> operation)
      throws Exception
  {
    checkNotNull(executor);
    checkArgument(concurrency > 0, "Concurrency must be positive");
    checkNotNull(operation);

    // Capture memory usage before the test
    long initialMemory = memoryMXBean.getHeapMemoryUsage().getUsed();

    // Create a latch to synchronize the start of all operations
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a latch to track completion
    CountDownLatch completionLatch = new CountDownLatch(concurrency);
    
    // Track operation results and timing
    List<T> results = new ArrayList<>(concurrency);
    List<Long> durations = new ArrayList<>(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit all operations
    long startTime = System.nanoTime();
    
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Measure the individual operation
          long opStartTime = System.nanoTime();
          T result = operation.call();
          long opEndTime = System.nanoTime();
          
          // Record the result and duration
          synchronized (results) {
            results.add(result);
            durations.add(TimeUnit.NANOSECONDS.toMillis(opEndTime - opStartTime));
          }
        }
        catch (Exception e) {
          log.error("Error in concurrent operation", e);
          errorCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all operations simultaneously
    startLatch.countDown();
    
    // Wait for all operations to complete
    boolean completed = completionLatch.await(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    long endTime = System.nanoTime();
    
    // Capture memory usage after the test
    long finalMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    long memoryDelta = finalMemory - initialMemory;
    
    // Calculate metrics
    long totalDurationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
    double operationsPerSecond = (double) results.size() / (totalDurationMs / 1000.0);
    
    // Calculate percentiles
    durations.sort(Long::compareTo);
    long p50 = percentile(durations, 50);
    long p95 = percentile(durations, 95);
    long p99 = percentile(durations, 99);
    
    return new ThroughputResult<>(
        results,
        concurrency,
        completed,
        errorCount.get(),
        totalDurationMs,
        operationsPerSecond,
        p50,
        p95,
        p99,
        memoryDelta
    );
  }

  /**
   * Compares the throughput of an operation between platform and virtual threads.
   *
   * @param concurrency the number of concurrent operations
   * @param platformThreadCount the number of platform threads to use
   * @param operation the operation to measure
   * @param <T> the return type of the operation
   * @return a comparison result containing metrics for both thread types
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public <T> ThreadModelComparisonResult<T> compareThreadModels(
      final int concurrency,
      final int platformThreadCount,
      final Callable<T> operation)
      throws Exception
  {
    checkArgument(concurrency > 0, "Concurrency must be positive");
    checkArgument(platformThreadCount > 0, "Platform thread count must be positive");
    checkNotNull(operation);

    // Create executors for both thread models
    ExecutorService platformExecutor = createPlatformThreadExecutor("platform-test", platformThreadCount);
    ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-test");

    try {
      // Measure throughput with platform threads
      log.info("Measuring throughput with platform threads (count: {})", platformThreadCount);
      ThroughputResult<T> platformResult = measureThroughput(platformExecutor, concurrency, operation);

      // Measure throughput with virtual threads
      log.info("Measuring throughput with virtual threads");
      ThroughputResult<T> virtualResult = measureThroughput(virtualExecutor, concurrency, operation);

      // Calculate improvement ratios
      double throughputImprovement = virtualResult.getOperationsPerSecond() / platformResult.getOperationsPerSecond();
      double p50Improvement = (double) platformResult.getP50LatencyMs() / virtualResult.getP50LatencyMs();
      double p95Improvement = (double) platformResult.getP95LatencyMs() / virtualResult.getP95LatencyMs();
      double p99Improvement = (double) platformResult.getP99LatencyMs() / virtualResult.getP99LatencyMs();
      double memoryEfficiency = (double) platformResult.getMemoryDeltaBytes() / virtualResult.getMemoryDeltaBytes();

      return new ThreadModelComparisonResult<>(
          platformResult,
          virtualResult,
          throughputImprovement,
          p50Improvement,
          p95Improvement,
          p99Improvement,
          memoryEfficiency
      );
    }
    finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
    }
  }

  /**
   * Executes a blob operation with increasing concurrency levels to measure scalability.
   *
   * @param maxConcurrency the maximum concurrency level to test
   * @param step the step size between concurrency levels
   * @param operation the operation to measure
   * @param <T> the return type of the operation
   * @return a map of concurrency levels to throughput results
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public <T> Map<Integer, ThroughputResult<T>> measureScalability(
      final int maxConcurrency,
      final int step,
      final Callable<T> operation)
      throws Exception
  {
    checkArgument(maxConcurrency > 0, "Max concurrency must be positive");
    checkArgument(step > 0, "Step must be positive");
    checkNotNull(operation);

    Map<Integer, ThroughputResult<T>> results = new ConcurrentHashMap<>();
    ExecutorService executor = createExecutor("scalability-test", maxConcurrency);

    try {
      for (int concurrency = step; concurrency <= maxConcurrency; concurrency += step) {
        log.info("Measuring throughput at concurrency level: {}", concurrency);
        ThroughputResult<T> result = measureThroughput(executor, concurrency, operation);
        results.put(concurrency, result);

        // If we encountered errors, stop increasing concurrency
        if (result.getErrorCount() > 0) {
          log.warn("Stopping scalability test due to errors at concurrency level: {}", concurrency);
          break;
        }
      }

      return results;
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Executes multiple operations concurrently using CompletableFuture and virtual threads.
   *
   * @param operations the operations to execute
   * @param <T> the return type of the operations
   * @return a list of results from the operations
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public <T> List<T> executeAsyncOperations(final List<Supplier<T>> operations) throws Exception {
    checkNotNull(operations);
    checkArgument(!operations.isEmpty(), "Operations list must not be empty");

    // Create a virtual thread executor
    ExecutorService executor = createVirtualThreadExecutor("async-ops");

    try {
      // Convert each operation to a CompletableFuture
      List<CompletableFuture<T>> futures = operations.stream()
          .map(op -> CompletableFuture.supplyAsync(op, executor))
          .toList();

      // Combine all futures into a single future that completes when all operations complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0]));

      // Wait for all operations to complete
      allFutures.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

      // Collect and return the results
      return futures.stream()
          .map(CompletableFuture::join)
          .toList();
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Executes a blob operation with a mix of read and write operations to simulate real-world usage.
   *
   * @param blobStore the blob store to use
   * @param readPct the percentage of read operations (0-100)
   * @param writePct the percentage of write operations (0-100)
   * @param deletePct the percentage of delete operations (0-100)
   * @param concurrency the number of concurrent operations
   * @param duration the duration to run the test
   * @return a summary of the mixed workload test
   * @throws Exception if any operation fails or the timeout is exceeded
   */
  public MixedWorkloadResult executeMixedWorkload(
      final BlobStore blobStore,
      final int readPct,
      final int writePct,
      final int deletePct,
      final int concurrency,
      final Duration duration)
      throws Exception
  {
    checkNotNull(blobStore);
    checkArgument(readPct >= 0 && readPct <= 100, "Read percentage must be between 0 and 100");
    checkArgument(writePct >= 0 && writePct <= 100, "Write percentage must be between 0 and 100");
    checkArgument(deletePct >= 0 && deletePct <= 100, "Delete percentage must be between 0 and 100");
    checkArgument(readPct + writePct + deletePct == 100, "Percentages must sum to 100");
    checkArgument(concurrency > 0, "Concurrency must be positive");
    checkNotNull(duration);

    // Create a pool of blob IDs to read from and delete
    List<BlobId> blobIds = new ArrayList<>();
    
    // Create some initial blobs
    int initialBlobCount = Math.max(100, concurrency);
    for (int i = 0; i < initialBlobCount; i++) {
      Blob blob = createBlob(blobStore);
      blobIds.add(blob.getId());
    }
    
    // Create executor
    ExecutorService executor = createExecutor("mixed-workload", concurrency);
    
    // Track operation counts
    AtomicLong readCount = new AtomicLong(0);
    AtomicLong writeCount = new AtomicLong(0);
    AtomicLong deleteCount = new AtomicLong(0);
    AtomicLong errorCount = new AtomicLong(0);
    
    // Create a latch for workers to signal completion
    CountDownLatch completionLatch = new CountDownLatch(concurrency);
    
    // Flag to signal workers to stop
    AtomicInteger running = new AtomicInteger(concurrency);
    
    // Start the timer
    long startTime = System.currentTimeMillis();
    long endTime = startTime + duration.toMillis();
    
    // Start worker threads
    for (int i = 0; i < concurrency; i++) {
      executor.submit(() -> {
        try {
          while (System.currentTimeMillis() < endTime && running.get() > 0) {
            // Determine operation type based on percentages
            int opType = random.nextInt(100);
            
            try {
              if (opType < readPct) {
                // Read operation
                synchronized (blobIds) {
                  if (!blobIds.isEmpty()) {
                    BlobId blobId = blobIds.get(random.nextInt(blobIds.size()));
                    Blob blob = blobStore.get(blobId);
                    if (blob != null) {
                      readBlob(blob);
                      readCount.incrementAndGet();
                    }
                  }
                }
              }
              else if (opType < readPct + writePct) {
                // Write operation
                Blob blob = createBlob(blobStore);
                synchronized (blobIds) {
                  blobIds.add(blob.getId());
                }
                writeCount.incrementAndGet();
              }
              else {
                // Delete operation
                synchronized (blobIds) {
                  if (!blobIds.isEmpty()) {
                    int index = random.nextInt(blobIds.size());
                    BlobId blobId = blobIds.get(index);
                    boolean deleted = blobStore.delete(blobId, "mixed-workload-test");
                    if (deleted) {
                      blobIds.remove(index);
                      deleteCount.incrementAndGet();
                    }
                  }
                }
              }
            }
            catch (Exception e) {
              log.error("Error in mixed workload operation", e);
              errorCount.incrementAndGet();
            }
          }
        }
        finally {
          completionLatch.countDown();
          running.decrementAndGet();
        }
      });
    }
    
    // Wait for completion
    completionLatch.await();
    long actualDuration = System.currentTimeMillis() - startTime;
    
    // Calculate operations per second
    double totalOps = readCount.get() + writeCount.get() + deleteCount.get();
    double opsPerSecond = totalOps / (actualDuration / 1000.0);
    
    return new MixedWorkloadResult(
        readCount.get(),
        writeCount.get(),
        deleteCount.get(),
        errorCount.get(),
        actualDuration,
        opsPerSecond
    );
  }

  /**
   * Calculates a percentile value from a sorted list of measurements.
   *
   * @param sortedValues the sorted list of values
   * @param percentile the percentile to calculate (0-100)
   * @return the percentile value
   */
  private long percentile(final List<Long> sortedValues, final int percentile) {
    if (sortedValues.isEmpty()) {
      return 0;
    }
    
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
  }

  /**
   * Result of a single operation execution with timing information.
   *
   * @param <T> the type of the operation result
   */
  public static class OperationResult<T> {
    private final T result;
    private final long durationMs;

    public OperationResult(final T result, final long durationMs) {
      this.result = result;
      this.durationMs = durationMs;
    }

    public T getResult() {
      return result;
    }

    public long getDurationMs() {
      return durationMs;
    }
  }

  /**
   * Result of a throughput measurement with detailed metrics.
   *
   * @param <T> the type of the operation results
   */
  public static class ThroughputResult<T> {
    private final List<T> results;
    private final int concurrency;
    private final boolean completed;
    private final int errorCount;
    private final long totalDurationMs;
    private final double operationsPerSecond;
    private final long p50LatencyMs;
    private final long p95LatencyMs;
    private final long p99LatencyMs;
    private final long memoryDeltaBytes;

    public ThroughputResult(
        final List<T> results,
        final int concurrency,
        final boolean completed,
        final int errorCount,
        final long totalDurationMs,
        final double operationsPerSecond,
        final long p50LatencyMs,
        final long p95LatencyMs,
        final long p99LatencyMs,
        final long memoryDeltaBytes)
    {
      this.results = results;
      this.concurrency = concurrency;
      this.completed = completed;
      this.errorCount = errorCount;
      this.totalDurationMs = totalDurationMs;
      this.operationsPerSecond = operationsPerSecond;
      this.p50LatencyMs = p50LatencyMs;
      this.p95LatencyMs = p95LatencyMs;
      this.p99LatencyMs = p99LatencyMs;
      this.memoryDeltaBytes = memoryDeltaBytes;
    }

    public List<T> getResults() {
      return results;
    }

    public int getConcurrency() {
      return concurrency;
    }

    public boolean isCompleted() {
      return completed;
    }

    public int getErrorCount() {
      return errorCount;
    }

    public long getTotalDurationMs() {
      return totalDurationMs;
    }

    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }

    public long getP50LatencyMs() {
      return p50LatencyMs;
    }

    public long getP95LatencyMs() {
      return p95LatencyMs;
    }

    public long getP99LatencyMs() {
      return p99LatencyMs;
    }

    public long getMemoryDeltaBytes() {
      return memoryDeltaBytes;
    }

    @Override
    public String toString() {
      return String.format(
          "ThroughputResult{concurrency=%d, completed=%s, errorCount=%d, totalDurationMs=%d, " +
              "operationsPerSecond=%.2f, p50LatencyMs=%d, p95LatencyMs=%d, p99LatencyMs=%d, " +
              "memoryDeltaBytes=%d}",
          concurrency, completed, errorCount, totalDurationMs, operationsPerSecond,
          p50LatencyMs, p95LatencyMs, p99LatencyMs, memoryDeltaBytes);
    }
  }

  /**
   * Result of a thread model comparison with improvement metrics.
   *
   * @param <T> the type of the operation results
   */
  public static class ThreadModelComparisonResult<T> {
    private final ThroughputResult<T> platformResult;
    private final ThroughputResult<T> virtualResult;
    private final double throughputImprovement;
    private final double p50Improvement;
    private final double p95Improvement;
    private final double p99Improvement;
    private final double memoryEfficiency;

    public ThreadModelComparisonResult(
        final ThroughputResult<T> platformResult,
        final ThroughputResult<T> virtualResult,
        final double throughputImprovement,
        final double p50Improvement,
        final double p95Improvement,
        final double p99Improvement,
        final double memoryEfficiency)
    {
      this.platformResult = platformResult;
      this.virtualResult = virtualResult;
      this.throughputImprovement = throughputImprovement;
      this.p50Improvement = p50Improvement;
      this.p95Improvement = p95Improvement;
      this.p99Improvement = p99Improvement;
      this.memoryEfficiency = memoryEfficiency;
    }

    public ThroughputResult<T> getPlatformResult() {
      return platformResult;
    }

    public ThroughputResult<T> getVirtualResult() {
      return virtualResult;
    }

    public double getThroughputImprovement() {
      return throughputImprovement;
    }

    public double getP50Improvement() {
      return p50Improvement;
    }

    public double getP95Improvement() {
      return p95Improvement;
    }

    public double getP99Improvement() {
      return p99Improvement;
    }

    public double getMemoryEfficiency() {
      return memoryEfficiency;
    }

    @Override
    public String toString() {
      return String.format(
          "ThreadModelComparisonResult{throughputImprovement=%.2fx, p50Improvement=%.2fx, " +
              "p95Improvement=%.2fx, p99Improvement=%.2fx, memoryEfficiency=%.2fx}",
          throughputImprovement, p50Improvement, p95Improvement, p99Improvement, memoryEfficiency);
    }

    /**
     * Logs a detailed comparison report.
     *
     * @param logger the logger to use
     */
    public void logDetailedReport(final Logger logger) {
      logger.info("Thread Model Comparison Report:");
      logger.info("--------------------------------");
      logger.info("Platform Threads: {} concurrent operations", platformResult.getConcurrency());
      logger.info("  - Operations/sec: {}", String.format("%.2f", platformResult.getOperationsPerSecond()));
      logger.info("  - P50 Latency: {} ms", platformResult.getP50LatencyMs());
      logger.info("  - P95 Latency: {} ms", platformResult.getP95LatencyMs());
      logger.info("  - P99 Latency: {} ms", platformResult.getP99LatencyMs());
      logger.info("  - Memory Delta: {} bytes", platformResult.getMemoryDeltaBytes());
      logger.info("  - Error Count: {}", platformResult.getErrorCount());
      logger.info("Virtual Threads: {} concurrent operations", virtualResult.getConcurrency());
      logger.info("  - Operations/sec: {}", String.format("%.2f", virtualResult.getOperationsPerSecond()));
      logger.info("  - P50 Latency: {} ms", virtualResult.getP50LatencyMs());
      logger.info("  - P95 Latency: {} ms", virtualResult.getP95LatencyMs());
      logger.info("  - P99 Latency: {} ms", virtualResult.getP99LatencyMs());
      logger.info("  - Memory Delta: {} bytes", virtualResult.getMemoryDeltaBytes());
      logger.info("  - Error Count: {}", virtualResult.getErrorCount());
      logger.info("Improvement Metrics:");
      logger.info("  - Throughput: {}x", String.format("%.2f", throughputImprovement));
      logger.info("  - P50 Latency: {}x", String.format("%.2f", p50Improvement));
      logger.info("  - P95 Latency: {}x", String.format("%.2f", p95Improvement));
      logger.info("  - P99 Latency: {}x", String.format("%.2f", p99Improvement));
      logger.info("  - Memory Efficiency: {}x", String.format("%.2f", memoryEfficiency));
    }
  }

  /**
   * Result of a mixed workload test with operation counts and rates.
   */
  public static class MixedWorkloadResult {
    private final long readCount;
    private final long writeCount;
    private final long deleteCount;
    private final long errorCount;
    private final long durationMs;
    private final double operationsPerSecond;

    public MixedWorkloadResult(
        final long readCount,
        final long writeCount,
        final long deleteCount,
        final long errorCount,
        final long durationMs,
        final double operationsPerSecond)
    {
      this.readCount = readCount;
      this.writeCount = writeCount;
      this.deleteCount = deleteCount;
      this.errorCount = errorCount;
      this.durationMs = durationMs;
      this.operationsPerSecond = operationsPerSecond;
    }

    public long getReadCount() {
      return readCount;
    }

    public long getWriteCount() {
      return writeCount;
    }

    public long getDeleteCount() {
      return deleteCount;
    }

    public long getErrorCount() {
      return errorCount;
    }

    public long getDurationMs() {
      return durationMs;
    }

    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }

    public long getTotalOperations() {
      return readCount + writeCount + deleteCount;
    }

    @Override
    public String toString() {
      return String.format(
          "MixedWorkloadResult{readCount=%d, writeCount=%d, deleteCount=%d, errorCount=%d, " +
              "durationMs=%d, operationsPerSecond=%.2f}",
          readCount, writeCount, deleteCount, errorCount, durationMs, operationsPerSecond);
    }

    /**
     * Logs a detailed workload report.
     *
     * @param logger the logger to use
     */
    public void logDetailedReport(final Logger logger) {
      logger.info("Mixed Workload Report:");
      logger.info("----------------------");
      logger.info("Duration: {} ms", durationMs);
      logger.info("Total Operations: {}", getTotalOperations());
      logger.info("Operations/sec: {}", String.format("%.2f", operationsPerSecond));
      logger.info("Read Operations: {} ({}%)", readCount, 
          String.format("%.1f", (double) readCount / getTotalOperations() * 100));
      logger.info("Write Operations: {} ({}%)", writeCount, 
          String.format("%.1f", (double) writeCount / getTotalOperations() * 100));
      logger.info("Delete Operations: {} ({}%)", deleteCount, 
          String.format("%.1f", (double) deleteCount / getTotalOperations() * 100));
      logger.info("Error Count: {}", errorCount);
    }
  }
}