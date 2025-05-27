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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.sonatype.goodies.testsupport.TestSupport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Tests for detecting and reporting thread pinning issues when using Virtual Threads.
 * <p>
 * Thread pinning occurs when a Virtual Thread gets "stuck" to its carrier thread, which can
 * significantly impact performance. This happens primarily in two scenarios:
 * <ul>
 *   <li>When using synchronized blocks or methods</li>
 *   <li>When executing native methods or foreign functions</li>
 * </ul>
 * <p>
 * These tests verify that thread pinning detection works correctly and that appropriate
 * diagnostics are generated.
 */
@Tag("Java21TestGroup")
@DisplayName("Thread Pinning Detection Tests")
public class ThreadPinningTest
    extends TestSupport
{
  private static final Duration TEST_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration BLOCKING_DURATION = Duration.ofMillis(500);
  
  private final ByteArrayOutputStream outputCapture = new ByteArrayOutputStream();
  private final PrintStream originalOut = System.out;
  private final PrintStream originalErr = System.err;
  
  @BeforeEach
  public void setupOutputCapture() {
    System.setOut(new PrintStream(outputCapture));
    System.setErr(new PrintStream(outputCapture));
    System.setProperty("jdk.tracePinnedThreads", "full");
  }
  
  @AfterEach
  public void restoreOutput() {
    System.setOut(originalOut);
    System.setErr(originalErr);
    System.clearProperty("jdk.tracePinnedThreads");
  }
  
  /**
   * Tests that thread pinning is correctly detected when using synchronized blocks.
   * <p>
   * This test creates a Virtual Thread that enters a synchronized block and then
   * performs a blocking operation (Thread.sleep). This should cause the Virtual Thread
   * to be pinned to its carrier thread, which should be detected and reported.
   */
  @Test
  @DisplayName("Detect pinning with synchronized blocks")
  public void testPinningWithSynchronizedBlocks() throws Exception {
    Object lock = new Object();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    Thread virtualThread = Thread.ofVirtual().name("test-pinned-thread").start(() -> {
      try {
        synchronized (lock) {
          // Blocking operation inside synchronized block will cause pinning
          Thread.sleep(BLOCKING_DURATION.toMillis());
          latch.countDown();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Wait for the thread to complete
    boolean completed = latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertThat("Thread execution completed", completed, org.hamcrest.Matchers.is(true));
    
    // Verify pinning was detected
    String output = outputCapture.toString();
    log.info("Captured output: {}\n", output);
    
    assertThat("Pinning detection output contains thread name", 
        output, containsString("test-pinned-thread"));
    assertThat("Pinning detection output contains reason", 
        output, containsString("reason:MONITOR"));
  }
  
  /**
   * Tests that thread pinning is correctly detected when using synchronized methods.
   * <p>
   * This test creates a Virtual Thread that calls a synchronized method which performs
   * a blocking operation (Thread.sleep). This should cause the Virtual Thread to be
   * pinned to its carrier thread, which should be detected and reported.
   */
  @Test
  @DisplayName("Detect pinning with synchronized methods")
  public void testPinningWithSynchronizedMethods() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    PinningTestHelper helper = new PinningTestHelper(latch);
    
    Thread virtualThread = Thread.ofVirtual().name("test-method-pinning").start(() -> {
      helper.synchronizedBlockingMethod();
    });
    
    // Wait for the thread to complete
    boolean completed = latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertThat("Thread execution completed", completed, org.hamcrest.Matchers.is(true));
    
    // Verify pinning was detected
    String output = outputCapture.toString();
    log.info("Captured output: {}\n", output);
    
    assertThat("Pinning detection output contains thread name", 
        output, containsString("test-method-pinning"));
    assertThat("Pinning detection output contains reason", 
        output, containsString("reason:MONITOR"));
    assertThat("Pinning detection output contains method name", 
        output, containsString("synchronizedBlockingMethod"));
  }
  
  /**
   * Tests that thread pinning is correctly detected in a concurrent scenario with multiple
   * Virtual Threads competing for a synchronized resource.
   * <p>
   * This test creates multiple Virtual Threads that all try to access a synchronized resource
   * and perform blocking operations. This should cause thread pinning, which should be detected
   * and reported.
   */
  @Test
  @DisplayName("Detect pinning in concurrent scenarios")
  public void testPinningInConcurrentScenarios() throws Exception {
    final int threadCount = 5;
    CountDownLatch latch = new CountDownLatch(threadCount);
    PinningTestHelper helper = new PinningTestHelper(latch);
    
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Submit multiple tasks that will cause pinning
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      executor.submit(() -> {
        Thread.currentThread().setName("concurrent-pinned-" + threadId);
        helper.synchronizedBlockingMethod();
        return null;
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertThat("All threads completed", completed, org.hamcrest.Matchers.is(true));
    
    executor.shutdown();
    boolean terminated = executor.awaitTermination(1, TimeUnit.SECONDS);
    assertThat("Executor terminated", terminated, org.hamcrest.Matchers.is(true));
    
    // Verify pinning was detected
    String output = outputCapture.toString();
    log.info("Captured output: {}\n", output);
    
    assertThat("Pinning detection output contains thread name pattern", 
        output, containsString("concurrent-pinned-"));
    assertThat("Pinning detection output contains reason", 
        output, containsString("reason:MONITOR"));
  }
  
  /**
   * Tests that using ReentrantLock instead of synchronized blocks avoids thread pinning.
   * <p>
   * This test creates a Virtual Thread that uses a ReentrantLock and then performs a
   * blocking operation. Unlike with synchronized blocks, this should not cause thread
   * pinning, so no pinning detection output should be generated.
   */
  @Test
  @DisplayName("Verify ReentrantLock avoids pinning")
  public void testReentrantLockAvoidsPinning() throws Exception {
    ReentrantLock lock = new ReentrantLock();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.ofVirtual().name("test-unpinned-thread").start(() -> {
      try {
        lock.lock();
        try {
          // Blocking operation with ReentrantLock should not cause pinning
          Thread.sleep(BLOCKING_DURATION.toMillis());
        }
        finally {
          lock.unlock();
        }
        latch.countDown();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Wait for the thread to complete
    boolean completed = latch.await(TEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertThat("Thread execution completed", completed, org.hamcrest.Matchers.is(true));
    
    // Verify no pinning was detected
    String output = outputCapture.toString();
    log.info("Captured output: {}\n", output);
    
    // The output should not contain pinning detection for this thread
    assertThat("No pinning detection output for ReentrantLock", 
        !output.contains("test-unpinned-thread") || !output.contains("reason:MONITOR"), 
        org.hamcrest.Matchers.is(true));
  }
  
  /**
   * Helper class for testing thread pinning scenarios.
   */
  private static class PinningTestHelper {
    private final CountDownLatch latch;
    
    public PinningTestHelper(CountDownLatch latch) {
      this.latch = latch;
    }
    
    /**
     * A synchronized method that performs a blocking operation, which will cause
     * thread pinning when called by a Virtual Thread.
     */
    public synchronized void synchronizedBlockingMethod() {
      try {
        // Blocking operation inside synchronized method will cause pinning
        Thread.sleep(BLOCKING_DURATION.toMillis());
        latch.countDown();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}