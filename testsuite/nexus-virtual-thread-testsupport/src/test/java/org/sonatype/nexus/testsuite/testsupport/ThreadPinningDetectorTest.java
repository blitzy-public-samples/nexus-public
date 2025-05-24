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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testsuite.testsupport.ThreadPinningDetector.PinningEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for {@link ThreadPinningDetector}.
 * 
 * This test validates the ThreadPinningDetector utility which identifies situations where Virtual Threads
 * become pinned to carrier threads, reducing the efficiency of the Virtual Thread model.
 */
public class ThreadPinningDetectorTest
    extends TestSupport
{
  private static final int PINNING_DURATION_MS = 100;
  
  private static final Object LOCK_OBJECT = new Object();
  
  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();
  
  private ThreadPinningDetector underTest;
  
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() {
    underTest = new ThreadPinningDetector();
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void tearDown() {
    virtualThreadExecutor.shutdownNow();
  }
  
  /**
   * Tests that the detector can identify thread pinning in synchronized blocks.
   */
  @Test
  public void detectPinningInSynchronizedBlock() throws Exception {
    // Start the detector
    underTest.start();
    
    // Create a task that will cause pinning in a synchronized block
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // This synchronized block will cause pinning when we sleep
        synchronized (LOCK_OBJECT) {
          // Signal that we've entered the synchronized block
          latch.countDown();
          
          // Sleep to simulate a blocking operation inside synchronized block
          Thread.sleep(PINNING_DURATION_MS);
        }
        taskCompleted.set(true);
      }
      catch (InterruptedException e) {
        // Ignore interruption
      }
    });
    
    // Wait for the task to enter the synchronized block
    assertThat("Task did not enter synchronized block", latch.await(1, TimeUnit.SECONDS), is(true));
    
    // Wait for the task to complete
    Thread.sleep(PINNING_DURATION_MS * 2);
    assertThat("Task did not complete", taskCompleted.get(), is(true));
    
    // Stop the detector
    List<PinningEvent> events = underTest.stop();
    
    // Verify that pinning was detected
    assertThat("No pinning events detected", events, hasSize(greaterThanOrEqualTo(1)));
    
    // Verify the pinning event details
    PinningEvent event = events.get(0);
    assertThat(event, notNullValue());
    assertThat(event.getDuration(), greaterThanOrEqualTo(Duration.ofMillis(PINNING_DURATION_MS - 20))); // Allow for some timing variance
    assertThat(event.getStackTrace(), containsString("synchronized"));
  }
  
  /**
   * Tests that the detector can identify thread pinning when calling native methods.
   */
  @Test
  public void detectPinningInNativeMethod() throws Exception {
    // Start the detector
    underTest.start();
    
    // Create a task that will cause pinning by calling a native method
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // Signal that we're about to call the native method
        latch.countDown();
        
        // Call a native method that will block
        // System.loadLibrary is a native method that can cause pinning
        try {
          // This is a bit of a hack, but we're trying to call a native method that will block
          // We're using Object.wait() which is a native method
          synchronized (LOCK_OBJECT) {
            LOCK_OBJECT.wait(PINNING_DURATION_MS);
          }
        }
        catch (Exception e) {
          // Ignore exceptions
        }
        
        taskCompleted.set(true);
      }
      catch (Exception e) {
        // Ignore exceptions
      }
    });
    
    // Wait for the task to signal it's about to call the native method
    assertThat("Task did not reach native method call", latch.await(1, TimeUnit.SECONDS), is(true));
    
    // Wait for the task to complete
    Thread.sleep(PINNING_DURATION_MS * 2);
    assertThat("Task did not complete", taskCompleted.get(), is(true));
    
    // Stop the detector
    List<PinningEvent> events = underTest.stop();
    
    // Verify that pinning was detected
    assertThat("No pinning events detected", events, hasSize(greaterThanOrEqualTo(1)));
    
    // Verify the pinning event details
    PinningEvent event = events.get(0);
    assertThat(event, notNullValue());
    assertThat(event.getDuration(), greaterThanOrEqualTo(Duration.ofMillis(PINNING_DURATION_MS - 20))); // Allow for some timing variance
    // The stack trace should contain reference to the wait method which is native
    assertThat(event.getStackTrace(), containsString("wait"));
  }
  
  /**
   * Tests that the detector can identify thread pinning during blocking I/O operations.
   */
  @Test
  public void detectPinningDuringBlockingIO() throws Exception {
    // Create a temporary file for I/O operations
    File tempFile = temporaryFolder.newFile();
    
    // Start the detector
    underTest.start();
    
    // Create a task that will cause pinning with blocking I/O inside a synchronized block
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // This synchronized block will cause pinning when we do I/O
        synchronized (LOCK_OBJECT) {
          // Signal that we've entered the synchronized block
          latch.countDown();
          
          // Perform blocking I/O operation
          try (FileOutputStream out = new FileOutputStream(tempFile)) {
            // Write some data
            byte[] data = new byte[1024 * 1024]; // 1MB of data
            out.write(data);
            out.flush();
            
            // Read it back to ensure I/O operation
            try (FileInputStream in = new FileInputStream(tempFile)) {
              byte[] buffer = new byte[8192];
              while (in.read(buffer) != -1) {
                // Just read the data
              }
            }
          }
          catch (IOException e) {
            // Ignore I/O exceptions
          }
        }
        taskCompleted.set(true);
      }
      catch (Exception e) {
        // Ignore exceptions
      }
    });
    
    // Wait for the task to enter the synchronized block
    assertThat("Task did not enter synchronized block", latch.await(1, TimeUnit.SECONDS), is(true));
    
    // Wait for the task to complete
    Thread.sleep(PINNING_DURATION_MS * 5); // I/O might take longer
    assertThat("Task did not complete", taskCompleted.get(), is(true));
    
    // Stop the detector
    List<PinningEvent> events = underTest.stop();
    
    // Verify that pinning was detected
    assertThat("No pinning events detected", events, hasSize(greaterThanOrEqualTo(1)));
    
    // Verify the pinning event details
    PinningEvent event = events.get(0);
    assertThat(event, notNullValue());
    // The stack trace should contain reference to file I/O
    assertThat(event.getStackTrace(), containsString("FileOutputStream"));
  }
  
  /**
   * Tests that the detector correctly reports pinning duration.
   */
  @Test
  public void reportsPinningDuration() throws Exception {
    // Start the detector
    underTest.start();
    
    // Create a task with a known pinning duration
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // This synchronized block will cause pinning when we sleep
        synchronized (LOCK_OBJECT) {
          // Signal that we've entered the synchronized block
          latch.countDown();
          
          // Sleep for a specific duration to test duration reporting
          Thread.sleep(PINNING_DURATION_MS);
        }
        taskCompleted.set(true);
      }
      catch (InterruptedException e) {
        // Ignore interruption
      }
    });
    
    // Wait for the task to enter the synchronized block
    assertThat("Task did not enter synchronized block", latch.await(1, TimeUnit.SECONDS), is(true));
    
    // Wait for the task to complete
    Thread.sleep(PINNING_DURATION_MS * 2);
    assertThat("Task did not complete", taskCompleted.get(), is(true));
    
    // Stop the detector
    List<PinningEvent> events = underTest.stop();
    
    // Verify that pinning was detected
    assertThat("No pinning events detected", events, hasSize(greaterThanOrEqualTo(1)));
    
    // Verify the pinning duration
    PinningEvent event = events.get(0);
    assertThat(event, notNullValue());
    assertThat(event.getDuration(), greaterThanOrEqualTo(Duration.ofMillis(PINNING_DURATION_MS - 20))); // Allow for some timing variance
    assertThat(event.getDuration().toMillis() <= PINNING_DURATION_MS * 2, is(true)); // Upper bound check
  }
  
  /**
   * Tests that the detector correctly reports stack traces for pinning events.
   */
  @Test
  public void reportsStackTraces() throws Exception {
    // Start the detector
    underTest.start();
    
    // Create a task that will cause pinning
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean taskCompleted = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        // Call a method that will cause pinning
        methodThatCausesPinning(latch);
        taskCompleted.set(true);
      }
      catch (Exception e) {
        // Ignore exceptions
      }
    });
    
    // Wait for the task to enter the synchronized block
    assertThat("Task did not enter synchronized block", latch.await(1, TimeUnit.SECONDS), is(true));
    
    // Wait for the task to complete
    Thread.sleep(PINNING_DURATION_MS * 2);
    assertThat("Task did not complete", taskCompleted.get(), is(true));
    
    // Stop the detector
    List<PinningEvent> events = underTest.stop();
    
    // Verify that pinning was detected
    assertThat("No pinning events detected", events, hasSize(greaterThanOrEqualTo(1)));
    
    // Verify the stack trace contains our method names
    PinningEvent event = events.get(0);
    assertThat(event, notNullValue());
    assertThat(event.getStackTrace(), containsString("methodThatCausesPinning"));
    assertThat(event.getStackTrace(), containsString("ThreadPinningDetectorTest"));
  }
  
  /**
   * Helper method that causes thread pinning for testing stack trace reporting.
   */
  private void methodThatCausesPinning(CountDownLatch latch) throws InterruptedException {
    // This synchronized block will cause pinning when we sleep
    synchronized (LOCK_OBJECT) {
      // Signal that we've entered the synchronized block
      latch.countDown();
      
      // Sleep to simulate a blocking operation inside synchronized block
      Thread.sleep(PINNING_DURATION_MS);
    }
  }
}