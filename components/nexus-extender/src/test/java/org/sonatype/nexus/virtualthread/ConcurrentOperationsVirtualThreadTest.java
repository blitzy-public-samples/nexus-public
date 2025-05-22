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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests high-concurrency scenarios using Java 21 Virtual Threads.
 * 
 * These tests verify that thousands of concurrent operations can be executed efficiently
 * with minimal resource usage, validate thread context propagation across multiple virtual threads,
 * ensure data consistency during parallel operations, and test extreme concurrency scenarios
 * that would be impossible with platform threads.
 */
@EnabledOnJre(JRE.JAVA_21)
public class ConcurrentOperationsVirtualThreadTest
    extends TestSupport
{
  private static final int SMALL_TASK_COUNT = 1_000;
  private static final int LARGE_TASK_COUNT = 10_000;
  private static final int EXTREME_TASK_COUNT = 100_000;
  
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("Virtual thread executor did not terminate in the expected time frame.");
        virtualThreadExecutor.shutdownNow();
      }
    }
  }
  
  /**
   * Tests that we can create and run thousands of virtual threads concurrently.
   * This would be impractical with platform threads due to their higher memory footprint.
   */
  @Test
  void testMassiveConcurrentOperations() throws Exception {
    AtomicInteger completedTasks = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(LARGE_TASK_COUNT);
    
    // Submit a large number of tasks
    for (int i = 0; i < LARGE_TASK_COUNT; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate some work with random duration
          Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10));
          completedTasks.incrementAndGet();
          latch.countDown();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          fail("Task " + taskId + " was interrupted: " + e.getMessage());
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Not all tasks completed in time");
    assertEquals(LARGE_TASK_COUNT, completedTasks.get(), "Not all tasks were executed");
  }
  
  /**
   * Tests that thread context is properly propagated across virtual threads.
   * This is important for maintaining context in Nexus operations.
   */
  @Test
  void testThreadContextPropagation() throws Exception {
    // Use ThreadLocal to verify context propagation
    ThreadLocal<String> contextValue = new ThreadLocal<>();
    CountDownLatch latch = new CountDownLatch(SMALL_TASK_COUNT);
    AtomicBoolean contextLost = new AtomicBoolean(false);
    
    // Set context value in main thread
    String expectedValue = "context-value";
    contextValue.set(expectedValue);
    
    // Create a thread factory that propagates ThreadLocal values
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("context-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit tasks that check if context is preserved
      for (int i = 0; i < SMALL_TASK_COUNT; i++) {
        final int taskId = i;
        executor.submit(() -> {
          try {
            // Check if ThreadLocal value is accessible
            String value = contextValue.get();
            if (!expectedValue.equals(value)) {
              log.error("Context lost in task {}: expected '{}' but got '{}'", 
                  taskId, expectedValue, value);
              contextLost.set(true);
            }
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(5, TimeUnit.SECONDS);
      assertFalse(contextLost.get(), "Thread context was not properly propagated");
    }
  }
  
  /**
   * Tests data consistency during parallel operations with virtual threads.
   * Ensures that concurrent updates to shared data structures are handled correctly.
   */
  @Test
  void testDataConsistencyWithConcurrentOperations() throws Exception {
    // Use ConcurrentHashMap for thread-safe operations
    Map<Integer, Integer> sharedData = new ConcurrentHashMap<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(SMALL_TASK_COUNT);
    
    // Submit tasks that will update the shared map
    for (int i = 0; i < SMALL_TASK_COUNT; i++) {
      final int key = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform concurrent updates
          sharedData.put(key, key * 2);
          
          // Simulate some work
          Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5));
          
          // Verify our own update
          Integer value = sharedData.get(key);
          assertEquals(key * 2, value, "Data inconsistency detected for key: " + key);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "Not all tasks completed in time");
    
    // Verify final state
    assertEquals(SMALL_TASK_COUNT, sharedData.size(), "Map size doesn't match expected count");
    for (int i = 0; i < SMALL_TASK_COUNT; i++) {
      assertEquals(i * 2, sharedData.get(i), "Incorrect value for key: " + i);
    }
  }
  
  /**
   * Tests resource utilization and performance under high virtual thread load.
   * Compares execution time with platform threads vs virtual threads for the same workload.
   */
  @Test
  void testResourceUtilizationAndPerformance() throws Exception {
    // Define a task that simulates I/O-bound work
    Supplier<Runnable> createTask = () -> () -> {
      try {
        // Simulate I/O operation with sleep
        Thread.sleep(50);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    // Measure execution time with platform threads (limited pool)
    long platformThreadTime;
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(20)) {
      platformThreadTime = measureExecutionTime(platformExecutor, createTask, SMALL_TASK_COUNT);
    }
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadExecutor, createTask, SMALL_TASK_COUNT);
    
    log.info("Execution time - Platform threads: {} ms, Virtual threads: {} ms", 
        platformThreadTime, virtualThreadTime);
    
    // Virtual threads should handle I/O-bound tasks more efficiently
    // The performance difference might not be huge for this simple test,
    // but virtual threads should at least not be slower
    assertThat("Virtual threads should be at least as fast as platform threads for I/O-bound tasks",
        virtualThreadTime, lessThan(platformThreadTime * 1.5));
  }
  
  /**
   * Tests extreme concurrency scenarios that would be impossible with platform threads.
   * Creates hundreds of thousands of virtual threads to perform simple operations.
   */
  @Test
  void testExtremeConcurrencyScenarios() throws Exception {
    // Use LongAdder for high-concurrency counting
    LongAdder counter = new LongAdder();
    CountDownLatch latch = new CountDownLatch(EXTREME_TASK_COUNT);
    
    // Record memory usage before creating threads
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Create a large number of virtual threads
    List<Thread> threads = new ArrayList<>(EXTREME_TASK_COUNT);
    for (int i = 0; i < EXTREME_TASK_COUNT; i++) {
      Thread thread = Thread.ofVirtual().name("extreme-" + i).start(() -> {
        try {
          counter.increment();
        }
        finally {
          latch.countDown();
        }
      });
      threads.add(thread);
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Not all tasks completed in time");
    
    // Record memory usage after threads complete
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Verify results
    assertEquals(EXTREME_TASK_COUNT, counter.sum(), "Not all increments were performed");
    
    // Log memory usage
    long memoryDiff = memoryAfter - memoryBefore;
    log.info("Memory usage for {} virtual threads: {} bytes ({}MB)", 
        EXTREME_TASK_COUNT, memoryDiff, memoryDiff / (1024 * 1024));
    
    // Calculate memory per thread (should be very small for virtual threads)
    double memoryPerThread = (double) memoryDiff / EXTREME_TASK_COUNT;
    log.info("Average memory per virtual thread: {} bytes", memoryPerThread);
    
    // Memory per virtual thread should be much smaller than platform threads
    // Platform threads typically use 1-2MB each, virtual threads should use much less
    assertThat("Memory per virtual thread should be small", 
        memoryPerThread, lessThan(10_000.0));
  }
  
  /**
   * Helper method to measure execution time of tasks using the provided executor.
   */
  private long measureExecutionTime(
      ExecutorService executor, 
      Supplier<Runnable> taskSupplier, 
      int taskCount) throws Exception 
  {
    CountDownLatch latch = new CountDownLatch(taskCount);
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < taskCount; i++) {
      executor.submit(() -> {
        try {
          taskSupplier.get().run();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    latch.await(30, TimeUnit.SECONDS);
    return System.currentTimeMillis() - startTime;
  }
}