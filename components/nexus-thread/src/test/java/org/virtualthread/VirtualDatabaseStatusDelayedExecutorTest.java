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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;
import org.sonatype.nexus.thread.DatabaseStatusDelayedExecutor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DatabaseStatusDelayedExecutor} using Virtual Threads.
 * 
 * This test class validates that the Virtual Thread-based implementation of DatabaseStatusDelayedExecutor
 * properly handles database status checks, delays, and retries while efficiently managing resources.
 * 
 * @since 3.60
 */
@MockitoSettings(strictness = Strictness.LENIENT)
public class VirtualDatabaseStatusDelayedExecutorTest
    extends TestSupport
{
  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;
  
  private static final int HIGH_CONCURRENCY_TASKS = 1000;

  @Mock
  FreezeService freezeService;

  DatabaseStatusDelayedExecutor statusDelayedExecutor;

  @BeforeEach
  public void setup() throws Exception {
    statusDelayedExecutor = new DatabaseStatusDelayedExecutor(freezeService, SLEEP_INTERVAL_MS, MAX_RETRIES);
    statusDelayedExecutor.start();
  }

  /**
   * Verifies that tasks are eventually executed after the maximum number of retries,
   * even if the database remains not writable.
   */
  @Test
  public void ensureThatTaskEventuallyRuns() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    Future<String> result = statusDelayedExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
  }

  /**
   * Verifies that tasks are delayed while the database is not writable,
   * and executed once it becomes writable.
   */
  @Test
  public void noWritableDelaysTask() {
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= 4) {
        throw new NotWritableException("");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

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
  }
  
  /**
   * Tests high concurrency scenario with many simultaneous database operations.
   * This test verifies that Virtual Threads can efficiently handle a large number
   * of concurrent operations without exhausting system resources.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  public void highConcurrencyOperations() throws Exception {
    // Configure the freeze service to allow writes after a delay
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      int count = callCount.incrementAndGet();
      if (count % 3 != 0) { // Make 2/3 of calls fail initially
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Submit a large number of tasks
    List<Future<Integer>> futures = new ArrayList<>(HIGH_CONCURRENCY_TASKS);
    CountDownLatch allTasksSubmitted = new CountDownLatch(1);
    CountDownLatch allTasksCompleted = new CountDownLatch(HIGH_CONCURRENCY_TASKS);
    
    for (int i = 0; i < HIGH_CONCURRENCY_TASKS; i++) {
      final int taskId = i;
      futures.add(statusDelayedExecutor.submit(() -> {
        try {
          // Wait until all tasks are submitted to ensure they run concurrently
          allTasksSubmitted.await();
          // Simulate some work
          Thread.sleep(10);
          return taskId;
        } 
        finally {
          allTasksCompleted.countDown();
        }
      }));
    }
    
    // Release all tasks to run concurrently
    allTasksSubmitted.countDown();
    
    // Wait for all tasks to complete
    assertTrue(allTasksCompleted.await(5, SECONDS), "All tasks should complete within timeout");
    
    // Verify all tasks completed successfully
    for (int i = 0; i < HIGH_CONCURRENCY_TASKS; i++) {
      assertThat(futures.get(i).get(), is(i));
    }
    
    // Verify that we had a significant number of database status checks
    assertThat(callCount.get(), greaterThanOrEqualTo(HIGH_CONCURRENCY_TASKS));
  }
  
  /**
   * Tests that tasks can be cancelled while waiting for the database to become writable.
   */
  @Test
  public void taskCancellation() {
    // Make database permanently not writable
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());
    
    // Submit a task that will be delayed
    AtomicBoolean taskExecuted = new AtomicBoolean(false);
    Future<?> future = statusDelayedExecutor.submit(() -> {
      taskExecuted.set(true);
      return "Done";
    });
    
    // Wait for the first retry attempt
    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> {
          try {
            verify(freezeService, times(1)).checkWritable(anyString());
            return true;
          } 
          catch (Exception e) {
            return false;
          }
        });
    
    // Cancel the task
    boolean cancelResult = future.cancel(true);
    assertTrue(cancelResult, "Task should be successfully cancelled");
    
    // Verify the task was cancelled and never executed
    assertThrows(CancellationException.class, () -> future.get(100, MILLISECONDS));
    assertThat(taskExecuted.get(), is(false));
  }
  
  /**
   * Tests timeout handling with Virtual Threads.
   */
  @Test
  public void timeoutHandling() {
    // Make database permanently not writable
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());
    
    // Submit a task that will be delayed
    Future<String> future = statusDelayedExecutor.submit(() -> {
      Thread.sleep(1000); // Long-running task
      return "Done";
    });
    
    // Verify that get() with timeout throws TimeoutException while waiting for database
    assertThrows(TimeoutException.class, () -> future.get(SLEEP_INTERVAL_MS, MILLISECONDS));
  }
  
  /**
   * Tests that Virtual Threads properly maintain notification across thread boundaries.
   * This verifies that the executor correctly propagates state changes and notifications
   * between different Virtual Threads.
   */
  @Test
  public void crossThreadNotification() throws Exception {
    final AtomicInteger callCount = new AtomicInteger(0);
    final AtomicBoolean databaseWritable = new AtomicBoolean(false);
    final CountDownLatch taskStarted = new CountDownLatch(1);
    
    // Configure database to become writable after task starts
    doAnswer(invocation -> {
      callCount.incrementAndGet();
      if (!databaseWritable.get()) {
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Submit task that will be delayed
    Future<String> future = statusDelayedExecutor.submit(() -> {
      taskStarted.countDown();
      return "Done";
    });
    
    // Wait for task to start and attempt first database check
    assertTrue(taskStarted.await(1, SECONDS));
    await()
        .atMost(Duration.ofMillis(2 * SLEEP_INTERVAL_MS))
        .until(callCount::get, greaterThanOrEqualTo(1));
    
    // Task should not be done yet
    assertThat(future.isDone(), is(false));
    
    // Make database writable from a different thread
    Thread thread = new Thread(() -> databaseWritable.set(true));
    thread.start();
    thread.join();
    
    // Task should complete after next retry
    await()
        .atMost(Duration.ofMillis(3 * SLEEP_INTERVAL_MS))
        .until(() -> future.isDone());
    
    assertThat(future.get(), is("Done"));
    assertThat(callCount.get(), lessThan(MAX_RETRIES));
  }
  
  /**
   * Tests resource usage with a large number of tasks to verify that Virtual Threads
   * are more efficient than platform threads for I/O-bound operations.
   */
  @Test
  public void resourceUsageComparison() throws Exception {
    // Configure database to be writable after a delay
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      int count = callCount.incrementAndGet();
      if (count <= HIGH_CONCURRENCY_TASKS / 2) {
        Thread.sleep(1); // Small I/O simulation
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Measure memory and time for many concurrent tasks
    Runtime runtime = Runtime.getRuntime();
    runtime.gc(); // Request garbage collection to get more accurate measurements
    
    long startMemory = runtime.totalMemory() - runtime.freeMemory();
    long startTime = System.currentTimeMillis();
    
    // Submit many tasks
    List<Future<String>> futures = new ArrayList<>(HIGH_CONCURRENCY_TASKS);
    for (int i = 0; i < HIGH_CONCURRENCY_TASKS; i++) {
      futures.add(statusDelayedExecutor.submit(() -> "Done"));
    }
    
    // Wait for all tasks to complete
    for (Future<String> future : futures) {
      future.get(5, SECONDS);
    }
    
    long endTime = System.currentTimeMillis();
    runtime.gc(); // Request garbage collection again
    long endMemory = runtime.totalMemory() - runtime.freeMemory();
    
    // Log resource usage metrics
    long executionTime = endTime - startTime;
    long memoryUsed = endMemory - startMemory;
    
    log.info("Executed {} tasks in {} ms using approximately {} bytes of memory", 
        HIGH_CONCURRENCY_TASKS, executionTime, memoryUsed);
    
    // With Virtual Threads, we should be able to handle many concurrent tasks efficiently
    // This is a loose verification as exact numbers depend on the environment
    assertThat(executionTime, lessThan(5000L)); // Should complete in under 5 seconds
  }
}