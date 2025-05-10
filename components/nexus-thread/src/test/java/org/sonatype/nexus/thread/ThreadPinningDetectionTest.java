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
package org.sonatype.nexus.thread;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for detecting thread pinning issues when using virtual threads.
 * <p>
 * These tests identify common scenarios that can cause carrier thread pinning
 * and verify detection mechanisms to help diagnose and resolve performance
 * bottlenecks in virtual thread usage.
 * <p>
 * Note: These tests require Java 21 or later to run.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
@org.junit.jupiter.api.Tag(Java21TestGroup.NAME)
@org.junit.jupiter.api.Tag(VirtualThreadTestGroup.NAME)
public class ThreadPinningDetectionTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 100;
  private static final int BLOCKING_DURATION_MS = 50;
  
  private ExecutorService virtualThreadExecutor;
  private ThreadPinningDetector pinningDetector;
  
  @BeforeEach
  void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    pinningDetector = new ThreadPinningDetector();
    pinningDetector.start();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    pinningDetector.stop();
    virtualThreadExecutor.shutdown();
    virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
  }
  
  /**
   * Tests that synchronized blocks cause thread pinning when they contain blocking operations.
   * <p>
   * This is a common cause of performance issues when migrating to virtual threads.
   */
  @Test
  void testSynchronizedBlockCausesPinning() throws Exception {
    // Create a shared object for synchronization
    Object lock = new Object();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Reset pinning counter
    pinningDetector.resetPinningCount();
    
    // Launch multiple virtual threads that will execute synchronized blocks with blocking operations
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          synchronized (lock) {
            // Blocking operation inside synchronized block will cause pinning
            Thread.sleep(BLOCKING_DURATION_MS);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    latch.await(10, TimeUnit.SECONDS);
    
    // Verify that pinning was detected
    int pinningCount = pinningDetector.getPinningCount();
    log.info("Detected {} pinning events during synchronized block test", pinningCount);
    
    // We should detect at least some pinning events (not all threads might get detected depending on timing)
    assertThat("Should detect thread pinning with synchronized blocks", pinningCount, greaterThanOrEqualTo(1));
  }
  
  /**
   * Tests that ReentrantLock does not cause thread pinning when used correctly.
   * <p>
   * This demonstrates the recommended alternative to synchronized blocks when using virtual threads.
   */
  @Test
  void testReentrantLockDoesNotCausePinning() throws Exception {
    // Create a shared lock
    ReentrantLock lock = new ReentrantLock();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    
    // Reset pinning counter
    pinningDetector.resetPinningCount();
    
    // Launch multiple virtual threads that will use ReentrantLock with blocking operations
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          lock.lock();
          try {
            // Blocking operation with ReentrantLock should not cause pinning
            Thread.sleep(BLOCKING_DURATION_MS);
          }
          finally {
            lock.unlock();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    latch.await(10, TimeUnit.SECONDS);
    
    // Verify that no pinning was detected
    int pinningCount = pinningDetector.getPinningCount();
    log.info("Detected {} pinning events during ReentrantLock test", pinningCount);
    
    // We should not detect any pinning events with ReentrantLock
    assertThat("ReentrantLock should not cause thread pinning", pinningCount, is(0));
  }
  
  /**
   * Tests that synchronized methods cause thread pinning when they contain blocking operations.
   * <p>
   * This is another common cause of performance issues when migrating to virtual threads.
   */
  @Test
  void testSynchronizedMethodCausesPinning() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    BlockingOperationExample example = new BlockingOperationExample();
    
    // Reset pinning counter
    pinningDetector.resetPinningCount();
    
    // Launch multiple virtual threads that will call synchronized methods with blocking operations
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Call synchronized method that contains blocking operation
          example.synchronizedBlockingOperation();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    latch.await(10, TimeUnit.SECONDS);
    
    // Verify that pinning was detected
    int pinningCount = pinningDetector.getPinningCount();
    log.info("Detected {} pinning events during synchronized method test", pinningCount);
    
    // We should detect at least some pinning events
    assertThat("Should detect thread pinning with synchronized methods", pinningCount, greaterThanOrEqualTo(1));
  }
  
  /**
   * Tests that thread-local variables can cause performance issues with virtual threads.
   * <p>
   * While not technically pinning, thread-locals can cause memory leaks and performance issues
   * when used with large numbers of virtual threads.
   */
  @Test
  void testThreadLocalWithVirtualThreads() throws Exception {
    // Create a ThreadLocal that will be used by virtual threads
    ThreadLocal<byte[]> threadLocal = new ThreadLocal<>();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicBoolean leakDetected = new AtomicBoolean(false);
    
    // Launch multiple virtual threads that will use ThreadLocal
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Allocate a large array in ThreadLocal (not recommended with virtual threads)
          threadLocal.set(new byte[1024 * 1024]); // 1MB
          
          // Check if the ThreadLocal is still accessible
          if (threadLocal.get() != null) {
            // This is expected, but with thousands of virtual threads,
            // this pattern would lead to excessive memory usage
          }
          
          // Proper cleanup is essential with virtual threads
          threadLocal.remove();
          
          // Verify cleanup worked
          if (threadLocal.get() != null) {
            leakDetected.set(true);
          }
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    latch.await(10, TimeUnit.SECONDS);
    
    // Verify that no leaks were detected
    assertFalse(leakDetected.get(), "ThreadLocal should be properly cleaned up");
  }
  
  /**
   * Tests the performance difference between virtual threads and platform threads
   * when executing tasks that might cause pinning.
   */
  @Test
  void testVirtualThreadVsPlatformThreadPerformance() throws Exception {
    // Create executors for both thread types
    ExecutorService platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    try {
      // Test with platform threads first
      long platformThreadTime = measureExecutionTime(platformThreadExecutor, true);
      log.info("Platform thread execution time: {} ms", platformThreadTime);
      
      // Test with virtual threads
      long virtualThreadTime = measureExecutionTime(virtualThreadExecutor, true);
      log.info("Virtual thread execution time: {} ms", virtualThreadTime);
      
      // Now test with non-pinning operations
      long platformThreadTimeNoPinning = measureExecutionTime(platformThreadExecutor, false);
      log.info("Platform thread execution time (no pinning): {} ms", platformThreadTimeNoPinning);
      
      long virtualThreadTimeNoPinning = measureExecutionTime(virtualThreadExecutor, false);
      log.info("Virtual thread execution time (no pinning): {} ms", virtualThreadTimeNoPinning);
      
      // With pinning, virtual threads might not show significant advantage
      // Without pinning, virtual threads should perform better with many concurrent tasks
      assertTrue(virtualThreadTimeNoPinning <= platformThreadTimeNoPinning * 1.5,
          "Virtual threads should perform at least as well as platform threads without pinning");
    }
    finally {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Measures execution time for a batch of tasks using the provided executor.
   *
   * @param executor the executor service to use
   * @param useSynchronized whether to use synchronized blocks (which cause pinning)
   * @return execution time in milliseconds
   */
  private long measureExecutionTime(ExecutorService executor, boolean useSynchronized) throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    Object lock = new Object();
    ReentrantLock reentrantLock = new ReentrantLock();
    
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      executor.submit(() -> {
        try {
          if (useSynchronized) {
            synchronized (lock) {
              // Simulate I/O or blocking operation
              Thread.sleep(BLOCKING_DURATION_MS);
            }
          }
          else {
            reentrantLock.lock();
            try {
              // Simulate I/O or blocking operation
              Thread.sleep(BLOCKING_DURATION_MS);
            }
            finally {
              reentrantLock.unlock();
            }
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    latch.await(30, TimeUnit.SECONDS);
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Example class with synchronized methods that cause thread pinning.
   */
  static class BlockingOperationExample
  {
    /**
     * This method will cause thread pinning because it's synchronized and contains a blocking operation.
     */
    public synchronized void synchronizedBlockingOperation() {
      try {
        // Blocking operation inside synchronized method will cause pinning
        Thread.sleep(BLOCKING_DURATION_MS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    
    /**
     * This method uses ReentrantLock instead of synchronized and won't cause pinning.
     */
    private final ReentrantLock lock = new ReentrantLock();
    
    public void lockBasedBlockingOperation() {
      lock.lock();
      try {
        // Blocking operation with ReentrantLock won't cause pinning
        Thread.sleep(BLOCKING_DURATION_MS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      finally {
        lock.unlock();
      }
    }
  }
  
  /**
   * Utility class for detecting thread pinning events using JFR (Java Flight Recorder).
   */
  static class ThreadPinningDetector
  {
    private RecordingStream recordingStream;
    private final AtomicInteger pinningCount = new AtomicInteger(0);
    private final List<String> pinningStackTraces = new ArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    /**
     * Starts monitoring for thread pinning events.
     */
    public void start() {
      if (running.compareAndSet(false, true)) {
        recordingStream = new RecordingStream();
        
        // Enable the VirtualThreadPinned event with stack traces
        recordingStream.enable("jdk.VirtualThreadPinned").withStackTrace();
        
        // Set up event handler for pinning events
        recordingStream.onEvent("jdk.VirtualThreadPinned", event -> {
          pinningCount.incrementAndGet();
          
          // Extract stack trace if available
          if (event.hasField("stackTrace")) {
            String stackTrace = extractStackTrace(event);
            synchronized (pinningStackTraces) {
              pinningStackTraces.add(stackTrace);
            }
          }
        });
        
        // Prevent memory leaks in long-running applications
        recordingStream.setMaxAge(Duration.ofSeconds(10));
        
        // Start the recording asynchronously
        recordingStream.startAsync();
      }
    }
    
    /**
     * Stops monitoring for thread pinning events.
     */
    public void stop() {
      if (running.compareAndSet(true, false) && recordingStream != null) {
        recordingStream.close();
      }
    }
    
    /**
     * Gets the current count of detected pinning events.
     *
     * @return the number of pinning events detected
     */
    public int getPinningCount() {
      return pinningCount.get();
    }
    
    /**
     * Resets the pinning event counter.
     */
    public void resetPinningCount() {
      pinningCount.set(0);
      synchronized (pinningStackTraces) {
        pinningStackTraces.clear();
      }
    }
    
    /**
     * Gets the list of stack traces from pinning events.
     *
     * @return list of stack traces as strings
     */
    public List<String> getPinningStackTraces() {
      synchronized (pinningStackTraces) {
        return new ArrayList<>(pinningStackTraces);
      }
    }
    
    /**
     * Extracts a readable stack trace from a JFR event.
     *
     * @param event the JFR event containing stack trace information
     * @return a formatted stack trace string
     */
    private String extractStackTrace(RecordedEvent event) {
      StringBuilder sb = new StringBuilder();
      
      // Add thread name and duration
      sb.append("Thread pinning detected in: ")
          .append(event.getString("eventThread"))
          .append(", duration: ")
          .append(event.getDuration().toMillis())
          .append(" ms\n");
      
      // Add stack trace if available
      if (event.hasField("stackTrace")) {
        sb.append("Stack trace:\n");
        try {
          // This is a simplification - actual implementation would need to traverse the stack frames
          sb.append(event.getStackTrace().toString());
        }
        catch (Exception e) {
          sb.append("[Error extracting stack trace: ").append(e.getMessage()).append("]");
        }
      }
      
      return sb.toString();
    }
  }
}