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
package org.sonatype.nexus.testsuite.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link ThreadPinningDetector}.
 * 
 * This test validates the detection of thread pinning scenarios in Java 21 Virtual Threads.
 * Thread pinning occurs when a Virtual Thread becomes "pinned" to its carrier thread,
 * preventing the carrier from being reused for other Virtual Threads and reducing efficiency.
 */
public class ThreadPinningDetectorTest extends TestSupport
{
  private static final Duration SHORT_SLEEP = Duration.ofMillis(50);
  private static final Duration MEDIUM_SLEEP = Duration.ofMillis(100);
  private static final Duration LONG_SLEEP = Duration.ofMillis(200);
  
  private ThreadPinningDetector detector;
  
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  
  @Before
  public void setUp() {
    detector = new ThreadPinningDetector();
    detector.start();
  }
  
  /**
   * Tests that the detector can identify thread pinning in synchronized blocks.
   * 
   * When a Virtual Thread executes a synchronized block and then performs a blocking
   * operation within that block, it becomes pinned to its carrier thread.
   */
  @Test
  public void testDetectPinningInSynchronizedBlock() throws Exception {
    // Create an object to synchronize on
    final Object lock = new Object();
    final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Set up a listener to detect pinning events
    detector.addPinningListener(event -> {
      log.info("Pinning detected: {} ms at {}", event.getDurationMillis(), event.getTimestamp());
      log.info("Stack trace: {}", event.getStackTrace());
      pinningDetected.set(true);
    });
    
    // Create and start a virtual thread that will get pinned
    Thread virtualThread = Thread.ofVirtual().name("test-synchronized-pinning").start(() -> {
      synchronized (lock) {
        try {
          // Sleeping inside a synchronized block will cause pinning
          Thread.sleep(MEDIUM_SLEEP);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify that pinning was detected
    assertThat("Pinning should be detected in synchronized block", pinningDetected.get(), is(true));
    
    // Verify that the detector captured the pinning event
    List<ThreadPinningEvent> events = detector.getPinningEvents();
    assertThat("Should have at least one pinning event", events.size(), greaterThanOrEqualTo(1));
    
    ThreadPinningEvent event = events.get(0);
    assertThat("Event should have a timestamp", event.getTimestamp(), is(notNullValue()));
    assertThat("Event should have a duration", event.getDurationMillis(), greaterThanOrEqualTo(MEDIUM_SLEEP.toMillis()));
    assertThat("Event should have a stack trace", event.getStackTrace(), containsString("test-synchronized-pinning"));
  }
  
  /**
   * Tests that the detector can identify thread pinning when calling native methods.
   * 
   * When a Virtual Thread calls a native method, it becomes pinned to its carrier thread
   * for the duration of the native method call.
   */
  @Test
  public void testDetectPinningInNativeMethod() throws Exception {
    final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Set up a listener to detect pinning events
    detector.addPinningListener(event -> {
      log.info("Pinning detected in native method: {} ms at {}", event.getDurationMillis(), event.getTimestamp());
      log.info("Stack trace: {}", event.getStackTrace());
      pinningDetected.set(true);
    });
    
    // Create and start a virtual thread that will call a native method
    Thread virtualThread = Thread.ofVirtual().name("test-native-pinning").start(() -> {
      // System.loadLibrary is a native method that will cause pinning
      try {
        // This will cause pinning due to native method call
        System.loadLibrary("nonexistent_library");
      }
      catch (UnsatisfiedLinkError e) {
        // Expected exception, we're just testing pinning detection
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify that pinning was detected
    assertThat("Pinning should be detected in native method call", pinningDetected.get(), is(true));
    
    // Verify that the detector captured the pinning event
    List<ThreadPinningEvent> events = detector.getPinningEvents();
    assertThat("Should have at least one pinning event", events.size(), greaterThanOrEqualTo(1));
    
    // Find the event related to the native method call
    ThreadPinningEvent nativeEvent = events.stream()
        .filter(e -> e.getStackTrace().contains("loadLibrary"))
        .findFirst()
        .orElse(null);
    
    assertThat("Should have found a native method pinning event", nativeEvent, is(notNullValue()));
    assertThat("Event should have a stack trace with the thread name", 
        nativeEvent.getStackTrace(), containsString("test-native-pinning"));
  }
  
  /**
   * Tests that the detector can identify thread pinning during blocking I/O operations.
   * 
   * When a Virtual Thread performs a blocking I/O operation inside a synchronized block,
   * it becomes pinned to its carrier thread for the duration of the I/O operation.
   */
  @Test
  public void testDetectPinningDuringBlockingIO() throws Exception {
    final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    final Path tempFile = temporaryFolder.newFile().toPath();
    final Object lock = new Object();
    
    // Set up a listener to detect pinning events
    detector.addPinningListener(event -> {
      log.info("Pinning detected during I/O: {} ms at {}", event.getDurationMillis(), event.getTimestamp());
      log.info("Stack trace: {}", event.getStackTrace());
      pinningDetected.set(true);
    });
    
    // Create and start a virtual thread that will perform blocking I/O inside a synchronized block
    Thread virtualThread = Thread.ofVirtual().name("test-io-pinning").start(() -> {
      synchronized (lock) {
        try {
          // Blocking I/O operation inside synchronized block will cause pinning
          Files.writeString(tempFile, "Testing thread pinning during I/O operations");
          // Add a small delay to ensure the I/O operation completes and pinning is detected
          Thread.sleep(SHORT_SLEEP);
        }
        catch (IOException | InterruptedException e) {
          log.error("Error during I/O operation", e);
        }
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify that pinning was detected
    assertThat("Pinning should be detected during blocking I/O", pinningDetected.get(), is(true));
    
    // Verify that the detector captured the pinning event
    List<ThreadPinningEvent> events = detector.getPinningEvents();
    assertThat("Should have at least one pinning event", events.size(), greaterThanOrEqualTo(1));
    
    // Find the event related to the I/O operation
    ThreadPinningEvent ioEvent = events.stream()
        .filter(e -> e.getStackTrace().contains("Files.writeString") || 
                     e.getStackTrace().contains("test-io-pinning"))
        .findFirst()
        .orElse(null);
    
    assertThat("Should have found an I/O pinning event", ioEvent, is(notNullValue()));
  }
  
  /**
   * Tests that the detector correctly reports pinning duration and stack traces.
   * 
   * This test verifies that the detector accurately measures the duration of pinning events
   * and captures detailed stack traces that can be used to identify the cause of pinning.
   */
  @Test
  public void testPinningDurationAndStackTraceReporting() throws Exception {
    final Object lock = new Object();
    final CountDownLatch latch = new CountDownLatch(1);
    final AtomicBoolean longPinningDetected = new AtomicBoolean(false);
    
    // Set up a listener to detect pinning events
    detector.addPinningListener(event -> {
      log.info("Pinning detected: {} ms at {}", event.getDurationMillis(), event.getTimestamp());
      log.info("Stack trace: {}", event.getStackTrace());
      
      // Check if this is our long pinning event
      if (event.getDurationMillis() >= LONG_SLEEP.toMillis() && 
          event.getStackTrace().contains("test-duration-pinning")) {
        longPinningDetected.set(true);
        latch.countDown();
      }
    });
    
    // Create and start a virtual thread that will be pinned for a specific duration
    Thread virtualThread = Thread.ofVirtual().name("test-duration-pinning").start(() -> {
      synchronized (lock) {
        try {
          // Sleep for a known duration to test accurate timing measurement
          Thread.sleep(LONG_SLEEP);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    
    // Wait for the pinning event to be detected or timeout
    boolean detected = latch.await(LONG_SLEEP.toMillis() * 2, TimeUnit.MILLISECONDS);
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify that the long pinning was detected
    assertThat("Long duration pinning should be detected", detected, is(true));
    assertThat("Long duration pinning should be recorded", longPinningDetected.get(), is(true));
    
    // Verify that the detector captured the pinning event with accurate duration
    List<ThreadPinningEvent> events = detector.getPinningEvents();
    assertThat("Should have at least one pinning event", events.size(), greaterThanOrEqualTo(1));
    
    // Find the event related to our long sleep
    ThreadPinningEvent longEvent = events.stream()
        .filter(e -> e.getStackTrace().contains("test-duration-pinning"))
        .findFirst()
        .orElse(null);
    
    assertThat("Should have found a long duration pinning event", longEvent, is(notNullValue()));
    assertThat("Event duration should be at least the sleep duration", 
        longEvent.getDurationMillis(), greaterThanOrEqualTo(LONG_SLEEP.toMillis()));
    assertThat("Event should have a detailed stack trace", 
        longEvent.getStackTrace().split("\n").length, greaterThan(3));
  }
  
  /**
   * Tests that the detector can identify when ReentrantLock is used correctly to avoid pinning.
   * 
   * This test verifies that using ReentrantLock instead of synchronized blocks allows
   * Virtual Threads to be unmounted during blocking operations, avoiding pinning.
   */
  @Test
  public void testNoPinningWithReentrantLock() throws Exception {
    final ReentrantLock lock = new ReentrantLock();
    final AtomicBoolean anyPinningDetected = new AtomicBoolean(false);
    final String threadName = "test-reentrant-lock-no-pinning";
    
    // Set up a listener to detect any pinning events
    detector.addPinningListener(event -> {
      log.info("Pinning detected: {} ms at {}", event.getDurationMillis(), event.getTimestamp());
      log.info("Stack trace: {}", event.getStackTrace());
      
      // Only count pinning for our specific test thread
      if (event.getStackTrace().contains(threadName)) {
        anyPinningDetected.set(true);
      }
    });
    
    // Create and start a virtual thread that uses ReentrantLock correctly
    Thread virtualThread = Thread.ofVirtual().name(threadName).start(() -> {
      lock.lock();
      try {
        // Sleep while holding the lock - this should NOT cause pinning
        // because ReentrantLock allows the virtual thread to be unmounted
        Thread.sleep(MEDIUM_SLEEP);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      finally {
        lock.unlock();
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Give the detector time to process any events
    Thread.sleep(SHORT_SLEEP);
    
    // Verify that no pinning was detected for our thread
    assertThat("No pinning should be detected with ReentrantLock", anyPinningDetected.get(), is(false));
  }
}