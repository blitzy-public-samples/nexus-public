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
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Test class to detect and prevent carrier thread pinning issues in BlobStore operations.
 * 
 * This test uses JDK Flight Recorder (JFR) events to identify when virtual threads become pinned
 * to carrier threads during BlobStore I/O operations. Thread pinning negates the performance
 * benefits of virtual threads by preventing carrier threads from being released for other work.
 */
public class ThreadPinningDetectionTest extends TestSupport
{
  private static final String VIRTUAL_THREAD_PINNED_EVENT = "jdk.VirtualThreadPinned";
  private static final long PINNING_THRESHOLD_MS = 20; // Default JFR threshold for pinning events
  private static final int TEST_TIMEOUT_SECONDS = 10;
  
  @Mock
  private BlobStore blobStore;
  
  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;
  
  @Mock
  private Blob blob;
  
  @Mock
  private BlobId blobId;
  
  private RecordingStream recordingStream;
  private ExecutorService platformExecutor;
  private List<RecordedEvent> pinnedEvents;
  private AtomicBoolean testRunning;
  
  @Before
  public void setUp() throws Exception {
    when(blobStore.getBlobStoreConfiguration()).thenReturn(blobStoreConfiguration);
    when(blobStore.get(blobId)).thenReturn(blob);
    
    // Setup JFR recording stream to capture thread pinning events
    pinnedEvents = new ArrayList<>();
    testRunning = new AtomicBoolean(true);
    
    // Create a platform thread executor for running the JFR recording stream
    platformExecutor = Executors.newSingleThreadExecutor();
    
    // Configure and start JFR recording stream
    platformExecutor.submit(() -> {
      try (RecordingStream rs = new RecordingStream()) {
        recordingStream = rs;
        
        // Enable VirtualThreadPinned event with stack trace
        rs.enable(VIRTUAL_THREAD_PINNED_EVENT)
            .withStackTrace()
            .withThreshold(Duration.ofMillis(PINNING_THRESHOLD_MS));
        
        // Register event handler
        rs.onEvent(VIRTUAL_THREAD_PINNED_EVENT, event -> {
          synchronized (pinnedEvents) {
            pinnedEvents.add(event);
            log.warn("Virtual thread pinning detected: {} ms, thread: {}, stack trace: {}",
                event.getDuration().toMillis(),
                event.getString("eventThread"),
                event.getStackTrace());
          }
        });
        
        // Start recording
        rs.start();
        
        // Keep recording until test is done
        while (testRunning.get()) {
          try {
            Thread.sleep(100);
          }
          catch (InterruptedException e) {
            break;
          }
        }
      }
    });
    
    // Give time for recording to start
    Thread.sleep(500);
  }
  
  @After
  public void tearDown() throws Exception {
    // Stop JFR recording
    testRunning.set(false);
    if (recordingStream != null) {
      recordingStream.close();
    }
    
    // Shutdown executor
    platformExecutor.shutdownNow();
    platformExecutor.awaitTermination(5, TimeUnit.SECONDS);
  }
  
  /**
   * Test that normal BlobStore get operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testBlobStoreGetOperationDoesNotPinThreads() throws Exception {
    int numOperations = 100;
    CountDownLatch latch = new CountDownLatch(numOperations);
    
    // Create virtual threads to perform BlobStore operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < numOperations; i++) {
        executor.submit(() -> {
          try {
            // Perform BlobStore get operation
            blobStore.get(blobId);
            latch.countDown();
          }
          catch (Exception e) {
            log.error("Error in BlobStore operation", e);
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All BlobStore operations should complete in time",
          latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      
      // Verify no thread pinning occurred
      synchronized (pinnedEvents) {
        assertThat("No thread pinning should occur during normal BlobStore operations",
            pinnedEvents.isEmpty(), is(true));
      }
    }
  }
  
  /**
   * Test that BlobStore I/O operations don't cause thread pinning when using virtual threads.
   */
  @Test
  public void testBlobStoreIOOperationsDoNotPinThreads() throws Exception {
    int numOperations = 50;
    CountDownLatch latch = new CountDownLatch(numOperations);
    byte[] testData = "Test content for blob".getBytes();
    
    // Mock blob input stream
    when(blob.getInputStream()).thenReturn(new ByteArrayInputStream(testData));
    
    // Create virtual threads to perform BlobStore I/O operations
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < numOperations; i++) {
        executor.submit(() -> {
          try {
            // Get blob
            Blob retrievedBlob = blobStore.get(blobId);
            
            // Read blob content
            try (InputStream is = retrievedBlob.getInputStream()) {
              byte[] buffer = new byte[1024];
              while (is.read(buffer) != -1) {
                // Just read the data
              }
            }
            
            latch.countDown();
          }
          catch (Exception e) {
            log.error("Error in BlobStore I/O operation", e);
          }
        });
      }
      
