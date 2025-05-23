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
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests to identify and validate potential thread pinning issues in the BlobStore API
 * when used with Virtual Threads.
 * <p>
 * This test uses JDK's pinning detection tools to find operations that may cause
 * Virtual Thread carrier-thread pinning, which could limit scalability. It verifies
 * that critical blob store operations either avoid pinning or have acceptable
 * performance characteristics when pinning occurs.
 *
 * @since 3.60
 */
public class ThreadPinningDetectionTest
{
  private static final String BLOB_NAME = "test-blob";
  private static final String CREATED_BY = "test-user";
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int PINNING_THRESHOLD_MS = 20; // Default JFR threshold for pinning events

  @Mock
  private BlobStore blobStore;

  private AutoCloseable mocks;
  private ExecutorService virtualThreadExecutor;
  private AtomicInteger pinnedThreadCount;
  private Map<String, Duration> pinnedOperations;
  private RecordingStream recordingStream;

  @Before
  public void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    pinnedThreadCount = new AtomicInteger(0);
    pinnedOperations = new ConcurrentHashMap<>();
    
    // Set up JFR recording to detect thread pinning
    recordingStream = new RecordingStream();
    recordingStream.enable("jdk.VirtualThreadPinned").withStackTrace();
    recordingStream.onEvent("jdk.VirtualThreadPinned", this::handlePinningEvent);
    recordingStream.startAsync();
    
