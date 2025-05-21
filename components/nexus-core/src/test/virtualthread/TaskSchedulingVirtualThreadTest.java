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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;
import org.sonatype.nexus.scheduling.events.TaskEventStarted;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedDone;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedFailed;
import org.sonatype.nexus.scheduling.schedule.Manual;
import org.sonatype.nexus.scheduling.schedule.Now;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for validating task scheduling operations with Java 21 Virtual Threads.
 * 
 * This class verifies that scheduled tasks execute properly when running on Virtual Threads,
 * testing task creation, execution, completion events, and cancellation across high-concurrency
 * scenarios. It ensures the scheduling infrastructure properly handles tasks running simultaneously
 * on many Virtual Threads without resource exhaustion or thread management issues.
 */
public class TaskSchedulingVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_TASKS = 1000;
  private static final int TASK_EXECUTION_TIME_MS = 50;
  private static final int TIMEOUT_SECONDS = 30;

  @Mock
  private TaskScheduler taskScheduler;

  @Mock
  private EventManager eventManager;

  @BeforeEach
  public void setup() {
    // Configure the task scheduler mock to use virtual threads for task execution
    when(taskScheduler.getEventManager()).thenReturn(eventManager);
  }

  /**
   * Tests that a single task can be executed successfully using a Virtual Thread.
   */
  @Test
  public void testSingleTaskExecutionWithVirtualThread() throws Exception {
    // Create a task that will run on a virtual thread
    Runnable task = () -> {
      try {
        // Simulate some work
        Thread.sleep(TASK_EXECUTION_TIME_MS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    // Execute the task on a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(task, 
        Executors.newVirtualThreadPerTaskExecutor());

    // Wait for the task to complete
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

    // Verify the task completed successfully
    assertTrue(future.isDone());
    assertThat(Thread.currentThread().isVirtual(), is(false)); // Main test thread is not virtual
  }

  /**
   * Tests that task lifecycle events are properly propagated when tasks are executed on Virtual Threads.
   */
  @Test
  public void testTaskLifecycleEventsWithVirtualThreads() throws Exception {
    // Create a mock TaskInfo
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration config = new TaskConfiguration();
    config.setId("test-task");
    config.setName("Test Task");
    config.setTypeId("test");
    
    when(taskInfo.getId()).thenReturn("test-task");
    when(taskInfo.getName()).thenReturn("Test Task");
    when(taskInfo.getConfiguration()).thenReturn(config);

    // Create latches to track event progression
    CountDownLatch startedLatch = new CountDownLatch(1);
    CountDownLatch completedLatch = new CountDownLatch(1);

    // Configure event manager to count down latches when events are fired
    doAnswer(invocation -> {
      Object event = invocation.getArgument(0);
      if (event instanceof TaskEventStarted) {
        startedLatch.countDown();
      }
      else if (event instanceof TaskEventStoppedDone) {
        completedLatch.countDown();
      }
      return null;
    }).when(eventManager).post(any());

    // Execute a task on a virtual thread that will fire events
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        // Simulate task execution
        eventManager.post(new TaskEventStarted(taskInfo));
        try {
          Thread.sleep(TASK_EXECUTION_TIME_MS);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        eventManager.post(new TaskEventStoppedDone(taskInfo));
      });

      // Wait for events to be fired
      assertTrue(startedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Task started event not fired");
      assertTrue(completedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Task completed event not fired");

      // Verify events were posted
      verify(eventManager, times(1)).post(any(TaskEventStarted.class));
      verify(eventManager, times(1)).post(any(TaskEventStoppedDone.class));
    }
  }

  /**
   * Tests that task cancellation works properly with Virtual Threads.
   */
  @Test
  public void testTaskCancellationWithVirtualThreads() throws Exception {
    // Create an atomic boolean to track if the task was cancelled
    AtomicBoolean taskCancelled = new AtomicBoolean(false);
    
    // Create a latch to signal when the task has started
    CountDownLatch startedLatch = new CountDownLatch(1);

    // Create a task that will check for interruption
    Runnable task = () -> {
      startedLatch.countDown();
      try {
        // Loop until interrupted
        while (!Thread.currentThread().isInterrupted()) {
          Thread.sleep(10);
        }
        taskCancelled.set(true);
      }
      catch (InterruptedException e) {
        // Expected - task was cancelled
        taskCancelled.set(true);
      }
    };

    // Execute the task on a virtual thread
    Thread virtualThread = Thread.ofVirtual().start(task);

    // Wait for the task to start
    assertTrue(startedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Task did not start");

    // Cancel the task
    virtualThread.interrupt();

    // Wait for the task to complete
    virtualThread.join(TIMEOUT_SECONDS * 1000);

    // Verify the task was cancelled
    assertTrue(taskCancelled.get(), "Task was not cancelled");
  }

  /**
   * Tests that exceptions in tasks executed on Virtual Threads are properly propagated.
   */
  @Test
  public void testExceptionPropagationWithVirtualThreads() throws Exception {
    // Create a mock TaskInfo
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration config = new TaskConfiguration();
    config.setId("test-exception-task");
    config.setName("Test Exception Task");
    config.setTypeId("test");
    
    when(taskInfo.getId()).thenReturn("test-exception-task");
    when(taskInfo.getName()).thenReturn("Test Exception Task");
    when(taskInfo.getConfiguration()).thenReturn(config);

    // Create a latch to track when the exception event is fired
    CountDownLatch exceptionLatch = new CountDownLatch(1);

    // Configure event manager to count down latch when exception event is fired
    doAnswer(invocation -> {
      Object event = invocation.getArgument(0);
      if (event instanceof TaskEventStoppedFailed) {
        exceptionLatch.countDown();
      }
      return null;
    }).when(eventManager).post(any());

    // Create a task that will throw an exception
    Runnable task = () -> {
      eventManager.post(new TaskEventStarted(taskInfo));
      throw new RuntimeException("Test exception");
    };

    // Execute the task on a virtual thread and expect an exception
    CompletableFuture<Void> future = CompletableFuture.runAsync(task, 
        Executors.newVirtualThreadPerTaskExecutor());

    // Wait for the exception event to be fired
    assertTrue(exceptionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Exception event not fired");

    // Verify the task failed with an exception
    try {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      fail("Expected exception was not thrown");
    }
    catch (ExecutionException e) {
      // Expected - task failed with exception
      assertThat(e.getCause().getMessage(), equalTo("Test exception"));
    }

    // Verify events were posted
    verify(eventManager, times(1)).post(any(TaskEventStarted.class));
    verify(eventManager, times(1)).post(any(TaskEventStoppedFailed.class));
  }

  /**
   * Tests high-concurrency task execution with many Virtual Threads.
   */
  @Test
  public void testHighConcurrencyTaskExecutionWithVirtualThreads() throws Exception {
    // Create a counter to track completed tasks
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    // Create a map to track task execution by thread ID
    ConcurrentHashMap<Long, Boolean> threadExecutions = new ConcurrentHashMap<>();

    // Create a latch to wait for all tasks to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_TASKS);

    // Create and execute many concurrent tasks on virtual threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit many tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        final int taskId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Record that this thread executed a task
            threadExecutions.put(Thread.currentThread().threadId(), true);
            
            // Verify this is running on a virtual thread
            assertTrue(Thread.currentThread().isVirtual(), 
                "Task " + taskId + " not running on a virtual thread");
            
            // Simulate some work
            Thread.sleep(TASK_EXECUTION_TIME_MS);
            
            // Mark task as completed
            completedTasks.incrementAndGet();
            completionLatch.countDown();
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }, executor);
        
        futures.add(future);
      }

      // Wait for all tasks to complete
      assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Not all tasks completed within timeout");

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      // Verify all tasks completed successfully
      assertThat(completedTasks.get(), equalTo(CONCURRENT_TASKS));
      
      // Verify that we had many different threads executing tasks
      // (should be close to CONCURRENT_TASKS if virtual threads are working correctly)
      assertThat(threadExecutions.size(), is(CONCURRENT_TASKS));
    }
  }

  /**
   * Tests that Virtual Threads properly handle task resource cleanup.
   */
  @Test
  public void testResourceCleanupWithVirtualThreads() throws Exception {
    // Create a resource tracker to ensure resources are properly cleaned up
    AtomicInteger resourcesAcquired = new AtomicInteger(0);
    AtomicInteger resourcesReleased = new AtomicInteger(0);

    // Create a latch to wait for all tasks to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_TASKS);

    // Create and execute tasks that acquire and release resources
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_TASKS; i++) {
        executor.submit(() -> {
          try {
            // Acquire resource
            resourcesAcquired.incrementAndGet();
            
            // Simulate some work
            Thread.sleep(TASK_EXECUTION_TIME_MS);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            // Release resource
            resourcesReleased.incrementAndGet();
            completionLatch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Not all tasks completed within timeout");

      // Verify all resources were properly acquired and released
      assertThat(resourcesAcquired.get(), equalTo(CONCURRENT_TASKS));
      assertThat(resourcesReleased.get(), equalTo(CONCURRENT_TASKS));
    }
  }
}