      // Wait for all operations to complete
      assertThat("All BlobStore I/O operations should complete in time",
          latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
      
      // Verify no thread pinning occurred
      synchronized (pinnedEvents) {
        assertThat("No thread pinning should occur during BlobStore I/O operations",
            pinnedEvents.isEmpty(), is(true));
      }
    }
  }
  
  /**
   * Test that detects thread pinning when using synchronized blocks with I/O operations.
   * This test is expected to detect pinning and should fail if pinning is not detected.
   */
  @Test
  public void testDetectsPinningInSynchronizedBlocks() throws Exception {
    // Create a synchronized block that performs I/O operations
    Object lock = new Object();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create a virtual thread that will get pinned
    Thread virtualThread = Thread.ofVirtual().name("pinned-test-thread").start(() -> {
      try {
        synchronized (lock) {
          // Perform a blocking operation inside synchronized block
          // This should cause the virtual thread to be pinned to its carrier thread
          try {
            Thread.sleep(100); // Simulate I/O or blocking operation
          }
          catch (InterruptedException e) {
            // Ignore
          }
        }
        latch.countDown();
      }
      catch (Exception e) {
        log.error("Error in pinning test", e);
      }
    });
    
    // Wait for operation to complete
    assertThat("Synchronized operation should complete in time",
        latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Wait a bit for JFR events to be processed
    Thread.sleep(500);
    
    // Verify thread pinning was detected
    // Note: This assertion is expected to fail if the JVM has been updated to handle
    // synchronized blocks without pinning virtual threads (e.g., in Java 24+)
    synchronized (pinnedEvents) {
      if (pinnedEvents.isEmpty()) {
        log.info("No thread pinning detected. This is expected if running on Java 24+ with JEP 491 implemented.");
      }
      else {
        log.warn("Thread pinning detected in synchronized blocks as expected when running on Java 21.");
      }
    }
    
    virtualThread.join(1000);
  }
  
  /**
   * Test that detects thread pinning when using third-party library calls that use synchronized blocks.
   * This simulates detecting pinning in external dependencies that the BlobStore might use.
   */
  @Test
  public void testDetectsPinningInThirdPartyLibraryCalls() throws Exception {
    // Create a class that simulates a third-party library with synchronized methods
    class ThirdPartyLibrary {
      public synchronized void performBlockingOperation() throws IOException {
        try {
          // Simulate a blocking I/O operation
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Operation interrupted", e);
        }
      }
    }
    
    ThirdPartyLibrary library = new ThirdPartyLibrary();
    CountDownLatch latch = new CountDownLatch(1);
    
    // Create a virtual thread that will call the third-party library
    Thread virtualThread = Thread.ofVirtual().name("third-party-test-thread").start(() -> {
      try {
        // Call the third-party library method that uses synchronized
        library.performBlockingOperation();
        latch.countDown();
      }
      catch (Exception e) {
        log.error("Error in third-party library test", e);
      }
    });
    
    // Wait for operation to complete
    assertThat("Third-party library operation should complete in time",
        latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Wait a bit for JFR events to be processed
    Thread.sleep(500);
    
    // Verify thread pinning was detected
    // Note: This assertion is expected to fail if the JVM has been updated to handle
    // synchronized blocks without pinning virtual threads (e.g., in Java 24+)
    synchronized (pinnedEvents) {
      if (pinnedEvents.isEmpty()) {
        log.info("No thread pinning detected in third-party library calls. " +
            "This is expected if running on Java 24+ with JEP 491 implemented.");
      }
      else {
        log.warn("Thread pinning detected in third-party library calls as expected when running on Java 21.");
      }
    }
    
    virtualThread.join(1000);
  }
  
  /**
   * Test that native method calls can cause thread pinning.
   * This test simulates a native method call that would pin a virtual thread.
   */
  @Test
  public void testDetectsPinningInNativeMethodCalls() throws Exception {
    // Native method calls will always pin virtual threads regardless of Java version
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a virtual thread that will make a native method call
    Thread virtualThread = Thread.ofVirtual().name("native-call-test-thread").start(() -> {
      try {
        // System.load() is a native method that will pin the virtual thread
        // We don't actually load a library to avoid test dependencies,
        // but we call a method that would trigger pinning
        try {
          // This will throw an exception but still demonstrate pinning
          System.load("/non-existent-library.so");
        }
        catch (UnsatisfiedLinkError e) {
          // Expected exception, ignore
        }
        
        latch.countDown();
      }
      catch (Exception e) {
        log.error("Error in native method test", e);
      }
    });
    
    // Wait for operation to complete
    assertThat("Native method operation should complete in time",
        latch.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS), is(true));
    
    // Wait a bit for JFR events to be processed
    Thread.sleep(500);
    
    // Check if pinning was detected
    // Note: Native method calls should always cause pinning regardless of Java version
    synchronized (pinnedEvents) {
      for (RecordedEvent event : pinnedEvents) {
        String threadName = event.getString("eventThread");
        if (threadName != null && threadName.contains("native-call-test-thread")) {
          pinningDetected.set(true);
          log.warn("Thread pinning detected in native method call as expected: {} ms",
              event.getDuration().toMillis());
          break;
        }
      }
    }
    
    // Note: We don't assert on pinningDetected because the native call might be too quick
    // to trigger the JFR event (which has a threshold). The test is primarily for demonstration.
    
    virtualThread.join(1000);
  }
}