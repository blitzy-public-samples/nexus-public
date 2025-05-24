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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests high-concurrency scenarios using Java 21 Virtual Threads in Nexus Extender.
 * 
 * This test class verifies that:
 * - Thousands of concurrent operations can be executed efficiently with minimal resource usage
 * - Thread context is properly propagated across multiple virtual threads
 * - Data consistency is maintained during parallel operations
 * - Extreme concurrency scenarios that would be impossible with platform threads can be handled
 */
@ExtendWith(MockitoExtension.class)
public class ConcurrentOperationsVirtualThreadTest
{
  private static final int SMALL_THREAD_COUNT = 100;
  private static final int MEDIUM_THREAD_COUNT = 1_000;
  private static final int LARGE_THREAD_COUNT = 10_000;
  private static final int EXTREME_THREAD_COUNT = 100_000;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Create executors for both virtual and platform threads for comparison
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Properly shut down executors
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that a large number of virtual threads can be created and executed concurrently
   * with minimal resource usage compared to platform threads.
   */
  @Test
  @DisplayName("Should execute thousands of concurrent operations efficiently")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testMassiveConcurrentOperations() throws Exception {
    int threadCount = MEDIUM_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Measure memory before starting virtual threads
    long memoryBefore = getUsedMemory();
    
    // Execute tasks using virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate some work
          Thread.sleep(10);
          completedTasks.incrementAndGet();
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
    assertTrue(latch.await(20, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Measure memory after virtual threads execution
    long memoryAfter = getUsedMemory();
    long memoryUsed = memoryAfter - memoryBefore;
    
    // Verify all tasks completed
    assertEquals(threadCount, completedTasks.get(), "All tasks should complete successfully");
    
    // Memory usage should be reasonable for the number of threads
    // Virtual threads should use significantly less memory than platform threads would
    System.out.println("Memory used for " + threadCount + " virtual threads: " + (memoryUsed / 1024 / 1024) + " MB");
    
    // A reasonable upper bound for memory usage - much less than what platform threads would use
    // Platform threads typically use 2-10MB each, so 1000 threads would use 2-10GB
    // Virtual threads should use orders of magnitude less
    assertThat(memoryUsed, lessThan(500L * 1024 * 1024)); // Less than 500MB for 1000 threads
  }
  
  /**
   * Tests that thread context is properly propagated across virtual threads.
   */
  @Test
  @DisplayName("Should propagate thread context across virtual threads")
  void testThreadContextPropagation() throws Exception {
    int threadCount = SMALL_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Use ThreadLocal to test context propagation
    ThreadLocal<String> threadContext = new ThreadLocal<>();
    Map<Integer, String> results = new ConcurrentHashMap<>();
    
    // Set initial context in the main thread
    threadContext.set("PARENT_CONTEXT");
    
    for (int i = 0; i < threadCount; i++) {
      final int taskId = i;
      final String expectedContext = "TASK_" + taskId;
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Set task-specific context
          threadContext.set(expectedContext);
          
          // Verify the context is set correctly
          String currentContext = threadContext.get();
          results.put(taskId, currentContext);
          
          // Create a nested virtual thread to verify context inheritance
          Thread nestedThread = Thread.ofVirtual().start(() -> {
            // In virtual threads, ThreadLocal values are not inherited by default
            // This should be null or different from the parent virtual thread
            String nestedContext = threadContext.get();
            results.put(taskId + threadCount, nestedContext != null ? nestedContext : "NULL");
          });
          
          nestedThread.join();
        }
        catch (Exception e) {
          results.put(taskId, "ERROR: " + e.getMessage());
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify each task had its own context
    for (int i = 0; i < threadCount; i++) {
      String taskContext = results.get(i);
      String expectedContext = "TASK_" + i;
      assertEquals(expectedContext, taskContext, "Task " + i + " should have its own context");
      
      // Verify nested threads don't inherit ThreadLocal values from parent virtual threads
      String nestedContext = results.get(i + threadCount);
      assertNotNull(nestedContext, "Nested thread context should be captured");
      // In virtual threads, ThreadLocal values are not inherited by default
      assertTrue(nestedContext.equals("NULL") || !nestedContext.equals(expectedContext),
          "Nested thread should not inherit parent's ThreadLocal value");
    }
    
    // Verify the main thread's context is still intact
    assertEquals("PARENT_CONTEXT", threadContext.get(), "Main thread context should be preserved");
  }
  
  /**
   * Tests that data consistency is maintained during parallel operations with virtual threads.
   */
  @Test
  @DisplayName("Should maintain data consistency during parallel operations")
  void testDataConsistencyWithParallelOperations() throws Exception {
    int threadCount = MEDIUM_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    // Shared data structure to test consistency
    ConcurrentHashMap<Integer, Integer> sharedMap = new ConcurrentHashMap<>();
    AtomicInteger inconsistencyCount = new AtomicInteger(0);
    
    // Each thread will perform multiple operations on the shared map
    for (int i = 0; i < threadCount; i++) {
      final int key = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Initial put
          sharedMap.put(key, key);
          
          // Verify and update in a loop to increase contention
          for (int j = 0; j < 10; j++) {
            Integer value = sharedMap.get(key);
            if (value == null || !value.equals(key)) {
              inconsistencyCount.incrementAndGet();
            }
            
            // Update with a new value based on the current one
            sharedMap.compute(key, (k, v) -> v == null ? key : v + 1);
            
            // Small delay to increase chance of interleaving
            Thread.sleep(1);
          }
          
          // Final verification
          Integer finalValue = sharedMap.get(key);
          if (finalValue == null || finalValue != key + 10) {
            inconsistencyCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          inconsistencyCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(20, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify no inconsistencies were detected
    assertEquals(0, inconsistencyCount.get(), "No data inconsistencies should occur");
    
    // Verify all entries are present and have the expected final value
    assertEquals(threadCount, sharedMap.size(), "Map should contain all keys");
    
    for (int i = 0; i < threadCount; i++) {
      Integer value = sharedMap.get(i);
      assertNotNull(value, "Value should exist for key " + i);
      assertEquals(i + 10, value.intValue(), "Final value should be key + 10 for key " + i);
    }
  }
  
  /**
   * Tests extreme concurrency scenarios that would be impossible with platform threads.
   */
  @Test
  @DisplayName("Should handle extreme concurrency scenarios")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testExtremeConcurrencyScenarios() throws Exception {
    // This test creates a very large number of virtual threads
    // This would be impossible with platform threads due to resource constraints
    int threadCount = LARGE_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start a large number of virtual threads
    for (int i = 0; i < threadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate a very short task
          Thread.yield();
          successCount.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify all tasks completed successfully
    assertEquals(threadCount, successCount.get(), "All tasks should complete successfully");
  }
  
  /**
   * Compares performance between virtual threads and platform threads for I/O-bound operations.
   */
  @Test
  @DisplayName("Should perform better than platform threads for I/O-bound operations")
  void testPerformanceComparisonForIOBoundOperations() throws Exception {
    int threadCount = MEDIUM_THREAD_COUNT;
    
    // Measure execution time for virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      return executeIOBoundTasks(virtualThreadExecutor, threadCount);
    });
    
    // Measure execution time for platform threads (with a smaller count to avoid resource exhaustion)
    int platformThreadCount = Math.min(threadCount, 200); // Limit to avoid OOM
    long platformThreadTime = measureExecutionTime(() -> {
      return executeIOBoundTasks(platformThreadExecutor, platformThreadCount);
    });
    
    // Normalize platform thread time to account for the difference in thread count
    long normalizedPlatformTime = platformThreadTime * threadCount / platformThreadCount;
    
    System.out.println("Virtual thread time for " + threadCount + " tasks: " + virtualThreadTime + "ms");
    System.out.println("Platform thread time for " + platformThreadCount + " tasks: " + platformThreadTime + "ms");
    System.out.println("Normalized platform thread time for " + threadCount + " tasks: " + normalizedPlatformTime + "ms");
    
    // Virtual threads should be more efficient for I/O-bound tasks
    // Even with fewer platform threads, the virtual threads should complete faster
    // when normalized to the same number of tasks
    assertThat(virtualThreadTime, lessThan(normalizedPlatformTime));
  }
  
  /**
   * Tests that virtual threads can handle a mix of CPU-bound and I/O-bound operations efficiently.
   */
  @Test
  @DisplayName("Should handle mixed CPU and I/O bound operations efficiently")
  void testMixedCPUAndIOBoundOperations() throws Exception {
    int threadCount = SMALL_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount * 2); // Both CPU and I/O tasks
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Submit I/O-bound tasks
    for (int i = 0; i < threadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate I/O operation (blocking)
          Thread.sleep(50);
          completedTasks.incrementAndGet();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Submit CPU-bound tasks
    for (int i = 0; i < threadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Simulate CPU-bound operation
          long result = 0;
          for (int j = 0; j < 1000000; j++) {
            result += j;
          }
          completedTasks.incrementAndGet();
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify all tasks completed
    assertEquals(threadCount * 2, completedTasks.get(), "All tasks should complete successfully");
  }
  
  /**
   * Tests that virtual threads properly handle exceptions and don't affect other threads.
   */
  @Test
  @DisplayName("Should properly handle exceptions in virtual threads")
  void testExceptionHandlingInVirtualThreads() throws Exception {
    int threadCount = SMALL_THREAD_COUNT;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    AtomicReference<Throwable> caughtException = new AtomicReference<>();
    
    // Create a mix of successful and failing tasks
    for (int i = 0; i < threadCount; i++) {
      final int taskId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          if (taskId % 5 == 0) {
            // Every 5th task throws an exception
            throw new RuntimeException("Deliberate test exception in task " + taskId);
          }
          
          // Simulate successful task
          Thread.sleep(10);
          successCount.incrementAndGet();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        catch (Exception e) {
          exceptionCount.incrementAndGet();
          caughtException.set(e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete within timeout");
    
    // Verify expected counts
    int expectedExceptions = threadCount / 5;
    int expectedSuccesses = threadCount - expectedExceptions;
    
    assertEquals(expectedExceptions, exceptionCount.get(), "Exception count should match expected");
    assertEquals(expectedSuccesses, successCount.get(), "Success count should match expected");
    assertNotNull(caughtException.get(), "At least one exception should have been caught");
    assertTrue(caughtException.get().getMessage().contains("Deliberate test exception"),
        "Exception message should match expected");
  }
  
  /**
   * Tests that virtual threads can be cancelled and interrupted properly.
   */
  @Test
  @DisplayName("Should support proper cancellation and interruption")
  void testCancellationAndInterruption() throws Exception {
    int threadCount = SMALL_THREAD_COUNT;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicInteger interruptedCount = new AtomicInteger(0);
    
    List<Thread> threads = new ArrayList<>();
    
    // Create virtual threads that will wait on the start latch
    for (int i = 0; i < threadCount; i++) {
      Thread thread = Thread.ofVirtual().start(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Enter a loop that checks for interruption
          while (!Thread.currentThread().isInterrupted()) {
            Thread.sleep(10);
          }
          
          // If we get here, we were interrupted
          interruptedCount.incrementAndGet();
        }
        catch (InterruptedException e) {
          // This is also an expected way to be interrupted
          interruptedCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      });
      
      threads.add(thread);
    }
    
    // Start all threads
    startLatch.countDown();
    
    // Let them run for a short time
    Thread.sleep(100);
    
    // Interrupt all threads
    for (Thread thread : threads) {
      thread.interrupt();
    }
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(5, TimeUnit.SECONDS), "All threads should complete after interruption");
    
    // Verify all threads were interrupted
    assertEquals(threadCount, interruptedCount.get(), "All threads should have been interrupted");
  }
  
  /**
   * Helper method to measure memory usage.
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate measurements
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Helper method to measure execution time of a task.
   */
  private long measureExecutionTime(Supplier<Boolean> task) {
    long startTime = System.currentTimeMillis();
    boolean success = task.get();
    long endTime = System.currentTimeMillis();
    
    assertTrue(success, "Task should complete successfully");
    return endTime - startTime;
  }
  
  /**
   * Helper method to execute I/O-bound tasks using the provided executor.
   */
  private boolean executeIOBoundTasks(ExecutorService executor, int taskCount) {
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicBoolean success = new AtomicBoolean(true);
    
    for (int i = 0; i < taskCount; i++) {
      executor.submit(() -> {
        try {
          // Simulate I/O operation (blocking)
          Thread.sleep(50);
        }
        catch (Exception e) {
          success.set(false);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    try {
      return latch.await(30, TimeUnit.SECONDS) && success.get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}