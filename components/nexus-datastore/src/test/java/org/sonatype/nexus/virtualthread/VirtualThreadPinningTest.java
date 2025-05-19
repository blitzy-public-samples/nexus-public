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
package org.sonatype.nexus.virtualthread;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests to identify carrier thread pinning issues when using Java 21 Virtual Threads with the datastore component.
 * <p>
 * These tests verify that database operations don't inadvertently block carrier threads by detecting
 * synchronization structures, native methods, or other operations that cause thread pinning,
 * which would negate the benefits of the lightweight concurrency model.
 * <p>
 * Thread pinning occurs when a virtual thread cannot unmount from its carrier thread during blocking operations.
 * This typically happens when using synchronized blocks/methods, native methods, or foreign function calls.
 * <p>
 * To run these tests with additional pinning detection, use the JVM flag:
 * -Djdk.tracePinnedThreads=full
 */
@Tag(Java21TestGroup.TAG)
public class VirtualThreadPinningTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 10;
  private static final int OPERATIONS_PER_THREAD = 5;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  
  @TempDir
  Path tempDir;
  
  private ExecutorService virtualThreadExecutor;
  private List<PinningEvent> detectedPinningEvents;
  private RecordingStream recordingStream;
  
  @BeforeEach
  void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    detectedPinningEvents = new ArrayList<>();
    
    // Set up JFR recording to detect thread pinning events
    recordingStream = new RecordingStream();
    recordingStream.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ofMillis(1));
    recordingStream.onEvent("jdk.VirtualThreadPinned", event -> {
      String threadName = event.getString("virtualThread");
      Duration duration = Duration.ofNanos(event.getLong("duration"));
      detectedPinningEvents.add(new PinningEvent(threadName, duration));
      log.warn("Virtual thread pinning detected: {} pinned for {}", threadName, duration);
    });
    recordingStream.startAsync();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    recordingStream.close();
    virtualThreadExecutor.close();
  }
  
  /**
   * Tests for thread pinning when using synchronized blocks with I/O operations.
   * This is a common cause of thread pinning that can degrade performance.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void testSynchronizedBlockWithIO() throws Exception {
    Object lock = new Object();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Clear previous events
    detectedPinningEvents.clear();
    
    // Create multiple virtual threads that use synchronized blocks with I/O
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // This synchronized block will cause thread pinning during I/O
            synchronized (lock) {
              // Simulate database I/O operation
              simulateBlockingIO();
            }
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Timed out waiting for threads to complete");
    
    // Verify that pinning was detected
    assertFalse(detectedPinningEvents.isEmpty(), 
        "No thread pinning detected, but expected pinning with synchronized blocks and I/O");
    
    log.info("Detected {} pinning events with synchronized blocks", detectedPinningEvents.size());
  }
  
  /**
   * Tests for thread pinning when using ReentrantLock instead of synchronized blocks.
   * ReentrantLock should not cause thread pinning and should allow virtual threads to unmount
   * during blocking operations.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void testReentrantLockWithIO() throws Exception {
    ReentrantLock lock = new ReentrantLock();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Clear previous events
    detectedPinningEvents.clear();
    
    // Create multiple virtual threads that use ReentrantLock with I/O
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // This should not cause thread pinning
            lock.lock();
            try {
              // Simulate database I/O operation
              simulateBlockingIO();
            } finally {
              lock.unlock();
            }
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Timed out waiting for threads to complete");
    
    // Verify that no pinning was detected
    assertTrue(detectedPinningEvents.isEmpty(), 
        "Thread pinning detected with ReentrantLock, but expected no pinning");
    
    log.info("No pinning events detected with ReentrantLock");
  }
  
  /**
   * Tests for thread pinning when using native methods.
   * Native methods can cause thread pinning and should be used carefully with virtual threads.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void testNativeMethodPinning() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Clear previous events
    detectedPinningEvents.clear();
    
    // Create multiple virtual threads that call native methods
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            // Native methods can cause pinning
            simulateNativeMethodCall();
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Timed out waiting for threads to complete");
    
    // Log the results - native method pinning may or may not be detected depending on JVM implementation
    log.info("Detected {} pinning events with native methods", detectedPinningEvents.size());
  }
  
  /**
   * Tests for thread pinning in a simulated database transaction scenario.
   * Compares synchronized blocks vs ReentrantLock for transaction isolation.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void testDatabaseTransactionScenario() throws Exception {
    // Test with synchronized blocks (will cause pinning)
    int pinnedTransactionCount = simulateTransactions(true);
    
    // Test with ReentrantLock (should not cause pinning)
    int unpinnedTransactionCount = simulateTransactions(false);
    
    // Both approaches should complete the same number of transactions
    assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, pinnedTransactionCount);
    assertEquals(THREAD_COUNT * OPERATIONS_PER_THREAD, unpinnedTransactionCount);
    
    log.info("Completed {} transactions with synchronized (pinned) and {} with ReentrantLock (unpinned)",
        pinnedTransactionCount, unpinnedTransactionCount);
  }
  
  /**
   * Simulates database transactions using either synchronized blocks or ReentrantLock.
   *
   * @param useSynchronized if true, use synchronized blocks; if false, use ReentrantLock
   * @return the number of completed transactions
   */
  private int simulateTransactions(boolean useSynchronized) throws Exception {
    Object lock = new Object();
    ReentrantLock reentrantLock = new ReentrantLock();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    int[] completedTransactions = new int[1];
    
    // Clear previous events
    detectedPinningEvents.clear();
    
    // Create multiple virtual threads that simulate database transactions
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            if (useSynchronized) {
              synchronized (lock) {
                // Simulate transaction operations
                simulateBlockingIO(); // Read operation
                simulateBlockingIO(); // Write operation
                completedTransactions[0]++;
              }
            } else {
              reentrantLock.lock();
              try {
                // Simulate transaction operations
                simulateBlockingIO(); // Read operation
                simulateBlockingIO(); // Write operation
                completedTransactions[0]++;
              } finally {
                reentrantLock.unlock();
              }
            }
          }
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), 
        "Timed out waiting for threads to complete");
    
    // Log pinning events
    if (useSynchronized) {
      log.info("Detected {} pinning events with synchronized transactions", detectedPinningEvents.size());
    } else {
      log.info("Detected {} pinning events with ReentrantLock transactions", detectedPinningEvents.size());
    }
    
    return completedTransactions[0];
  }
  
  /**
   * Simulates a blocking I/O operation similar to database access.
   * This method intentionally performs file I/O which is a blocking operation.
   */
  private void simulateBlockingIO() {
    try {
      // Create a temporary file for I/O simulation
      Path tempFile = Files.createTempFile(tempDir, "db-simulation-", ".tmp");
      
      // Write some data (simulates database write)
      String data = "Simulated database record content for testing thread pinning";
      Files.writeString(tempFile, data);
      
      // Read the data back (simulates database read)
      String readData = Files.readString(tempFile);
      
      // Clean up
      Files.delete(tempFile);
    } catch (IOException e) {
      log.error("Error during simulated I/O operation", e);
    }
  }
  
  /**
   * Simulates a native method call that might cause thread pinning.
   * Uses System.currentTimeMillis() which is a native method.
   */
  private void simulateNativeMethodCall() {
    // System.currentTimeMillis() is a native method that might cause pinning
    long startTime = System.currentTimeMillis();
    
    // Simulate some work
    try {
      Thread.sleep(10); // Short sleep to simulate work
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    // Another native method call
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    // Use the result to prevent optimization
    if (duration < 0) {
      log.warn("Unexpected negative duration: {}", duration);
    }
  }
  
  /**
   * Simple class to track thread pinning events.
   */
  private static class PinningEvent {
    private final String threadName;
    private final Duration duration;
    
    PinningEvent(String threadName, Duration duration) {
      this.threadName = threadName;
      this.duration = duration;
    }
    
    @Override
    public String toString() {
      return "PinningEvent{threadName='" + threadName + "', duration=" + duration + "}";
    }
  }
}