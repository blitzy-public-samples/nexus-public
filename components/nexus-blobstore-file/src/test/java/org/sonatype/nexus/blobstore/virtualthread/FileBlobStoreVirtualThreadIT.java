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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.file.FileBlobDeletionIndex;
import org.sonatype.nexus.blobstore.file.FileBlobStore;
import org.sonatype.nexus.blobstore.file.FileBlobStoreITSupport;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;

/**
 * Integration test for {@link FileBlobStore} with Java 21 Virtual Threads.
 * 
 * This test compares the performance and behavior of FileBlobStore operations
 * when executed with platform threads versus virtual threads.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class FileBlobStoreVirtualThreadIT extends FileBlobStoreITSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int OPERATION_TIMEOUT_SECONDS = 30;
  
  /**
   * Required implementation of abstract method from parent class.
   */
  @Override
  protected FileBlobDeletionIndex fileBlobDeletionIndex() {
    return mock(FileBlobDeletionIndex.class);
  }
  
  /**
   * Creates a platform thread executor with the specified number of threads.
   */
  private ExecutorService createPlatformThreadExecutor(String name, int threadCount) {
    ThreadFactory threadFactory = r -> {
      Thread t = new Thread(r);
      t.setName(name + "-" + t.getId());
      return t;
    };
    return Executors.newFixedThreadPool(threadCount, threadFactory);
  }
  
  /**
   * Creates a virtual thread executor that creates a new virtual thread for each task.
   */
  private ExecutorService createVirtualThreadExecutor(String name) {
    ThreadFactory threadFactory = Thread.ofVirtual()
        .name(name + "-", 0)
        .factory();
    return Executors.newThreadPerTaskExecutor(threadFactory);
  }
  
  /**
   * Test that compares the performance of blob creation operations between platform and virtual threads.
   */
  @Test
  public void compareCreateBlobPerformance() throws Exception {
    // Create executors
    ExecutorService platformExecutor = createPlatformThreadExecutor("platform", 10);
    ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual");
    
    try {
      // Run operations with platform threads
      long platformStartTime = System.currentTimeMillis();
      List<BlobId> platformBlobIds = runCreateBlobOperations(platformExecutor, CONCURRENT_OPERATIONS);
      long platformEndTime = System.currentTimeMillis();
      long platformDuration = platformEndTime - platformStartTime;
      
      // Run operations with virtual threads
      long virtualStartTime = System.currentTimeMillis();
      List<BlobId> virtualBlobIds = runCreateBlobOperations(virtualExecutor, CONCURRENT_OPERATIONS);
      long virtualEndTime = System.currentTimeMillis();
      long virtualDuration = virtualEndTime - virtualStartTime;
      
      // Log performance results
      log("Platform thread create blob operations took {} ms", platformDuration);
      log("Virtual thread create blob operations took {} ms", virtualDuration);
      
      // Verify results
      assertThat(platformBlobIds.size(), is(equalTo(CONCURRENT_OPERATIONS)));
      assertThat(virtualBlobIds.size(), is(equalTo(CONCURRENT_OPERATIONS)));
      
      // Clean up created blobs
      cleanupBlobs(platformBlobIds);
      cleanupBlobs(virtualBlobIds);
      
      // Virtual threads should perform better at high concurrency for I/O operations
      // but we don't want to make this a hard assertion as it depends on the test environment
      log("Virtual thread performance ratio: {}", (double) platformDuration / virtualDuration);
    } finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
      platformExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      virtualExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Test that compares the performance of blob retrieval operations between platform and virtual threads.
   */
  @Test
  public void compareGetBlobPerformance() throws Exception {
    // Create test blobs first
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      Blob blob = createTestBlob();
      blobIds.add(blob.getId());
    }
    
    // Create executors
    ExecutorService platformExecutor = createPlatformThreadExecutor("platform", 10);
    ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual");
    
    try {
      // Run operations with platform threads
      long platformStartTime = System.currentTimeMillis();
      runGetBlobOperations(platformExecutor, blobIds);
      long platformEndTime = System.currentTimeMillis();
      long platformDuration = platformEndTime - platformStartTime;
      
      // Run operations with virtual threads
      long virtualStartTime = System.currentTimeMillis();
      runGetBlobOperations(virtualExecutor, blobIds);
      long virtualEndTime = System.currentTimeMillis();
      long virtualDuration = virtualEndTime - virtualStartTime;
      
      // Log performance results
      log("Platform thread get blob operations took {} ms", platformDuration);
      log("Virtual thread get blob operations took {} ms", virtualDuration);
      
      // Clean up created blobs
      cleanupBlobs(blobIds);
      
      // Virtual threads should perform better at high concurrency for I/O operations
      // but we don't want to make this a hard assertion as it depends on the test environment
      log("Virtual thread performance ratio: {}", (double) platformDuration / virtualDuration);
    } finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
      platformExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      virtualExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Test that compares the performance of blob deletion operations between platform and virtual threads.
   */
  @Test
  public void compareDeleteBlobPerformance() throws Exception {
    // Create test blobs first for platform thread test
    List<BlobId> platformBlobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      Blob blob = createTestBlob();
      platformBlobIds.add(blob.getId());
    }
    
    // Create test blobs for virtual thread test
    List<BlobId> virtualBlobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      Blob blob = createTestBlob();
      virtualBlobIds.add(blob.getId());
    }
    
    // Create executors
    ExecutorService platformExecutor = createPlatformThreadExecutor("platform", 10);
    ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual");
    
    try {
      // Run operations with platform threads
      long platformStartTime = System.currentTimeMillis();
      runDeleteBlobOperations(platformExecutor, platformBlobIds);
      long platformEndTime = System.currentTimeMillis();
      long platformDuration = platformEndTime - platformStartTime;
      
      // Run operations with virtual threads
      long virtualStartTime = System.currentTimeMillis();
      runDeleteBlobOperations(virtualExecutor, virtualBlobIds);
      long virtualEndTime = System.currentTimeMillis();
      long virtualDuration = virtualEndTime - virtualStartTime;
      
      // Log performance results
      log("Platform thread delete blob operations took {} ms", platformDuration);
      log("Virtual thread delete blob operations took {} ms", virtualDuration);
      
      // Virtual threads should perform better at high concurrency for I/O operations
      // but we don't want to make this a hard assertion as it depends on the test environment
      log("Virtual thread performance ratio: {}", (double) platformDuration / virtualDuration);
      
      // Verify all blobs were deleted
      for (BlobId blobId : platformBlobIds) {
        assertThat(underTest.exists(blobId), is(false));
      }
      for (BlobId blobId : virtualBlobIds) {
        assertThat(underTest.exists(blobId), is(false));
      }
    } finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
      platformExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      virtualExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Test that verifies high concurrency operations with virtual threads.
   * This test creates a large number of virtual threads to verify that the FileBlobStore
   * can handle high concurrency scenarios efficiently with virtual threads.
   */
  @Test
  public void highConcurrencyVirtualThreadTest() throws Exception {
    // Use a higher number of concurrent operations for this test
    final int highConcurrencyOperations = CONCURRENT_OPERATIONS * 5;
    
    // Create virtual thread executor
    ExecutorService virtualExecutor = createVirtualThreadExecutor("high-concurrency");
    
    try {
      // Run create operations with high concurrency
      long startTime = System.currentTimeMillis();
      List<BlobId> blobIds = runCreateBlobOperations(virtualExecutor, highConcurrencyOperations);
      long endTime = System.currentTimeMillis();
      
      // Log performance results
      log("High concurrency virtual thread create operations ({} operations) took {} ms", 
          highConcurrencyOperations, endTime - startTime);
      
      // Verify results
      assertThat(blobIds.size(), is(equalTo(highConcurrencyOperations)));
      
      // Clean up created blobs
      cleanupBlobs(blobIds);
    } finally {
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(OPERATION_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Creates a test blob with random content.
   */
  private Blob createTestBlob() {
    byte[] content = randomBytes();
    return underTest.create(new ByteArrayInputStream(content), TEST_HEADERS);
  }
  
  /**
   * Runs blob creation operations concurrently using the provided executor.
   */
  private List<BlobId> runCreateBlobOperations(ExecutorService executor, int operationCount) 
      throws InterruptedException, ExecutionException {
    List<Future<BlobId>> futures = new ArrayList<>();
    
    // Submit create operations
    for (int i = 0; i < operationCount; i++) {
      futures.add(executor.submit(() -> {
        byte[] content = randomBytes();
        Blob blob = underTest.create(new ByteArrayInputStream(content), TEST_HEADERS);
        return blob.getId();
      }));
    }
    
    // Collect results
    List<BlobId> blobIds = new ArrayList<>();
    for (Future<BlobId> future : futures) {
      BlobId blobId = future.get();
      assertThat(blobId, is(notNullValue()));
      blobIds.add(blobId);
    }
    
    return blobIds;
  }
  
  /**
   * Runs blob retrieval operations concurrently using the provided executor.
   */
  private void runGetBlobOperations(ExecutorService executor, List<BlobId> blobIds) 
      throws InterruptedException, ExecutionException {
    List<Future<Blob>> futures = new ArrayList<>();
    
    // Submit get operations
    for (BlobId blobId : blobIds) {
      futures.add(executor.submit(() -> {
        Blob blob = underTest.get(blobId);
        // Read the blob content to ensure I/O operations are performed
        blob.getInputStream().readAllBytes();
        return blob;
      }));
    }
    
    // Wait for all operations to complete
    for (Future<Blob> future : futures) {
      Blob blob = future.get();
      assertThat(blob, is(notNullValue()));
    }
  }
  
  /**
   * Runs blob deletion operations concurrently using the provided executor.
   */
  private void runDeleteBlobOperations(ExecutorService executor, List<BlobId> blobIds) 
      throws InterruptedException, ExecutionException {
    List<Future<Boolean>> futures = new ArrayList<>();
    
    // Submit delete operations
    for (BlobId blobId : blobIds) {
      futures.add(executor.submit(() -> 
          underTest.delete(blobId, "FileBlobStoreVirtualThreadIT")));
    }
    
    // Wait for all operations to complete
    for (Future<Boolean> future : futures) {
      Boolean result = future.get();
      assertThat(result, is(true));
    }
  }
  
  /**
   * Cleans up the blobs with the given IDs.
   */
  private void cleanupBlobs(List<BlobId> blobIds) {
    for (BlobId blobId : blobIds) {
      try {
        if (underTest.exists(blobId)) {
          underTest.delete(blobId, "cleanup");
        }
      } catch (Exception e) {
        log.warn("Failed to clean up blob {}", blobId, e);
      }
    }
  }
}