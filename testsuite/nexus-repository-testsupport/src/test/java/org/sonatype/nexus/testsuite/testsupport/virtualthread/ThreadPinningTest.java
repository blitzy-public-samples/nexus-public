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
package org.sonatype.nexus.testsuite.testsupport.virtualthread;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for verifying the detection and reporting of thread pinning issues when using Virtual Threads.
 * 
 * Thread pinning occurs when a Virtual Thread gets "stuck" to its carrier thread (platform thread) and
 * can't be unmounted. This significantly impacts the performance benefits of Virtual Threads.
 * 
 * These tests verify that the system correctly identifies operations that cause thread pinning
 * and provides appropriate diagnostic information.
 */
public class ThreadPinningTest
{
  private static final String THREAD_PINNING_PROPERTY = "jdk.tracePinnedThreads";
  private static final String PINNING_DETECTION_ENABLED = "full";
  
  private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;
  private String originalPinningProperty;
    
  @BeforeEach
  public void setUp() {
    // Capture system output to verify pinning detection messages
    System.setOut(new PrintStream(outputCapture));
    
    // Store original property value
    originalPinningProperty = System.getProperty(THREAD_PINNING_PROPERTY);
    
    // Enable thread pinning detection
    System.setProperty(THREAD_PINNING_PROPERTY, PINNING_DETECTION_ENABLED);
  }
  
  @AfterEach
  public void tearDown() {
    // Restore original system output
    System.setOut(originalOut);
    
    // Restore original property value
    if (originalPinningProperty != null) {
      System.setProperty(THREAD_PINNING_PROPERTY, originalPinningProperty);
    } else {
      System.clearProperty(THREAD_PINNING_PROPERTY);
    }
  }
    
