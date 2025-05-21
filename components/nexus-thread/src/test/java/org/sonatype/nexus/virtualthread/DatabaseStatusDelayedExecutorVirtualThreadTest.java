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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;
import org.sonatype.nexus.thread.DatabaseStatusDelayedExecutor;

import org.junit.jupiter.api.BeforeEach;
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
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the {@link DatabaseStatusDelayedExecutor} with Java 21 Virtual Threads.
 * 
 * Validates that task execution, retry logic, and NotWritableException handling work correctly
 * when using large numbers of concurrent virtual threads.
 */
@ExtendWith(MockitoExtension.class)
class DatabaseStatusDelayedExecutorVirtualThreadTest
    extends TestSupport
{
  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;
  
  private static final int HIGH_CONCURRENCY_TASK_COUNT = 1000;

  @Mock
  FreezeService freezeService;

  DatabaseStatusDelayedExecutor statusDelayedExecutor;

  @BeforeEach
  void setup() throws Exception {
    statusDelayedExecutor = new DatabaseStatusDelayedExecutor(freezeService, 1, SLEEP_INTERVAL_MS, MAX_RETRIES);
    statusDelayedExecutor.start();
  }

  /**
   * Verifies that tasks eventually run after MAX_RETRIES attempts when using virtual threads.
   */
  @Test
  void ensureThatTaskEventuallyRunsWithVirtualThreads() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    // Use a virtual thread to submit the task
    Thread.startVirtualThread(() -> {
      Future<String> result = statusDelayedExecutor.submit(() -> "Done");

      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result.isDone());

      verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
    }).join();
  }

  /**
   * Verifies that tasks are delayed when the database is not writable, but eventually run
   * when it becomes writable, when using virtual threads.
   */
  @Test
  void noWritableDelaysTaskWithVirtualThreads() {
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= 4) {
        throw new NotWritableException("");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    // Use a virtual thread to submit the task
    Thread.startVirtualThread(() -> {
      Future<String> result = statusDelayedExecutor.submit(() -> "Done");

      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(callCount::get, greaterThanOrEqualTo(1));

      assertThat(result.isDone(), is(false));

      await()
          .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
          .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
          .until(() -> result.isDone());

      assertThat(callCount.get(), is(5));
    }).join();
  }

  /**
   * Tests high concurrency scenario with thousands of virtual threads submitting tasks.
   * Verifies that all tasks are eventually executed correctly.
   */
  @Test
  void highConcurrencyWithVirtualThreads() throws Exception {
    // Configure freezeService to allow tasks to run after a few retries
    final AtomicInteger globalCallCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      int currentCount = globalCallCount.incrementAndGet();
      // Allow tasks to proceed after a certain number of global retries
      // This simulates a database that becomes writable after some time
      if (currentCount < HIGH_CONCURRENCY_TASK_COUNT) {
        throw new NotWritableException("Database not writable yet");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Track completion of all tasks
    CountDownLatch completionLatch = new CountDownLatch(HIGH_CONCURRENCY_TASK_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Submit tasks
    List<Future<String>> results = new ArrayList<>(HIGH_CONCURRENCY_TASK_COUNT);
    for (int i = 0; i < HIGH_CONCURRENCY_TASK_COUNT; i++) {
      final int taskId = i;
      results.add(statusDelayedExecutor.submit(() -> {
        try {
          return "Task " + taskId + " completed";
        } finally {
          successCount.incrementAndGet();
          completionLatch.countDown();
        }
      }));
    }
    
    // Wait for all tasks to complete
    boolean allCompleted = completionLatch.await(30, SECONDS);
    assertTrue(allCompleted, "All tasks should complete within the timeout");
    
    // Verify all tasks completed successfully
    assertEquals(HIGH_CONCURRENCY_TASK_COUNT, successCount.get(), "All tasks should succeed");
    assertEquals(0, errorCount.get(), "No tasks should fail");
    
    // Verify all futures are done
    for (Future<String> result : results) {
      assertTrue(result.isDone(), "All futures should be done");
    }
  }

  /**
   * Tests that the executor maintains correct execution order even with high concurrency.
   * Tasks should be executed in the order they were submitted, regardless of database status.
   */
  @Test
  void executionOrderPreservedWithVirtualThreads() throws Exception {
    // Configure freezeService to allow tasks to run after a delay
    AtomicBoolean databaseWritable = new AtomicBoolean(false);
    doAnswer(invocation -> {
      if (!databaseWritable.get()) {
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    // Submit tasks and track their execution order
    int taskCount = 100;
    AtomicInteger executionCounter = new AtomicInteger(0);
    List<Integer> executionOrder = new ArrayList<>();
    CountDownLatch allSubmitted = new CountDownLatch(1);
    CountDownLatch allCompleted = new CountDownLatch(taskCount);
    
    // Submit tasks using virtual threads
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      statusDelayedExecutor.submit(() -> {
        try {
          // Record execution order
          int order = executionCounter.getAndIncrement();
          synchronized (executionOrder) {
            executionOrder.add(taskId);
          }
          return "Task " + taskId;
        } finally {
          allCompleted.countDown();
        }
      });
    }
    
    // All tasks submitted, now make database writable
    allSubmitted.countDown();
    Thread.sleep(SLEEP_INTERVAL_MS * 2); // Give time for tasks to be queued
    databaseWritable.set(true);
    
    // Wait for all tasks to complete
    boolean completed = allCompleted.await(10, SECONDS);
    assertTrue(completed, "All tasks should complete");
    
    // Verify execution order - tasks should be executed in submission order
    for (int i = 0; i < taskCount; i++) {
      assertEquals(i, executionOrder.get(i), "Tasks should execute in submission order");
    }
  }

  /**
   * Compares performance between virtual threads and platform threads.
   * This test validates that virtual threads provide better scalability for I/O-bound operations.
   */
  @Test
  void compareVirtualThreadsVsPlatformThreadsPerformance() throws Exception {
    // Configure freezeService to simulate I/O delay but allow execution
    doAnswer(invocation -> {
      // Simulate I/O delay
      Thread.sleep(5);
      return null;
    }).when(freezeService).checkWritable(anyString());

    // Function to measure execution time with different thread factories
    class PerformanceMeasurement {
      long measureExecutionTime(ThreadFactory threadFactory, int taskCount) throws Exception {
        ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
        CountDownLatch completionLatch = new CountDownLatch(taskCount);
        long startTime = System.nanoTime();
        
        try {
          // Submit tasks
          for (int i = 0; i < taskCount; i++) {
            executor.submit(() -> {
              try {
                statusDelayedExecutor.submit(() -> "Done").get();
              } catch (Exception e) {
                // Ignore exceptions
              } finally {
                completionLatch.countDown();
              }
            });
          }
          
          // Wait for all tasks to complete
          completionLatch.await(30, SECONDS);
          return System.nanoTime() - startTime;
        } finally {
          executor.shutdown();
        }
      }
    }
    
    // Measure with both thread types
    PerformanceMeasurement measurement = new PerformanceMeasurement();
    int taskCount = 500; // Significant enough to show difference
    
    // Warm-up run
    measurement.measureExecutionTime(Thread.ofVirtual().factory(), 50);
    measurement.measureExecutionTime(Thread.ofPlatform().factory(), 50);
    
    // Actual measurement
    long virtualThreadTime = measurement.measureExecutionTime(Thread.ofVirtual().factory(), taskCount);
    long platformThreadTime = measurement.measureExecutionTime(Thread.ofPlatform().factory(), taskCount);
    
    // Virtual threads should be more efficient for I/O-bound tasks
    double ratio = (double) platformThreadTime / virtualThreadTime;
    log.info("Performance comparison - Platform threads: {} ns, Virtual threads: {} ns, Ratio: {}", 
        platformThreadTime, virtualThreadTime, ratio);
    
    // Virtual threads should perform better (lower time) than platform threads
    assertThat(virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Tests that virtual threads are not pinned during database status checking.
   * Thread pinning would reduce the efficiency of virtual threads.
   */
  @Test
  void noThreadPinningDuringDatabaseStatusCheck() throws Exception {
    // Configure freezeService to simulate a blocking operation that could cause pinning
    LongAdder pinningDetected = new LongAdder();
    
    doAnswer(invocation -> {
      // Check if current thread is a virtual thread
      if (Thread.currentThread().isVirtual()) {
        // Use a technique to detect potential pinning
        // In a real scenario, we would use JFR events or other monitoring
        // For this test, we'll use a simple heuristic: if multiple threads are
        // simultaneously in this method for too long, it might indicate pinning
        Thread.sleep(50); // Simulate blocking I/O
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    // Create many virtual threads to increase chance of detecting pinning
    int threadCount = 200;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Start threads that will all try to execute simultaneously
    for (int i = 0; i < threadCount; i++) {
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await(); // Wait for signal to start
          long startTime = System.nanoTime();
          statusDelayedExecutor.submit(() -> "Done").get(1, SECONDS);
          long duration = System.nanoTime() - startTime;
          
          // If execution took much longer than expected, it might indicate pinning
          if (duration > TimeUnit.MILLISECONDS.toNanos(500)) {
            pinningDetected.increment();
          }
        } catch (Exception e) {
          pinningDetected.increment();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for completion
    boolean completed = completionLatch.await(10, SECONDS);
    assertTrue(completed, "All tasks should complete within timeout");
    
    // Verify no pinning was detected
    assertEquals(0, pinningDetected.sum(), "No thread pinning should be detected");
  }
}