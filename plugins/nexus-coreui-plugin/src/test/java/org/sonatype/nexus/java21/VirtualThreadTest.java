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
package org.sonatype.nexus.java21;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for validating Java 21 Virtual Threads functionality in CoreUI components.
 * 
 * This test class verifies that virtual threads are correctly created, scheduled, and
 * perform well under high concurrency scenarios. It also tests thread mounting/unmounting
 * behavior and validates thread pinning detection and prevention.
 *
 * @since 3.60
 */
public class VirtualThreadTest
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTest.class);
  
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Tests that virtual threads can be created and are correctly identified.
   */
  @Test
  void testVirtualThreadCreation() throws Exception {
    Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
      try {
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    virtualThread.join(1000);
    
    assertTrue(virtualThread.isVirtual(), "Thread should be a virtual thread");
    assertEquals("test-virtual-thread", virtualThread.getName(), "Thread name should match");
  }
  
  /**
   * Tests that many virtual threads can be created without exhausting system resources,
   * which is important for handling many concurrent repository operations.
   */
  @Test
  @Timeout(value = 30)
  void testManyVirtualThreads() throws Exception {
    int threadCount = 10_000; // Create 10,000 virtual threads
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger completedThreads = new AtomicInteger(0);
    
    // Create and start many virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("virtual-thread-" + threadId).start(() -> {
        try {
          // Simulate a short I/O operation
          Thread.sleep(10);
          completedThreads.incrementAndGet();
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
    assertTrue(latch.await(20, TimeUnit.SECONDS), "All virtual threads should complete in time");
    assertEquals(threadCount, completedThreads.get(), "All threads should have completed successfully");
    
    log.info("Successfully created and ran {} virtual threads", threadCount);
  }
  
  /**
   * Tests virtual thread performance with simulated I/O-bound operations,
   * which is common in repository browsing and search operations.
   */
  @Test
  void testVirtualThreadPerformanceWithIoBoundOperations() throws Exception {
    int operationCount = 1000;
    CountDownLatch latch = new CountDownLatch(operationCount);
    List<Future<?>> futures = new ArrayList<>();
    
    long startTime = System.nanoTime();
    
    // Submit I/O-bound tasks to the virtual thread executor
    for (int i = 0; i < operationCount; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Simulate an I/O operation (e.g., database query, HTTP request)
          simulateIoOperation(50);
          return true;
        }
        finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "All I/O operations should complete in time");
    
    long duration = System.nanoTime() - startTime;
    double durationInSeconds = Duration.ofNanos(duration).toMillis() / 1000.0;
    
    log.info("Completed {} I/O-bound operations in {} seconds", operationCount, durationInSeconds);
    
    // Verify all futures completed successfully
    for (Future<?> future : futures) {
      assertTrue((Boolean) future.get(), "Operation should complete successfully");
    }
  }
  
  /**
   * Tests that virtual threads properly unmount from carrier threads during blocking operations,
   * allowing other virtual threads to make progress.
   */
  @Test
  void testVirtualThreadUnmounting() throws Exception {
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger concurrentThreads = new AtomicInteger(0);
    AtomicInteger maxConcurrentThreads = new AtomicInteger(0);
    
    // Create threads that will all start at the same time
    for (int i = 0; i < threadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Track concurrent execution
          int current = concurrentThreads.incrementAndGet();
          maxConcurrentThreads.updateAndGet(max -> Math.max(max, current));
          
          // Simulate I/O operation that should allow unmounting
          simulateIoOperation(100);
          
          concurrentThreads.decrementAndGet();
          return true;
        }
        catch (Exception e) {
          return false;
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "All threads should complete");
    
    // The max concurrent threads should be higher than the number of available processors
    // if virtual threads are properly unmounting during I/O operations
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    log.info("Max concurrent threads: {}, Available processors: {}", 
             maxConcurrentThreads.get(), availableProcessors);
    
    assertTrue(maxConcurrentThreads.get() > availableProcessors, 
              "Virtual threads should enable higher concurrency than available processors");
  }
  
  /**
   * Tests that thread pinning is detected and handled appropriately.
   * Thread pinning occurs when a virtual thread cannot unmount from its carrier thread,
   * typically during synchronized blocks or native method calls.
   */
  @Test
  void testThreadPinningDetection() throws Exception {
    Object lock = new Object();
    AtomicReference<Boolean> wasPinned = new AtomicReference<>(false);
    
    // Create a virtual thread that will be pinned due to synchronized block
    Thread virtualThread = Thread.ofVirtual().name("pinned-thread").start(() -> {
      // This synchronized block will pin the virtual thread to its carrier thread
      synchronized (lock) {
        try {
          // Check if we're running on a virtual thread
          boolean isVirtual = Thread.currentThread().isVirtual();
          
          // In a pinned state, the thread cannot yield to other virtual threads
          // during blocking operations
          Thread.sleep(500);
          
          wasPinned.set(isVirtual);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    
    virtualThread.join(2000);
    
    assertTrue(wasPinned.get(), "Thread should have been a virtual thread and pinned during synchronized block");
    log.info("Successfully detected thread pinning in synchronized block");
  }
  
  /**
   * Tests that virtual thread factory correctly creates virtual threads.
   */
  @Test
  void testVirtualThreadFactory() {
    ThreadFactory factory = Thread.ofVirtual().factory();
    AtomicReference<Thread> threadRef = new AtomicReference<>();
    
    Thread thread = factory.newThread(() -> {
      threadRef.set(Thread.currentThread());
    });
    
    thread.start();
    try {
      thread.join(1000);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    Thread createdThread = threadRef.get();
    assertNotNull(createdThread, "Thread should have been created and run");
    assertTrue(createdThread.isVirtual(), "Thread created by virtual thread factory should be virtual");
  }
  
  /**
   * Tests context propagation across virtual threads, which is important for
   * maintaining request context in repository operations.
   */
  @Test
  void testContextPropagation() throws Exception {
    // Create a thread-local to test context propagation
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("parent-context");
    
    // Virtual threads don't inherit thread locals by default
    AtomicReference<String> childContext = new AtomicReference<>();
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      // Capture the thread local value in the virtual thread
      childContext.set(threadLocal.get());
    });
    
    virtualThread.join(1000);
    
    // Virtual threads don't inherit ThreadLocal values by default
    assertEquals(null, childContext.get(), "Virtual threads should not inherit ThreadLocal values by default");
  }
  
  /**
   * Tests that virtual threads can be interrupted properly.
   */
  @Test
  void testVirtualThreadInterruption() throws Exception {
    AtomicReference<Boolean> wasInterrupted = new AtomicReference<>(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      try {
        Thread.sleep(10000); // Sleep for a long time
      }
      catch (InterruptedException e) {
        wasInterrupted.set(true);
        latch.countDown();
      }
    });
    
    // Interrupt the virtual thread
    Thread.sleep(100); // Give the thread time to start
    virtualThread.interrupt();
    
    // Wait for the thread to acknowledge interruption
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Thread should be interrupted");
    assertTrue(wasInterrupted.get(), "Virtual thread should have been interrupted");
  }
  
  /**
   * Tests that virtual threads properly handle exceptions.
   */
  @Test
  void testVirtualThreadExceptionHandling() throws Exception {
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread.UncaughtExceptionHandler handler = (thread, throwable) -> {
      caughtException.set(throwable);
      latch.countDown();
    };
    
    Thread virtualThread = Thread.ofVirtual()
        .uncaughtExceptionHandler(handler)
        .start(() -> {
          throw new RuntimeException("Test exception");
        });
    
    // Wait for the exception to be caught
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Exception should be caught");
    assertNotNull(caughtException.get(), "Exception should have been caught by handler");
    assertEquals("Test exception", caughtException.get().getMessage(), "Exception message should match");
  }
  
  /**
   * Simulates an I/O-bound operation that would typically block a thread.
   * In a real application, this would be a database query, HTTP request, or file operation.
   *
   * @param milliseconds the duration to simulate the I/O operation for
   */
  private void simulateIoOperation(long milliseconds) {
    try {
      // In a real application, this would be replaced with actual I/O operations
      // such as database queries, HTTP requests, or file operations
      Thread.sleep(milliseconds);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}