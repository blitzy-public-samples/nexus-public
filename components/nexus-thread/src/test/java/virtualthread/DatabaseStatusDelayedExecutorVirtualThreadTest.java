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
package virtualthread;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests the {@link DatabaseStatusDelayedExecutor} with Java 21 Virtual Threads to ensure that
 * database-dependent tasks are properly delayed when the database is not writable.
 */
@ExtendWith(MockitoExtension.class)
public class DatabaseStatusDelayedExecutorVirtualThreadTest
    extends TestSupport
{
  private static final int SLEEP_INTERVAL_MS = 25;

  private static final int MAX_RETRIES = 5;
  
  private static final int CONCURRENT_TASKS = 1000;

  @Mock
  FreezeService freezeService;

  DatabaseStatusDelayedExecutor statusDelayedExecutor;
  
  ThreadFactory virtualThreadFactory;

  @BeforeEach
  public void setup() throws Exception {
    // Create a virtual thread factory for the test
    virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Configure the executor with virtual threads
    ExecutorService executorService = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    statusDelayedExecutor = new DatabaseStatusDelayedExecutor(
        freezeService, executorService, SLEEP_INTERVAL_MS, MAX_RETRIES);
    statusDelayedExecutor.start();
  }

  @Test
  public void ensureThatTaskEventuallyRunsWithVirtualThreads() {
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());

    Future<String> result = statusDelayedExecutor.submit(() -> "Done");

    await()
        .pollDelay(SLEEP_INTERVAL_MS / 2, MILLISECONDS)
        .atMost(2 * MAX_RETRIES * SLEEP_INTERVAL_MS, MILLISECONDS)
        .until(() -> result.isDone());

    verify(freezeService, times(MAX_RETRIES)).checkWritable(anyString());
  }

  @Test
  public void noWritableDelaysTaskWithVirtualThreads() {
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
  
  @Test
  public void concurrentTasksWithVirtualThreads() throws Exception {
    // Configure the freezeService to become writable after a delay
    final AtomicBoolean isWritable = new AtomicBoolean(false);
    doAnswer(invocation -> {
      if (!isWritable.get()) {
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Submit many concurrent tasks using virtual threads
    List<Future<Integer>> futures = new ArrayList<>(CONCURRENT_TASKS);
    AtomicInteger executionCount = new AtomicInteger(0);
    
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      final int taskId = i;
      futures.add(statusDelayedExecutor.submit(() -> {
        executionCount.incrementAndGet();
        return taskId;
      }));
    }
    
    // Verify that no tasks have completed yet
    Thread.sleep(SLEEP_INTERVAL_MS * 2);
    assertThat(executionCount.get(), is(0));
    
    // Make the database writable
    isWritable.set(true);
    
    // Wait for all tasks to complete
    await()
        .atMost(5, SECONDS)
        .until(() -> futures.stream().allMatch(Future::isDone));
    
    // Verify that all tasks executed exactly once
    assertThat(executionCount.get(), is(CONCURRENT_TASKS));
    
    // Verify that all tasks returned their expected values
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      assertThat(futures.get(i).get(), is(i));
    }
  }
  
  @Test
  public void resourceUtilizationDuringDelayWithVirtualThreads() throws Exception {
    // Configure the freezeService to never be writable during this test
    doThrow(NotWritableException.class).when(freezeService).checkWritable(anyString());
    
    // Submit many concurrent tasks
    List<Future<Integer>> futures = new ArrayList<>(CONCURRENT_TASKS);
    for (int i = 0; i < CONCURRENT_TASKS; i++) {
      final int taskId = i;
      futures.add(statusDelayedExecutor.submit(() -> taskId));
    }
    
    // Measure memory before and after to verify minimal resource usage
    System.gc(); // Request garbage collection to get more accurate measurements
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Wait for a few retry cycles
    Thread.sleep(SLEEP_INTERVAL_MS * 3);
    
    System.gc(); // Request garbage collection again
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Virtual threads should be very lightweight, so memory usage should not increase dramatically
    // even with many pending tasks. This is a simple heuristic check.
    long memoryDifference = memoryAfter - memoryBefore;
    
    // The memory difference should be relatively small compared to the number of tasks
    // This is a rough estimate - in a real environment, you'd need more sophisticated measurements
    assertThat(memoryDifference, lessThan(CONCURRENT_TASKS * 1000L)); // Less than 1KB per task on average
    
    // Verify that none of the tasks have completed (since database is never writable)
    for (Future<Integer> future : futures) {
      assertThat(future.isDone(), is(false));
    }
  }
  
  @Test
  public void tasksExecuteAfterDatabaseBecomesWritable() throws Exception {
    // Configure the freezeService to become writable after a specific number of calls
    final AtomicInteger callCount = new AtomicInteger(0);
    final int writableAfterCalls = 3;
    
    doAnswer(invocation -> {
      if (callCount.incrementAndGet() <= writableAfterCalls) {
        throw new NotWritableException("Database not writable");
      }
      return null;
    }).when(freezeService).checkWritable(anyString());
    
    // Create CompletableFuture that we can use to track task execution order
    List<CompletableFuture<Integer>> completableFutures = new ArrayList<>();
    List<Future<Integer>> executorFutures = new ArrayList<>();
    
    // Submit tasks to the executor
    for (int i = 0; i < 10; i++) {
      final int taskId = i;
      CompletableFuture<Integer> completableFuture = new CompletableFuture<>();
      completableFutures.add(completableFuture);
      
      executorFutures.add(statusDelayedExecutor.submit(() -> {
        completableFuture.complete(taskId);
        return taskId;
      }));
    }
    
    // Wait for all tasks to complete
    await()
        .atMost(5, SECONDS)
        .until(() -> executorFutures.stream().allMatch(Future::isDone));
    
    // Verify that the database was checked the expected number of times
    // Each task should check once when it succeeds, plus the failed attempts
    verify(freezeService, times(10 + writableAfterCalls)).checkWritable(anyString());
    
    // Verify that all tasks completed successfully
    for (int i = 0; i < 10; i++) {
      assertThat(completableFutures.get(i).isDone(), is(true));
      assertThat(completableFutures.get(i).get(), is(i));
    }
  }
}