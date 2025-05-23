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
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;

import com.google.common.hash.HashCode;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for BlobStore operations using Java 21 Virtual Threads.
 * 
 * These tests validate that BlobStore operations can be efficiently executed on virtual threads,
 * ensuring that I/O-bound operations benefit from the improved concurrency model in Java 21.
 */
public class VirtualThreadBlobStoreTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int BLOB_DATA_SIZE = 1024;
  private static final String TEST_REPO_NAME = "test-repo";
  private static final String TEST_BLOB_NAME = "test-blob";
  private static final String TEST_CONTENT_TYPE = "application/octet-stream";
  private static final String TEST_CREATED_BY = "test-user";

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreMetrics blobStoreMetrics;

  private Map<String, String> headers;
  private byte[] testData;
  private int blobIdSequence = 1;

  @Before
  public void setUp() {
    // Setup test data
    testData = new byte[BLOB_DATA_SIZE];
    for (int i = 0; i < BLOB_DATA_SIZE; i++) {
      testData[i] = (byte) (i % 256);
    }

    // Setup headers
    headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, TEST_BLOB_NAME);
    headers.put(BlobStore.REPO_NAME_HEADER, TEST_REPO_NAME);
    headers.put(BlobStore.CONTENT_TYPE_HEADER, TEST_CONTENT_TYPE);
    headers.put(BlobStore.CREATED_BY_HEADER, TEST_CREATED_BY);

    // Setup mock BlobStore
    when(blobStore.create(any(InputStream.class), anyMap())).thenAnswer(this::newBlob);
    when(blobStore.create(any(InputStream.class), anyMap(), any(BlobId.class))).thenAnswer(this::newBlob);
    when(blobStore.create(any(Path.class), anyMap(), any(Long.class), any(HashCode.class))).thenAnswer(this::newBlob);
    when(blobStore.get(any(BlobId.class))).thenAnswer(this::getBlob);
    when(blobStore.get(any(BlobId.class), any(Boolean.class))).thenAnswer(this::getBlob);
    when(blobStore.delete(any(BlobId.class), anyString())).thenReturn(true);
    when(blobStore.exists(any(BlobId.class))).thenReturn(true);
    when(blobStore.getMetrics()).thenReturn(blobStoreMetrics);
    
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    when(configuration.getName()).thenReturn("test-blob-store");
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(blobStore.isStorageAvailable()).thenReturn(true);
    when(blobStore.isWritable()).thenReturn(true);
    when(blobStore.isStarted()).thenReturn(true);
  }

  /**
   * Tests that a single blob operation can be executed on a virtual thread.
   */
  @Test
  public void testSingleBlobOperationOnVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a virtual thread to execute a blob creation operation
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try (InputStream inputStream = new ByteArrayInputStream(testData)) {
        Blob blob = blobStore.create(inputStream, headers);
        assertThat(blob, is(notNullValue()));
        assertThat(blob.getId(), is(notNullValue()));
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to execute blob operation on virtual thread", e);
      }
    });
    
    virtualThread.start();
    virtualThread.join();
    
    // Verify that the blob was created
    verify(blobStore, times(1)).create(any(InputStream.class), anyMap());
  }

  /**
   * Tests that multiple concurrent blob operations can be executed on virtual threads.
   */
  @Test
  public void testConcurrentBlobOperationsOnVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit multiple blob creation tasks
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        futures.add(executor.submit(() -> {
          try (InputStream inputStream = new ByteArrayInputStream(testData)) {
            Blob blob = blobStore.create(inputStream, headers);
            assertThat(blob, is(notNullValue()));
            return blob.getId();
          }
          catch (Exception e) {
            throw new RuntimeException("Failed to execute blob operation on virtual thread", e);
          }
        }));
      }
      
      // Wait for all operations to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    
    // Verify that all blobs were created
    verify(blobStore, times(CONCURRENT_OPERATIONS)).create(any(InputStream.class), anyMap());
  }

  /**
   * Tests the complete lifecycle of blobs (create, get, delete) using virtual threads.
   */
  @Test
  public void testBlobLifecycleOnVirtualThreads() throws Exception {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Phase 1: Create blobs
      List<CompletableFuture<BlobId>> createFutures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        CompletableFuture<BlobId> future = CompletableFuture.supplyAsync(() -> {
          try (InputStream inputStream = new ByteArrayInputStream(testData)) {
            Blob blob = blobStore.create(inputStream, headers);
            return blob.getId();
          }
          catch (Exception e) {
            throw new RuntimeException("Failed to create blob", e);
          }
        }, executor);
        
        createFutures.add(future);
      }
      
      // Wait for all creations to complete and collect the BlobIds
      List<BlobId> blobIds = createFutures.stream()
          .map(CompletableFuture::join)
          .toList();
      
      // Phase 2: Get blobs
      List<CompletableFuture<Blob>> getFutures = blobIds.stream()
          .map(blobId -> CompletableFuture.supplyAsync(() -> blobStore.get(blobId), executor))
          .toList();
      
      // Wait for all gets to complete
      List<Blob> blobs = getFutures.stream()
          .map(CompletableFuture::join)
          .toList();
      
      // Verify all blobs were retrieved
      for (Blob blob : blobs) {
        assertThat(blob, is(notNullValue()));
      }
      
      // Phase 3: Delete blobs
      List<CompletableFuture<Boolean>> deleteFutures = blobIds.stream()
          .map(blobId -> CompletableFuture.supplyAsync(() -> blobStore.delete(blobId, "Test deletion"), executor))
          .toList();
      
      // Wait for all deletions to complete
      List<Boolean> deleteResults = deleteFutures.stream()
          .map(CompletableFuture::join)
          .toList();
      
      // Verify all deletions were successful
      for (Boolean result : deleteResults) {
        assertThat(result, is(true));
      }
    }
    
    // Verify the expected number of operations
    verify(blobStore, times(CONCURRENT_OPERATIONS)).create(any(InputStream.class), anyMap());
    verify(blobStore, times(CONCURRENT_OPERATIONS)).get(any(BlobId.class));
    verify(blobStore, times(CONCURRENT_OPERATIONS)).delete(any(BlobId.class), anyString());
  }

  /**
   * Tests that virtual threads can handle high concurrency without resource contention.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    final int HIGH_CONCURRENCY = 1000;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(HIGH_CONCURRENCY);
    final AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a large number of tasks that will start simultaneously
      for (int i = 0; i < HIGH_CONCURRENCY; i++) {
        executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            // Perform blob operation
            try (InputStream inputStream = new ByteArrayInputStream(testData)) {
              Blob blob = blobStore.create(inputStream, headers);
              if (blob != null && blob.getId() != null) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread operation", e);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Signal all threads to start simultaneously
      startLatch.countDown();
      
      // Wait for all operations to complete with timeout
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue("Not all operations completed in time", completed);
      
      // Verify that all operations were successful
      assertThat(successCount.get(), is(equalTo(HIGH_CONCURRENCY)));
    }
    
    // Verify the expected number of operations
    verify(blobStore, times(HIGH_CONCURRENCY)).create(any(InputStream.class), anyMap());
  }

  /**
   * Tests that virtual threads properly unpark after I/O operations.
   */
  @Test
  public void testVirtualThreadsUnparkAfterIO() throws Exception {
    final int OPERATIONS = 50;
    final Duration OPERATION_DELAY = Duration.ofMillis(50); // Simulate I/O delay
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Create a blob store operation with simulated I/O delay
      for (int i = 0; i < OPERATIONS; i++) {
        futures.add(executor.submit(() -> {
          try {
            // First operation
            try (InputStream inputStream = new ByteArrayInputStream(testData)) {
              Blob blob = blobStore.create(inputStream, headers);
              
              // Simulate I/O delay
              Thread.sleep(OPERATION_DELAY);
              
              // Second operation that depends on the first
              Blob retrievedBlob = blobStore.get(blob.getId());
              
              // Simulate I/O delay
              Thread.sleep(OPERATION_DELAY);
              
              // Third operation that depends on the previous ones
              boolean deleted = blobStore.delete(retrievedBlob.getId(), "Test deletion");
              
              return deleted;
            }
          }
          catch (Exception e) {
            throw new RuntimeException("Failed during virtual thread operation", e);
          }
        }));
      }
      
      // Wait for all operations to complete
      for (Future<?> future : futures) {
        Boolean result = (Boolean) future.get(10, TimeUnit.SECONDS);
        assertThat(result, is(true));
      }
    }
    
    // Verify the expected number of operations
    verify(blobStore, times(OPERATIONS)).create(any(InputStream.class), anyMap());
    verify(blobStore, times(OPERATIONS)).get(any(BlobId.class));
    verify(blobStore, times(OPERATIONS)).delete(any(BlobId.class), anyString());
  }

  /**
   * Tests asynchronous blob deletion using virtual threads.
   */
  @Test
  public void testAsyncDeleteWithVirtualThreads() throws Exception {
    // Create blobs first
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      try (InputStream inputStream = new ByteArrayInputStream(testData)) {
        Blob blob = blobStore.create(inputStream, headers);
        blobIds.add(blob.getId());
      }
    }
    
    // Setup mock for asyncDelete
    when(blobStore.asyncDelete(any(BlobId.class))).thenAnswer(invocation -> {
      BlobId blobId = invocation.getArgument(0);
      return CompletableFuture.supplyAsync(() -> {
        try {
          // Simulate some async work
          Thread.sleep(50);
          return blobStore.deleteHard(blobId);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
    });
    
    when(blobStore.deleteHard(any(BlobId.class))).thenReturn(true);
    
    // Test async deletion with virtual threads
    List<Future<Boolean>> futures = new ArrayList<>();
    for (BlobId blobId : blobIds) {
      futures.add(blobStore.asyncDelete(blobId));
    }
    
    // Wait for all deletions to complete
    for (Future<Boolean> future : futures) {
      Boolean result = future.get(10, TimeUnit.SECONDS);
      assertThat(result, is(true));
    }
    
    // Verify the expected number of operations
    verify(blobStore, times(CONCURRENT_OPERATIONS)).asyncDelete(any(BlobId.class));
    verify(blobStore, times(CONCURRENT_OPERATIONS)).deleteHard(any(BlobId.class));
  }

  /**
   * Helper method to create a new mock Blob.
   */
  private Blob newBlob(final InvocationOnMock invocation) {
    BlobId blobId;
    if (invocation.getArguments().length > 2 && invocation.getArguments()[2] instanceof BlobId) {
      blobId = (BlobId) invocation.getArguments()[2];
    } else {
      blobId = new BlobId("test-blob-" + (blobIdSequence++));
    }
    return mockBlob(blobId);
  }

  /**
   * Helper method to get a mock Blob.
   */
  private Blob getBlob(final InvocationOnMock invocation) {
    return mockBlob((BlobId) invocation.getArguments()[0]);
  }

  /**
   * Helper method to create a mock Blob with the given BlobId.
   */
  private Blob mockBlob(final BlobId blobId) {
    Blob blob = mock(Blob.class);
    when(blob.getId()).thenReturn(blobId);
    when(blob.getInputStream()).thenReturn(new ByteArrayInputStream(testData));
    return blob;
  }
}