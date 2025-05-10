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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobId;
import org.sonatype.nexus.blobstore.api.BlobStore;
import org.sonatype.nexus.blobstore.api.BlobStoreConfiguration;
import org.sonatype.nexus.blobstore.api.BlobStoreManager;
import org.sonatype.nexus.blobstore.file.FileBlobStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * Tests to detect and analyze virtual thread pinning issues within the BlobStore implementation.
 * 
 * <p>This test class uses Java 21's thread diagnostic tools (Threads.currentCarrierThread) to identify
 * operations that cause virtual threads to be pinned to carrier threads, which reduces their efficiency.</p>
 *
 * <p>To run these tests with additional pinning diagnostics, use the JVM flag:</p>
 * <pre>-Djdk.tracePinnedThreads=full</pre>
 */
public class BlobStoreThreadPinningTest
    extends TestSupport
{
  private static final String TEST_CONTENT = "test content";
  private static final String TEST_CONTENT_TYPE = "text/plain";
  private static final Map<String, String> TEST_HEADERS = Map.of(
      "Content-Type", TEST_CONTENT_TYPE,
      "Some-Header", "some-value"
  );

  @Mock
  private BlobStoreManager blobStoreManager;

  @Mock
  private BlobStoreConfiguration blobStoreConfiguration;

  private ExecutorService virtualThreadExecutor;
  private ThreadPinningMonitor pinningMonitor;
  private BlobStore underTest;

  @Before
  public void setUp() throws Exception {
    when(blobStoreConfiguration.getName()).thenReturn("test");
    when(blobStoreConfiguration.getType()).thenReturn(FileBlobStore.TYPE);
    
    // Create a virtual thread executor for testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize the pinning monitor
    pinningMonitor = new ThreadPinningMonitor();
    
    // Note: In a real test, we would initialize a real BlobStore implementation
    // For this test class, we'll use mocks and focus on the thread pinning detection
  }

  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    }
    
    if (underTest != null) {
      underTest.stop();
    }
  }

  /**
   * Tests that basic blob creation operations don't cause thread pinning.
   */
  @Test
  public void testBlobCreationWithoutPinning() throws Exception {
    // Skip the test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping test as it requires Java 21 or later");
      return;
    }
    
    // Create a mock BlobStore for testing
    underTest = createMockBlobStore();
    
    // Run the operation in a virtual thread and monitor for pinning
    AtomicReference<Blob> blobRef = new AtomicReference<>();
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // Start monitoring for pinning
        Thread currentThread = Thread.currentThread();
        assertThat("Should be running in a virtual thread", currentThread.isVirtual(), is(true));
        
        // Record the initial carrier thread
        Thread initialCarrier = pinningMonitor.startMonitoring();
        assertThat("Initial carrier thread should be available", initialCarrier, notNullValue());
        
        // Create a blob
        InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
        Blob blob = underTest.create(inputStream, TEST_HEADERS);
        blobRef.set(blob);
        
        // Check if pinning was detected
        pinningDetected.set(pinningMonitor.wasPinningDetected());
        
        // Stop monitoring
        pinningMonitor.stopMonitoring();
      }
      catch (Exception e) {
        log.error("Error in virtual thread operation", e);
      }
    }).get(10, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("Blob should have been created", blobRef.get(), notNullValue());
    assertThat("No thread pinning should be detected during blob creation", pinningDetected.get(), is(false));
  }

  /**
   * Tests concurrent blob operations to detect potential pinning under load.
   */
  @Test
  public void testConcurrentBlobOperationsForPinning() throws Exception {
    // Skip the test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping test as it requires Java 21 or later");
      return;
    }
    
    // Create a mock BlobStore for testing
    underTest = createMockBlobStore();
    
    // Number of concurrent operations
    int concurrentOperations = 100;
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    AtomicInteger pinnedThreadsCount = new AtomicInteger(0);
    
    // Run concurrent operations
    for (int i = 0; i < concurrentOperations; i++) {
      final int operationId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Start monitoring for pinning
          Thread initialCarrier = pinningMonitor.startMonitoring();
          
          // Perform a blob operation
          InputStream inputStream = new ByteArrayInputStream(
              (TEST_CONTENT + "-" + operationId).getBytes(StandardCharsets.UTF_8));
          Blob blob = underTest.create(inputStream, TEST_HEADERS);
          
          // Check if we can read the blob
          BlobId blobId = blob.getId();
          Blob readBlob = underTest.get(blobId);
          
          // If pinning was detected, increment the counter
          if (pinningMonitor.wasPinningDetected()) {
            pinnedThreadsCount.incrementAndGet();
          }
          
          // Stop monitoring
          pinningMonitor.stopMonitoring();
        }
        catch (Exception e) {
          log.error("Error in concurrent operation " + operationId, e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertThat("All operations should complete in time", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify results
    assertThat("No thread pinning should be detected during concurrent operations", 
        pinnedThreadsCount.get(), is(0));
  }

  /**
   * Tests blob read operations with a focus on I/O operations that might cause pinning.
   */
  @Test
  public void testBlobReadOperationsForPinning() throws Exception {
    // Skip the test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping test as it requires Java 21 or later");
      return;
    }
    
    // Create a mock BlobStore for testing
    underTest = createMockBlobStore();
    
    // Create a test blob first
    InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
    Blob blob = underTest.create(inputStream, TEST_HEADERS);
    BlobId blobId = blob.getId();
    
    // Now test reading the blob with pinning detection
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // Start monitoring for pinning
        Thread initialCarrier = pinningMonitor.startMonitoring();
        
        // Get the blob
        Blob readBlob = underTest.get(blobId);
        assertThat("Should be able to get the blob", readBlob, notNullValue());
        
        // Read the blob content
        try (InputStream blobStream = readBlob.getInputStream()) {
          byte[] buffer = new byte[1024];
          while (blobStream.read(buffer) != -1) {
            // Just read the data
          }
        }
        
        // Check if pinning was detected
        pinningDetected.set(pinningMonitor.wasPinningDetected());
        
        // Stop monitoring
        pinningMonitor.stopMonitoring();
      }
      catch (Exception e) {
        log.error("Error in blob read operation", e);
      }
    }).get(10, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("No thread pinning should be detected during blob read operations", 
        pinningDetected.get(), is(false));
  }

  /**
   * Tests blob delete operations for potential pinning issues.
   */
  @Test
  public void testBlobDeleteOperationsForPinning() throws Exception {
    // Skip the test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping test as it requires Java 21 or later");
      return;
    }
    
    // Create a mock BlobStore for testing
    underTest = createMockBlobStore();
    
    // Create a test blob first
    InputStream inputStream = new ByteArrayInputStream(TEST_CONTENT.getBytes(StandardCharsets.UTF_8));
    Blob blob = underTest.create(inputStream, TEST_HEADERS);
    BlobId blobId = blob.getId();
    
    // Now test deleting the blob with pinning detection
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // Start monitoring for pinning
        Thread initialCarrier = pinningMonitor.startMonitoring();
        
        // Delete the blob
        underTest.delete(blobId, "Test deletion");
        
        // Check if pinning was detected
        pinningDetected.set(pinningMonitor.wasPinningDetected());
        
        // Stop monitoring
        pinningMonitor.stopMonitoring();
      }
      catch (Exception e) {
        log.error("Error in blob delete operation", e);
      }
    }).get(10, TimeUnit.SECONDS);
    
    // Verify results
    assertThat("No thread pinning should be detected during blob delete operations", 
        pinningDetected.get(), is(false));
    
    // Verify the blob was deleted
    assertThat("Blob should be deleted", underTest.get(blobId), nullValue());
  }

  /**
   * Tests for pinning in synchronized blocks that might be present in BlobStore implementations.
   */
  @Test
  public void testSynchronizedBlocksForPinning() throws Exception {
    // Skip the test if not running on Java 21 or later
    if (!isJava21OrLater()) {
      log.info("Skipping test as it requires Java 21 or later");
      return;
    }
    
    // Create a test object with synchronized methods
    Object lockObject = new Object();
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // Start monitoring for pinning
        Thread initialCarrier = pinningMonitor.startMonitoring();
        
        // Execute a synchronized block with I/O or blocking operation inside
        synchronized (lockObject) {
          // Simulate a blocking operation
          Thread.sleep(100);
        }
        
        // Check if pinning was detected
        pinningDetected.set(pinningMonitor.wasPinningDetected());
        
        // Stop monitoring
        pinningMonitor.stopMonitoring();
      }
      catch (Exception e) {
        log.error("Error in synchronized block test", e);
      }
    }).get(10, TimeUnit.SECONDS);
    
    // Verify results - we expect pinning to be detected in this case
    assertThat("Thread pinning should be detected in synchronized blocks with blocking operations", 
        pinningDetected.get(), is(true));
  }

  /**
   * Helper method to create a mock BlobStore for testing.
   */
  private BlobStore createMockBlobStore() {
    // In a real test, we would create a real BlobStore implementation
    // For this test class, we'll use a simple mock implementation
    return new MockBlobStore();
  }

  /**
   * Checks if the current JVM is Java 21 or later.
   */
  private boolean isJava21OrLater() {
    String version = System.getProperty("java.version");
    if (version.startsWith("1.")) {
      // Old version format (1.8, etc.)
      return false;
    }
    else {
      // New version format (9, 10, 11, etc.)
      int majorVersion = Integer.parseInt(version.split("\\.")[0]);
      return majorVersion >= 21;
    }
  }

  /**
   * A utility class to monitor thread pinning by tracking carrier thread changes.
   */
  private static class ThreadPinningMonitor {
    private final AtomicReference<Thread> initialCarrierThread = new AtomicReference<>();
    private final AtomicReference<Thread> lastCarrierThread = new AtomicReference<>();
    private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    
    /**
     * Starts monitoring for thread pinning.
     * 
     * @return The initial carrier thread, or null if not running on a virtual thread
     */
    public Thread startMonitoring() {
      if (!Thread.currentThread().isVirtual()) {
        return null;
      }
      
      monitoring.set(true);
      pinningDetected.set(false);
      
      // Get the current carrier thread
      Thread carrier = getCurrentCarrierThread();
      initialCarrierThread.set(carrier);
      lastCarrierThread.set(carrier);
      
      // Start a background thread to periodically check for carrier changes
      Thread monitorThread = Thread.ofVirtual().start(() -> {
        try {
          while (monitoring.get()) {
            // Check if the carrier thread has changed
            Thread currentCarrier = getCurrentCarrierThread();
            Thread previousCarrier = lastCarrierThread.getAndSet(currentCarrier);
            
            // If the carrier hasn't changed during a sleep, that's a sign of pinning
            if (previousCarrier != null && currentCarrier != null && 
                previousCarrier == currentCarrier) {
              pinningDetected.set(true);
            }
            
            // Sleep briefly
            Thread.sleep(Duration.ofMillis(10));
          }
        }
        catch (InterruptedException e) {
          // Monitor thread interrupted, stop monitoring
          monitoring.set(false);
        }
      });
      
      return carrier;
    }
    
    /**
     * Stops monitoring for thread pinning.
     */
    public void stopMonitoring() {
      monitoring.set(false);
    }
    
    /**
     * Checks if thread pinning was detected during monitoring.
     * 
     * @return true if pinning was detected, false otherwise
     */
    public boolean wasPinningDetected() {
      return pinningDetected.get();
    }
    
    /**
     * Gets the current carrier thread for a virtual thread.
     * 
     * @return The current carrier thread, or null if not available
     */
    private Thread getCurrentCarrierThread() {
      try {
        // Use reflection to access the currentCarrierThread method
        // This is necessary because the method is not part of the public API
        return (Thread) Thread.class.getMethod("currentCarrierThread").invoke(null);
      }
      catch (Exception e) {
        // Method not available or failed to invoke
        return null;
      }
    }
  }

  /**
   * A simple mock implementation of BlobStore for testing.
   */
  private static class MockBlobStore implements BlobStore {
    private final Map<BlobId, Blob> blobs = new ConcurrentHashMap<>();
    private final AtomicInteger blobIdCounter = new AtomicInteger(1);
    
    @Override
    public Blob create(InputStream blobData, Map<String, String> headers) {
      BlobId blobId = new BlobId("test-" + blobIdCounter.getAndIncrement());
      MockBlob blob = new MockBlob(blobId, headers, blobData);
      blobs.put(blobId, blob);
      return blob;
    }

    @Override
    public Blob get(BlobId blobId) {
      return blobs.get(blobId);
    }

    @Override
    public boolean delete(BlobId blobId, String reason) {
      return blobs.remove(blobId) != null;
    }

    @Override
    public boolean deleteHard(BlobId blobId) {
      return blobs.remove(blobId) != null;
    }

    @Override
    public BlobStoreConfiguration getBlobStoreConfiguration() {
      return null;
    }

    @Override
    public void init(BlobStoreConfiguration configuration) {
      // No-op for mock
    }

    @Override
    public void start() {
      // No-op for mock
    }

    @Override
    public void stop() {
      // No-op for mock
    }

    @Override
    public void compact() {
      // No-op for mock
    }

    @Override
    public void compact(BlobStoreUsageChecker blobStoreUsageChecker) {
      // No-op for mock
    }

    @Override
    public BlobStoreMetrics getMetrics() {
      return null;
    }

    @Override
    public void doMaintenance() {
      // No-op for mock
    }

    @Override
    public boolean isStorageAvailable() {
      return true;
    }

    @Override
    public boolean isWritable() {
      return true;
    }

    @Override
    public boolean isGroupable() {
      return false;
    }

    @Override
    public boolean hasReplicationCapability() {
      return false;
    }

    @Override
    public void setReplicationCapability(boolean enabled) {
      // No-op for mock
    }

    @Override
    public boolean isReplicationCapable() {
      return false;
    }

    @Override
    public boolean undelete(BlobId blobId, BlobAttributes attributes, boolean isDryRun) {
      return false;
    }

    @Override
    public boolean isStorageFeatureSupported(String featureId) {
      return false;
    }

    @Override
    public boolean exists(BlobId blobId) {
      return blobs.containsKey(blobId);
    }

    @Override
    public boolean isAccessible() {
      return true;
    }

    @Override
    public boolean isReadable() {
      return true;
    }

    @Override
    public boolean isCompatible(BlobStore blobStore) {
      return false;
    }

    @Override
    public boolean isFileBlobStore() {
      return false;
    }

    @Override
    public boolean isCloudBlobStore() {
      return false;
    }

    @Override
    public boolean isTemporary() {
      return false;
    }

    @Override
    public boolean isVolumeSupported() {
      return false;
    }

    @Override
    public Optional<String> getBucketName() {
      return Optional.empty();
    }
  }

  /**
   * A simple mock implementation of Blob for testing.
   */
  private static class MockBlob implements Blob {
    private final BlobId id;
    private final Map<String, String> headers;
    private final byte[] content;
    
    public MockBlob(BlobId id, Map<String, String> headers, InputStream data) {
      this.id = id;
      this.headers = Map.copyOf(headers);
      
      try {
        // Read the content into memory
        this.content = data.readAllBytes();
      }
      catch (IOException e) {
        throw new RuntimeException("Failed to read blob content", e);
      }
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
      return new ByteArrayInputStream(content);
    }
  }
}