    // Configure mock BlobStore for testing
    setupMockBlobStore();
  }

  @After
  public void tearDown() throws Exception {
    recordingStream.close();
    virtualThreadExecutor.shutdown();
    virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    mocks.close();
  }

  /**
   * Tests that blob creation operations don't cause excessive thread pinning.
   * <p>
   * This test verifies that when multiple virtual threads concurrently create blobs,
   * they don't experience thread pinning that would limit scalability.
   */
  @Test
  public void testBlobCreationWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Launch multiple virtual threads to create blobs concurrently
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          Map<String, String> headers = createBlobHeaders("blob-" + index);
          byte[] content = ("content-" + index).getBytes(StandardCharsets.UTF_8);
          try (InputStream inputStream = new ByteArrayInputStream(content)) {
            blobStore.create(inputStream, headers);
          }
        } catch (Exception e) {
          e.printStackTrace();
          fail("Exception during blob creation: " + e.getMessage());
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertThat("Blob creation operations timed out", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify that no excessive thread pinning occurred
    assertThat("Too many thread pinning events detected", 
        pinnedThreadCount.get(), is(lessThan(CONCURRENT_OPERATIONS / 10)));
  }

  /**
   * Tests that blob retrieval operations don't cause excessive thread pinning.
   * <p>
   * This test verifies that when multiple virtual threads concurrently retrieve blobs,
   * they don't experience thread pinning that would limit scalability.
   */
  @Test
  public void testBlobRetrievalWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Launch multiple virtual threads to retrieve blobs concurrently
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          BlobId blobId = new BlobId("test-blob-" + index);
          Blob blob = blobStore.get(blobId);
          if (blob != null) {
            // Read the blob content to ensure I/O operations are performed
            try (InputStream is = blob.getInputStream()) {
              byte[] buffer = new byte[1024];
              while (is.read(buffer) != -1) {
                // Just consume the stream
              }
            }
          }
        } catch (Exception e) {
          e.printStackTrace();
          fail("Exception during blob retrieval: " + e.getMessage());
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertThat("Blob retrieval operations timed out", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify that no excessive thread pinning occurred
    assertThat("Too many thread pinning events detected", 
        pinnedThreadCount.get(), is(lessThan(CONCURRENT_OPERATIONS / 10)));
  }

  /**
   * Tests that blob deletion operations don't cause excessive thread pinning.
   * <p>
   * This test verifies that when multiple virtual threads concurrently delete blobs,
   * they don't experience thread pinning that would limit scalability.
   */
  @Test
  public void testBlobDeletionWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Launch multiple virtual threads to delete blobs concurrently
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          BlobId blobId = new BlobId("test-blob-" + index);
          blobStore.delete(blobId, "Test deletion");
        } catch (Exception e) {
          e.printStackTrace();
          fail("Exception during blob deletion: " + e.getMessage());
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertThat("Blob deletion operations timed out", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify that no excessive thread pinning occurred
    assertThat("Too many thread pinning events detected", 
        pinnedThreadCount.get(), is(lessThan(CONCURRENT_OPERATIONS / 10)));
  }

  /**
   * Tests that blob operations with synchronized blocks cause thread pinning.
   * <p>
   * This test demonstrates how using synchronized blocks in blob operations
   * can lead to thread pinning, which should be avoided in Virtual Thread implementations.
   */
  @Test
  public void testSynchronizedBlockCausesPinning() throws Exception {
    // Create a lock object for synchronized blocks
    final Object lock = new Object();
    final AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    final CountDownLatch latch = new CountDownLatch(1);
    
    // Set up a listener for pinning events specific to this test
    recordingStream.onEvent("jdk.VirtualThreadPinned", event -> {
      String stackTrace = event.getStackTrace().toString();
      if (stackTrace.contains("testSynchronizedBlockCausesPinning")) {
        pinnedThreadDetected.set(true);
        latch.countDown();
      }
    });
    
    // Execute a virtual thread with a synchronized block that performs I/O
    virtualThreadExecutor.submit(() -> {
      synchronized (lock) {
        try {
          // Simulate I/O operation inside synchronized block
          Thread.sleep(100); // This will cause pinning
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    
    // Wait for pinning event or timeout
    assertThat("No thread pinning detected for synchronized block",
        latch.await(5, TimeUnit.SECONDS), is(true));
    assertThat("Thread pinning should be detected for synchronized block",
        pinnedThreadDetected.get(), is(true));
  }

  /**
   * Tests that using ReentrantLock instead of synchronized blocks avoids thread pinning.
   * <p>
   * This test demonstrates how using ReentrantLock can help avoid thread pinning
   * when performing I/O operations that require synchronization.
   */
  @Test
  public void testReentrantLockAvoidsPinning() throws Exception {
    // Create a ReentrantLock for thread-safe operations
    final ReentrantLock lock = new ReentrantLock();
    final AtomicBoolean pinnedThreadDetected = new AtomicBoolean(false);
    final CountDownLatch latch = new CountDownLatch(1);
    
    // Set up a listener for pinning events specific to this test
    recordingStream.onEvent("jdk.VirtualThreadPinned", event -> {
      String stackTrace = event.getStackTrace().toString();
      if (stackTrace.contains("testReentrantLockAvoidsPinning")) {
        pinnedThreadDetected.set(true);
        latch.countDown();
      }
    });
    
    // Execute a virtual thread with a ReentrantLock that performs I/O
    virtualThreadExecutor.submit(() -> {
      lock.lock();
      try {
        // Simulate I/O operation inside locked section
        Thread.sleep(100); // This should NOT cause pinning with ReentrantLock
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    });
    
    // Wait for potential pinning event or timeout
    latch.await(1, TimeUnit.SECONDS); // Short timeout as we don't expect pinning
    
    assertThat("Thread pinning should not be detected with ReentrantLock",
        pinnedThreadDetected.get(), is(false));
  }

  /**
   * Handles JFR VirtualThreadPinned events by recording information about pinned threads.
   *
   * @param event the JFR event containing pinning information
   */
  private void handlePinningEvent(RecordedEvent event) {
    pinnedThreadCount.incrementAndGet();
    
    // Extract information from the event
    String threadName = event.getThread("thread").getJavaName();
    Duration duration = event.getDuration();
    String reason = event.getString("reason");
    
    // Record the pinning operation for analysis
    pinnedOperations.put(threadName, duration);
    
    // Log pinning information for debugging
    System.out.printf("Thread pinning detected: thread=%s, duration=%s, reason=%s%n", 
        threadName, duration, reason);
  }

  /**
   * Sets up the mock BlobStore for testing.
   */
  private void setupMockBlobStore() throws IOException {
    // Mock blob creation
    when(blobStore.create(any(InputStream.class), anyMap())).thenAnswer(invocation -> {
      InputStream is = invocation.getArgument(0);
      // Simulate I/O operation by reading the stream
      byte[] buffer = new byte[1024];
      while (is.read(buffer) != -1) {
        // Just consume the stream
      }
      
      // Create and return a mock Blob
      Blob mockBlob = createMockBlob();
      return mockBlob;
    });
    
    // Mock blob retrieval
    when(blobStore.get(any(BlobId.class))).thenAnswer(invocation -> {
      // Simulate some I/O delay
      Thread.sleep(5);
      return createMockBlob();
    });
    
    // Mock blob deletion
    when(blobStore.delete(any(BlobId.class), any(String.class))).thenAnswer(invocation -> {
      // Simulate some I/O delay
      Thread.sleep(5);
      return true;
    });
  }

  /**
   * Creates a mock Blob for testing.
   */
  private Blob createMockBlob() {
    Blob mockBlob = org.mockito.Mockito.mock(Blob.class);
    
    // Mock the getInputStream method to return a test stream
    doAnswer(invocation -> {
      String content = "Test blob content";
      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }).when(mockBlob).getInputStream();
    
    return mockBlob;
  }

  /**
   * Creates headers for a test blob.
   */
  private Map<String, String> createBlobHeaders(String blobName) {
    Map<String, String> headers = new HashMap<>();
    headers.put(BlobStore.BLOB_NAME_HEADER, blobName);
    headers.put(BlobStore.CREATED_BY_HEADER, CREATED_BY);
    return headers;
  }
}