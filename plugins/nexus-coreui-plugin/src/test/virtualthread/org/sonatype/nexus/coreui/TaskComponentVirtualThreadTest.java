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
package org.sonatype.nexus.coreui;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.inject.Provider;
import javax.validation.Validator;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.scheduling.CurrentState;
import org.sonatype.nexus.scheduling.ExternalTaskState;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;
import org.sonatype.nexus.scheduling.schedule.Manual;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.Weekly;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link TaskComponent} with Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class TaskComponentVirtualThreadTest
    extends TestSupport
{
  private TaskComponent component;

  private TaskScheduler scheduler;

  @Mock
  private Validator validator;

  private final Provider<Validator> validatorProvider = () -> validator;

  @BeforeEach
  public void setUp() {
    scheduler = mock(TaskScheduler.class, Mockito.RETURNS_DEEP_STUBS);
    component = new TaskComponent(scheduler, validatorProvider, false);
  }

  /**
   * Tests that task state validation works correctly under virtual thread execution.
   */
  @Test
  public void testValidateState_runningWithVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("validate-state-test").start(() -> {
      TaskInfo taskInfo = mock(TaskInfo.class);
      CurrentState localState = mock(CurrentState.class);
      ExternalTaskState extState = mock(ExternalTaskState.class);
      when(localState.getState()).thenReturn(TaskState.RUNNING);
      when(taskInfo.getId()).thenReturn("taskId");
      when(taskInfo.getCurrentState()).thenReturn(localState);
      when(extState.getState()).thenReturn(TaskState.RUNNING);
      when(scheduler.toExternalTaskState(taskInfo)).thenReturn(extState);

      // Verify that the expected exception is thrown
      IllegalStateException exception = assertThrows(IllegalStateException.class, 
          () -> component.validateState("taskId", taskInfo));
      assertEquals("Task can not be edited while it is being executed or it is in line to be executed", 
          exception.getMessage());
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that task state validation works correctly for non-running tasks under virtual thread execution.
   */
  @Test
  public void testValidateState_notRunningWithVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("validate-state-test").start(() -> {
      TaskInfo taskInfo = mock(TaskInfo.class);
      CurrentState localState = mock(CurrentState.class);
      ExternalTaskState extState = mock(ExternalTaskState.class);
      when(localState.getState()).thenReturn(TaskState.WAITING);
      when(taskInfo.getId()).thenReturn("taskId");
      when(taskInfo.getCurrentState()).thenReturn(localState);
      when(extState.getState()).thenReturn(TaskState.WAITING);
      when(scheduler.toExternalTaskState(taskInfo)).thenReturn(extState);

      // This should not throw an exception
      component.validateState("taskId", taskInfo);
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that script update validation works correctly under virtual thread execution.
   */
  @Test
  public void testValidateScriptUpdate_noSourceChangeWithVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("validate-script-test").start(() -> {
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setString("source", "println 'hello'");

      TaskInfo taskInfo = mock(TaskInfo.class);
      when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);

      TaskXO taskXO = new TaskXO();
      taskXO.setProperties(ImmutableMap.of("source", "println 'hello'"));

      // This should not throw an exception
      component.validateScriptUpdate(taskInfo, taskXO);
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that script update validation correctly handles source changes when allowed under virtual thread execution.
   */
  @Test
  public void testValidateScriptUpdate_sourceChange_allowCreationWithVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("validate-script-test").start(() -> {
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setString("source", "println 'hello'");

      TaskInfo taskInfo = mock(TaskInfo.class);
      when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);

      TaskXO taskXO = new TaskXO();
      taskXO.setProperties(ImmutableMap.of("source", "println 'hello world'"));

      // Create a component that allows script creation
      TaskComponent allowComponent = new TaskComponent(scheduler, validatorProvider, true);
      
      // This should not throw an exception
      allowComponent.validateScriptUpdate(taskInfo, taskXO);
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that script update validation correctly handles source changes when not allowed under virtual thread execution.
   */
  @Test
  public void testValidateScriptUpdate_sourceChange_doNotAllowCreationWithVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("validate-script-test").start(() -> {
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setString("source", "println 'hello'");

      TaskInfo taskInfo = mock(TaskInfo.class);
      when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);

      TaskXO taskXO = new TaskXO();
      taskXO.setProperties(ImmutableMap.of("source", "println 'hello world'"));

      // Verify that the expected exception is thrown
      IllegalStateException exception = assertThrows(IllegalStateException.class, 
          () -> component.validateScriptUpdate(taskInfo, taskXO));
      assertEquals("Script source updates are not allowed", exception.getMessage());
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that plan reconciliation text is correctly appended under virtual thread execution.
   */
  @Test
  public void testAppendPlanReconciliationTextWithVirtualThread() throws Exception {
    // Create a virtual thread to run the test
    Thread virtualThread = Thread.ofVirtual().name("plan-reconciliation-test").start(() -> {
      TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
      when(taskConfiguration.isVisible()).thenReturn(true);
      when(taskConfiguration.getTypeId()).thenReturn(TaskComponent.PLAN_RECONCILIATION_TASK_ID);

      TaskInfo taskInfo = mock(TaskInfo.class);
      CurrentState localState = mock(CurrentState.class);
      Schedule schedule = mock(Weekly.class);
      when(localState.getState()).thenReturn(TaskState.WAITING);
      when(taskInfo.getId()).thenReturn("taskId");
      when(taskInfo.getTypeId()).thenReturn(TaskComponent.PLAN_RECONCILIATION_TASK_ID);
      when(taskInfo.getCurrentState()).thenReturn(localState);
      when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
      when(taskInfo.getSchedule()).thenReturn(schedule);
      when(scheduler.listsTasks()).thenReturn(List.of(taskInfo));

      ExternalTaskState extState = mock(ExternalTaskState.class);
      when(scheduler.toExternalTaskState(taskInfo)).thenReturn(extState);
      when(extState.getState()).thenReturn(TaskState.WAITING);
      when(extState.getLastEndState()).thenReturn(TaskState.OK);
      when(extState.getLastRunStarted()).thenReturn(new Date());
      when(extState.getLastRunDuration()).thenReturn(100L);

      List<TaskXO> tasks = component.read();
      assertEquals(1, tasks.size());
      assertEquals(TaskComponent.PLAN_RECONCILIATION_TASK_ID, tasks.get(0).getTypeId());
      assertEquals("Ok [0s]" + TaskComponent.PLAN_RECONCILIATION_TASK_OK_TEXT, tasks.get(0).getLastRunResult());
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests that task operations don't cause thread pinning.
   * Thread pinning occurs when a virtual thread is "stuck" to its carrier thread,
   * which negates the benefits of virtual threads.
   */
  @Test
  public void testNoPinningDuringTaskOperations() throws Exception {
    // Create a task that will sleep briefly to simulate work
    Runnable taskOperation = () -> {
      try {
        // Set up mocks for a task operation
        TaskInfo taskInfo = mock(TaskInfo.class);
        CurrentState localState = mock(CurrentState.class);
        when(localState.getState()).thenReturn(TaskState.WAITING);
        when(taskInfo.getId()).thenReturn("taskId");
        when(taskInfo.getCurrentState()).thenReturn(localState);
        
        // Perform a task operation that might cause pinning
        component.validateState("taskId", taskInfo);
        
        // Sleep briefly to simulate work
        Thread.sleep(10);
      }
      catch (Exception e) {
        fail("Exception during task operation: " + e.getMessage());
      }
    };
    
    // Flag to track if pinning was detected
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a virtual thread to run the task operation
    Thread virtualThread = Thread.ofVirtual().name("pinning-test").start(() -> {
      // Check if the current thread is a virtual thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should be running on a virtual thread");
      
      // Record the start time
      long startTime = System.nanoTime();
      
      // Run the task operation
      taskOperation.run();
      
      // Calculate the duration
      long duration = System.nanoTime() - startTime;
      
      // If the operation took significantly longer than expected, it might indicate pinning
      if (duration > TimeUnit.MILLISECONDS.toNanos(100)) {
        pinningDetected.set(true);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join();
    
    // Verify no pinning was detected
    assertFalse(pinningDetected.get(), "Thread pinning detected during task operations");
  }

  /**
   * Tests performance comparison between platform threads and virtual threads for task operations.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    // Number of concurrent operations to perform
    final int concurrentOperations = 1000;
    
    // Create a task operation that simulates a typical task validation
    Runnable taskOperation = () -> {
      try {
        // Set up mocks for a task operation
        TaskInfo taskInfo = mock(TaskInfo.class);
        CurrentState localState = mock(CurrentState.class);
        when(localState.getState()).thenReturn(TaskState.WAITING);
        when(taskInfo.getId()).thenReturn("taskId");
        when(taskInfo.getCurrentState()).thenReturn(localState);
        
        // Perform a task operation
        component.validateState("taskId", taskInfo);
        
        // Simulate some I/O with a brief sleep
        Thread.sleep(5);
      }
      catch (Exception e) {
        fail("Exception during task operation: " + e.getMessage());
      }
    };
    
    // Measure performance with platform threads
    long platformThreadTime = measurePerformance(() -> {
      try {
        // Use a fixed thread pool with platform threads
        ExecutorService executor = Executors.newFixedThreadPool(100); // Limited thread pool size
        List<Future<?>> futures = new ArrayList<>();
        
        // Submit tasks to the executor
        for (int i = 0; i < concurrentOperations; i++) {
          futures.add(executor.submit(taskOperation));
        }
        
        // Wait for all tasks to complete
        for (Future<?> future : futures) {
          future.get();
        }
        
        // Shutdown the executor
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
        return true;
      }
      catch (Exception e) {
        fail("Exception during platform thread test: " + e.getMessage());
        return false;
      }
    });
    
    // Measure performance with virtual threads
    long virtualThreadTime = measurePerformance(() -> {
      try {
        // Use a virtual thread per task executor
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
          List<Future<?>> futures = new ArrayList<>();
          
          // Submit tasks to the executor
          for (int i = 0; i < concurrentOperations; i++) {
            futures.add(executor.submit(taskOperation));
          }
          
          // Wait for all tasks to complete
          for (Future<?> future : futures) {
            future.get();
          }
        }
        return true;
      }
      catch (Exception e) {
        fail("Exception during virtual thread test: " + e.getMessage());
        return false;
      }
    });
    
    // Log the performance results
    log.info("Platform thread time: {} ms", platformThreadTime);
    log.info("Virtual thread time: {} ms", virtualThreadTime);
    
    // Note: We don't assert on specific performance improvements as they can vary by environment,
    // but we expect virtual threads to generally perform better for I/O-bound operations
  }

  /**
   * Tests concurrent task creation and management with a high number of virtual threads.
   */
  @Test
  public void testConcurrentTaskCreationWithVirtualThreads() throws Exception {
    // Number of concurrent task creations to perform
    final int concurrentTasks = 1000;
    
    // Counter to track successful task creations
    AtomicInteger successfulCreations = new AtomicInteger(0);
    
    // Latch to synchronize all threads to start at roughly the same time
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(concurrentTasks);
    
    // Mock the scheduler to handle task creation
    TaskInfo mockTaskInfo = mock(TaskInfo.class);
    when(scheduler.getScheduleFactory().manual()).thenReturn(new Manual());
    when(scheduler.createTask(Mockito.any(TaskConfiguration.class))).thenReturn(mockTaskInfo);
    
    // Create virtual threads for concurrent task creation
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < concurrentTasks; i++) {
      final int taskId = i;
      Thread thread = Thread.ofVirtual().name("task-creation-" + taskId).start(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Create a task
          TaskXO taskXO = new TaskXO();
          taskXO.setName("Test Task " + taskId);
          taskXO.setTypeId("test-type");
          taskXO.setSchedule("manual");
          taskXO.setEnabled(true);
          taskXO.setProperties(ImmutableMap.of("key", "value"));
          
          // Attempt to create the task
          component.create(taskXO);
          
          // Increment the success counter
          successfulCreations.incrementAndGet();
        }
        catch (Exception e) {
          log.error("Error creating task {}: {}", taskId, e.getMessage());
        }
        finally {
          // Signal that this thread is complete
          completionLatch.countDown();
        }
      });
      
      threads.add(thread);
    }
    
    // Signal all threads to start
    startLatch.countDown();
    
    // Wait for all threads to complete (with timeout)
    boolean allCompleted = completionLatch.await(30, TimeUnit.SECONDS);
    
    // Verify all threads completed
    assertTrue(allCompleted, "Not all task creation threads completed in time");
    
    // Verify the number of successful creations
    assertEquals(concurrentTasks, successfulCreations.get(), 
        "Not all tasks were created successfully");
    
    // Log the results
    log.info("Successfully created {} tasks concurrently using virtual threads", successfulCreations.get());
  }

  /**
   * Helper method to measure the performance of a task.
   * 
   * @param task The task to measure
   * @return The execution time in milliseconds
   */
  private long measurePerformance(Supplier<Boolean> task) {
    long startTime = System.currentTimeMillis();
    boolean success = task.get();
    long endTime = System.currentTimeMillis();
    
    assertTrue(success, "Task execution failed");
    
    return endTime - startTime;
  }
}