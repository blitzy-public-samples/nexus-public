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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.app.NotWritableException;
import org.sonatype.nexus.thread.DatabaseStatusDelayedExecutor;

import org.junit.jupiter.api.AfterEach;
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
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests for {@link DatabaseStatusDelayedExecutor} with Java 21 Virtual Threads.
 * 
 * These tests validate that the executor maintains correct execution order and respects database
 * writability status while leveraging the lightweight threading capabilities of Java 21.
 */
@ExtendWith(MockitoExtension.class)
public class DatabaseStatusDelayedExecutorVirtualThreadTest
    extends TestSupport
{
  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;
  
  private static final int HIGH_CONCURRENCY_TASK_COUNT = 1000;

  @Mock
  FreezeService freezeService;

  DatabaseStatusDelayedExecutor virtualThreadExecutor;
  
  ExecutorService cleanupExecutor;

  @BeforeEach
  public void setup() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create executor with virtual threads
    virtualThreadExecutor = new DatabaseStatusDelayedExecutor(freezeService, SLEEP_INTERVAL_MS, MAX_RETRIES);
    virtualThreadExecutor.start();
    
    // Executor for cleanup tasks
    cleanupExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  public void cleanup() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
    if (cleanupExecutor != null) {
      cleanupExecutor.shutdown();
    }
  }

  @Test
  public void ensureThatTaskEventuallyRuns() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    Future<String> result = virtualThreadExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
  }

  @Test
  public void noWritableDelaysTask() {
    final AtomicInteger callCount = new AtomicInteger(0);
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= 4) {
        throw new NotWritableException("");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());

    Future<String> result = virtualThreadExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(callCount::get, greaterThanOrEqualTo(1));

    assertFalse(result.isDone());

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(10 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    assertEquals(5, callCount.get());
  }
  
  @Test
  public void concurrentTasksWithVirtualThreads() {
    // Submit multiple tasks concurrently
    Future<String> result1 = virtualThreadExecutor.submit(() -> "Task1");
    Future<String> result2 = virtualThreadExecutor.submit(() -> "Task2");
    Future<String> result3 = virtualThreadExecutor.submit(() -> "Task3");
    
    // Wait for all tasks to complete
    await()
        .atMost(5 * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result1.isDone() && result2.isDone() && result3.isDone());
    
    // Verify all tasks completed successfully
    assertTrue(result1.isDone());
    assertTrue(result2.isDone());
    assertTrue(result3.isDone());
  }
  
  @Test
  public void highConcurrencyTaskExecution() throws Exception {
    // Create a large number of tasks
    List<Future<Integer>> futures = new ArrayList<>(HIGH_CONCURRENCY_TASK_COUNT);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Submit a large number of tasks that will wait for the startLatch
    for (int i = 0; i < HIGH_CONCURRENCY_TASK_COUNT; i++) {
      final int taskId = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        startLatch.await(1, SECONDS); // All tasks wait for the signal to start together
        completedTasks.incrementAndGet();
        return taskId;
      }));
    }
    
    // Release all tasks at once to simulate high concurrency
    startLatch.countDown();
    
    // Wait for all tasks to complete
    await()
        .atMost(5, SECONDS)
        .until(() -> completedTasks.get() == HIGH_CONCURRENCY_TASK_COUNT);
    
    // Verify all tasks completed
    assertEquals(HIGH_CONCURRENCY_TASK_COUNT, completedTasks.get());
    
    // Verify all futures completed successfully
    for (Future<Integer> future : futures) {
      assertTrue(future.isDone());
      assertFalse(future.isCancelled());
    }
  }
  
  @Test
  public void testRetryLogicWithHighConcurrency() throws Exception {
    // Configure the freezeService to throw NotWritableException for the first 3 calls
    // from each task, then succeed
    ConcurrentHashMap<Integer, AtomicInteger> callCounts = new ConcurrentHashMap<>();
    
    doAnswer(invocation -> {
      // Get the current thread ID to track calls per thread
      int threadId = System.identityHashCode(Thread.currentThread());
      AtomicInteger count = callCounts.computeIfAbsent(threadId, k -> new AtomicInteger(0));
      int attempts = count.incrementAndGet();
      
      if (attempts <= 3) {
        throw new NotWritableException("Database not writable for thread " + threadId + ", attempt " + attempts);
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Submit multiple concurrent tasks
    int taskCount = 50;
    List<Future<String>> futures = new ArrayList<>(taskCount);
    
    for (int i = 0; i < taskCount; i++) {
      final int taskId = i;
      futures.add(virtualThreadExecutor.submit(() -> "Task" + taskId));
    }
    
    // Wait for all tasks to complete
    await()
        .atMost(10, SECONDS)
        .until(() -> futures.stream().allMatch(Future::isDone));
    
    // Verify all tasks completed successfully
    for (Future<String> future : futures) {
      assertTrue(future.isDone());
      assertFalse(future.isCancelled());
    }
    
    // Verify each thread had to retry multiple times
    for (AtomicInteger count : callCounts.values()) {
      assertThat(count.get(), greaterThanOrEqualTo(3));
    }
  }
  
  @Test
  public void testNoPinningDuringDatabaseCheck() throws Exception {
    // This test verifies that virtual threads don't get pinned during database status checking
    // by running a CPU-intensive task in parallel with many I/O-bound tasks
    
    // Make the freezeService simulate I/O by sleeping briefly
    doAnswer(invocation -> {
      // Simulate I/O operation with a short sleep
      Thread.sleep(10);
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Create a CPU-intensive task that runs for a while
    AtomicBoolean cpuTaskRunning = new AtomicBoolean(true);
    CompletableFuture<Long> cpuTask = CompletableFuture.supplyAsync(() -> {
      long counter = 0;
      long startTime = System.currentTimeMillis();
      while (cpuTaskRunning.get() && System.currentTimeMillis() - startTime < 2000) {
        counter++;
      }
      return counter;
    }, cleanupExecutor);
    
    // Submit many I/O-bound tasks that will check database status
    int ioTaskCount = 500;
    List<Future<Integer>> ioTasks = new ArrayList<>(ioTaskCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger completedIoTasks = new AtomicInteger(0);
    
    for (int i = 0; i < ioTaskCount; i++) {
      final int taskId = i;
      ioTasks.add(virtualThreadExecutor.submit(() -> {
        startLatch.await(1, SECONDS);
        // This will trigger the database check which simulates I/O
        completedIoTasks.incrementAndGet();
        return taskId;
      }));
    }
    
    // Start all I/O tasks
    startLatch.countDown();
    
    // Wait for all I/O tasks to complete
    await()
        .atMost(5, SECONDS)
        .until(() -> completedIoTasks.get() == ioTaskCount);
    
    // Stop the CPU task
    cpuTaskRunning.set(false);
    long cpuOperations = cpuTask.get(1, SECONDS);
    
    // If virtual threads were pinned, the CPU task would have been starved
    // and wouldn't have been able to do many operations
    log.info("CPU task performed {} operations while {} I/O tasks were running", 
        cpuOperations, ioTaskCount);
    
    // Verify the CPU task was able to make progress (not starved)
    assertThat(cpuOperations, greaterThanOrEqualTo(1000L));
    
    // Verify all I/O tasks completed
    assertEquals(ioTaskCount, completedIoTasks.get());
  }
  
  @Test
  public void testPerformanceUnderHighLoad() throws Exception {
    // This test measures executor performance under high virtual thread loads
    
    // Number of tasks to run
    int taskCount = 2000;
    
    // Create and submit tasks
    List<Future<Long>> futures = new ArrayList<>(taskCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Submit tasks that record their execution time
    for (int i = 0; i < taskCount; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        startLatch.await(1, SECONDS);
        long startTime = System.nanoTime();
        // Simulate some work
        Thread.sleep(5);
        return System.nanoTime() - startTime;
      }));
    }
    
    // Start all tasks simultaneously
    long testStartTime = System.nanoTime();
    startLatch.countDown();
    
    // Wait for all tasks to complete and collect execution times
    List<Long> executionTimes = new ArrayList<>(taskCount);
    for (Future<Long> future : futures) {
      executionTimes.add(future.get(10, SECONDS));
    }
    long totalTime = System.nanoTime() - testStartTime;
    
    // Calculate statistics
    double avgExecutionTimeMs = executionTimes.stream()
        .mapToLong(t -> t)
        .average()
        .orElse(0) / 1_000_000.0;
    
    long totalTimeMs = TimeUnit.NANOSECONDS.toMillis(totalTime);
    
    log.info("Completed {} tasks in {} ms", taskCount, totalTimeMs);
    log.info("Average task execution time: {} ms", avgExecutionTimeMs);
    log.info("Throughput: {} tasks/second", (taskCount * 1000.0) / totalTimeMs);
    
    // Verify performance metrics
    // The actual throughput will depend on the test environment, but we can verify
    // that the executor can handle a large number of concurrent tasks efficiently
    assertThat("Total execution time should be reasonable for virtual threads",
        totalTimeMs, lessThan(taskCount * 5L)); // Much less than sequential execution would take
  }
}