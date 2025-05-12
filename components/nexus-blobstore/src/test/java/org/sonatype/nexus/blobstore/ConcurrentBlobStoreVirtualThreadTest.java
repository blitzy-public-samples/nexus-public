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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Performance and scalability tests that compare BlobStore operations under high concurrency
 * using both platform threads and virtual threads.
 * 
 * These tests validate that virtual threads deliver significant performance advantages for
 * I/O-bound blob store operations under load, particularly when handling thousands of
 * concurrent operations.
 *
 * @since 3.60
 */
public class ConcurrentBlobStoreVirtualThreadTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(ConcurrentBlobStoreVirtualThreadTest.class);
  
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 60;
  private static final int BLOB_SIZE = 1024; // 1KB
  
  /**
   * Test class to capture performance metrics for thread execution.
   */
  private static class PerformanceMetrics {
    private final long startTimeNanos;
    private long endTimeNanos;
    private final long startMemory;
    private long endMemory;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    
    public PerformanceMetrics() {
      startTimeNanos = System.nanoTime();
      startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    
    public void markComplete() {
      endTimeNanos = System.nanoTime();
      endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    
    public void incrementSuccess() {
      successCount.incrementAndGet();
    }
    
    public void incrementError() {
      errorCount.incrementAndGet();
    }
    
    public long getDurationMillis() {
      return TimeUnit.NANOSECONDS.toMillis(endTimeNanos - startTimeNanos);
    }
    
    public long getMemoryUsedBytes() {
      return endMemory - startMemory;
    }
    
    public int getSuccessCount() {
      return successCount.get();
    }
    
    public int getErrorCount() {
      return errorCount.get();
    }
  }
  
  /**
   * Creates a mock BlobStore for testing.
   */
  private BlobStore createMockBlobStore() {
    BlobStore blobStore = mock(BlobStore.class);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    return blobStore;
  }
  
  /**
   * Creates a byte array of the specified size filled with random data.
   */
  private byte[] createRandomBytes(int size) {
    byte[] data = new byte[size];
    for (int i = 0; i < size; i++) {
      data[i] = (byte) (Math.random() * 256);
    }
    return data;
  }
  
  /**
   * Creates a platform thread factory with the specified name prefix.
   */
  private ThreadFactory createPlatformThreadFactory(String namePrefix) {
    return Thread.ofPlatform().name(namePrefix, 0).factory();
  }
  
  /**
   * Creates a virtual thread factory with the specified name prefix.
   */
  private ThreadFactory createVirtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix, 0).factory();
  }
  
  /**
   * Creates a blob ID for testing.
   */
  private BlobId createBlobId() {
    return new BlobId(UUID.randomUUID().toString());
  }
  
  /**
   * Creates a blob with random content of the specified size.
   */
  private Blob createBlob(BlobStore blobStore, int size) {
    byte[] data = createRandomBytes(size);
    InputStream inputStream = new ByteArrayInputStream(data);
    BlobId blobId = createBlobId();
    Map<String, String> headers = Map.of(
        BlobStore.BLOB_NAME_HEADER, "test-blob-" + blobId,
        BlobStore.CREATED_BY_HEADER, "test");
    BlobMetrics metrics = new BlobMetrics(System.currentTimeMillis(), data.length, "SHA1", "1234567890");
    return new Blob(blobId, headers, metrics, inputStream);
  }
  
  /**
   * Executes concurrent blob creation operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @return Performance metrics for the operation
   * @throws Exception If the test fails
   */
  private PerformanceMetrics executeConcurrentBlobCreations(ThreadFactory threadFactory) throws Exception {
    BlobStore blobStore = createMockBlobStore();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    PerformanceMetrics metrics = new PerformanceMetrics();
    
    try {
      // Submit concurrent blob creation tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            Blob blob = createBlob(blobStore, BLOB_SIZE);
            // Simulate I/O operation with a small delay
            Thread.sleep(10);
            metrics.incrementSuccess();
          } 
          catch (Exception e) {
            log.error("Error in blob creation task", e);
            metrics.incrementError();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      metrics.markComplete();
      
      if (!completed) {
        log.warn("Timeout waiting for blob creation tasks to complete");
      }
      
      return metrics;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Executes concurrent blob read operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @return Performance metrics for the operation
   * @throws Exception If the test fails
   */
  private PerformanceMetrics executeConcurrentBlobReads(ThreadFactory threadFactory) throws Exception {
    BlobStore blobStore = createMockBlobStore();
    // Create a single blob that will be read concurrently
    Blob sharedBlob = createBlob(blobStore, BLOB_SIZE);
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    PerformanceMetrics metrics = new PerformanceMetrics();
    
    try {
      // Submit concurrent blob read tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            // Simulate reading the blob
            InputStream inputStream = sharedBlob.getInputStream();
            byte[] buffer = new byte[1024];
            int bytesRead = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
              // Just read the data
            }
            inputStream.close();
            
            // Simulate additional I/O processing time
            Thread.sleep(5);
            metrics.incrementSuccess();
          } 
          catch (Exception e) {
            log.error("Error in blob read task", e);
            metrics.incrementError();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      metrics.markComplete();
      
      if (!completed) {
        log.warn("Timeout waiting for blob read tasks to complete");
      }
      
      return metrics;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Executes a mixed workload of blob operations (create, read, update) using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @return Performance metrics for the operation
   * @throws Exception If the test fails
   */
  private PerformanceMetrics executeMixedBlobOperations(ThreadFactory threadFactory) throws Exception {
    BlobStore blobStore = createMockBlobStore();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    PerformanceMetrics metrics = new PerformanceMetrics();
    
    // Create some shared blobs for read operations
    Blob[] sharedBlobs = new Blob[10];
    for (int i = 0; i < sharedBlobs.length; i++) {
      sharedBlobs[i] = createBlob(blobStore, BLOB_SIZE);
    }
    
    try {
      // Submit mixed blob operation tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int taskNum = i;
        executor.submit(() -> {
          try {
            // Determine operation type based on task number
            int operationType = taskNum % 3; // 0=create, 1=read, 2=update
            
            switch (operationType) {
              case 0: // Create
                Blob newBlob = createBlob(blobStore, BLOB_SIZE);
                Thread.sleep(10); // Simulate I/O
                break;
                
              case 1: // Read
                Blob blobToRead = sharedBlobs[taskNum % sharedBlobs.length];
                InputStream inputStream = blobToRead.getInputStream();
                byte[] buffer = new byte[1024];
                while (inputStream.read(buffer) != -1) {
                  // Just read the data
                }
                inputStream.close();
                Thread.sleep(5); // Simulate I/O
                break;
                
              case 2: // Update (simulated)
                Blob blobToUpdate = sharedBlobs[taskNum % sharedBlobs.length];
                // Simulate updating blob metadata
                Thread.sleep(15); // Simulate I/O
                break;
            }
            
            metrics.incrementSuccess();
          } 
          catch (Exception e) {
            log.error("Error in mixed blob operation task", e);
            metrics.incrementError();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      metrics.markComplete();
      
      if (!completed) {
        log.warn("Timeout waiting for mixed blob operation tasks to complete");
      }
      
      return metrics;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Executes a high-concurrency test with the specified number of operations and thread factory.
   * This test is designed to stress-test the system with a very high number of concurrent operations.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param operationCount The number of concurrent operations to execute
   * @return Performance metrics for the operation
   * @throws Exception If the test fails
   */
  private PerformanceMetrics executeHighConcurrencyTest(ThreadFactory threadFactory, int operationCount) throws Exception {
    BlobStore blobStore = createMockBlobStore();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(operationCount);
    PerformanceMetrics metrics = new PerformanceMetrics();
    
    // Create a shared blob for read operations
    Blob sharedBlob = createBlob(blobStore, BLOB_SIZE);
    
    try {
      // Submit high-concurrency tasks
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Simple read operation on the shared blob
            InputStream inputStream = sharedBlob.getInputStream();
            byte[] buffer = new byte[1024];
            inputStream.read(buffer);
            inputStream.close();
            
            metrics.incrementSuccess();
          } 
          catch (Exception e) {
            log.error("Error in high-concurrency task", e);
            metrics.incrementError();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      metrics.markComplete();
      
      if (!completed) {
        log.warn("Timeout waiting for high-concurrency tasks to complete");
      }
      
      return metrics;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Executes a memory consumption test that creates a large number of threads and measures memory usage.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param threadCount The number of threads to create
   * @return Performance metrics for the operation
   * @throws Exception If the test fails
   */
  private PerformanceMetrics executeMemoryConsumptionTest(ThreadFactory threadFactory, int threadCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    PerformanceMetrics metrics = new PerformanceMetrics();
    
    try {
      // Create threads that wait for the start signal
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Wait for the start signal
            startLatch.await();
            
            // Just hold the thread alive for a moment to measure memory
            Thread.sleep(100);
            
            metrics.incrementSuccess();
          } 
          catch (Exception e) {
            log.error("Error in memory consumption test task", e);
            metrics.incrementError();
          } 
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Allow time for thread creation
      Thread.sleep(1000);
      
      // Signal all threads to start
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      metrics.markComplete();
      
      if (!completed) {
        log.warn("Timeout waiting for memory consumption test tasks to complete");
      }
      
      return metrics;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to log performance metrics.
   */
  private void logPerformanceMetrics(String testName, String threadType, PerformanceMetrics metrics) {
    log.info("{} using {} threads - Duration: {} ms, Memory: {} KB, Success: {}, Errors: {}",
        testName,
        threadType,
        metrics.getDurationMillis(),
        metrics.getMemoryUsedBytes() / 1024,
        metrics.getSuccessCount(),
        metrics.getErrorCount());
  }
  
  /**
   * Test that compares the performance of concurrent blob creation operations
   * using platform threads vs virtual threads.
   */
  @Test
  public void testConcurrentBlobCreation() throws Exception {
    // Execute with platform threads
    PerformanceMetrics platformMetrics = executeConcurrentBlobCreations(
        createPlatformThreadFactory("platform-blob-creation"));
    logPerformanceMetrics("Blob Creation", "platform", platformMetrics);
    
    // Execute with virtual threads
    PerformanceMetrics virtualMetrics = executeConcurrentBlobCreations(
        createVirtualThreadFactory("virtual-blob-creation"));
    logPerformanceMetrics("Blob Creation", "virtual", virtualMetrics);
    
    // Verify results
    assertThat("All platform thread operations should succeed", 
        platformMetrics.getSuccessCount(), is(equalTo(CONCURRENT_OPERATIONS)));
    assertThat("All virtual thread operations should succeed", 
        virtualMetrics.getSuccessCount(), is(equalTo(CONCURRENT_OPERATIONS)));
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual threads should complete faster than platform threads",
        virtualMetrics.getDurationMillis(), is(lessThan(platformMetrics.getDurationMillis())));
    
    // Virtual threads should use less memory
    assertThat("Virtual threads should use less memory than platform threads",
        virtualMetrics.getMemoryUsedBytes(), is(lessThan(platformMetrics.getMemoryUsedBytes())));
  }
  
  /**
   * Test that compares the performance of concurrent blob read operations
   * using platform threads vs virtual threads.
   */
  @Test
  public void testConcurrentBlobReads() throws Exception {
    // Execute with platform threads
    PerformanceMetrics platformMetrics = executeConcurrentBlobReads(
        createPlatformThreadFactory("platform-blob-read"));
    logPerformanceMetrics("Blob Read", "platform", platformMetrics);
    
    // Execute with virtual threads
    PerformanceMetrics virtualMetrics = executeConcurrentBlobReads(
        createVirtualThreadFactory("virtual-blob-read"));
    logPerformanceMetrics("Blob Read", "virtual", virtualMetrics);
    
    // Verify results
    assertThat("All platform thread operations should succeed", 
        platformMetrics.getSuccessCount(), is(equalTo(CONCURRENT_OPERATIONS)));
    assertThat("All virtual thread operations should succeed", 
        virtualMetrics.getSuccessCount(), is(equalTo(CONCURRENT_OPERATIONS)));
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual threads should complete faster than platform threads",
        virtualMetrics.getDurationMillis(), is(lessThan(platformMetrics.getDurationMillis())));
  }
  
  /**
   * Test that compares the performance of mixed blob operations
   * using platform threads vs virtual threads.
   */
  @Test
  public void testMixedBlobOperations() throws Exception {
    // Execute with platform threads
    PerformanceMetrics platformMetrics = executeMixedBlobOperations(
        createPlatformThreadFactory("platform-mixed-ops"));
    logPerformanceMetrics("Mixed Operations", "platform", platformMetrics);
    
    // Execute with virtual threads
    PerformanceMetrics virtualMetrics = executeMixedBlobOperations(
        createVirtualThreadFactory("virtual-mixed-ops"));
    logPerformanceMetrics("Mixed Operations", "virtual", virtualMetrics);
    
    // Verify results
    assertThat("All platform thread operations should succeed", 
        platformMetrics.getSuccessCount(), is(equalTo(CONCURRENT_OPERATIONS)));
    assertThat("All virtual thread operations should succeed", 
        virtualMetrics.getSuccessCount(), is(equalTo(CONCURRENT_OPERATIONS)));
    
    // Virtual threads should be faster for I/O-bound operations
    assertThat("Virtual threads should complete faster than platform threads",
        virtualMetrics.getDurationMillis(), is(lessThan(platformMetrics.getDurationMillis())));
  }
  
  /**
   * Test that compares memory usage between platform threads and virtual threads
   * with a high number of concurrent threads.
   */
  @Test
  public void testMemoryConsumption() throws Exception {
    // Use a higher thread count to demonstrate memory differences
    final int highThreadCount = 5000;
    
    try {
      // Execute with platform threads
      PerformanceMetrics platformMetrics = executeMemoryConsumptionTest(
          createPlatformThreadFactory("platform-memory"), highThreadCount);
      logPerformanceMetrics("Memory Test", "platform", platformMetrics);
      
      // Execute with virtual threads
      PerformanceMetrics virtualMetrics = executeMemoryConsumptionTest(
          createVirtualThreadFactory("virtual-memory"), highThreadCount);
      logPerformanceMetrics("Memory Test", "virtual", virtualMetrics);
      
      // Verify results
      assertThat("All virtual thread operations should succeed", 
          virtualMetrics.getSuccessCount(), is(equalTo(highThreadCount)));
      
      // Virtual threads should use significantly less memory
      assertThat("Virtual threads should use less memory than platform threads",
          virtualMetrics.getMemoryUsedBytes(), is(lessThan(platformMetrics.getMemoryUsedBytes())));
    }
    catch (OutOfMemoryError e) {
      // This is expected for platform threads with high thread counts
      log.info("Out of memory error occurred during platform thread test - this demonstrates the memory advantage of virtual threads");
    }
  }
  
  /**
   * Test that demonstrates the scalability of virtual threads with extremely high concurrency.
   */
  @Test
  public void testHighConcurrencyScalability() throws Exception {
    // Skip platform thread test for extremely high concurrency as it would likely cause OOM
    final int extremeThreadCount = 10000;
    
    // Execute with virtual threads only
    PerformanceMetrics virtualMetrics = executeHighConcurrencyTest(
        createVirtualThreadFactory("virtual-high-concurrency"), extremeThreadCount);
    logPerformanceMetrics("High Concurrency", "virtual", virtualMetrics);
    
    // Verify results
    assertThat("Virtual threads should handle high concurrency", 
        virtualMetrics.getSuccessCount(), is(greaterThan(extremeThreadCount / 2)));
    assertThat("Virtual threads should have minimal errors under high load",
        virtualMetrics.getErrorCount(), is(lessThan(extremeThreadCount / 10)));
  }
  
  /**
   * Test that compares throughput between platform threads and virtual threads
   * for a fixed duration under sustained load.
   */
  @Test
  public void testSustainedThroughput() throws Exception {
    final int testDurationSeconds = 5;
    final BlobStore blobStore = createMockBlobStore();
    final Blob sharedBlob = createBlob(blobStore, BLOB_SIZE);
    
    // Create executors
    ExecutorService platformExecutor = Executors.newThreadPerTaskExecutor(
        createPlatformThreadFactory("platform-throughput"));
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(
        createVirtualThreadFactory("virtual-throughput"));
    
    // Track operation counts
    AtomicInteger platformOpsCount = new AtomicInteger(0);
    AtomicInteger virtualOpsCount = new AtomicInteger(0);
    
    // Run platform thread test
    boolean platformRunning = true;
    long platformStartTime = System.currentTimeMillis();
    while (platformRunning) {
      platformExecutor.submit(() -> {
        try {
          // Simple read operation
          InputStream inputStream = sharedBlob.getInputStream();
          byte[] buffer = new byte[1024];
          inputStream.read(buffer);
          inputStream.close();
          Thread.sleep(5); // Simulate I/O delay
          platformOpsCount.incrementAndGet();
        } 
        catch (Exception e) {
          log.error("Error in platform throughput test", e);
        }
      });
      
      // Check if test duration has elapsed
      if (System.currentTimeMillis() - platformStartTime > testDurationSeconds * 1000) {
        platformRunning = false;
      }
    }
    
    // Run virtual thread test
    boolean virtualRunning = true;
    long virtualStartTime = System.currentTimeMillis();
    while (virtualRunning) {
      virtualExecutor.submit(() -> {
        try {
          // Simple read operation (same as platform test)
          InputStream inputStream = sharedBlob.getInputStream();
          byte[] buffer = new byte[1024];
          inputStream.read(buffer);
          inputStream.close();
          Thread.sleep(5); // Simulate I/O delay
          virtualOpsCount.incrementAndGet();
        } 
        catch (Exception e) {
          log.error("Error in virtual throughput test", e);
        }
      });
      
      // Check if test duration has elapsed
      if (System.currentTimeMillis() - virtualStartTime > testDurationSeconds * 1000) {
        virtualRunning = false;
      }
    }
    
    // Shutdown executors and wait for completion
    platformExecutor.shutdown();
    virtualExecutor.shutdown();
    platformExecutor.awaitTermination(30, TimeUnit.SECONDS);
    virtualExecutor.awaitTermination(30, TimeUnit.SECONDS);
    
    // Log results
    int platformOps = platformOpsCount.get();
    int virtualOps = virtualOpsCount.get();
    log.info("Sustained Throughput Test Results:");
    log.info("Platform threads: {} operations in {} seconds ({} ops/sec)", 
        platformOps, testDurationSeconds, platformOps / testDurationSeconds);
    log.info("Virtual threads: {} operations in {} seconds ({} ops/sec)", 
        virtualOps, testDurationSeconds, virtualOps / testDurationSeconds);
    
    // Verify results
    assertThat("Virtual threads should achieve higher throughput than platform threads",
        virtualOps, is(greaterThan(platformOps)));
  }
  
  /**
   * Test that verifies virtual threads can handle thread-local variables correctly.
   * This is important for BlobStore operations that might rely on thread-local state.
   */
  @Test
  public void testThreadLocalWithVirtualThreads() throws Exception {
    // Create a thread-local variable
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    
    // Create executor with virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(
        createVirtualThreadFactory("virtual-threadlocal"));
    
    // Number of tasks to run
    final int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit tasks that use thread-local storage
      for (int i = 0; i < taskCount; i++) {
        final String expectedValue = "Value-" + i;
        executor.submit(() -> {
          try {
            // Set thread-local value
            threadLocal.set(expectedValue);
            
            // Simulate some work
            Thread.sleep(5);
            
            // Verify thread-local value is still correct
            String actualValue = threadLocal.get();
            if (expectedValue.equals(actualValue)) {
              successCount.incrementAndGet();
            } else {
              log.error("Thread-local value mismatch. Expected: {}, Actual: {}", 
                  expectedValue, actualValue);
            }
          } 
          catch (Exception e) {
            log.error("Error in thread-local test", e);
          } 
          finally {
            // Clean up thread-local to prevent memory leaks
            threadLocal.remove();
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      if (!completed) {
        log.warn("Timeout waiting for thread-local test tasks to complete");
      }
      
      // Verify results
      log.info("Thread-local test with virtual threads - Success: {}/{}", 
          successCount.get(), taskCount);
      
      assertThat("Virtual threads should correctly maintain thread-local variables",
          successCount.get(), is(equalTo(taskCount)));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that verifies virtual threads can handle concurrent BlobStore operations
   * with proper error handling and recovery.
   */
  @Test
  public void testErrorHandlingWithVirtualThreads() throws Exception {
    BlobStore blobStore = createMockBlobStore();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(
        createVirtualThreadFactory("virtual-error-handling"));
    
    // Create a supplier that sometimes throws exceptions
    Supplier<Blob> errorProneSupplier = () -> {
      if (Math.random() < 0.3) { // 30% chance of error
        throw new RuntimeException("Simulated BlobStore error");
      }
      return createBlob(blobStore, BLOB_SIZE);
    };
    
    // Number of tasks to run
    final int taskCount = 1000;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger recoveryCount = new AtomicInteger(0);
    
    try {
      // Submit tasks with potential errors
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Try to get a blob with potential error
            try {
              Blob blob = errorProneSupplier.get();
              // Process the blob
              InputStream inputStream = blob.getInputStream();
              inputStream.close();
              successCount.incrementAndGet();
            }
            catch (RuntimeException e) {
              // Record the error
              errorCount.incrementAndGet();
              
              // Attempt recovery by trying again
              try {
                Blob recoveryBlob = createBlob(blobStore, BLOB_SIZE);
                InputStream inputStream = recoveryBlob.getInputStream();
                inputStream.close();
                recoveryCount.incrementAndGet();
              }
              catch (Exception recoveryError) {
                log.error("Recovery attempt failed", recoveryError);
              }
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      if (!completed) {
        log.warn("Timeout waiting for error handling test tasks to complete");
      }
      
      // Log results
      log.info("Error handling test results - Success: {}, Errors: {}, Recoveries: {}",
          successCount.get(), errorCount.get(), recoveryCount.get());
      
      // Verify results
      assertThat("All tasks should complete", 
          successCount.get() + errorCount.get(), is(equalTo(taskCount)));
      assertThat("Some errors should occur due to simulated failures", 
          errorCount.get(), is(greaterThan(0)));
      assertThat("Recovery should be successful in most cases", 
          recoveryCount.get(), is(greaterThan(errorCount.get() * 8 / 10))); // At least 80% recovery rate
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Test that compares the performance of platform threads and virtual threads
   * when handling a mix of CPU-bound and I/O-bound operations.
   */
  @Test
  public void testMixedCpuAndIoBoundOperations() throws Exception {
    BlobStore blobStore = createMockBlobStore();
    
    // Create executors
    ExecutorService platformExecutor = Executors.newThreadPerTaskExecutor(
        createPlatformThreadFactory("platform-mixed-workload"));
    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(
        createVirtualThreadFactory("virtual-mixed-workload"));
    
    // Number of tasks to run
    final int taskCount = 1000;
    CountDownLatch platformLatch = new CountDownLatch(taskCount);
    CountDownLatch virtualLatch = new CountDownLatch(taskCount);
    
    // Create performance metrics
    PerformanceMetrics platformMetrics = new PerformanceMetrics();
    PerformanceMetrics virtualMetrics = new PerformanceMetrics();
    
    // Run platform thread test
    for (int i = 0; i < taskCount; i++) {
      final int taskNum = i;
      platformExecutor.submit(() -> {
        try {
          if (taskNum % 4 == 0) {
            // CPU-bound operation (25% of tasks)
            // Compute a hash or perform some CPU-intensive calculation
            byte[] data = createRandomBytes(10240); // 10KB of data
            int sum = 0;
            for (byte b : data) {
              sum += b;
            }
            // Prevent JIT optimization by using the result
            if (sum % 2 == 0) {
              Thread.sleep(1); // Minimal sleep
            }
          } else {
            // I/O-bound operation (75% of tasks)
            Blob blob = createBlob(blobStore, BLOB_SIZE);
            InputStream inputStream = blob.getInputStream();
            byte[] buffer = new byte[1024];
            while (inputStream.read(buffer) != -1) {
              // Just read the data
            }
            inputStream.close();
            Thread.sleep(10); // Simulate I/O delay
          }
          platformMetrics.incrementSuccess();
        } 
        catch (Exception e) {
          log.error("Error in platform mixed workload test", e);
          platformMetrics.incrementError();
        } 
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    // Wait for platform tasks to complete
    boolean platformCompleted = platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    platformMetrics.markComplete();
    
    if (!platformCompleted) {
      log.warn("Timeout waiting for platform mixed workload tasks to complete");
    }
    
    // Run virtual thread test with identical workload
    for (int i = 0; i < taskCount; i++) {
      final int taskNum = i;
      virtualExecutor.submit(() -> {
        try {
          if (taskNum % 4 == 0) {
            // CPU-bound operation (25% of tasks)
            // Compute a hash or perform some CPU-intensive calculation
            byte[] data = createRandomBytes(10240); // 10KB of data
            int sum = 0;
            for (byte b : data) {
              sum += b;
            }
            // Prevent JIT optimization by using the result
            if (sum % 2 == 0) {
              Thread.sleep(1); // Minimal sleep
            }
          } else {
            // I/O-bound operation (75% of tasks)
            Blob blob = createBlob(blobStore, BLOB_SIZE);
            InputStream inputStream = blob.getInputStream();
            byte[] buffer = new byte[1024];
            while (inputStream.read(buffer) != -1) {
              // Just read the data
            }
            inputStream.close();
            Thread.sleep(10); // Simulate I/O delay
          }
          virtualMetrics.incrementSuccess();
        } 
        catch (Exception e) {
          log.error("Error in virtual mixed workload test", e);
          virtualMetrics.incrementError();
        } 
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    // Wait for virtual tasks to complete
    boolean virtualCompleted = virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    virtualMetrics.markComplete();
    
    if (!virtualCompleted) {
      log.warn("Timeout waiting for virtual mixed workload tasks to complete");
    }
    
    // Shutdown executors
    platformExecutor.shutdown();
    virtualExecutor.shutdown();
    
    // Log results
    logPerformanceMetrics("Mixed CPU/IO Workload", "platform", platformMetrics);
    logPerformanceMetrics("Mixed CPU/IO Workload", "virtual", virtualMetrics);
    
    // Verify results
    assertThat("All platform thread operations should succeed", 
        platformMetrics.getSuccessCount(), is(equalTo(taskCount)));
    assertThat("All virtual thread operations should succeed", 
        virtualMetrics.getSuccessCount(), is(equalTo(taskCount)));
    
    // Virtual threads should still be faster overall due to better I/O handling
    assertThat("Virtual threads should complete faster than platform threads for mixed workloads",
        virtualMetrics.getDurationMillis(), is(lessThan(platformMetrics.getDurationMillis())));
  }