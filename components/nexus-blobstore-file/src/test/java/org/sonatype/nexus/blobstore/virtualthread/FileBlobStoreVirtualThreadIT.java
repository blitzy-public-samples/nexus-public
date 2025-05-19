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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.blobstore.MockBlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;
import org.sonatype.nexus.blobstore.file.FileBlobDeletionIndex;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.FileBlobStoreITSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static com.google.common.io.ByteStreams.toByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;

/**
 * Integration test for {@link FileBlobStore} with Java 21 Virtual Threads.
 * 
 * This test validates that FileBlobStore operations perform well with Virtual Threads
 * and compares performance between platform threads and virtual threads for I/O-bound operations.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class FileBlobStoreVirtualThreadIT
    extends FileBlobStoreITSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int BLOB_SIZE = 1024 * 10; // 10KB
  private static final int OPERATION_TIMEOUT_SECONDS = 30;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @Override
  protected FileBlobDeletionIndex fileBlobDeletionIndex() {
    return mock(FileBlobDeletionIndex.class);
  }
  
  @Before
  public void setupExecutors() {
    // Create a platform thread executor with a fixed thread pool
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(),
        createThreadFactory("platform-thread"));
    
    // Create a virtual thread executor using Java 21's virtual threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void shutdownExecutors() {
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }
  
  /**
   * Creates a named thread factory for better thread identification in logs and debugging.
   */
  private ThreadFactory createThreadFactory(final String prefix) {
    return new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger();
      
      @Override
      public Thread newThread(final Runnable r) {
        Thread thread = new Thread(r);
        thread.setName(prefix + "-" + counter.incrementAndGet());
        return thread;
      }
    };
  }
  
  /**
   * Test that compares the performance of creating blobs using platform threads vs virtual threads.
   * Virtual threads should provide better throughput for I/O-bound operations.
   */
  @Test
  public void testBlobCreationPerformanceComparison() throws Exception {
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> 
        createBlobsConcurrently(platformThreadExecutor, CONCURRENT_OPERATIONS));
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> 
        createBlobsConcurrently(virtualThreadExecutor, CONCURRENT_OPERATIONS));
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should generally be more efficient for I/O operations
    // but we don't make this a hard assertion as it depends on the environment
    if (virtualThreadTime < platformThreadTime) {
      log.info("Virtual threads were {}% faster than platform threads", 
          Math.round((platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime));
    }
  }
  
  /**
   * Test that verifies blob retrieval works correctly with high concurrency using virtual threads.
   */
  @Test
  public void testConcurrentBlobRetrievalWithVirtualThreads() throws Exception {
    // Create test blobs
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      Blob blob = createRandomBlob();
      blobIds.add(blob.getId());
    }
    
    // Retrieve blobs concurrently using virtual threads
    List<CompletableFuture<byte[]>> futures = new ArrayList<>();
    for (BlobId blobId : blobIds) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Blob blob = underTest.get(blobId);
          assertThat(blob, is(notNullValue()));
          try (InputStream inputStream = blob.getInputStream()) {
            return toByteArray(inputStream);
          }
        }
        catch (IOException e) {
          throw new BlobStoreException(e, blobId);
        }
      }, virtualThreadExecutor));
    }
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all blobs were retrieved successfully
    for (int i = 0; i < futures.size(); i++) {
      byte[] content = futures.get(i).get();
      assertThat(content.length, is(equalTo(BLOB_SIZE)));
    }
  }
  
  /**
   * Test that verifies blob deletion works correctly with high concurrency using virtual threads.
   */
  @Test
  public void testConcurrentBlobDeletionWithVirtualThreads() throws Exception {
    // Create test blobs
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      Blob blob = createRandomBlob();
      blobIds.add(blob.getId());
    }
    
    // Delete blobs concurrently using virtual threads
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    for (BlobId blobId : blobIds) {
      futures.add(CompletableFuture.supplyAsync(() -> 
          underTest.delete(blobId, "testConcurrentBlobDeletionWithVirtualThreads"), 
          virtualThreadExecutor));
    }
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all blobs were deleted successfully
    for (CompletableFuture<Boolean> future : futures) {
      assertThat(future.get(), is(true));
    }
    
    // Compact to ensure deletions are processed
    underTest.compact(null);
    
    // Verify blobs are no longer accessible
    for (BlobId blobId : blobIds) {
      assertThat(underTest.exists(blobId), is(false));
    }
  }
  
  /**
   * Test that compares the performance of a complete blob lifecycle (create, get, delete)
   * between platform threads and virtual threads.
   */
  @Test
  public void testCompleteBlobLifecyclePerformanceComparison() throws Exception {
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> 
        executeBlobLifecycleConcurrently(platformThreadExecutor, CONCURRENT_OPERATIONS / 2));
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> 
        executeBlobLifecycleConcurrently(virtualThreadExecutor, CONCURRENT_OPERATIONS / 2));
    
    log.info("Platform thread complete lifecycle time: {} ms", platformThreadTime);
    log.info("Virtual thread complete lifecycle time: {} ms", virtualThreadTime);
    
    // Log performance difference
    if (virtualThreadTime < platformThreadTime) {
      log.info("Virtual threads were {}% faster for complete lifecycle operations", 
          Math.round((platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime));
    }
  }
  
  /**
   * Test that verifies virtual threads can handle a very high number of concurrent operations
   * without exhausting system resources.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a higher number of concurrent operations for this test
    final int highConcurrency = CONCURRENT_OPERATIONS * 5;
    
    // Create and execute many concurrent operations using virtual threads
    List<CompletableFuture<Blob>> futures = new ArrayList<>();
    for (int i = 0; i < highConcurrency; i++) {
      final int index = i;
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          // Create a small blob to avoid excessive memory usage
          byte[] content = new byte[128];
          new Random().nextBytes(content);
          return underTest.create(new ByteArrayInputStream(content), TEST_HEADERS);
        }
        catch (Exception e) {
          log.error("Error in virtual thread operation {}", index, e);
          throw new RuntimeException(e);
        }
      }, virtualThreadExecutor));
    }
    
    // Wait for all operations to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    // This should complete without exhausting system resources
    allFutures.get(OPERATION_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
    
    // Verify all operations completed successfully
    int successCount = 0;
    for (CompletableFuture<Blob> future : futures) {
      Blob blob = future.get();
      if (blob != null && blob.getId() != null) {
        successCount++;
      }
    }
    
    assertThat("All blob creation operations should succeed", 
        successCount, is(equalTo(highConcurrency)));
  }
  
  /**
   * Creates a blob with random content of the specified size.
   */
  private Blob createRandomBlob() {
    byte[] content = new byte[BLOB_SIZE];
    new Random().nextBytes(content);
    return underTest.create(new ByteArrayInputStream(content), TEST_HEADERS);
  }
  
  /**
   * Creates multiple blobs concurrently using the provided executor.
   */
  private void createBlobsConcurrently(ExecutorService executor, int count) 
      throws InterruptedException, ExecutionException {
    List<CompletableFuture<Blob>> futures = new ArrayList<>();
    
    for (int i = 0; i < count; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> createRandomBlob(), executor));
    }
    
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Executes a complete blob lifecycle (create, get, delete) concurrently using the provided executor.
   */
  private void executeBlobLifecycleConcurrently(ExecutorService executor, int count) 
      throws InterruptedException, ExecutionException {
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < count; i++) {
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          // Create blob
          Blob blob = createRandomBlob();
          BlobId blobId = blob.getId();
          
          // Get blob
          Blob retrievedBlob = underTest.get(blobId);
          try (InputStream inputStream = retrievedBlob.getInputStream()) {
            byte[] content = toByteArray(inputStream);
            assertThat(content.length, is(equalTo(BLOB_SIZE)));
          }
          
          // Delete blob
          boolean deleted = underTest.delete(blobId, "executeBlobLifecycleConcurrently");
          assertThat(deleted, is(true));
          
          return null;
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, executor));
    }
    
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    
    allFutures.get(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Measures the execution time of the provided runnable in milliseconds.
   */
  private long measureExecutionTime(RunnableWithException runnable) throws Exception {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Functional interface for a runnable that can throw exceptions.
   */
  @FunctionalInterface
  private interface RunnableWithException {
    void run() throws Exception;
  }
  
  /**
   * Marker interface for Java 21 test category.
   */
  public interface Java21TestGroup {
    // Marker interface
  }
  
  /**
   * Marker interface for Virtual Thread test category.
   */
  public interface VirtualThreadTestGroup {
    // Marker interface
  }
}