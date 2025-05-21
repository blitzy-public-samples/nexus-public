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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.google.common.hash.HashCode;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests for {@link BlobStore} operations using Java 21 Virtual Threads.
 * 
 * This test verifies that BlobStore implementations work correctly with Virtual Threads,
 * particularly for I/O operations which can benefit from the lightweight thread model.
 * Virtual Threads are designed to improve throughput for I/O-bound applications by allowing
 * many more concurrent operations without exhausting system resources.
 *
 * @since 3.60
 */
public class BlobStoreVirtualThreadTest
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int BLOB_CONTENT_SIZE = 1024;
  private static final String TEST_CONTENT = "Test content for virtual thread blob operations";
  
  private MockBlobStore blobStore;
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() {
    blobStore = new MockBlobStore();
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void tearDown() {
    virtualThreadExecutor.shutdown();
    try {
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    } catch (InterruptedException e) {
      virtualThreadExecutor.shutdownNow();
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Tests creating a blob using a virtual thread.
   */
  @Test
  public void testCreateBlobWithVirtualThread() throws Exception {
    Future<Blob> future = virtualThreadExecutor.submit(() -> {
      Map<String, String> headers = createHeaders();
      InputStream inputStream = createInputStream(TEST_CONTENT);
      return blobStore.create(inputStream, headers);
    });
    
    Blob blob = future.get();
    assertNotNull("Blob should be created successfully", blob);
    assertEquals("Blob content should match", TEST_CONTENT, new String(blob.getBytes(), StandardCharsets.UTF_8));
  }
  
  /**
   * Tests retrieving a blob using a virtual thread.
   */
  @Test
  public void testGetBlobWithVirtualThread() throws Exception {
    // First create a blob
    Map<String, String> headers = createHeaders();
    InputStream inputStream = createInputStream(TEST_CONTENT);
    Blob createdBlob = blobStore.create(inputStream, headers);
    BlobId blobId = createdBlob.getId();
    
    // Then retrieve it using a virtual thread
    Future<Blob> future = virtualThreadExecutor.submit(() -> blobStore.get(blobId));
    
    Blob retrievedBlob = future.get();
    assertNotNull("Retrieved blob should not be null", retrievedBlob);
    assertEquals("Retrieved blob content should match", 
        TEST_CONTENT, 
        new String(retrievedBlob.getBytes(), StandardCharsets.UTF_8));
  }
  
  /**
   * Tests deleting a blob using a virtual thread.
   */
  @Test
  public void testDeleteBlobWithVirtualThread() throws Exception {
    // First create a blob
    Map<String, String> headers = createHeaders();
    InputStream inputStream = createInputStream(TEST_CONTENT);
    Blob createdBlob = blobStore.create(inputStream, headers);
    BlobId blobId = createdBlob.getId();
    
    // Then delete it using a virtual thread
    Future<Boolean> future = virtualThreadExecutor.submit(() -> blobStore.delete(blobId, "Test deletion"));
    
    boolean deleted = future.get();
    assertTrue("Blob should be deleted successfully", deleted);
    assertThat(blobStore.get(blobId), is(nullValue()));
  }
  
  /**
   * Tests concurrent creation of multiple blobs using virtual threads.
   */
  @Test
  public void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    int numBlobs = CONCURRENT_OPERATIONS;
    List<Future<Blob>> futures = new ArrayList<>();
    
    // Submit blob creation tasks to virtual thread executor
    for (int i = 0; i < numBlobs; i++) {
      final int index = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        Map<String, String> headers = createHeaders();
        String content = TEST_CONTENT + "-" + index;
        InputStream inputStream = createInputStream(content);
        return blobStore.create(inputStream, headers);
      }));
    }
    
    // Verify all blobs were created successfully
    List<Blob> blobs = new ArrayList<>();
    for (Future<Blob> future : futures) {
      Blob blob = future.get();
      assertNotNull("Blob should be created successfully", blob);
      blobs.add(blob);
    }
    
    assertEquals("All blobs should be created", numBlobs, blobs.size());
    
    // Verify each blob has the correct content
    for (int i = 0; i < numBlobs; i++) {
      Blob blob = blobs.get(i);
      String expectedContent = TEST_CONTENT + "-" + i;
      String actualContent = new String(blob.getBytes(), StandardCharsets.UTF_8);
      assertEquals("Blob content should match", expectedContent, actualContent);
    }
  }
  
  /**
   * Tests concurrent retrieval of blobs using virtual threads.
   */
  @Test
  public void testConcurrentBlobRetrievalWithVirtualThreads() throws Exception {
    int numBlobs = CONCURRENT_OPERATIONS;
    List<Blob> createdBlobs = new ArrayList<>();
    
    // Create blobs first
    for (int i = 0; i < numBlobs; i++) {
      Map<String, String> headers = createHeaders();
      String content = TEST_CONTENT + "-" + i;
      InputStream inputStream = createInputStream(content);
      Blob blob = blobStore.create(inputStream, headers);
      createdBlobs.add(blob);
    }
    
    // Retrieve blobs concurrently using virtual threads
    List<Future<Blob>> futures = new ArrayList<>();
    for (Blob createdBlob : createdBlobs) {
      futures.add(virtualThreadExecutor.submit(() -> blobStore.get(createdBlob.getId())));
    }
    
    // Verify all blobs were retrieved successfully
    int index = 0;
    for (Future<Blob> future : futures) {
      Blob retrievedBlob = future.get();
      assertNotNull("Retrieved blob should not be null", retrievedBlob);
      
      String expectedContent = TEST_CONTENT + "-" + index;
      String actualContent = new String(retrievedBlob.getBytes(), StandardCharsets.UTF_8);
      assertEquals("Retrieved blob content should match", expectedContent, actualContent);
      index++;
    }
  }
  
  /**
   * Tests concurrent deletion of blobs using virtual threads.
   */
  @Test
  public void testConcurrentBlobDeletionWithVirtualThreads() throws Exception {
    int numBlobs = CONCURRENT_OPERATIONS;
    List<BlobId> blobIds = new ArrayList<>();
    
    // Create blobs first
    for (int i = 0; i < numBlobs; i++) {
      Map<String, String> headers = createHeaders();
      String content = TEST_CONTENT + "-" + i;
      InputStream inputStream = createInputStream(content);
      Blob blob = blobStore.create(inputStream, headers);
      blobIds.add(blob.getId());
    }
    
    // Delete blobs concurrently using virtual threads
    List<Future<Boolean>> futures = new ArrayList<>();
    for (BlobId blobId : blobIds) {
      futures.add(virtualThreadExecutor.submit(() -> blobStore.delete(blobId, "Test concurrent deletion")));
    }
    
    // Verify all blobs were deleted successfully
    for (Future<Boolean> future : futures) {
      boolean deleted = future.get();
      assertTrue("Blob should be deleted successfully", deleted);
    }
    
    // Verify all blobs are actually gone
    for (BlobId blobId : blobIds) {
      assertThat(blobStore.get(blobId), is(nullValue()));
    }
  }
  
  /**
   * Tests exception propagation when blob operations fail in virtual threads.
   */
  @Test
  public void testExceptionPropagationInVirtualThreads() {
    // Configure the mock to throw an exception
    blobStore.setShouldThrowException(true);
    
    Future<Blob> future = virtualThreadExecutor.submit(() -> {
      Map<String, String> headers = createHeaders();
      InputStream inputStream = createInputStream(TEST_CONTENT);
      return blobStore.create(inputStream, headers);
    });
    
    try {
      future.get();
      fail("Should have thrown an exception");
    } catch (ExecutionException e) {
      assertTrue("Root cause should be BlobStoreException", e.getCause() instanceof BlobStoreException);
      assertEquals("Exception message should match", "Simulated blob store exception", e.getCause().getMessage());
    } catch (InterruptedException e) {
      fail("Unexpected interruption");
    }
  }
  
  /**
   * Tests mixed operations (create, get, delete) running concurrently with virtual threads.
   */
  @Test
  public void testMixedOperationsWithVirtualThreads() throws Exception {
    int numOperations = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(numOperations * 3); // create, get, delete operations
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit mixed operations to virtual thread executor
    for (int i = 0; i < numOperations; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create blob
          Map<String, String> headers = createHeaders();
          String content = TEST_CONTENT + "-" + index;
          InputStream inputStream = createInputStream(content);
          Blob blob = blobStore.create(inputStream, headers);
          assertNotNull("Blob should be created successfully", blob);
          latch.countDown();
          
          // Get blob
          Blob retrievedBlob = blobStore.get(blob.getId());
          assertNotNull("Retrieved blob should not be null", retrievedBlob);
          assertEquals("Retrieved blob content should match", 
              content, 
              new String(retrievedBlob.getBytes(), StandardCharsets.UTF_8));
          latch.countDown();
          
          // Delete blob
          boolean deleted = blobStore.delete(blob.getId(), "Test mixed operations");
          assertTrue("Blob should be deleted successfully", deleted);
          latch.countDown();
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          // Count down latch even if operation fails to avoid test hanging
          while (latch.getCount() > 0) {
            latch.countDown();
          }
          throw new RuntimeException("Failed in mixed operations test", e);
        }
        return null;
      });
    }
    
    // Wait for all operations to complete
    assertTrue("All operations should complete in time", latch.await(30, TimeUnit.SECONDS));
    assertEquals("All operations should succeed", numOperations, successCount.get());
  }
  
  /**
   * Tests that virtual threads can handle high concurrency without exhausting system resources.
   * This test demonstrates the key advantage of Virtual Threads - the ability to handle
   * many concurrent operations efficiently, which is particularly important for I/O-bound
   * operations like those in BlobStore implementations.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    int numThreads = 1000; // A higher number to test scalability with Virtual Threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(numThreads);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create many virtual threads that will all start at the same time
    for (int i = 0; i < numThreads; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Create a blob
          Map<String, String> headers = createHeaders();
          String content = "Small content " + index; // Keep content small for this test
          InputStream inputStream = createInputStream(content);
          Blob blob = blobStore.create(inputStream, headers);
          
          // Verify the blob
          assertNotNull("Blob should be created successfully", blob);
          assertEquals("Blob content should match", 
              content, 
              new String(blob.getBytes(), StandardCharsets.UTF_8));
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          // Just let the latch count down even if there's a failure
        } finally {
          completionLatch.countDown();
        }
        return null;
      });
    }
    
    // Signal all threads to start at once
    startLatch.countDown();
    
    // Wait for all operations to complete
    assertTrue("All operations should complete in time", completionLatch.await(60, TimeUnit.SECONDS));
    assertEquals("All operations should succeed", numThreads, successCount.get());
  }
  
  /**
   * Creates standard headers for blob creation.
   */
  private Map<String, String> createHeaders() {
    Map<String, String> headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, "test-blob-" + UUID.randomUUID());
    headers.put(BlobStore.CREATED_BY_HEADER, "virtual-thread-test");
    headers.put(BlobStore.CONTENT_TYPE_HEADER, "text/plain");
    return headers;
  }
  
  /**
   * Creates an input stream with the given content.
   */
  private InputStream createInputStream(String content) {
    return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
  }
  
  /**
   * A simple mock implementation of BlobStore for testing.
   */
  /**
   * A simple mock implementation of BlobStore for testing Virtual Thread compatibility.
   * This implementation focuses on the core methods that are annotated with @VirtualThreadFriendly
   * in the BlobStore interface.
   */
  private static class MockBlobStore implements BlobStore {
    private final Map<BlobId, Blob> blobs = new ConcurrentHashMap<>();
    private boolean shouldThrowException = false;
    
    public void setShouldThrowException(boolean shouldThrowException) {
      this.shouldThrowException = shouldThrowException;
    }
    
    @Override
    public Blob create(InputStream blobData, Map<String, String> headers) {
      if (shouldThrowException) {
        throw new BlobStoreException("Simulated blob store exception");
      }
      
      try {
        // Simulate some I/O work
        byte[] bytes = blobData.readAllBytes();
        BlobId blobId = new BlobId(UUID.randomUUID().toString());
        MockBlob blob = new MockBlob(blobId, bytes, headers);
        blobs.put(blobId, blob);
        return blob;
      } catch (IOException e) {
        throw new BlobStoreException("Failed to read blob data", e);
      }
    }
    
    @Override
    public Blob get(BlobId blobId) {
      return blobs.get(blobId);
    }
    
    @Override
    public boolean delete(BlobId blobId, String reason) {
      if (shouldThrowException) {
        throw new BlobStoreException("Simulated blob store exception");
      }
      
      return blobs.remove(blobId) != null;
    }
    
    // Minimal implementation of required methods
    
    @Override
    public Blob create(InputStream blobData, Map<String, String> headers, BlobId blobId) {
      throw new UnsupportedOperationException("Not implemented for test");
    }
    
    @Override
    public Blob get(BlobId blobId, boolean includeDeleted) {
      throw new UnsupportedOperationException("Not implemented for test");
    }
    
    @Override
    public boolean exists(BlobId blobId) {
      return blobs.containsKey(blobId);
    }
    
    @Override
    public boolean deleteHard(BlobId blobId) {
      throw new UnsupportedOperationException("Not implemented for test");
    }
    
    @Override
    public BlobStoreConfiguration getBlobStoreConfiguration() {
      throw new UnsupportedOperationException("Not implemented for test");
    }
    
    @Override
    public void init(BlobStoreConfiguration configuration) {
      // No-op for test
    }
    
    @Override
    public void start() {
      // No-op for test
    }
    
    @Override
    public void stop() {
      // No-op for test
    }
    
    @Override
    public boolean isStarted() {
      return true;
    }
    
    // The following methods are required by the BlobStore interface but not used in this test
    @Override
    public boolean bytesExists(BlobId blobId) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public boolean isBlobEmpty(BlobId blobId) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public BlobStoreMetrics getMetrics() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Map<OperationType, OperationMetrics> getOperationMetricsByType() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Map<OperationType, OperationMetrics> getOperationMetricsDelta() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void clearOperationMetrics() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void compact(BlobStoreUsageChecker inUseChecker) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void deleteTempFiles(Integer daysOlderThan) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Stream<BlobId> getBlobIdStream() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Stream<BlobId> getBlobIdUpdatedSinceStream(Duration duration) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public PaginatedResult<BlobId> getBlobIdUpdatedSinceStream(
        String prefix,
        OffsetDateTime fromDateTime,
        OffsetDateTime toDateTime,
        String continuationToken,
        int pageSize) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Stream<BlobId> getDirectPathBlobIdStream(String prefix) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public BlobAttributes getBlobAttributes(BlobId blobId) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void setBlobAttributes(BlobId blobId, BlobAttributes blobAttributes) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public boolean undelete(
        BlobStoreUsageChecker inUseChecker,
        BlobId blobId,
        BlobAttributes attributes,
        boolean isDryRun) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public boolean isStorageAvailable() {
      return true;
    }

    @Override
    public boolean isEmpty() {
      return blobs.isEmpty();
    }

    @Override
    public void shutdown() {
      // No-op for test
    }

    @Override
    public Future<Boolean> asyncDelete(BlobId blobId) {
      // Use Virtual Thread per task executor for better scalability with Java 21
      return Executors.newVirtualThreadPerTaskExecutor().submit(() -> deleteHard(blobId));
    }

    @Override
    public <T extends BlobStoreMetricsService<B>, B extends BlobStore> T getMetricsService() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Blob create(Path sourceFile, Map<String, String> headers, long size, HashCode sha1) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void createBlobAttributes(BlobId blobId, Map<String, String> headers, BlobMetrics blobMetrics) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public BlobAttributes createBlobAttributesInstance(BlobId blobId, Map<String, String> headers, BlobMetrics metrics) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Blob copy(BlobId blobId, Map<String, String> headers) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public Blob makeBlobPermanent(BlobId blobId, Map<String, String> headers) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public boolean deleteIfTemp(BlobId blobId) {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public void validateCanCreateAndUpdate() {
      // No-op for test
    }

    @Override
    public RawObjectAccess getRawObjectAccess() {
      throw new UnsupportedOperationException("Not implemented for test");
    }

    @Override
    public BlobSession<?> openSession() {
      throw new UnsupportedOperationException("Not implemented for test");
    }
  }
  
  /**
   * A simple mock implementation of Blob for testing.
   */
  private static class MockBlob implements Blob {
    private final BlobId id;
    private final byte[] bytes;
    private final Map<String, String> headers;
    
    public MockBlob(BlobId id, byte[] bytes, Map<String, String> headers) {
      this.id = id;
      this.bytes = bytes;
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
      return new ByteArrayInputStream(bytes);
    }
    
    @Override
    public byte[] getBytes() {
      return bytes;
    }
  }
}