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
package org.virtualthread;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link NexusVirtualThreadFactory} to validate virtual thread creation and behavior.
 *
 * @since 3.60
 */
public class NexusVirtualThreadFactoryTest
{
  private static final Logger LOGGER = Logger.getLogger(NexusVirtualThreadFactoryTest.class.getName());
  private static final String TEST_PREFIX = "test-virtual";
  private static final int THREAD_COUNT = 10;
  
  private ThreadFactory virtualThreadFactory;
  
  @BeforeEach
  public void setUp() {
    // Assuming NexusVirtualThreadFactory is implemented similar to NexusThreadFactory
    // but creates virtual threads instead of platform threads
    virtualThreadFactory = new NexusVirtualThreadFactory(TEST_PREFIX);
  }
  
  @Test
  @DisplayName("Verify threads created are virtual threads")
  public void testThreadsAreVirtual() throws Exception {
    Thread thread = virtualThreadFactory.newThread(() -> {});
    
    // Verify the thread is a virtual thread
    assertTrue(thread.isVirtual(), "Thread should be a virtual thread");
  }
  
  @Test
  @DisplayName("Verify thread naming convention with prefix")
  public void testThreadNaming() throws Exception {
    Thread thread = virtualThreadFactory.newThread(() -> {});
    
    // Verify the thread name contains the specified prefix
    assertTrue(thread.getName().contains(TEST_PREFIX), 
        "Thread name should contain the specified prefix: " + TEST_PREFIX);
  }
  
  @Test
  @DisplayName("Verify multiple threads have unique names")
  public void testMultipleThreadsHaveUniqueNames() throws Exception {
    Thread[] threads = new Thread[THREAD_COUNT];
    
    // Create multiple threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      threads[i] = virtualThreadFactory.newThread(() -> {});
    }
    
