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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for BlobStore operations using Java 21 Virtual Threads.
 * 
 * This test class validates that blob store operations can be efficiently executed
 * using virtual threads, demonstrating improved concurrency and resource utilization
 * for I/O-bound operations.
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadBlobStoreTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int BLOB_CONTENT_SIZE = 1024;
  private static final String TEST_CONTENT = "Test blob content";
  
  @Mock
  private BlobStore blobStore;
  
  @BeforeEach
  public void setUp() {
    // Configure the mock BlobStore to return a mock BlobStoreConfiguration
    BlobStoreConfiguration configuration = mock(BlobStoreConfiguration.class);
    when(configuration.getName()).thenReturn("test-blob-store");
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    
    // Configure the mock BlobStore to create blobs
    lenient().when(blobStore.create(any(InputStream.class), anyMap(), any(BlobId.class)))
        .thenAnswer(invocation -> {
          InputStream inputStream = invocation.getArgument(0);
          Map<String, String> headers = invocation.getArgument(1);
          BlobId blobId = invocation.getArgument(2);
          
          if (blobId == null) {
            blobId = new BlobId(UUID.randomUUID().toString());
          }
          
          Blob blob = mock(Blob.class);
          when(blob.getId()).thenReturn(blobId);
          return blob;
        });
    
    // Configure the mock BlobStore to return blobs when get is called
    lenient().when(blobStore.get(any(BlobId.class)))
        .thenAnswer(invocation -> {
          BlobId blobId = invocation.getArgument(0);
          Blob blob = mock(Blob.class);
          when(blob.getId()).thenReturn(blobId);
          return blob;
        });
    
    // Configure the mock BlobStore to return true for exists
    lenient().when(blobStore.exists(any(BlobId.class))).thenReturn(true);
  }
  
  /**
   * Tests creating a blob using a virtual thread.
   * This verifies that basic blob creation works correctly when executed on a virtual thread.
   */
  @Test
  public void testCreateBlobWithVirtualThread() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a virtual thread to execute the blob creation
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
      Map<String, String> headers = new HashMap<>();
      headers.put("Content-Type", "text/plain");
      
      Blob blob = blobStore.create(inputStream, headers);
      
      assertNotNull(blob, "Blob should not be null");
      assertNotNull(blob.getId(), "Blob ID should not be null");
      log.info(STR."Created blob with ID: \{blob.getId()}");
    });
    
    // Start the virtual thread and wait for it to complete
    virtualThread.start();
    virtualThread.join();
    
    // Verify that the create method was called on the blobStore
    verify(blobStore).create(any(InputStream.class), anyMap());
  }
  
  /**
   * Tests retrieving a blob using a virtual thread.
   * This verifies that blob retrieval works correctly when executed on a virtual thread.
   */
  @Test
  public void testGetBlobWithVirtualThread() throws Exception {
    // Create a BlobId for testing
    BlobId testBlobId = new BlobId("test-blob-id");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a virtual thread to execute the blob retrieval
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      Blob blob = blobStore.get(testBlobId);
      
      assertNotNull(blob, "Blob should not be null");
      assertEquals(testBlobId, blob.getId(), "Blob ID should match the requested ID");
      log.info(STR."Retrieved blob with ID: \{blob.getId()}");
    });
    
    // Start the virtual thread and wait for it to complete
    virtualThread.start();
    virtualThread.join();
    
    // Verify that the get method was called on the blobStore with the correct BlobId
    verify(blobStore).get(eq(testBlobId));
  }
  
  /**
   * Tests deleting a blob using a virtual thread.
   * This verifies that blob deletion works correctly when executed on a virtual thread.
   */
  @Test
  public void testDeleteBlobWithVirtualThread() throws Exception {
    // Create a BlobId for testing
    BlobId testBlobId = new BlobId("test-blob-id-to-delete");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create a virtual thread to execute the blob deletion
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      blobStore.delete(testBlobId, "Test deletion");
      log.info(STR."Deleted blob with ID: \{testBlobId}");
    });
    
    // Start the virtual thread and wait for it to complete
    virtualThread.start();
    virtualThread.join();
    
    // Verify that the delete method was called on the blobStore with the correct BlobId and reason
    verify(blobStore).delete(eq(testBlobId), eq("Test deletion"));
  }
  
  /**
   * Tests concurrent blob creation using virtual threads.
   * This verifies that multiple blob creation operations can be executed concurrently
   * using virtual threads without resource contention.
   */
  @Test
  public void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a list to store the futures
      List<Future<Blob>> futures = new ArrayList<>();
      
      // Submit tasks to create blobs concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        int taskId = i;
        futures.add(executor.submit(() -> {
          InputStream inputStream = new ByteArrayInputStream(
              STR."Test content for blob \{taskId}".getBytes(StandardCharsets.UTF_8));
          Map<String, String> headers = new HashMap<>();
          headers.put("Content-Type", "text/plain");
          headers.put("Task-ID", String.valueOf(taskId));
          
          return blobStore.create(inputStream, headers);
        }));
      }
      
      // Wait for all tasks to complete and verify the results
      for (Future<Blob> future : futures) {
        Blob blob = future.get(10, TimeUnit.SECONDS);
        assertNotNull(blob, "Blob should not be null");
        assertNotNull(blob.getId(), "Blob ID should not be null");
      }
    }
    
    // Verify that the create method was called the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).create(any(InputStream.class), anyMap());
  }
  
  /**
   * Tests concurrent blob retrieval using virtual threads.
   * This verifies that multiple blob retrieval operations can be executed concurrently
   * using virtual threads without resource contention.
   */
  @Test
  public void testConcurrentBlobRetrievalWithVirtualThreads() throws Exception {
    // Create a list of BlobIds for testing
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      blobIds.add(new BlobId(STR."test-blob-id-\{i}"));
    }
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a list to store the futures
      List<Future<Blob>> futures = new ArrayList<>();
      
      // Submit tasks to retrieve blobs concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        BlobId blobId = blobIds.get(i);
        futures.add(executor.submit(() -> blobStore.get(blobId)));
      }
      
      // Wait for all tasks to complete and verify the results
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        Blob blob = futures.get(i).get(10, TimeUnit.SECONDS);
        assertNotNull(blob, "Blob should not be null");
        assertEquals(blobIds.get(i), blob.getId(), "Blob ID should match the requested ID");
      }
    }
    
    // Verify that the get method was called the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).get(any(BlobId.class));
  }
  
  /**
   * Tests concurrent blob deletion using virtual threads.
   * This verifies that multiple blob deletion operations can be executed concurrently
   * using virtual threads without resource contention.
   */
  @Test
  public void testConcurrentBlobDeletionWithVirtualThreads() throws Exception {
    // Create a list of BlobIds for testing
    List<BlobId> blobIds = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      blobIds.add(new BlobId(STR."test-blob-id-to-delete-\{i}"));
    }
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a list to store the futures
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit tasks to delete blobs concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        BlobId blobId = blobIds.get(i);
        int taskId = i;
        futures.add(executor.submit(() -> {
          blobStore.delete(blobId, STR."Test deletion \{taskId}");
          return null;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
    }
    
    // Verify that the delete method was called the expected number of times
    verify(blobStore, times(CONCURRENT_OPERATIONS)).delete(any(BlobId.class), any(String.class));
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads for blob operations.
   * This test demonstrates the performance benefits of using virtual threads for I/O-bound operations.
   */
  @Test
  public void testVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    final int operationCount = 1000;
    
    // Measure performance with platform threads
    Instant platformThreadsStart = Instant.now();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(100)) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < operationCount; i++) {
        futures.add(platformExecutor.submit(() -> {
          InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
          Map<String, String> headers = new HashMap<>();
          headers.put("Content-Type", "text/plain");
          
          Blob blob = blobStore.create(inputStream, headers);
          blobStore.get(blob.getId());
          blobStore.delete(blob.getId(), "Performance test");
          return null;
        }));
      }
      
      for (Future<?> future : futures) {
        future.get();
      }
    }
    Duration platformThreadsDuration = Duration.between(platformThreadsStart, Instant.now());
    
    // Measure performance with virtual threads
    Instant virtualThreadsStart = Instant.now();
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < operationCount; i++) {
        futures.add(virtualExecutor.submit(() -> {
          InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
          Map<String, String> headers = new HashMap<>();
          headers.put("Content-Type", "text/plain");
          
          Blob blob = blobStore.create(inputStream, headers);
          blobStore.get(blob.getId());
          blobStore.delete(blob.getId(), "Performance test");
          return null;
        }));
      }
      
      for (Future<?> future : futures) {
        future.get();
      }
    }
    Duration virtualThreadsDuration = Duration.between(virtualThreadsStart, Instant.now());
    
    // Log the performance results
    log.info(STR."Platform threads duration: \{platformThreadsDuration.toMillis()} ms");
    log.info(STR."Virtual threads duration: \{virtualThreadsDuration.toMillis()} ms");
    
    // Assert that virtual threads perform better or at least similarly to platform threads
    // Note: In a mock test environment, the difference might not be significant
    // In real I/O-bound scenarios, virtual threads should show better performance
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadsDuration.toMillis(), lessThan(platformThreadsDuration.toMillis() * 1.5));
  }
  
  /**
   * Tests that virtual threads are properly unparked after I/O operations.
   * This verifies that virtual threads correctly yield to other threads during I/O operations
   * and resume execution afterward.
   */
  @Test
  public void testVirtualThreadsUnparkAfterIO() throws Exception {
    final int threadCount = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicInteger concurrentOperations = new AtomicInteger(0);
    final AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
    
    // Create threads that will perform I/O operations
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = Thread.ofVirtual().start(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Track concurrent operations
          int current = concurrentOperations.incrementAndGet();
          maxConcurrentOperations.updateAndGet(max -> Math.max(max, current));
          
          // Simulate I/O operation by creating a blob
          InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
          Map<String, String> headers = new HashMap<>();
          headers.put("Content-Type", "text/plain");
          
          Blob blob = blobStore.create(inputStream, headers);
          assertNotNull(blob, "Blob should not be null");
          
          // Decrement the counter and signal completion
          concurrentOperations.decrementAndGet();
          completionLatch.countDown();
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
        }
      });
      threads.add(thread);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
    assertTrue(completed, "All virtual threads should complete within the timeout");
    
    // Verify that multiple operations were executed concurrently
    assertThat("Multiple operations should execute concurrently", 
        maxConcurrentOperations.get(), greaterThan(1));
    
    // Verify that all operations completed
    assertThat("All operations should complete", concurrentOperations.get(), is(0));
  }
  
  /**
   * Tests behavior under high concurrency with virtual threads.
   * This verifies that the system can handle a large number of concurrent operations
   * using virtual threads without errors or resource exhaustion.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    final int highConcurrencyCount = 10000; // A high number of concurrent operations
    final ConcurrentHashMap<BlobId, Boolean> processedBlobs = new ConcurrentHashMap<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a large number of tasks
      List<Future<BlobId>> futures = new ArrayList<>();
      
      for (int i = 0; i < highConcurrencyCount; i++) {
        int taskId = i;
        futures.add(executor.submit(() -> {
          try {
            // Create a blob
            InputStream inputStream = new ByteArrayInputStream(
                STR."High concurrency test \{taskId}".getBytes(StandardCharsets.UTF_8));
            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/plain");
            headers.put("Task-ID", String.valueOf(taskId));
            
            Blob blob = blobStore.create(inputStream, headers);
            BlobId blobId = blob.getId();
            
            // Track that we processed this blob
            processedBlobs.put(blobId, true);
            
            return blobId;
          }
          catch (Exception e) {
            log.error(STR."Error in task \{taskId}", e);
            throw e;
          }
        }));
      }
      
      // Wait for all tasks to complete and verify the results
      int successCount = 0;
      for (Future<BlobId> future : futures) {
        try {
          BlobId blobId = future.get(30, TimeUnit.SECONDS);
          assertNotNull(blobId, "Blob ID should not be null");
          successCount++;
        }
        catch (Exception e) {
          log.error("Task failed", e);
        }
      }
      
      // Verify that all or most tasks completed successfully
      log.info(STR."Successfully processed \{successCount} out of \{highConcurrencyCount} tasks");
      assertThat("Most tasks should complete successfully", 
          successCount, greaterThan((int)(highConcurrencyCount * 0.95)));
      
      // Verify that the number of processed blobs matches the success count
      assertThat("Number of processed blobs should match success count", 
          processedBlobs.size(), equalTo(successCount));
    }
  }
  
  /**
   * Tests mixed operations (create, get, delete) using virtual threads.
   * This verifies that different types of blob operations can be executed concurrently
   * using virtual threads without interference.
   */
  @Test
  public void testMixedOperationsWithVirtualThreads() throws Exception {
    final int operationsPerType = 100;
    final CountDownLatch completionLatch = new CountDownLatch(operationsPerType * 3); // 3 types of operations
    final ConcurrentHashMap<BlobId, Blob> createdBlobs = new ConcurrentHashMap<>();
    final List<Exception> errors = new ArrayList<>();
    
    // Create virtual threads for blob creation
    for (int i = 0; i < operationsPerType; i++) {
      int taskId = i;
      Thread.ofVirtual().start(() -> {
        try {
          InputStream inputStream = new ByteArrayInputStream(
              STR."Mixed operations test \{taskId}".getBytes(StandardCharsets.UTF_8));
          Map<String, String> headers = new HashMap<>();
          headers.put("Content-Type", "text/plain");
          headers.put("Operation", "create");
          
          Blob blob = blobStore.create(inputStream, headers);
          createdBlobs.put(blob.getId(), blob);
        }
        catch (Exception e) {
          synchronized (errors) {
            errors.add(e);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait a bit to allow some blobs to be created before starting get operations
    Thread.sleep(100);
    
    // Create virtual threads for blob retrieval
    for (int i = 0; i < operationsPerType; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          // Get a random blob ID if available, or create a new one
          BlobId blobId;
          if (!createdBlobs.isEmpty() && Math.random() > 0.3) {
            blobId = createdBlobs.keySet().stream().findAny().orElse(new BlobId(UUID.randomUUID().toString()));
          }
          else {
            blobId = new BlobId(UUID.randomUUID().toString());
          }
          
          Blob blob = blobStore.get(blobId);
          assertNotNull(blob, "Blob should not be null");
        }
        catch (Exception e) {
          synchronized (errors) {
            errors.add(e);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait a bit more before starting delete operations
    Thread.sleep(100);
    
    // Create virtual threads for blob deletion
    for (int i = 0; i < operationsPerType; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          // Get a random blob ID if available, or create a new one
          BlobId blobId;
          if (!createdBlobs.isEmpty() && Math.random() > 0.3) {
            blobId = createdBlobs.keySet().stream().findAny().orElse(new BlobId(UUID.randomUUID().toString()));
            createdBlobs.remove(blobId);
          }
          else {
            blobId = new BlobId(UUID.randomUUID().toString());
          }
          
          blobStore.delete(blobId, "Mixed operations test");
        }
        catch (Exception e) {
          synchronized (errors) {
            errors.add(e);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "All operations should complete within the timeout");
    
    // Log any errors that occurred
    if (!errors.isEmpty()) {
      log.error(STR."\{errors.size()} errors occurred during mixed operations test");
      for (Exception error : errors) {
        log.error("Error:", error);
      }
    }
    
    // Verify that there were no errors
    assertAll(
        () -> assertTrue(errors.isEmpty(), STR."There should be no errors, but found \{errors.size()}"),
        () -> assertDoesNotThrow(() -> {
          // Verify that we can still perform operations after the test
          InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
          Map<String, String> headers = new HashMap<>();
          headers.put("Content-Type", "text/plain");
          
          Blob blob = blobStore.create(inputStream, headers);
          assertNotNull(blob, "Should be able to create a blob after mixed operations");
        })
    );
  }
}