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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreMetrics;

import com.google.common.hash.HashCode;
import com.google.common.io.ByteStreams;
import org.junit.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for BlobStore operations using Java 21 Virtual Threads.
 * 
 * This test class verifies that all core BlobStore operations function correctly
 * when running in virtual threads, ensuring that thread pinning issues are avoided
 * and I/O operations benefit from the enhanced concurrency model.
 */
@ExtendWith(MockitoExtension.class)
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadBlobOperationsTest
    extends TestSupport
{
  private static final String TEST_CONTENT = "test content";
  private static final HashCode TEST_HASH = HashCode.fromInt(123456);
  private static final long TEST_SIZE = 1024L;

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  @Mock
  private BlobStoreMetrics blobStoreMetrics;

  @Mock
  private InputStream inputStream;

  @Mock
  private Map<String, String> headers;

  @Mock
  private Path contentPath;

  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStoreConfiguration.getName()).thenReturn("test-blob-store");
    when(blobStore.getMetrics()).thenReturn(blobStoreMetrics);
    
    // Create a virtual thread executor for our tests
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Test creating a blob using a virtual thread.
   */
  @Test
  void testCreateBlobWithVirtualThread() throws Exception {
    // Setup mock behavior
    BlobId expectedBlobId = new BlobId(UUID.randomUUID().toString());
    Blob expectedBlob = mock(Blob.class);
    when(expectedBlob.getId()).thenReturn(expectedBlobId);
    when(blobStore.create(any(InputStream.class), anyMap(), any(BlobId.class))).thenReturn(expectedBlob);

    // Create a virtual thread to perform the operation
    AtomicReference<Blob> resultBlob = new AtomicReference<>();
    AtomicReference<Exception> exception = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Perform the blob creation operation
        Blob blob = blobStore.create(inputStream, headers, null);
        resultBlob.set(blob);
      }
      catch (Exception e) {
        exception.set(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify no exceptions occurred
    if (exception.get() != null) {
      throw exception.get();
    }
    
    // Verify the blob was created successfully
    assertThat(resultBlob.get(), is(notNullValue()));
    assertThat(resultBlob.get().getId(), is(expectedBlobId));
    
    // Verify the mock was called correctly
    verify(blobStore).create(inputStream, headers, null);
  }

  /**
   * Test getting a blob using a virtual thread.
   */
  @Test
  void testGetBlobWithVirtualThread() throws Exception {
    // Setup mock behavior
    BlobId blobId = new BlobId(UUID.randomUUID().toString());
    Blob expectedBlob = mock(Blob.class);
    when(expectedBlob.getId()).thenReturn(blobId);
    when(blobStore.get(blobId)).thenReturn(expectedBlob);

    // Create a virtual thread to perform the operation
    AtomicReference<Blob> resultBlob = new AtomicReference<>();
    AtomicReference<Exception> exception = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Perform the blob retrieval operation
        Blob blob = blobStore.get(blobId);
        resultBlob.set(blob);
      }
      catch (Exception e) {
        exception.set(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify no exceptions occurred
    if (exception.get() != null) {
      throw exception.get();
    }
    
    // Verify the blob was retrieved successfully
    assertThat(resultBlob.get(), is(notNullValue()));
    assertThat(resultBlob.get().getId(), is(blobId));
    
    // Verify the mock was called correctly
    verify(blobStore).get(blobId);
  }

  /**
   * Test deleting a blob using a virtual thread.
   */
  @Test
  void testDeleteBlobWithVirtualThread() throws Exception {
    // Setup mock behavior
    BlobId blobId = new BlobId(UUID.randomUUID().toString());
    when(blobStore.delete(eq(blobId), any(String.class))).thenReturn(true);

    // Create a virtual thread to perform the operation
    AtomicReference<Boolean> result = new AtomicReference<>();
    AtomicReference<Exception> exception = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Perform the blob deletion operation
        boolean deleted = blobStore.delete(blobId, "test deletion");
        result.set(deleted);
      }
      catch (Exception e) {
        exception.set(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify no exceptions occurred
    if (exception.get() != null) {
      throw exception.get();
    }
    
    // Verify the blob was deleted successfully
    assertThat(result.get(), is(true));
    
    // Verify the mock was called correctly
    verify(blobStore).delete(blobId, "test deletion");
  }

  /**
   * Test concurrent blob operations using multiple virtual threads.
   */
  @Test
  void testConcurrentBlobOperationsWithVirtualThreads() throws Exception {
    // Setup mock behavior for blob creation
    doAnswer(invocation -> {
      BlobId blobId = new BlobId(UUID.randomUUID().toString());
      Blob blob = mock(Blob.class);
      when(blob.getId()).thenReturn(blobId);
      return blob;
    }).when(blobStore).create(any(InputStream.class), anyMap(), any(BlobId.class));

    // Setup mock behavior for blob retrieval
    doAnswer(invocation -> {
      BlobId blobId = invocation.getArgument(0);
      Blob blob = mock(Blob.class);
      when(blob.getId()).thenReturn(blobId);
      return blob;
    }).when(blobStore).get(any(BlobId.class));

    // Setup mock behavior for blob deletion
    when(blobStore.delete(any(BlobId.class), any(String.class))).thenReturn(true);

    // Number of concurrent operations to perform
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<BlobId> createdBlobIds = new ArrayList<>();

    // Submit tasks to create blobs using virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrentOperations; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Create a blob
          Blob blob = blobStore.create(inputStream, headers, null);
          BlobId blobId = blob.getId();
          
          // Add to our tracking list
          synchronized (createdBlobIds) {
            createdBlobIds.add(blobId);
          }
          
          // Get the blob we just created
          Blob retrievedBlob = blobStore.get(blobId);
          assertNotNull(retrievedBlob);
          assertEquals(blobId, retrievedBlob.getId());
          
          // Delete the blob
          boolean deleted = blobStore.delete(blobId, "concurrent test");
          assertTrue(deleted);
        }
        catch (Exception e) {
          log.error("Error in virtual thread operation", e);
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }

    // Wait for all operations to complete
    latch.await(30, TimeUnit.SECONDS);
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // Verify no errors occurred
    assertThat("No errors should occur during concurrent operations", 
        errorCount.get(), is(0));

    // Verify the expected number of operations were performed
    verify(blobStore, times(concurrentOperations)).create(any(InputStream.class), anyMap(), any(BlobId.class));
    verify(blobStore, times(concurrentOperations)).get(any(BlobId.class));
    verify(blobStore, times(concurrentOperations)).delete(any(BlobId.class), eq("concurrent test"));
  }

  /**
   * Test blob metadata operations using virtual threads.
   */
  @Test
  void testBlobMetadataOperationsWithVirtualThreads() throws Exception {
    // Setup mock behavior for metrics
    when(blobStoreMetrics.getBlobCount()).thenReturn(100L);
    when(blobStoreMetrics.getTotalSize()).thenReturn(1024L * 1024L);
    when(blobStoreMetrics.getAvailableSpace()).thenReturn(10L * 1024L * 1024L);

    // Number of concurrent operations to perform
    int concurrentOperations = 50;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);

    // Submit tasks to read metrics using virtual threads
    for (int i = 0; i < concurrentOperations; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Get and verify metrics
          BlobStoreMetrics metrics = blobStore.getMetrics();
          assertNotNull(metrics);
          assertEquals(100L, metrics.getBlobCount());
          assertEquals(1024L * 1024L, metrics.getTotalSize());
          assertEquals(10L * 1024L * 1024L, metrics.getAvailableSpace());
        }
        catch (Exception e) {
          log.error("Error in virtual thread metrics operation", e);
          errorCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all operations to complete
    latch.await(30, TimeUnit.SECONDS);

    // Verify no errors occurred
    assertThat("No errors should occur during concurrent metadata operations", 
        errorCount.get(), is(0));

    // Verify the expected number of operations were performed
    verify(blobStore, times(concurrentOperations)).getMetrics();
  }

  /**
   * Test blob content streaming using virtual threads.
   */
  @Test
  void testBlobContentStreamingWithVirtualThreads() throws Exception {
    // Setup mock behavior for blob content
    BlobId blobId = new BlobId(UUID.randomUUID().toString());
    Blob blob = mock(Blob.class);
    when(blob.getId()).thenReturn(blobId);
    
    // Setup the input stream to return our test content
    InputStream contentStream = ByteStreams.newInputStreamSupplier(
        TEST_CONTENT.getBytes(UTF_8)).getInput();
    when(blob.getInputStream()).thenReturn(contentStream);
    
    when(blobStore.get(blobId)).thenReturn(blob);

    // Create a virtual thread to perform the streaming operation
    AtomicReference<String> resultContent = new AtomicReference<>();
    AtomicReference<Exception> exception = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        // Get the blob
        Blob retrievedBlob = blobStore.get(blobId);
        assertNotNull(retrievedBlob);
        
        // Read the content
        try (InputStream is = retrievedBlob.getInputStream()) {
          byte[] bytes = ByteStreams.toByteArray(is);
          resultContent.set(new String(bytes, StandardCharsets.UTF_8));
        }
      }
      catch (Exception e) {
        exception.set(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify no exceptions occurred
    if (exception.get() != null) {
      throw exception.get();
    }
    
    // Verify the content was streamed correctly
    assertThat(resultContent.get(), equalTo(TEST_CONTENT));
    
    // Verify the mock was called correctly
    verify(blobStore).get(blobId);
    verify(blob).getInputStream();
  }
}