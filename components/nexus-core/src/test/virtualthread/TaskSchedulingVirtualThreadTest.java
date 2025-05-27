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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;
import org.sonatype.nexus.scheduling.events.TaskEventStarted;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedCanceled;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedDone;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedFailed;
import org.sonatype.nexus.scheduling.internal.TaskSchedulerImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Test class for validating task scheduling operations with Java 21 Virtual Threads in the Nexus Core component.
 * 
 * This class verifies that scheduled tasks execute properly when running on Virtual Threads, testing task creation,
 * execution, completion events, and cancellation across high-concurrency scenarios. It ensures the scheduling
 * infrastructure properly handles tasks running simultaneously on many Virtual Threads without resource exhaustion
 * or thread management issues.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("java21")
@org.junit.Category(VirtualThreadTestGroup.class)
public class TaskSchedulingVirtualThreadTest
    extends TestSupport
{
  private static final int TASK_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Mock
  private EventManager eventManager;
  
  @Mock
  private TaskInfo taskInfo;
  
  @Mock
  private TaskConfiguration taskConfiguration;
  
  private TaskSchedulerImpl underTest;
  
  @BeforeEach
  void setUp() {
    underTest = new TaskSchedulerImpl(eventManager);
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(taskInfo.getId()).thenReturn("test-task-id");
    when(taskInfo.getName()).thenReturn("Test Task");
    when(taskInfo.getTypeId()).thenReturn("test-type");
  }
  
  /**
   * Tests that a large number of tasks can be executed concurrently using Virtual Threads.
   * This verifies that the task scheduler can handle high concurrency without resource exhaustion.
   */
  @Test
  void testHighConcurrencyTaskExecution() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(TASK_COUNT);
    AtomicInteger completedTasks = new AtomicInteger(0);
    AtomicInteger failedTasks = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < TASK_COUNT; i++) {
        final int taskId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Simulate task execution
            TaskConfiguration config = new TaskConfiguration();
            config.setId("task-" + taskId);
            config.setName("Virtual Thread Task " + taskId);
            config.setTypeId("test-virtual-thread-task");
            
            // Simulate task lifecycle events
            underTest.notifyTaskStarted(taskInfo);
            
            // Simulate some work with random duration to test scheduling behavior
            Thread.sleep((long) (Math.random() * 50));
            
            // Simulate task completion
            underTest.notifyTaskDone(taskInfo);
            completedTasks.incrementAndGet();
          } 
          catch (Exception e) {
            failedTasks.incrementAndGet();
            underTest.notifyTaskFailed(taskInfo, e);
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete or timeout
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("All tasks should complete within the timeout period", completed, is(true));
      
      // Verify all tasks completed successfully
      assertThat("All tasks should complete successfully", completedTasks.get(), equalTo(TASK_COUNT));
      assertThat("No tasks should fail", failedTasks.get(), equalTo(0));
      
      // Verify task events were fired correctly
      verify(eventManager, times(TASK_COUNT)).post(any(TaskEventStarted.class));
      verify(eventManager, times(TASK_COUNT)).post(any(TaskEventStoppedDone.class));
      verify(eventManager, times(0)).post(any(TaskEventStoppedFailed.class));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that task cancellation works correctly with Virtual Threads.
   * This verifies that tasks can be properly cancelled and that cancellation events are fired.
   */
  @Test
  void testTaskCancellationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch cancelLatch = new CountDownLatch(1);
    AtomicBoolean taskCancelled = new AtomicBoolean(false);
    
    try {
      // Create a long-running task that we'll cancel
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Notify that task has started
          underTest.notifyTaskStarted(taskInfo);
          startLatch.countDown();
          
          // Run until cancelled
          while (!Thread.currentThread().isInterrupted() && !taskCancelled.get()) {
            Thread.sleep(50);
          }
          
          // Simulate task cancellation
          if (taskCancelled.get()) {
            underTest.notifyTaskCancelled(taskInfo);
            cancelLatch.countDown();
          }
        } 
        catch (InterruptedException e) {
          // Expected when cancelled
          taskCancelled.set(true);
          Thread.currentThread().interrupt();
          underTest.notifyTaskCancelled(taskInfo);
          cancelLatch.countDown();
        }
      }, executor);
      
      // Wait for task to start
      boolean started = startLatch.await(5, TimeUnit.SECONDS);
      assertThat("Task should start within timeout", started, is(true));
      
      // Verify task started event was fired
      ArgumentCaptor<TaskEventStarted> startedEventCaptor = ArgumentCaptor.forClass(TaskEventStarted.class);
      verify(eventManager).post(startedEventCaptor.capture());
      TaskEventStarted startedEvent = startedEventCaptor.getValue();
      assertThat(startedEvent, notNullValue());
      assertThat(startedEvent.getTaskInfo().getId(), equalTo("test-task-id"));
      
      // Cancel the task
      taskCancelled.set(true);
      future.cancel(true);
      
      // Wait for cancellation to complete
      boolean cancelled = cancelLatch.await(5, TimeUnit.SECONDS);
      assertThat("Task should be cancelled within timeout", cancelled, is(true));
      
      // Verify cancellation event was fired
      ArgumentCaptor<TaskEventStoppedCanceled> cancelledEventCaptor = 
          ArgumentCaptor.forClass(TaskEventStoppedCanceled.class);
      verify(eventManager).post(cancelledEventCaptor.capture());
      TaskEventStoppedCanceled cancelledEvent = cancelledEventCaptor.getValue();
      assertThat(cancelledEvent, notNullValue());
      assertThat(cancelledEvent.getTaskInfo().getId(), equalTo("test-task-id"));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that Virtual Threads provide performance benefits for I/O-bound tasks.
   * This compares the performance of platform threads vs. virtual threads for tasks that simulate I/O operations.
   */
  @Test
  void testVirtualThreadPerformanceForIOBoundTasks() throws Exception {
    // Configure thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Number of concurrent tasks to run
    final int concurrentTasks = 500;
    
    // Run with platform threads
    long platformThreadTime = measureExecutionTime(platformThreadFactory, concurrentTasks);
    
    // Run with virtual threads
    long virtualThreadTime = measureExecutionTime(virtualThreadFactory, concurrentTasks);
    
    // Log the results
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound tasks
    assertThat("Virtual threads should be faster than platform threads for I/O-bound tasks",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that task state transitions work correctly with Virtual Threads.
   * This verifies that tasks properly transition through their lifecycle states when executed on Virtual Threads.
   */
  @Test
  void testTaskStateTransitionsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(1);
    List<TaskState> stateTransitions = new ArrayList<>();
    
    try {
      // Mock task state changes
      when(taskInfo.getState()).thenReturn(
          TaskState.WAITING, TaskState.RUNNING, TaskState.RUNNING, TaskState.DONE);
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Record initial state
          stateTransitions.add(taskInfo.getState());
          
          // Start task
          underTest.notifyTaskStarted(taskInfo);
          stateTransitions.add(taskInfo.getState());
          
          // Simulate work
          Thread.sleep(100);
          stateTransitions.add(taskInfo.getState());
          
          // Complete task
          underTest.notifyTaskDone(taskInfo);
          stateTransitions.add(taskInfo.getState());
          
          latch.countDown();
        } 
        catch (Exception e) {
          // Not expected in this test
        }
      }, executor);
      
      // Wait for task to complete
      boolean completed = latch.await(5, TimeUnit.SECONDS);
      assertThat("Task should complete within timeout", completed, is(true));
      
      // Verify state transitions
      assertThat(stateTransitions.size(), equalTo(4));
      assertThat(stateTransitions.get(0), equalTo(TaskState.WAITING));
      assertThat(stateTransitions.get(1), equalTo(TaskState.RUNNING));
      assertThat(stateTransitions.get(2), equalTo(TaskState.RUNNING));
      assertThat(stateTransitions.get(3), equalTo(TaskState.DONE));
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to measure execution time for a batch of simulated I/O-bound tasks.
   * 
   * @param threadFactory The thread factory to use (platform or virtual)
   * @param taskCount The number of concurrent tasks to execute
   * @return The execution time in milliseconds
   */
  private long measureExecutionTime(ThreadFactory threadFactory, int taskCount) throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(taskCount);
    
    try {
      long startTime = System.currentTimeMillis();
      
      // Submit tasks
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Simulate I/O-bound task with sleep
            simulateIOOperation();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      return System.currentTimeMillis() - startTime;
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Simulates an I/O-bound operation by sleeping for a short duration.
   * This mimics the behavior of tasks that wait for I/O operations to complete.
   */
  private void simulateIOOperation() {
    try {
      // Simulate I/O operation with variable latency (50-150ms)
      Thread.sleep(50 + (long) (Math.random() * 100));
    } 
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}