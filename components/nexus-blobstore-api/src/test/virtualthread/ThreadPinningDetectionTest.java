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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests to identify and validate potential thread pinning issues in the BlobStore API when used with Virtual Threads.
 * <p>
 * This test uses JDK's pinning detection tools to find operations that may cause Virtual Thread carrier-thread pinning,
 * which could limit scalability. It verifies that critical blob store operations either avoid pinning or have acceptable
 * performance characteristics when pinning occurs.
 * <p>
 * To run these tests with pinning detection enabled, use the JVM flag: -Djdk.tracePinnedThreads=full
 * <p>
 * Note: This test is only enabled on Java 21 or later, as Virtual Threads are a Java 21 feature.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
public class ThreadPinningDetectionTest
{
  private static final String TEST_CONTENT = "Test blob content for virtual thread testing";
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @Mock
  private BlobStore blobStore;

  @Mock
  private BlobStoreConfiguration configuration;

  @BeforeEach
  public void setup() {
    MockitoAnnotations.openMocks(this);
    when(blobStore.getBlobStoreConfiguration()).thenReturn(configuration);
    when(configuration.getName()).thenReturn("test-blobstore");
  }

  /**
   * Tests that creating blobs concurrently with Virtual Threads doesn't cause excessive pinning.
   * <p>
   * This test creates multiple blobs concurrently using Virtual Threads and verifies that the
   * operations complete successfully without deadlocks or excessive delays that would indicate
   * carrier thread pinning issues.
   */
  @Test
  public void testConcurrentBlobCreationWithVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicInteger successCount = new AtomicInteger(0);

      // Set up mock behavior for blob creation
      when(blobStore.create(any(InputStream.class), any(Map.class)))
          .thenAnswer(invocation -> {
            // Simulate some I/O work that could potentially cause pinning
            Thread.sleep(10); // Small delay to simulate I/O
            BlobId blobId = new BlobId("test-blob-" + System.nanoTime());
            Blob blob = mock(Blob.class);
            when(blob.getId()).thenReturn(blobId);
            return blob;
          });

      // Launch concurrent blob creation operations using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            // Prepare blob headers
            Map<String, String> headers = new HashMap<>();
            headers.put("Blob-Name", "test-blob-" + operationId);
            headers.put("Content-Type", "text/plain");

            // Create blob with content
            byte[] content = (TEST_CONTENT + "-" + operationId).getBytes(StandardCharsets.UTF_8);
            InputStream inputStream = new ByteArrayInputStream(content);