    // Verify each thread has a unique name
    for (int i = 0; i < THREAD_COUNT; i++) {
      for (int j = i + 1; j < THREAD_COUNT; j++) {
        assertNotEquals(threads[i].getName(), threads[j].getName(), 
            "Thread names should be unique");
      }
    }
  }
  
  @Test
  @DisplayName("Verify thread-local variable propagation")
  public void testThreadLocalPropagation() throws Exception {
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    CountDownLatch latch = new CountDownLatch(1);
    String expectedValue = "test-value";
    AtomicInteger result = new AtomicInteger(0);
    
    // Set thread-local in parent thread
    threadLocal.set(expectedValue);
    
    Thread thread = virtualThreadFactory.newThread(() -> {
      try {
        // Check if thread-local is inherited (it should not be by default)
        if (threadLocal.get() == null) {
          result.set(1); // Expected - thread locals are not inherited by default
          LOGGER.fine("Thread-local value not inherited as expected");
        } else {
          result.set(2); // Unexpected - thread locals should not be inherited
          LOGGER.warning("Thread-local value unexpectedly inherited: " + threadLocal.get());
        }
      } finally {
        latch.countDown();
      }
    });
    
    thread.start();
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Thread did not complete in time");
    assertEquals(1, result.get(), "Thread-local variables should not be inherited by default");
  }
  
  @Test
  @DisplayName("Verify inheritable thread-local variable propagation")
  public void testInheritableThreadLocalPropagation() throws Exception {
    InheritableThreadLocal<String> inheritableThreadLocal = new InheritableThreadLocal<>();
    CountDownLatch latch = new CountDownLatch(1);
    String expectedValue = "test-inheritable-value";
    AtomicInteger result = new AtomicInteger(0);
    
    // Set inheritable thread-local in parent thread
    inheritableThreadLocal.set(expectedValue);
    
    Thread thread = virtualThreadFactory.newThread(() -> {
      try {
        // Check if inheritable thread-local is inherited
        String actualValue = inheritableThreadLocal.get();
        if (expectedValue.equals(actualValue)) {
          result.set(1); // Expected - inheritable thread locals should be inherited
          LOGGER.fine("Inheritable thread-local value inherited as expected: " + actualValue);
        } else {
          result.set(2); // Unexpected - inheritable thread locals not inherited
          LOGGER.warning("Inheritable thread-local value not inherited correctly. Expected: " 
              + expectedValue + ", Actual: " + actualValue);
        }
      } finally {
        latch.countDown();
      }
    });
    
    thread.start();
    assertTrue(latch.await(1, TimeUnit.SECONDS), "Thread did not complete in time");
    assertEquals(1, result.get(), "Inheritable thread-local variables should be inherited");
  }
  
  @Test
  @DisplayName("Compare performance between virtual and platform threads")
  public void testPerformanceComparison() throws Exception {
    final int taskCount = 1000;
    final Duration sleepTime = Duration.ofMillis(10);
    
    // Create a platform thread factory (using standard Java thread factory)
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Measure time for platform threads
    long platformTime = measureExecutionTime(platformThreadFactory, taskCount, sleepTime);
    
    // Measure time for virtual threads
    long virtualTime = measureExecutionTime(virtualThreadFactory, taskCount, sleepTime);
    
    LOGGER.info("Platform threads execution time: " + platformTime + "ms");
    LOGGER.info("Virtual threads execution time: " + virtualTime + "ms");
    
    // Virtual threads should be more efficient for I/O-bound tasks
    // This is not a strict assertion as performance can vary, but virtual threads
    // should generally be faster for I/O-bound tasks with many threads
    assertTrue(virtualTime <= platformTime * 1.5, 
        "Virtual threads should not be significantly slower than platform threads");
  }
  
  @Test
  @DisplayName("Verify thread group assignment")
  public void testThreadGroupAssignment() throws Exception {
    // Create a specific thread group for testing
    ThreadGroup expectedThreadGroup = Thread.currentThread().getThreadGroup();
    
    // Create a thread with the factory
    Thread thread = virtualThreadFactory.newThread(() -> {});
    
    // Virtual threads should inherit the thread group from the creating thread
    assertEquals(expectedThreadGroup, thread.getThreadGroup(), 
        "Virtual thread should inherit the thread group from the creating thread");
  }
  
  @Test
  @DisplayName("Verify concurrent execution of virtual threads")
  public void testConcurrentExecution() throws Exception {
    final int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger concurrentExecutionCount = new AtomicInteger(0);
    AtomicInteger maxConcurrentExecution = new AtomicInteger(0);
    
    // Create and start multiple virtual threads
    Thread[] threads = new Thread[threadCount];
    for (int i = 0; i < threadCount; i++) {
      threads[i] = virtualThreadFactory.newThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Increment counter to track concurrent execution
          int current = concurrentExecutionCount.incrementAndGet();
          // Update max concurrent execution count
          maxConcurrentExecution.updateAndGet(max -> Math.max(max, current));
          
          // Simulate some work
          Thread.sleep(50);
          
          // Decrement counter
          concurrentExecutionCount.decrementAndGet();
          completionLatch.countDown();
        } 
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      threads[i].start();
    }
    
    // Release all threads to run concurrently
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Not all threads completed in time");
    
    // Verify that threads executed concurrently
    assertTrue(maxConcurrentExecution.get() > 1, 
        "Virtual threads should execute concurrently, but max concurrent execution was: " 
        + maxConcurrentExecution.get());
    
    // With virtual threads, we should be able to achieve high concurrency
    assertTrue(maxConcurrentExecution.get() > threadCount / 2, 
        "Virtual threads should achieve high concurrency, but max concurrent execution was only: " 
        + maxConcurrentExecution.get() + " out of " + threadCount);
  }
  
  /**
   * Measures execution time for running tasks using the specified thread factory.
   * This method simulates I/O-bound tasks by having each thread sleep for a specified duration.
   * Virtual threads are expected to handle this type of workload more efficiently than platform threads
   * when there are many concurrent tasks.
   *
   * @param threadFactory the thread factory to use
   * @param taskCount the number of tasks to execute
   * @param sleepTime the duration each task should sleep (simulating I/O)
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(
      ThreadFactory threadFactory, 
      int taskCount, 
      Duration sleepTime) throws Exception {
    
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks that simulate I/O-bound work
    for (int i = 0; i < taskCount; i++) {
      executor.submit(() -> {
        try {
          // Simulate I/O operation
          Thread.sleep(sleepTime);
        } 
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    latch.await();
    executor.shutdown();
    executor.awaitTermination(1, TimeUnit.MINUTES);
    
    return System.currentTimeMillis() - startTime;
  }
}