  /**
   * Verifies that the system detects thread pinning when using synchronized blocks
   * with blocking operations inside them.
   */
  @Test
  @DisplayName("Detect thread pinning with synchronized block")
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testSynchronizedBlockPinning() throws Exception {
    // Create and start a virtual thread that will be pinned
    Thread virtualThread = Thread.ofVirtual().name("test-pinned-thread").start(() -> {
      Object lock = new Object();
      synchronized (lock) {
        try {
          // Blocking operation inside synchronized block causes pinning
          Thread.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    
    // Wait for the thread to complete
    virtualThread.join();
    
    // Verify that pinning was detected and reported
    String output = outputCapture.toString();
    assertTrue(output.contains("VirtualThread"), "Should detect virtual thread");
    assertTrue(output.contains("pinned"), "Should report thread as pinned");
    assertTrue(output.contains("MONITOR"), "Should identify monitor as the reason for pinning");
    assertTrue(output.contains("testSynchronizedBlockPinning"), "Should include the test method in stack trace");
    }
    
  /**
   * Verifies that the system detects thread pinning when using synchronized methods
   * with blocking operations inside them.
   */
  @Test
  @DisplayName("Detect thread pinning with synchronized method")
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testSynchronizedMethodPinning() throws Exception {
    // Create and start a virtual thread that will call a synchronized method
    Thread virtualThread = Thread.ofVirtual().name("test-pinned-method-thread").start(() -> {
      performSynchronizedBlockingOperation();
    });
    
    // Wait for the thread to complete
    virtualThread.join();
    
    // Verify that pinning was detected and reported
    String output = outputCapture.toString();
    assertTrue(output.contains("VirtualThread"), "Should detect virtual thread");
    assertTrue(output.contains("pinned"), "Should report thread as pinned");
    assertTrue(output.contains("MONITOR"), "Should identify monitor as the reason for pinning");
    assertTrue(output.contains("performSynchronizedBlockingOperation"), "Should include the synchronized method in stack trace");
    }
    
  /**
   * Verifies that the system correctly reports stack traces for pinned threads,
   * which is essential for diagnosing and fixing thread pinning issues.
   */
  @Test
  @DisplayName("Verify stack trace quality for pinned threads")
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testPinningStackTraceQuality() throws Exception {
    // Create and start a virtual thread with nested method calls leading to pinning
    Thread virtualThread = Thread.ofVirtual().name("test-stack-trace-thread").start(() -> {
      nestedMethodLevel1();
    });
    
    // Wait for the thread to complete
    virtualThread.join();
    
    // Verify that the stack trace includes all method levels
    String output = outputCapture.toString();
    assertTrue(output.contains("nestedMethodLevel1"), "Stack trace should include level 1 method");
    assertTrue(output.contains("nestedMethodLevel2"), "Stack trace should include level 2 method");
    assertTrue(output.contains("nestedMethodLevel3"), "Stack trace should include level 3 method");
    }
    
  /**
   * Verifies that ReentrantLock does not cause thread pinning, unlike synchronized blocks.
   * This test demonstrates the recommended alternative to synchronized for Virtual Threads.
   */
  @Test
  @DisplayName("Verify ReentrantLock doesn't cause thread pinning")
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testReentrantLockNoPinning() throws Exception {
    // Create and start a virtual thread that uses ReentrantLock
    Thread virtualThread = Thread.ofVirtual().name("test-reentrant-lock-thread").start(() -> {
      ReentrantLock lock = new ReentrantLock();
      lock.lock();
      try {
        // Blocking operation with ReentrantLock should not cause pinning
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        lock.unlock();
      }
    });
    
    // Wait for the thread to complete
    virtualThread.join();
    
    // Verify that no pinning was detected
    String output = outputCapture.toString();
    assertFalse(output.contains("test-reentrant-lock-thread") && output.contains("pinned"), 
            "ReentrantLock should not cause thread pinning");
    }
    
  /**
   * Tests a common repository operation scenario that might cause thread pinning.
   * This simulates I/O operations within synchronized blocks that could occur in repository code.
   */
  @Test
  @DisplayName("Detect pinning in simulated repository operations")
  @Timeout(value = 5, unit = TimeUnit.SECONDS)
  public void testRepositoryOperationPinning() throws Exception {
    // Create and start a virtual thread that simulates a repository operation
    Thread virtualThread = Thread.ofVirtual().name("repository-operation-thread").start(() -> {
      simulateRepositoryOperation();
    });
    
    // Wait for the thread to complete
    virtualThread.join();
    
    // Verify that pinning was detected and reported
    String output = outputCapture.toString();
    assertTrue(output.contains("repository-operation-thread"), "Should identify the repository thread");
    assertTrue(output.contains("pinned"), "Should report thread as pinned");
    assertTrue(output.contains("simulateRepositoryOperation"), "Should include the repository operation in stack trace");
    }
    
  /**
   * Tests concurrent operations that might cause thread pinning, verifying that
   * the system correctly identifies and reports pinning in a concurrent environment.
   */
  @Test
  @DisplayName("Detect pinning in concurrent operations")
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void testConcurrentPinning() throws Exception {
    final int threadCount = 5;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create and start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("concurrent-thread-" + threadId).start(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform operation that will cause pinning
          Object lock = new Object();
          synchronized (lock) {
            // Blocking operation inside synchronized block
            Thread.sleep(50);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Verify that pinning was detected for multiple threads
    String output = outputCapture.toString();
    assertTrue(output.contains("concurrent-thread-"), "Should identify concurrent threads");
    assertTrue(output.contains("pinned"), "Should report threads as pinned");
    
    // Count occurrences of pinning reports (may be less than threadCount due to JVM optimizations)
    int pinningCount = countOccurrences(output, "pinned");
    assertTrue(pinningCount > 0, "Should detect at least one pinning event");
    }
    
  // Helper methods
  
  /**
   * A synchronized method that performs a blocking operation, which will cause thread pinning.
   */
  private synchronized void performSynchronizedBlockingOperation() {
    try {
      // Blocking operation inside synchronized method causes pinning
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * First level of nested method calls to test stack trace quality.
   */
  private void nestedMethodLevel1() {
    nestedMethodLevel2();
  }
  
  /**
   * Second level of nested method calls to test stack trace quality.
   */
  private void nestedMethodLevel2() {
    nestedMethodLevel3();
  }
  
  /**
   * Third level of nested method calls that causes thread pinning.
   */
  private synchronized void nestedMethodLevel3() {
    try {
      // Blocking operation inside synchronized method causes pinning
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Simulates a repository operation that might cause thread pinning.
   * This represents operations like file I/O or database access within synchronized blocks.
   */
  private void simulateRepositoryOperation() {
    Object repositoryLock = new Object();
    synchronized (repositoryLock) {
      // Simulate I/O operation (e.g., reading from a blob store)
      simulateIOOperation(100);
    }
  }
  
  /**
   * Simulates an I/O operation by sleeping for the specified duration.
   */
  private void simulateIOOperation(long milliseconds) {
    try {
      Thread.sleep(milliseconds);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Counts the number of occurrences of a substring within a string.
   */
  private int countOccurrences(String text, String substring) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(substring, index)) != -1) {
      count++;
      index += substring.length();
    }
    return count;
  }
}