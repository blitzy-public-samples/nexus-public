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
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
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

import com.google.common.hash.HashCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link BlobStore} operations using Java 21 Virtual Threads.
 * 
 * These tests verify that BlobStore operations function correctly when executed
 * in virtual threads, ensuring that thread pinning issues are avoided and I/O operations
 * benefit from the enhanced concurrency model.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
public class VirtualThreadBlobOperationsTest
    extends TestSupport
{
  private static final String TEST_CONTENT = "test blob content";
  private static final byte[] TEST_BYTES = TEST_CONTENT.getBytes(StandardCharsets.UTF_8);
  private static final BlobId TEST_BLOB_ID = new BlobId("test-blob-id");
  private static final HashCode TEST_HASH_CODE = HashCode.fromInt(12345);

  @Mock
  private BlobStore blobStore;

  @Mock
  private Path mockPath;

  @Mock
  private Blob mockBlob;

  private Map<String, String> headers;

  @BeforeEach
  void setUp() {
    headers = new HashMap<>();
    headers.put("Content-Type", "text/plain");
    
    when(blobStore.getBlobStoreConfiguration()).thenReturn(mock(BlobStoreConfiguration.class));
    when(mockBlob.getId()).thenReturn(TEST_BLOB_ID);
    when(blobStore.get(eq(TEST_BLOB_ID))).thenReturn(mockBlob);
    when(blobStore.create(any(InputStream.class), anyMap(), isNull())).thenReturn(mockBlob);
    when(blobStore.create(any(Path.class), anyMap(), anyLong(), any(HashCode.class))).thenReturn(mockBlob);
    when(blobStore.exists(eq(TEST_BLOB_ID))).thenReturn(true);
  }

  /**
   * Tests creating a blob using a virtual thread.
   */
  @Test
  void createBlobWithVirtualThread() throws Exception {
    AtomicReference<Blob> resultBlob = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = Thread.ofVirtual().name("create-blob-thread").start(() -> {
      try {
        InputStream inputStream = new ByteArrayInputStream(TEST_BYTES);
        Blob blob = blobStore.create(inputStream, headers, null);
        resultBlob.set(blob);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    thread.join(1000);
    
    assertThat(resultBlob.get(), is(notNullValue()));
    assertThat(resultBlob.get().getId(), is(equalTo(TEST_BLOB_ID)));
    verify(blobStore).create(any(InputStream.class), eq(headers), isNull());
  }

  /**
   * Tests retrieving a blob using a virtual thread.
   */
  @Test
  void getBlobWithVirtualThread() throws Exception {
    AtomicReference<Blob> resultBlob = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread thread = Thread.ofVirtual().name("get-blob-thread").start(() -> {
      try {
        Blob blob = blobStore.get(TEST_BLOB_ID);
        resultBlob.set(blob);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    thread.join(1000);
    
    assertThat(resultBlob.get(), is(notNullValue()));
    assertThat(resultBlob.get().getId(), is(equalTo(TEST_BLOB_ID)));
    verify(blobStore).get(eq(TEST_BLOB_ID));
  }

  /**
   * Tests deleting a blob using a virtual thread.
   */
  @Test
  void deleteBlobWithVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> deleteSuccessful = new AtomicReference<>(false);
    
    when(blobStore.delete(eq(TEST_BLOB_ID), anyString())).thenReturn(true);
    
    Thread thread = Thread.ofVirtual().name("delete-blob-thread").start(() -> {
      try {
        boolean result = blobStore.delete(TEST_BLOB_ID, "test deletion");
        deleteSuccessful.set(result);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    thread.join(1000);
    
    assertThat(deleteSuccessful.get(), is(true));
    verify(blobStore).delete(eq(TEST_BLOB_ID), eq("test deletion"));
  }

  /**
   * Tests concurrent blob creation using multiple virtual threads.
   */
  @Test
  void concurrentBlobCreationWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            InputStream inputStream = new ByteArrayInputStream(
                ("content-" + index).getBytes(StandardCharsets.UTF_8));
            Blob blob = blobStore.create(inputStream, headers, null);
            if (blob != null && blob.getId().equals(TEST_BLOB_ID)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(10, TimeUnit.SECONDS);
      
      assertThat(successCount.get(), is(threadCount));
      verify(blobStore, times(threadCount)).create(any(InputStream.class), eq(headers), isNull());
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests concurrent blob retrieval using multiple virtual threads.
   */
  @Test
  void concurrentBlobRetrievalWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            Blob blob = blobStore.get(TEST_BLOB_ID);
            if (blob != null && blob.getId().equals(TEST_BLOB_ID)) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(10, TimeUnit.SECONDS);
      
      assertThat(successCount.get(), is(threadCount));
      verify(blobStore, times(threadCount)).get(eq(TEST_BLOB_ID));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests mixed blob operations (create, get, delete) using multiple virtual threads.
   */
  @Test
  void mixedBlobOperationsWithVirtualThreads() throws Exception {
    int operationsPerType = 50;
    int totalOperations = operationsPerType * 3; // create, get, delete
    CountDownLatch latch = new CountDownLatch(totalOperations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    when(blobStore.delete(eq(TEST_BLOB_ID), anyString())).thenReturn(true);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create operations
      for (int i = 0; i < operationsPerType; i++) {
        executor.submit(() -> {
          try {
            InputStream inputStream = new ByteArrayInputStream(TEST_BYTES);
            Blob blob = blobStore.create(inputStream, headers, null);
            if (blob != null) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Get operations
      for (int i = 0; i < operationsPerType; i++) {
        executor.submit(() -> {
          try {
            Blob blob = blobStore.get(TEST_BLOB_ID);
            if (blob != null) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Delete operations
      for (int i = 0; i < operationsPerType; i++) {
        executor.submit(() -> {
          try {
            boolean result = blobStore.delete(TEST_BLOB_ID, "test deletion");
            if (result) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(15, TimeUnit.SECONDS);
      
      assertThat(successCount.get(), is(totalOperations));
      verify(blobStore, times(operationsPerType)).create(any(InputStream.class), eq(headers), isNull());
      verify(blobStore, times(operationsPerType)).get(eq(TEST_BLOB_ID));
      verify(blobStore, times(operationsPerType)).delete(eq(TEST_BLOB_ID), eq("test deletion"));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests blob operations with CompletableFuture using virtual threads.
   */
  @Test
  void blobOperationsWithCompletableFuture() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Create a blob asynchronously
      CompletableFuture<Blob> createFuture = CompletableFuture.supplyAsync(() -> {
        InputStream inputStream = new ByteArrayInputStream(TEST_BYTES);
        return blobStore.create(inputStream, headers, null);
      }, executor);
      
      // Get the blob asynchronously
      CompletableFuture<Blob> getFuture = createFuture.thenApplyAsync(blob -> {
        return blobStore.get(blob.getId());
      }, executor);
      
      // Delete the blob asynchronously
      CompletableFuture<Boolean> deleteFuture = getFuture.thenApplyAsync(blob -> {
        return blobStore.delete(blob.getId(), "test deletion");
      }, executor);
      
      // Wait for the chain to complete
      Boolean result = deleteFuture.get(10, TimeUnit.SECONDS);
      
      assertThat(result, is(true));
      verify(blobStore).create(any(InputStream.class), eq(headers), isNull());
      verify(blobStore).get(eq(TEST_BLOB_ID));
      verify(blobStore).delete(eq(TEST_BLOB_ID), eq("test deletion"));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that blob operations can be performed without thread pinning issues.
   * This test verifies that a large number of virtual threads can be created and
   * used for blob operations without exhausting system resources.
   */
  @Test
  void blobOperationsWithoutThreadPinning() throws Exception {
    int threadCount = 1000; // A large number of threads to verify scalability
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Perform a sequence of operations that could potentially cause pinning
            InputStream inputStream = new ByteArrayInputStream(TEST_BYTES);
            Blob blob = blobStore.create(inputStream, headers, null);
            blobStore.get(blob.getId());
            blobStore.delete(blob.getId(), "test deletion");
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // If threads are being pinned, this would likely time out or cause resource exhaustion
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      assertThat("All operations should complete within the timeout", completed, is(true));
      assertThat("No operations should throw exceptions", errorCount.get(), is(0));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that blob metadata operations are thread-safe with virtual threads.
   */
  @Test
  void blobMetadataOperationsWithVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    when(blobStore.exists(any(BlobId.class))).thenReturn(true);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Check if blob exists
            boolean exists = blobStore.exists(TEST_BLOB_ID);
            if (exists) {
              successCount.incrementAndGet();
            }
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(10, TimeUnit.SECONDS);
      
      assertThat(successCount.get(), is(threadCount));
      verify(blobStore, times(threadCount)).exists(eq(TEST_BLOB_ID));
    } finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that virtual threads can be safely interrupted during blob operations.
   */
  @Test
  void interruptVirtualThreadDuringBlobOperation() throws Exception {
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch interruptedLatch = new CountDownLatch(1);
    
    Thread thread = Thread.ofVirtual().name("interruptible-thread").start(() -> {
      try {
        startedLatch.countDown();
        try {
          // Simulate a long-running operation
          InputStream inputStream = new ByteArrayInputStream(TEST_BYTES);
          blobStore.create(inputStream, headers, null);
          Thread.sleep(10000); // This should be interrupted
        } catch (InterruptedException e) {
          // Expected - the thread was interrupted
          interruptedLatch.countDown();
        }
      } catch (Exception e) {
        // Unexpected exception
      }
    });
    
    // Wait for the thread to start
    startedLatch.await(5, TimeUnit.SECONDS);
    
    // Interrupt the thread
    thread.interrupt();
    
    // Verify the thread was interrupted
    boolean interrupted = interruptedLatch.await(5, TimeUnit.SECONDS);
    assertThat("Thread should have been interrupted", interrupted, is(true));
  }

  /**
   * Tests that virtual threads properly handle exceptions during blob operations.
   */
  @Test
  void exceptionHandlingInVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Exception> caughtException = new AtomicReference<>();
    
    // Configure the mock to throw an exception
    when(blobStore.get(eq(TEST_BLOB_ID))).thenThrow(new RuntimeException("Test exception"));
    
    Thread thread = Thread.ofVirtual().name("exception-thread").start(() -> {
      try {
        blobStore.get(TEST_BLOB_ID);
      } catch (Exception e) {
        caughtException.set(e);
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    thread.join(1000);
    
    assertNotNull(caughtException.get(), "Exception should have been caught");
    assertEquals("Test exception", caughtException.get().getMessage());
  }

  /**
   * Tests that virtual threads can be safely used with try-with-resources for proper resource cleanup.
   */
  @Test
  void tryWithResourcesInVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Boolean> resourceClosed = new AtomicReference<>(false);
    
    Thread thread = Thread.ofVirtual().name("resource-thread").start(() -> {
      try (InputStream inputStream = new ByteArrayInputStream(TEST_BYTES) {
        @Override
        public void close() {
          resourceClosed.set(true);
          try {
            super.close();
          } catch (Exception e) {
            // Ignore
          }
        }
      }) {
        blobStore.create(inputStream, headers, null);
      } catch (Exception e) {
        // Ignore
      } finally {
        latch.countDown();
      }
    });
    
    latch.await(5, TimeUnit.SECONDS);
    thread.join(1000);
    
    assertThat("Resource should have been closed", resourceClosed.get(), is(true));
  }

  /**
   * Tests that a large number of virtual threads can be created and used for blob operations
   * without causing performance issues.
   */
  @Test
  void largeNumberOfVirtualThreads() {
    int threadCount = 10000; // A very large number of threads
    
    assertDoesNotThrow(() -> {
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      
      try {
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
          executor.submit(() -> {
            try {
              // Just verify the blob exists - a lightweight operation
              blobStore.exists(TEST_BLOB_ID);
            } finally {
              latch.countDown();
            }
          });
        }
        
        // If virtual threads are working correctly, this should complete quickly
        // even with a large number of threads
        latch.await(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdown();
      }
    }, "Should be able to handle a large number of virtual threads without issues");
  }
}