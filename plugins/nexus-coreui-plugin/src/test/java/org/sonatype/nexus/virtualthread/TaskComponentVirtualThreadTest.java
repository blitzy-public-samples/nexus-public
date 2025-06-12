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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.inject.Provider;
import jakarta.validation.Validator;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.coreui.TaskComponent;
import org.sonatype.nexus.coreui.TaskXO;
import org.sonatype.nexus.scheduling.*;
import org.sonatype.nexus.scheduling.schedule.Manual;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link TaskComponent} with Java 21 Virtual Threads to ensure it works correctly
 * under high concurrency with the new threading model.
 */
@ExtendWith(MockitoExtension.class)
public class TaskComponentVirtualThreadTest
    extends TestSupport
{
  private TaskComponent component;

  @Mock
  private TaskScheduler scheduler;

  @Mock
  private TaskFactory taskFactory;

  @Mock
  private ScheduleFactory scheduleFactory;

  @Mock
  private Validator validator;

  @Captor
  private ArgumentCaptor<TaskConfiguration> taskConfigurationCaptor;

  private final Provider<Validator> validatorProvider = () -> validator;

  @BeforeEach
  public void setUp() {
    when(scheduler.getTaskFactory()).thenReturn(taskFactory);
    when(scheduler.getScheduleFactory()).thenReturn(scheduleFactory);
    when(scheduleFactory.manual()).thenReturn(new Manual());

    component = new TaskComponent(scheduler, validatorProvider, false);
  }

  /**
   * Test that task creation works correctly with Virtual Threads.
   * This test simulates multiple concurrent task creation operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskCreationWithVirtualThreads() throws Exception {
    // Set up mock task descriptors
    List<TaskDescriptor> descriptors = new ArrayList<>();
    TaskDescriptor descriptor = mock(TaskDescriptor.class);
    when(descriptor.getId()).thenReturn("test-task");
    when(descriptor.getName()).thenReturn("Test Task");
    when(descriptor.isExposed()).thenReturn(true);
    descriptors.add(descriptor);
    when(taskFactory.getDescriptors()).thenReturn(descriptors);

    // Set up mock task configuration
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId("test-task-id");
    taskConfiguration.setName("Test Task");
    taskConfiguration.setTypeId("test-task");
    taskConfiguration.setExposed(true);

    // Set up mock task info
    TaskInfo taskInfo = mock(TaskInfo.class);
    when(taskInfo.getId()).thenReturn("test-task-id");
    when(taskInfo.getName()).thenReturn("Test Task");
    when(taskInfo.getTypeId()).thenReturn("test-task");
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    CurrentState currentState = mock(CurrentState.class);
    when(currentState.getState()).thenReturn(TaskState.WAITING);
    when(taskInfo.getCurrentState()).thenReturn(currentState);
    when(scheduler.scheduleTask(any(TaskConfiguration.class), any(Schedule.class))).thenReturn(taskInfo);

    // Set up mock external task state
    ExternalTaskState externalTaskState = mock(ExternalTaskState.class);
    when(externalTaskState.getState()).thenReturn(TaskState.WAITING);
    when(scheduler.toExternalTaskState(any(TaskInfo.class))).thenReturn(externalTaskState);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent task creation operations
      int taskCount = 100;
      CountDownLatch latch = new CountDownLatch(taskCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      Map<String, TaskXO> createdTasks = new ConcurrentHashMap<>();

      // Submit concurrent task creation operations using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a task with unique name
            TaskXO taskXO = new TaskXO();
            taskXO.setTypeId("test-task");
            taskXO.setName("Test Task " + index);
            taskXO.setEnabled(true);
            taskXO.setSchedule("manual");

            // Create the task
            TaskXO result = component.create(taskXO);
            createdTasks.put(result.getId(), result);
          } 
          catch (Exception e) {
            logger.error("Error creating task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All tasks should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task creation", errorCount.get(), is(0));
      verify(scheduler, times(taskCount)).scheduleTask(any(TaskConfiguration.class), any(Schedule.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task state validation works correctly with Virtual Threads.
   * This test simulates multiple concurrent task validation operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskValidationWithVirtualThreads() throws Exception {
    // Set up mock task info for running task
    TaskInfo runningTaskInfo = mock(TaskInfo.class);
    CurrentState runningState = mock(CurrentState.class);
    when(runningState.getState()).thenReturn(TaskState.RUNNING);
    when(runningTaskInfo.getId()).thenReturn("running-task-id");
    when(runningTaskInfo.getCurrentState()).thenReturn(runningState);
    ExternalTaskState runningExternalState = mock(ExternalTaskState.class);
    when(runningExternalState.getState()).thenReturn(TaskState.RUNNING);

    // Set up mock task info for waiting task
    TaskInfo waitingTaskInfo = mock(TaskInfo.class);
    CurrentState waitingState = mock(CurrentState.class);
    when(waitingState.getState()).thenReturn(TaskState.WAITING);
    when(waitingTaskInfo.getId()).thenReturn("waiting-task-id");
    when(waitingTaskInfo.getCurrentState()).thenReturn(waitingState);
    ExternalTaskState waitingExternalState = mock(ExternalTaskState.class);
    when(waitingExternalState.getState()).thenReturn(TaskState.WAITING);

    // Configure scheduler to return appropriate task info and state based on task ID
    lenient().when(scheduler.getTaskById("running-task-id")).thenReturn(runningTaskInfo);
    lenient().when(scheduler.toExternalTaskState(runningTaskInfo)).thenReturn(runningExternalState);
    lenient().when(scheduler.getTaskById("waiting-task-id")).thenReturn(waitingTaskInfo);
    lenient().when(scheduler.toExternalTaskState(waitingTaskInfo)).thenReturn(waitingExternalState);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent validation operations
      int operationCount = 100;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger runningTaskExceptionCount = new AtomicInteger(0);
      AtomicInteger waitingTaskSuccessCount = new AtomicInteger(0);

      // Submit concurrent validation operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between running and waiting tasks
            String taskId = (index % 2 == 0) ? "running-task-id" : "waiting-task-id";
            TaskInfo taskInfo = scheduler.getTaskById(taskId);

            try {
              // Validate task state
              component.validateState(taskId, taskInfo);
              // If we get here, it should be a waiting task
              if ("waiting-task-id".equals(taskId)) {
                waitingTaskSuccessCount.incrementAndGet();
              }
            } 
            catch (IllegalStateException e) {
              // Running tasks should throw an exception
              if ("running-task-id".equals(taskId)) {
                runningTaskExceptionCount.incrementAndGet();
              }
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("Running tasks should throw exceptions", runningTaskExceptionCount.get(), is(operationCount / 2));
      assertThat("Waiting tasks should succeed", waitingTaskSuccessCount.get(), is(operationCount / 2));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task reading works correctly with Virtual Threads.
   * This test simulates multiple concurrent task read operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskReadingWithVirtualThreads() throws Exception {
    // Set up mock task list
    List<TaskInfo> taskInfoList = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      TaskInfo taskInfo = mock(TaskInfo.class);
      TaskConfiguration config = new TaskConfiguration();
      config.setId("task-" + i);
      config.setName("Task " + i);
      config.setTypeId("test-task");
      config.setVisible(true);
      
      CurrentState state = mock(CurrentState.class);
      when(state.getState()).thenReturn(TaskState.WAITING);
      
      when(taskInfo.getId()).thenReturn("task-" + i);
      when(taskInfo.getName()).thenReturn("Task " + i);
      when(taskInfo.getTypeId()).thenReturn("test-task");
      when(taskInfo.getConfiguration()).thenReturn(config);
      when(taskInfo.getCurrentState()).thenReturn(state);
      
      ExternalTaskState externalState = mock(ExternalTaskState.class);
      when(externalState.getState()).thenReturn(TaskState.WAITING);
      when(externalState.getLastEndState()).thenReturn(TaskState.OK);
      when(externalState.getLastRunStarted()).thenReturn(new Date());
      when(externalState.getLastRunDuration()).thenReturn(100L);
      
      when(scheduler.toExternalTaskState(taskInfo)).thenReturn(externalState);
      
      taskInfoList.add(taskInfo);
    }
    
    when(scheduler.listsTasks()).thenReturn(taskInfoList);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent read operations
      int operationCount = 100;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicBoolean hasErrors = new AtomicBoolean(false);
      List<List<TaskXO>> results = new ArrayList<>(operationCount);

      // Submit concurrent read operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Read tasks
            List<TaskXO> tasks = component.read();
            synchronized (results) {
              results.add(tasks);
            }
          } 
          catch (Exception e) {
            logger.error("Error reading tasks", e);
            hasErrors.set(true);
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task reading", hasErrors.get(), is(false));
      assertThat("Should have results for all operations", results, hasSize(operationCount));
      
      // Verify that all results are consistent
      for (List<TaskXO> taskList : results) {
        assertThat("Task list should have 10 tasks", taskList, hasSize(10));
      }
      
      // Verify the scheduler was called the expected number of times
      verify(scheduler, times(operationCount)).listsTasks();
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task updating works correctly with Virtual Threads.
   * This test simulates multiple concurrent task update operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskUpdatingWithVirtualThreads() throws Exception {
    // Set up mock task info
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId("test-task-id");
    taskConfiguration.setName("Test Task");
    taskConfiguration.setTypeId("test-task");
    
    CurrentState currentState = mock(CurrentState.class);
    when(currentState.getState()).thenReturn(TaskState.WAITING);
    
    when(taskInfo.getId()).thenReturn("test-task-id");
    when(taskInfo.getName()).thenReturn("Test Task");
    when(taskInfo.getTypeId()).thenReturn("test-task");
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(taskInfo.getCurrentState()).thenReturn(currentState);
    
    ExternalTaskState externalState = mock(ExternalTaskState.class);
    when(externalState.getState()).thenReturn(TaskState.WAITING);
    
    when(scheduler.getTaskById("test-task-id")).thenReturn(taskInfo);
    when(scheduler.toExternalTaskState(taskInfo)).thenReturn(externalState);
    when(scheduler.scheduleTask(any(TaskConfiguration.class), any(Schedule.class))).thenReturn(taskInfo);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent update operations
      int operationCount = 50;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Submit concurrent update operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create task update
            TaskXO taskXO = new TaskXO();
            taskXO.setId("test-task-id");
            taskXO.setTypeId("test-task");
            taskXO.setName("Updated Task " + index);
            taskXO.setEnabled(true);
            taskXO.setSchedule("manual");

            // Update the task
            TaskXO result = component.update(taskXO);
            assertThat(result, notNullValue());
            assertEquals("test-task-id", result.getId());
          } 
          catch (Exception e) {
            logger.error("Error updating task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task updating", errorCount.get(), is(0));
      verify(scheduler, times(operationCount)).scheduleTask(any(TaskConfiguration.class), any(Schedule.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task removal works correctly with Virtual Threads.
   * This test simulates multiple concurrent task removal operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskRemovalWithVirtualThreads() throws Exception {
    // Set up mock task info
    TaskInfo taskInfo = mock(TaskInfo.class);
    when(scheduler.getTaskById("test-task-id")).thenReturn(taskInfo);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent removal operations
      int operationCount = 50;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Submit concurrent removal operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Remove the task
            component.remove("test-task-id");
          } 
          catch (Exception e) {
            logger.error("Error removing task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task removal", errorCount.get(), is(0));
      verify(scheduler, times(operationCount)).getTaskById("test-task-id");
      verify(taskInfo, times(operationCount)).remove();
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task running works correctly with Virtual Threads.
   * This test simulates multiple concurrent task run operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskRunningWithVirtualThreads() throws Exception {
    // Set up mock task info
    TaskInfo taskInfo = mock(TaskInfo.class);
    when(scheduler.getTaskById("test-task-id")).thenReturn(taskInfo);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent run operations
      int operationCount = 50;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Submit concurrent run operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Run the task
            component.run("test-task-id");
          } 
          catch (Exception e) {
            logger.error("Error running task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task running", errorCount.get(), is(0));
      verify(scheduler, times(operationCount)).getTaskById("test-task-id");
      verify(taskInfo, times(operationCount)).runNow();
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task stopping works correctly with Virtual Threads.
   * This test simulates multiple concurrent task stop operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskStoppingWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent stop operations
      int operationCount = 50;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Submit concurrent stop operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Stop the task
            component.stop("test-task-id");
          } 
          catch (Exception e) {
            logger.error("Error stopping task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task stopping", errorCount.get(), is(0));
      verify(scheduler, times(operationCount)).cancel("test-task-id", false);
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that task type reading works correctly with Virtual Threads.
   * This test simulates multiple concurrent task type read operations using Virtual Threads.
   */
  @Test
  public void testConcurrentTaskTypeReadingWithVirtualThreads() throws Exception {
    // Set up mock task descriptors
    List<TaskDescriptor> descriptors = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      TaskDescriptor descriptor = mock(TaskDescriptor.class);
      when(descriptor.getId()).thenReturn("task-type-" + i);
      when(descriptor.getName()).thenReturn("Task Type " + i);
      when(descriptor.isExposed()).thenReturn(true);
      descriptors.add(descriptor);
    }
    when(taskFactory.getDescriptors()).thenReturn(descriptors);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent read operations
      int operationCount = 100;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicBoolean hasErrors = new AtomicBoolean(false);

      // Submit concurrent read operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            // Read task types
            var taskTypes = component.readTypes();
            assertThat(taskTypes, hasSize(5));
          } 
          catch (Exception e) {
            logger.error("Error reading task types", e);
            hasErrors.set(true);
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("No errors should occur during task type reading", hasErrors.get(), is(false));
      verify(taskFactory, times(operationCount)).getDescriptors();
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test that script update validation works correctly with Virtual Threads.
   * This test simulates multiple concurrent script validation operations using Virtual Threads.
   */
  @Test
  public void testConcurrentScriptUpdateValidationWithVirtualThreads() throws Exception {
    // Set up mock task info for script task
    TaskInfo scriptTaskInfo = mock(TaskInfo.class);
    TaskConfiguration scriptConfig = new TaskConfiguration();
    scriptConfig.setString("source", "println 'hello'");
    when(scriptTaskInfo.getTypeId()).thenReturn("script");
    when(scriptTaskInfo.getConfiguration()).thenReturn(scriptConfig);

    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Number of concurrent validation operations
      int operationCount = 100;
      CountDownLatch latch = new CountDownLatch(operationCount);
      AtomicInteger noChangeSuccessCount = new AtomicInteger(0);
      AtomicInteger changeFailureCount = new AtomicInteger(0);

      // Submit concurrent validation operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create task update with alternating script changes
            TaskXO taskXO = new TaskXO();
            
            if (index % 2 == 0) {
              // No change to script source
              taskXO.setProperties(Map.of("source", "println 'hello'"));
              
              // Should succeed
              assertDoesNotThrow(() -> component.validateScriptUpdate(scriptTaskInfo, taskXO));
              noChangeSuccessCount.incrementAndGet();
            } 
            else {
              // Change to script source
              taskXO.setProperties(Map.of("source", "println 'hello world'"));
              
              // Should fail
              assertThrows(IllegalStateException.class, 
                  () -> component.validateScriptUpdate(scriptTaskInfo, taskXO));
              changeFailureCount.incrementAndGet();
            }
          } 
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all operations to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);

      // Verify results
      assertThat("All operations should complete within timeout", completed, is(true));
      assertThat("Operations with no script change should succeed", 
          noChangeSuccessCount.get(), equalTo(operationCount / 2));
      assertThat("Operations with script change should fail", 
          changeFailureCount.get(), equalTo(operationCount / 2));
    } 
    finally {
      executor.shutdown();
    }
  }
}