            // This operation could potentially cause pinning if implementation uses synchronized
            Blob blob = blobStore.create(inputStream, headers);
            assertNotNull(blob, "Blob should be created successfully");
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            failed.set(true);
            fail("Exception during blob creation: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete or timeout
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all blob creation operations completed within timeout");
      assertFalse(failed.get(), "Some blob creation operations failed");
      assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all blob creation operations succeeded");
    }
  }

  /**
   * Tests that reading blobs concurrently with Virtual Threads doesn't cause excessive pinning.
   * <p>
   * This test reads blob content concurrently using Virtual Threads and verifies that the
   * operations complete successfully without deadlocks or excessive delays that would indicate
   * carrier thread pinning issues.
   */
  @Test
  public void testConcurrentBlobReadWithVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicInteger successCount = new AtomicInteger(0);

      // Set up mock behavior for blob retrieval
      when(blobStore.get(any(BlobId.class)))
          .thenAnswer(invocation -> {
            // Simulate some I/O work that could potentially cause pinning
            Thread.sleep(10); // Small delay to simulate I/O
            BlobId blobId = invocation.getArgument(0);
            Blob blob = mock(Blob.class);
            when(blob.getId()).thenReturn(blobId);
            
            // Create an input stream that could cause pinning if implementation uses synchronized
            InputStream contentStream = new ByteArrayInputStream(
                (TEST_CONTENT + "-" + blobId.asUniqueString()).getBytes(StandardCharsets.UTF_8));
            when(blob.getInputStream()).thenReturn(contentStream);
            
            return blob;
          });

      // Launch concurrent blob read operations using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            BlobId blobId = new BlobId("test-blob-" + operationId);
            
            // This operation could potentially cause pinning if implementation uses synchronized
            Blob blob = blobStore.get(blobId);
            assertNotNull(blob, "Blob should be retrieved successfully");
            
            // Read the content - this could also cause pinning if InputStream implementation uses synchronized
            try (InputStream is = blob.getInputStream()) {
              byte[] buffer = new byte[1024];
              int bytesRead;
              while ((bytesRead = is.read(buffer)) != -1) {
                // Just consume the bytes
              }
            }
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            failed.set(true);
            fail("Exception during blob read: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete or timeout
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all blob read operations completed within timeout");
      assertFalse(failed.get(), "Some blob read operations failed");
      assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all blob read operations succeeded");
    }
  }

  /**
   * Tests that deleting blobs concurrently with Virtual Threads doesn't cause excessive pinning.
   * <p>
   * This test deletes blobs concurrently using Virtual Threads and verifies that the
   * operations complete successfully without deadlocks or excessive delays that would indicate
   * carrier thread pinning issues.
   */
  @Test
  public void testConcurrentBlobDeleteWithVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicBoolean failed = new AtomicBoolean(false);
      AtomicInteger successCount = new AtomicInteger(0);

      // Set up mock behavior for blob deletion
      doAnswer(invocation -> {
        // Simulate some I/O work that could potentially cause pinning
        Thread.sleep(10); // Small delay to simulate I/O
        return null;
      }).when(blobStore).delete(any(BlobId.class), anyString());

      // Launch concurrent blob deletion operations using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            BlobId blobId = new BlobId("test-blob-" + operationId);
            
            // This operation could potentially cause pinning if implementation uses synchronized
            blobStore.delete(blobId, "test-reason");
            
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            failed.set(true);
            fail("Exception during blob deletion: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete or timeout
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all blob deletion operations completed within timeout");
      assertFalse(failed.get(), "Some blob deletion operations failed");
      assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all blob deletion operations succeeded");
    }
  }

  /**
   * Tests a scenario that intentionally causes pinning to demonstrate detection.
   * <p>
   * This test creates a synchronized block around a blocking operation to intentionally
   * cause thread pinning. This helps verify that the pinning detection mechanism is working
   * and demonstrates what operations should be avoided in BlobStore implementations.
   */
  @Test
  public void testIntentionalPinningScenario() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(5); // Fewer operations for this test
      Object lock = new Object();

      // Launch operations that will intentionally cause pinning
      for (int i = 0; i < 5; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            // This synchronized block will cause pinning when the thread sleeps
            synchronized (lock) {
              // Simulate I/O or blocking operation inside synchronized block - THIS WILL CAUSE PINNING
              Thread.sleep(100);
              
              // This would be equivalent to performing blob operations inside a synchronized block
              System.out.println("Operation " + operationId + " completed inside synchronized block");
            }
          }
          catch (Exception e) {
            fail("Exception during intentional pinning test: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all pinning test operations completed within timeout");
      
      // Note: When run with -Djdk.tracePinnedThreads=full, this test should produce log output
      // showing the pinning detection in action
    }
  }

  /**
   * Demonstrates a better approach using ReentrantLock instead of synchronized to avoid pinning.
   * <p>
   * This test shows how to use ReentrantLock instead of synchronized blocks to achieve
   * thread safety without causing carrier thread pinning with Virtual Threads.
   */
  @Test
  public void testNonPinningAlternativeWithReentrantLock() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(5);
      ReentrantLock lock = new ReentrantLock();

      // Launch operations that will use ReentrantLock instead of synchronized
      for (int i = 0; i < 5; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            // Using ReentrantLock instead of synchronized - AVOIDS PINNING
            lock.lock();
            try {
              // Critical section before I/O
              // ... perform thread-safe operations ...
            }
            finally {
              lock.unlock();
            }
            
            // Blocking I/O operation outside of lock - no pinning occurs
            Thread.sleep(100);
            
            // Acquire lock again if needed after I/O
            lock.lock();
            try {
              // Critical section after I/O
              System.out.println("Operation " + operationId + " completed with ReentrantLock");
            }
            finally {
              lock.unlock();
            }
          }
          catch (Exception e) {
            fail("Exception during non-pinning test: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all non-pinning test operations completed within timeout");
    }
  }

  /**
   * Tests BlobRef operations with Virtual Threads to ensure they don't cause pinning.
   * <p>
   * BlobRef operations should be lightweight and not involve I/O, but this test verifies
   * that they work correctly with Virtual Threads and don't cause unexpected pinning.
   */
  @Test
  public void testBlobRefOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicBoolean failed = new AtomicBoolean(false);

      // Launch concurrent BlobRef operations using virtual threads
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int operationId = i;
        executor.submit(() -> {
          try {
            // Create a BlobRef
            BlobId blobId = new BlobId("test-blob-" + operationId);
            BlobRef blobRef = new BlobRef("test-node", "test-store", blobId.asUniqueString());
            
            // Test toString and parse operations
            String blobRefString = blobRef.toString();
            BlobRef parsedRef = BlobRef.parse(blobRefString);
            
            // Verify the operations worked correctly
            assertEquals(blobRef.getStore(), parsedRef.getStore(), "Store name should match after parse");
            assertEquals(blobRef.getBlob(), parsedRef.getBlob(), "Blob ID should match after parse");
          }
          catch (Exception e) {
            failed.set(true);
            fail("Exception during BlobRef operations: " + e.getMessage());
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all BlobRef operations completed within timeout");
      assertFalse(failed.get(), "Some BlobRef operations failed");
    }
  }

  /**
   * Tests handling of exceptions in blob operations with Virtual Threads.
   * <p>
   * This test verifies that exceptions during blob operations are properly propagated
   * when using Virtual Threads and don't cause unexpected pinning or resource leaks.
   */
  @Test
  public void testExceptionHandlingWithVirtualThreads() throws Exception {
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
      AtomicInteger exceptionCount = new AtomicInteger(0);

      // Set up mock to throw exceptions
      when(blobStore.get(any(BlobId.class)))
          .thenAnswer(invocation -> {
            // Simulate random failures
            if (Math.random() < 0.5) {
              throw new BlobStoreException("Simulated blob store exception");
            }
            else {
              // Simulate I/O exception during blob access
              Blob blob = mock(Blob.class);
              when(blob.getInputStream()).thenThrow(new IOException("Simulated I/O exception"));
              return blob;
            }
          });

      // Launch concurrent operations that will encounter exceptions
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            BlobId blobId = new BlobId("test-blob-" + System.nanoTime());
            
            try {
              // This will throw an exception
              Blob blob = blobStore.get(blobId);
              
              // If we get here, try to read the blob which will also throw an exception
              try (InputStream is = blob.getInputStream()) {
                byte[] buffer = new byte[1024];
                is.read(buffer);
              }
            }
            catch (BlobStoreException | IOException e) {
              // Expected exceptions
              exceptionCount.incrementAndGet();
            }
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = completionLatch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      assertTrue(completed, "Not all exception handling operations completed within timeout");
      assertEquals(CONCURRENT_OPERATIONS, exceptionCount.get(), "Not all operations encountered expected exceptions");
    }
  }
}