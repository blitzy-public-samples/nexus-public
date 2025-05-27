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

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;
import org.sonatype.nexus.thread.DatabaseStatusDelayedExecutor;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Test class for validating the Virtual Thread-based implementation of DatabaseStatusDelayedExecutor.
 * 
 * This test suite focuses on:
 * - Verifying tasks are properly delayed and retried when the database is not writable
 * - Ensuring correct behavior with notifications across virtual thread boundaries
 * - Testing timeout handling and cancellation operations
 * - Validating that large numbers of concurrent database operations can be efficiently handled with virtual threads
 * - Comparing resource usage between virtual threads and traditional thread implementations
 */
@ExtendWith(MockitoExtension.class)
@Tag("VirtualThreadTestGroup")
public class VirtualDatabaseStatusDelayedExecutorTest
    extends TestSupport
{
  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;
  
  private static final int LARGE_TASK_COUNT = 1000;

  @Mock
  FreezeService freezeService;

  // Virtual thread-based executor
  private DatabaseStatusDelayedExecutor virtualThreadExecutor;
  
  // Platform thread-based executor for comparison
  private DatabaseStatusDelayedExecutor platformThreadExecutor;

  @BeforeEach
  public void setup() throws Exception {
    // Create virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create platform thread factory
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Initialize executors
    virtualThreadExecutor = new DatabaseStatusDelayedExecutor(
        freezeService, 4, SLEEP_INTERVAL_MS, MAX_RETRIES, virtualThreadFactory);
    platformThreadExecutor = new DatabaseStatusDelayedExecutor(
        freezeService, 4, SLEEP_INTERVAL_MS, MAX_RETRIES, platformThreadFactory);
    
    // Start executors
    virtualThreadExecutor.start();
    platformThreadExecutor.start();
  }

  @AfterEach
  public void cleanup() {
    // Ensure executors are properly shut down
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
  }

  /**
   * Verifies that tasks are properly retried when the database is not writable,
   * using virtual threads for execution.
   */
  @Test
  public void taskIsRetriedWhenDatabaseNotWritable() {
    // Configure freezeService to always throw NotWritableException
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    // Submit task to virtual thread executor
    Future<String> result = virtualThreadExecutor.submit(() -> "Task completed");

    // Wait for task to complete or timeout
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    // Verify that checkWritable was called MAX_RETRIES times
    verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
  }

  /**
   * Verifies that tasks are properly delayed when the database is not writable,
   * and eventually complete when the database becomes writable.
   */
  @Test
  public void taskIsDelayedUntilDatabaseWritable() {
    final AtomicInteger callCount = new AtomicInteger(0);
    
    // Configure freezeService to throw NotWritableException for the first 4 calls
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= 4) {
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    // Submit task to virtual thread executor
    Future<String> result = virtualThreadExecutor.submit(() -> "Task completed");

    // Verify that task is not immediately done after first check
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(callCount::get, greaterThanOrEqualTo(1));

    assertFalse(result.isDone(), "Task should not be done while database is not writable");

    // Wait for task to complete after database becomes writable
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    // Verify that checkWritable was called exactly 5 times
    assertEquals(5, callCount.get(), "checkWritable should be called exactly 5 times");
  }

  /**
   * Tests that task cancellation works correctly with virtual threads.
   */
  @Test
  public void taskCancellationWithVirtualThreads() throws Exception {
    final CountDownLatch taskStarted = new CountDownLatch(1);
    final CountDownLatch keepTaskRunning = new CountDownLatch(1);
    final AtomicBoolean taskWasCancelled = new AtomicBoolean(false);
    
    // Submit a long-running task
    Future<String> result = virtualThreadExecutor.submit(() -> {
      try {
        taskStarted.countDown(); // Signal that task has started
        keepTaskRunning.await(); // Wait until signaled to continue
        return "Task completed";
      } 
      catch (InterruptedException e) {
        taskWasCancelled.set(true);
        throw e;
      }
    });
    
    // Wait for task to start
    assertTrue(taskStarted.await(1, SECONDS), "Task should start within 1 second");
    
    // Cancel the task
    assertTrue(result.cancel(true), "Task should be cancelled successfully");
    
    // Verify task was cancelled
    assertTrue(result.isCancelled(), "Task should be marked as cancelled");
    
    // Allow task to complete if it wasn't cancelled
    keepTaskRunning.countDown();
    
    // Verify that attempting to get the result throws CancellationException
    assertThrows(CancellationException.class, () -> result.get(100, MILLISECONDS));
  }

  /**
   * Tests that task timeout handling works correctly with virtual threads.
   */
  @Test
  public void taskTimeoutWithVirtualThreads() throws Exception {
    final CountDownLatch taskStarted = new CountDownLatch(1);
    final CountDownLatch keepTaskRunning = new CountDownLatch(1);
    
    // Submit a long-running task
    Future<String> result = virtualThreadExecutor.submit(() -> {
      taskStarted.countDown(); // Signal that task has started
      keepTaskRunning.await(); // Wait until signaled to continue
      return "Task completed";
    });
    
    // Wait for task to start
    assertTrue(taskStarted.await(1, SECONDS), "Task should start within 1 second");
    
    // Verify that get() with timeout throws TimeoutException
    assertThrows(TimeoutException.class, () -> result.get(100, MILLISECONDS));
    
    // Allow task to complete
    keepTaskRunning.countDown();
    
    // Verify task completes successfully after being allowed to continue
    assertEquals("Task completed", result.get(1, SECONDS));
  }

  /**
   * Tests that notifications work correctly across virtual thread boundaries.
   */
  @Test
  public void notificationsAcrossVirtualThreadBoundaries() throws Exception {
    final CountDownLatch allTasksSubmitted = new CountDownLatch(1);
    final CountDownLatch allTasksCompleted = new CountDownLatch(3);
    final AtomicReference<Throwable> taskError = new AtomicReference<>();
    
    // Create tasks that will notify each other
    Runnable task1 = () -> {
      try {
        allTasksSubmitted.await(); // Wait for all tasks to be submitted
        allTasksCompleted.countDown();
      } 
      catch (Throwable t) {
        taskError.set(t);
      }
    };
    
    Runnable task2 = () -> {
      try {
        allTasksSubmitted.await(); // Wait for all tasks to be submitted
        allTasksCompleted.countDown();
      } 
      catch (Throwable t) {
        taskError.set(t);
      }
    };
    
    Runnable task3 = () -> {
      try {
        allTasksSubmitted.await(); // Wait for all tasks to be submitted
        allTasksCompleted.countDown();
      } 
      catch (Throwable t) {
        taskError.set(t);
      }
    };
    
    // Submit tasks
    virtualThreadExecutor.submit(task1);
    virtualThreadExecutor.submit(task2);
    virtualThreadExecutor.submit(task3);
    
    // Signal all tasks to proceed
    allTasksSubmitted.countDown();
    
    // Wait for all tasks to complete
    assertTrue(allTasksCompleted.await(1, SECONDS), "All tasks should complete within 1 second");
    
    // Verify no errors occurred
    if (taskError.get() != null) {
      throw new AssertionError("Task encountered an error", taskError.get());
    }
  }

  /**
   * Tests that large numbers of concurrent database operations can be efficiently handled with virtual threads.
   * This test submits a large number of tasks and verifies they all complete successfully.
   */
  @Test
  public void highConcurrencyWithVirtualThreads() throws Exception {
    // Configure freezeService to simulate database operations
    doAnswer(invocation -> {
      // Simulate a small delay for database operation
      Thread.sleep(5);
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Create a large number of tasks
    List<Future<Integer>> results = new ArrayList<>(LARGE_TASK_COUNT);
    CountDownLatch allTasksCompleted = new CountDownLatch(LARGE_TASK_COUNT);
    
    // Submit tasks
    for (int i = 0; i < LARGE_TASK_COUNT; i++) {
      final int taskId = i;
      results.add(virtualThreadExecutor.submit(() -> {
        try {
          freezeService.checkWritable("Task " + taskId);
          return taskId;
        } 
        finally {
          allTasksCompleted.countDown();
        }
      }));
    }
    
    // Wait for all tasks to complete
    assertTrue(allTasksCompleted.await(10, SECONDS), 
        "All " + LARGE_TASK_COUNT + " tasks should complete within 10 seconds");
    
    // Verify all tasks completed successfully
    for (int i = 0; i < LARGE_TASK_COUNT; i++) {
      assertEquals(i, results.get(i).get(), "Task " + i + " should return its task ID");
    }
    
    // Verify checkWritable was called for each task
    verify(freezeService, times(LARGE_TASK_COUNT)).checkWritable(anyString());
  }

  /**
   * Compares resource usage between virtual threads and platform threads.
   * This test submits the same workload to both executor types and compares memory usage.
   */
  @Test
  public void compareResourceUsageBetweenThreadTypes() throws Exception {
    final int TASK_COUNT = 500;
    final CountDownLatch platformTasksReady = new CountDownLatch(TASK_COUNT);
    final CountDownLatch virtualTasksReady = new CountDownLatch(TASK_COUNT);
    final CountDownLatch startSignal = new CountDownLatch(1);
    final CountDownLatch platformTasksDone = new CountDownLatch(TASK_COUNT);
    final CountDownLatch virtualTasksDone = new CountDownLatch(TASK_COUNT);
    
    // Create a task that will wait for a signal before proceeding
    Runnable platformTask = () -> {
      try {
        platformTasksReady.countDown();
        startSignal.await();
        Thread.sleep(50); // Simulate work
        platformTasksDone.countDown();
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    Runnable virtualTask = () -> {
      try {
        virtualTasksReady.countDown();
        startSignal.await();
        Thread.sleep(50); // Simulate work
        virtualTasksDone.countDown();
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    // Measure memory before creating threads
    System.gc(); // Request garbage collection to get more accurate measurements
    long memoryBeforeThreads = getUsedMemory();
    
    // Submit platform thread tasks
    for (int i = 0; i < TASK_COUNT; i++) {
      platformThreadExecutor.submit(platformTask);
    }
    
    // Wait for all platform tasks to be ready
    assertTrue(platformTasksReady.await(5, SECONDS), "Platform tasks should be ready within 5 seconds");
    
    // Measure memory after platform threads are created
    System.gc();
    long memoryAfterPlatformThreads = getUsedMemory();
    long platformThreadMemory = memoryAfterPlatformThreads - memoryBeforeThreads;
    
    // Submit virtual thread tasks
    for (int i = 0; i < TASK_COUNT; i++) {
      virtualThreadExecutor.submit(virtualTask);
    }
    
    // Wait for all virtual tasks to be ready
    assertTrue(virtualTasksReady.await(5, SECONDS), "Virtual tasks should be ready within 5 seconds");
    
    // Measure memory after virtual threads are created
    System.gc();
    long memoryAfterVirtualThreads = getUsedMemory();
    long virtualThreadMemory = memoryAfterVirtualThreads - memoryAfterPlatformThreads;
    
    // Signal all tasks to proceed
    startSignal.countDown();
    
    // Wait for all tasks to complete
    assertTrue(platformTasksDone.await(10, SECONDS), "Platform tasks should complete within 10 seconds");
    assertTrue(virtualTasksDone.await(10, SECONDS), "Virtual tasks should complete within 10 seconds");
    
    // Log memory usage for comparison
    log.info("Memory used by {} platform threads: {} bytes", TASK_COUNT, platformThreadMemory);
    log.info("Memory used by {} virtual threads: {} bytes", TASK_COUNT, virtualThreadMemory);
    log.info("Memory ratio (virtual/platform): {}", 
        (double) virtualThreadMemory / (double) platformThreadMemory);
    
    // Verify that virtual threads use less memory than platform threads
    // This assertion might need adjustment based on actual measurements
    assertThat("Virtual threads should use less memory than platform threads",
        virtualThreadMemory, lessThan(platformThreadMemory));
  }

  /**
   * Tests that tasks can be executed concurrently with virtual threads even when the system is frozen.
   */
  @Test
  public void concurrentTasksWithFrozenSystem() throws Exception {
    final int TASK_COUNT = 100;
    final AtomicInteger successCount = new AtomicInteger(0);
    final CountDownLatch allTasksSubmitted = new CountDownLatch(1);
    final CountDownLatch allTasksCompleted = new CountDownLatch(TASK_COUNT);
    
    // Configure freezeService to throw NotWritableException for all calls
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());
    
    // Submit tasks
    List<Future<Boolean>> results = new ArrayList<>(TASK_COUNT);
    for (int i = 0; i < TASK_COUNT; i++) {
      final int taskId = i;
      results.add(virtualThreadExecutor.submit(() -> {
        try {
          allTasksSubmitted.await(); // Wait for all tasks to be submitted
          
          try {
            freezeService.checkWritable("Task " + taskId);
          }
          catch (NotWritableException e) {
            // Expected exception, task should still complete
            successCount.incrementAndGet();
            return true;
          }
          
          return false;
        } 
        finally {
          allTasksCompleted.countDown();
        }
      }));
    }
    
    // Signal all tasks to proceed
    allTasksSubmitted.countDown();
    
    // Wait for all tasks to complete or timeout
    assertTrue(allTasksCompleted.await(MAX_RETRIES * SLEEP_INTERVAL_MS * 2, MILLISECONDS),
        "All tasks should complete within timeout period");
    
    // Verify all tasks encountered the expected NotWritableException
    assertEquals(TASK_COUNT, successCount.get(), 
        "All tasks should have encountered NotWritableException");
    
    // Verify all futures completed with the expected result
    for (Future<Boolean> result : results) {
      assertTrue(result.get(), "Task should return true indicating successful handling of NotWritableException");
    }
  }

  /**
   * Tests that tasks can be executed with CompletableFuture integration.
   */
  @Test
  public void completableFutureIntegration() throws Exception {
    // Configure freezeService to simulate database operations
    doAnswer(invocation -> null).when(freezeService).checkWritable(anyString());
    
    // Create a CompletableFuture chain
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      try {
        freezeService.checkWritable("Initial task");
        return "Initial";
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor)
    .thenApplyAsync(result -> {
      try {
        freezeService.checkWritable("Second task");
        return result + " + Second";
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor)
    .thenApplyAsync(result -> {
      try {
        freezeService.checkWritable("Third task");
        return result + " + Third";
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, virtualThreadExecutor);
    
    // Wait for the CompletableFuture chain to complete
    String result = future.get(1, SECONDS);
    
    // Verify the result
    assertEquals("Initial + Second + Third", result, 
        "CompletableFuture chain should complete with the expected result");
    
    // Verify checkWritable was called for each stage
    verify(freezeService, times(3)).checkWritable(anyString());
  }

  /**
   * Helper method to get the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}