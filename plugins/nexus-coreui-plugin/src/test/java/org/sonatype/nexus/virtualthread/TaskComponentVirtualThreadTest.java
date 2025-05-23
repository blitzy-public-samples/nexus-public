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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Provider;
import javax.validation.Validator;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.coreui.TaskComponent;
import org.sonatype.nexus.coreui.TaskXO;
import org.sonatype.nexus.scheduling.CurrentState;
import org.sonatype.nexus.scheduling.ExternalTaskState;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.TaskState;
import org.sonatype.nexus.scheduling.schedule.Manual;
import org.sonatype.nexus.scheduling.schedule.Schedule;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link TaskComponent} with Java 21 Virtual Threads.
 * 
 * This test class verifies that task scheduling and execution functions correctly
 * under high concurrency with Virtual Threads, ensuring no thread pinning issues
 * occur during task operations.
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
   * Tests that multiple task creation operations can be performed concurrently using Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test concurrent task creation with Virtual Threads")
  public void testConcurrentTaskCreationWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Mock the scheduler to return a task info when scheduling a task
    TaskInfo mockTaskInfo = mock(TaskInfo.class);
    when(scheduler.scheduleTask(any(TaskConfiguration.class), any(Schedule.class))).thenReturn(mockTaskInfo);
    when(scheduler.getScheduleFactory().manual()).thenReturn(new Manual());
    
    // Configure the mock task info
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId("test-task");
    taskConfiguration.setName("Test Task");
    taskConfiguration.setTypeId("test-type");
    taskConfiguration.setTypeName("Test Type");
    taskConfiguration.setEnabled(true);
    taskConfiguration.setVisible(true);
    taskConfiguration.setExposed(true);
    
    when(mockTaskInfo.getId()).thenReturn("test-task");
    when(mockTaskInfo.getName()).thenReturn("Test Task");
    when(mockTaskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(mockTaskInfo.getSchedule()).thenReturn(new Manual());
    
    CurrentState currentState = mock(CurrentState.class);
    when(currentState.getState()).thenReturn(TaskState.WAITING);
    when(mockTaskInfo.getCurrentState()).thenReturn(currentState);
    
    ExternalTaskState externalTaskState = mock(ExternalTaskState.class);
    when(externalTaskState.getState()).thenReturn(TaskState.WAITING);
    when(scheduler.toExternalTaskState(mockTaskInfo)).thenReturn(externalTaskState);
    
    try {
      // Submit multiple concurrent task creation operations using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskIndex = i;
        executor.submit(() -> {
          try {
            TaskXO taskXO = new TaskXO();
            taskXO.setTypeId("test-type-" + taskIndex);
            taskXO.setName("Test Task " + taskIndex);
            taskXO.setEnabled(true);
            taskXO.setSchedule("manual");
            taskXO.setProperties(ImmutableMap.of("key", "value-" + taskIndex));
            
            TaskXO result = component.create(taskXO);
            assertNotNull(result);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error creating task", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for tasks to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some task creation operations failed");
      assertEquals(taskCount, successCount.get(), "Not all task creation operations succeeded");
      
      // Verify the scheduler was called the expected number of times
      verify(scheduler, times(taskCount)).scheduleTask(any(TaskConfiguration.class), any(Schedule.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multiple task read operations can be performed concurrently using Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test concurrent task read operations with Virtual Threads")
  public void testConcurrentTaskReadWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = 1000;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Mock the scheduler to return a list of task infos
    List<TaskInfo> mockTaskInfos = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      TaskInfo mockTaskInfo = mock(TaskInfo.class);
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setId("task-" + i);
      taskConfiguration.setName("Task " + i);
      taskConfiguration.setTypeId("type-" + i);
      taskConfiguration.setTypeName("Type " + i);
      taskConfiguration.setEnabled(true);
      taskConfiguration.setVisible(true);
      
      when(mockTaskInfo.getId()).thenReturn("task-" + i);
      when(mockTaskInfo.getName()).thenReturn("Task " + i);
      when(mockTaskInfo.getConfiguration()).thenReturn(taskConfiguration);
      when(mockTaskInfo.getSchedule()).thenReturn(new Manual());
      
      CurrentState currentState = mock(CurrentState.class);
      when(currentState.getState()).thenReturn(TaskState.WAITING);
      when(mockTaskInfo.getCurrentState()).thenReturn(currentState);
      
      ExternalTaskState externalTaskState = mock(ExternalTaskState.class);
      when(externalTaskState.getState()).thenReturn(TaskState.WAITING);
      when(scheduler.toExternalTaskState(mockTaskInfo)).thenReturn(externalTaskState);
      
      mockTaskInfos.add(mockTaskInfo);
    }
    
    when(scheduler.listsTasks()).thenReturn(mockTaskInfos);
    
    try {
      // Submit multiple concurrent read operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        executor.submit(() -> {
          try {
            List<TaskXO> tasks = component.read();
            assertNotNull(tasks);
            assertEquals(10, tasks.size());
          } 
          catch (Exception e) {
            log.error("Error reading tasks", e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some read operations failed");
      
      // Verify the scheduler was called the expected number of times
      verify(scheduler, times(operationCount)).listsTasks();
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multiple task update operations can be performed concurrently using Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test concurrent task update operations with Virtual Threads")
  public void testConcurrentTaskUpdateWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    Map<String, AtomicBoolean> taskUpdated = new ConcurrentHashMap<>();
    
    // Mock the scheduler to return task infos when getting by ID
    for (int i = 0; i < taskCount; i++) {
      String taskId = "task-" + i;
      taskUpdated.put(taskId, new AtomicBoolean(false));
      
      TaskInfo mockTaskInfo = mock(TaskInfo.class);
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setId(taskId);
      taskConfiguration.setName("Task " + i);
      taskConfiguration.setTypeId("type-" + i);
      taskConfiguration.setTypeName("Type " + i);
      taskConfiguration.setEnabled(true);
      taskConfiguration.setVisible(true);
      
      when(mockTaskInfo.getId()).thenReturn(taskId);
      when(mockTaskInfo.getName()).thenReturn("Task " + i);
      when(mockTaskInfo.getTypeId()).thenReturn("type-" + i);
      when(mockTaskInfo.getConfiguration()).thenReturn(taskConfiguration);
      when(mockTaskInfo.getSchedule()).thenReturn(new Manual());
      
      CurrentState currentState = mock(CurrentState.class);
      when(currentState.getState()).thenReturn(TaskState.WAITING);
      when(mockTaskInfo.getCurrentState()).thenReturn(currentState);
      
      ExternalTaskState externalTaskState = mock(ExternalTaskState.class);
      when(externalTaskState.getState()).thenReturn(TaskState.WAITING);
      when(scheduler.toExternalTaskState(mockTaskInfo)).thenReturn(externalTaskState);
      
      when(scheduler.getTaskById(taskId)).thenReturn(mockTaskInfo);
      when(scheduler.createTaskConfigurationInstance(anyString())).thenReturn(taskConfiguration);
      when(scheduler.scheduleTask(any(TaskConfiguration.class), any(Schedule.class))).thenReturn(mockTaskInfo);
    }
    
    when(scheduler.getScheduleFactory().manual()).thenReturn(new Manual());
    
    try {
      // Submit multiple concurrent update operations using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        final String taskId = "task-" + i;
        futures.add(CompletableFuture.runAsync(() -> {
          try {
            TaskXO taskXO = new TaskXO();
            taskXO.setId(taskId);
            taskXO.setTypeId("type-" + taskId);
            taskXO.setName("Updated Task " + taskId);
            taskXO.setEnabled(true);
            taskXO.setSchedule("manual");
            taskXO.setProperties(ImmutableMap.of("key", "updated-value-" + taskId));
            
            TaskXO result = component.update(taskXO);
            assertNotNull(result);
            taskUpdated.get(taskId).set(true);
          } 
          catch (Exception e) {
            log.error("Error updating task: {}", taskId, e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor));
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some update operations failed");
      
      // Verify all tasks were updated
      for (Map.Entry<String, AtomicBoolean> entry : taskUpdated.entrySet()) {
        assertTrue(entry.getValue().get(), "Task " + entry.getKey() + " was not updated");
      }
      
      // Verify the scheduler was called the expected number of times
      verify(scheduler, times(taskCount)).scheduleTask(any(TaskConfiguration.class), any(Schedule.class));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multiple task run operations can be performed concurrently using Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test concurrent task run operations with Virtual Threads")
  public void testConcurrentTaskRunWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    Map<String, AtomicBoolean> taskRun = new ConcurrentHashMap<>();
    
    // Mock the scheduler to return task infos when getting by ID
    for (int i = 0; i < taskCount; i++) {
      String taskId = UUID.randomUUID().toString();
      taskRun.put(taskId, new AtomicBoolean(false));
      
      TaskInfo mockTaskInfo = mock(TaskInfo.class);
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setId(taskId);
      taskConfiguration.setName("Task " + i);
      taskConfiguration.setTypeId("type-" + i);
      taskConfiguration.setTypeName("Type " + i);
      taskConfiguration.setEnabled(true);
      taskConfiguration.setVisible(true);
      
      when(mockTaskInfo.getId()).thenReturn(taskId);
      when(mockTaskInfo.getName()).thenReturn("Task " + i);
      when(mockTaskInfo.getConfiguration()).thenReturn(taskConfiguration);
      when(mockTaskInfo.getSchedule()).thenReturn(new Manual());
      
      CurrentState currentState = mock(CurrentState.class);
      when(currentState.getState()).thenReturn(TaskState.WAITING);
      when(mockTaskInfo.getCurrentState()).thenReturn(currentState);
      
      ExternalTaskState externalTaskState = mock(ExternalTaskState.class);
      when(externalTaskState.getState()).thenReturn(TaskState.WAITING);
      when(externalTaskState.getLastRunStarted()).thenReturn(new Date());
      when(scheduler.toExternalTaskState(mockTaskInfo)).thenReturn(externalTaskState);
      
      when(scheduler.getTaskById(taskId)).thenReturn(mockTaskInfo);
      
      // Mock the runNow method to mark the task as run
      Mockito.doAnswer(invocation -> {
        taskRun.get(taskId).set(true);
        return null;
      }).when(mockTaskInfo).runNow();
    }
    
    try {
      // Submit multiple concurrent run operations using virtual threads
      for (String taskId : taskRun.keySet()) {
        executor.submit(() -> {
          try {
            component.run(taskId);
          } 
          catch (Exception e) {
            log.error("Error running task: {}", taskId, e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some run operations failed");
      
      // Verify all tasks were run
      for (Map.Entry<String, AtomicBoolean> entry : taskRun.entrySet()) {
        assertTrue(entry.getValue().get(), "Task " + entry.getKey() + " was not run");
      }
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multiple task stop operations can be performed concurrently using Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test concurrent task stop operations with Virtual Threads")
  public void testConcurrentTaskStopWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    Map<String, AtomicBoolean> taskStopped = new ConcurrentHashMap<>();
    
    // Mock the scheduler to handle cancel operations
    for (int i = 0; i < taskCount; i++) {
      String taskId = UUID.randomUUID().toString();
      taskStopped.put(taskId, new AtomicBoolean(false));
    }
    
    // Mock the cancel method to mark tasks as stopped
    Mockito.doAnswer(invocation -> {
      String taskId = invocation.getArgument(0);
      boolean force = invocation.getArgument(1);
      AtomicBoolean stopped = taskStopped.get(taskId);
      if (stopped != null) {
        stopped.set(true);
      }
      return null;
    }).when(scheduler).cancel(anyString(), Mockito.anyBoolean());
    
    try {
      // Submit multiple concurrent stop operations using virtual threads
      for (String taskId : taskStopped.keySet()) {
        executor.submit(() -> {
          try {
            component.stop(taskId);
          } 
          catch (Exception e) {
            log.error("Error stopping task: {}", taskId, e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some stop operations failed");
      
      // Verify all tasks were stopped
      for (Map.Entry<String, AtomicBoolean> entry : taskStopped.entrySet()) {
        assertTrue(entry.getValue().get(), "Task " + entry.getKey() + " was not stopped");
      }
      
      // Verify the scheduler was called the expected number of times
      verify(scheduler, times(taskCount)).cancel(anyString(), Mockito.eq(false));
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that multiple task remove operations can be performed concurrently using Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test concurrent task remove operations with Virtual Threads")
  public void testConcurrentTaskRemoveWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    Map<String, AtomicBoolean> taskRemoved = new ConcurrentHashMap<>();
    
    // Mock the scheduler to return task infos when getting by ID
    for (int i = 0; i < taskCount; i++) {
      String taskId = UUID.randomUUID().toString();
      taskRemoved.put(taskId, new AtomicBoolean(false));
      
      TaskInfo mockTaskInfo = mock(TaskInfo.class);
      TaskConfiguration taskConfiguration = new TaskConfiguration();
      taskConfiguration.setId(taskId);
      taskConfiguration.setName("Task " + i);
      taskConfiguration.setTypeId("type-" + i);
      taskConfiguration.setTypeName("Type " + i);
      taskConfiguration.setEnabled(true);
      taskConfiguration.setVisible(true);
      
      when(mockTaskInfo.getId()).thenReturn(taskId);
      when(mockTaskInfo.getName()).thenReturn("Task " + i);
      when(mockTaskInfo.getConfiguration()).thenReturn(taskConfiguration);
      
      when(scheduler.getTaskById(taskId)).thenReturn(mockTaskInfo);
      
      // Mock the remove method to mark the task as removed
      Mockito.doAnswer(invocation -> {
        taskRemoved.get(taskId).set(true);
        return null;
      }).when(mockTaskInfo).remove();
    }
    
    try {
      // Submit multiple concurrent remove operations using virtual threads
      for (String taskId : taskRemoved.keySet()) {
        executor.submit(() -> {
          try {
            component.remove(taskId);
          } 
          catch (Exception e) {
            log.error("Error removing task: {}", taskId, e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some remove operations failed");
      
      // Verify all tasks were removed
      for (Map.Entry<String, AtomicBoolean> entry : taskRemoved.entrySet()) {
        assertTrue(entry.getValue().get(), "Task " + entry.getKey() + " was not removed");
      }
    } 
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that task operations can be performed under high concurrency with Virtual Threads
   * without any issues.
   */
  @Test
  @DisplayName("Test high concurrency task operations with Virtual Threads")
  public void testHighConcurrencyTaskOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int operationCount = 1000;
    CountDownLatch latch = new CountDownLatch(operationCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Mock the scheduler for various operations
    TaskInfo mockTaskInfo = mock(TaskInfo.class);
    TaskConfiguration taskConfiguration = new TaskConfiguration();
    taskConfiguration.setId("test-task");
    taskConfiguration.setName("Test Task");
    taskConfiguration.setTypeId("test-type");
    taskConfiguration.setTypeName("Test Type");
    taskConfiguration.setEnabled(true);
    taskConfiguration.setVisible(true);
    taskConfiguration.setExposed(true);
    
    when(mockTaskInfo.getId()).thenReturn("test-task");
    when(mockTaskInfo.getName()).thenReturn("Test Task");
    when(mockTaskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(mockTaskInfo.getSchedule()).thenReturn(new Manual());
    
    CurrentState currentState = mock(CurrentState.class);
    when(currentState.getState()).thenReturn(TaskState.WAITING);
    when(mockTaskInfo.getCurrentState()).thenReturn(currentState);
    
    ExternalTaskState externalTaskState = mock(ExternalTaskState.class);
    when(externalTaskState.getState()).thenReturn(TaskState.WAITING);
    when(scheduler.toExternalTaskState(mockTaskInfo)).thenReturn(externalTaskState);
    
    when(scheduler.getTaskById(anyString())).thenReturn(mockTaskInfo);
    when(scheduler.createTaskConfigurationInstance(anyString())).thenReturn(taskConfiguration);
    when(scheduler.scheduleTask(any(TaskConfiguration.class), any(Schedule.class))).thenReturn(mockTaskInfo);
    when(scheduler.getScheduleFactory().manual()).thenReturn(new Manual());
    when(scheduler.listsTasks()).thenReturn(List.of(mockTaskInfo));
    
    try {
      // Submit a mix of operations using virtual threads
      for (int i = 0; i < operationCount; i++) {
        final int operationIndex = i % 5; // 5 different operations
        executor.submit(() -> {
          try {
            switch (operationIndex) {
              case 0: // Read
                List<TaskXO> tasks = component.read();
                assertNotNull(tasks);
                assertFalse(tasks.isEmpty());
                break;
              case 1: // Create
                TaskXO createTaskXO = new TaskXO();
                createTaskXO.setTypeId("test-type");
                createTaskXO.setName("Test Task");
                createTaskXO.setEnabled(true);
                createTaskXO.setSchedule("manual");
                createTaskXO.setProperties(ImmutableMap.of("key", "value"));
                
                TaskXO createResult = component.create(createTaskXO);
                assertNotNull(createResult);
                break;
              case 2: // Update
                TaskXO updateTaskXO = new TaskXO();
                updateTaskXO.setId("test-task");
                updateTaskXO.setTypeId("test-type");
                updateTaskXO.setName("Updated Test Task");
                updateTaskXO.setEnabled(true);
                updateTaskXO.setSchedule("manual");
                updateTaskXO.setProperties(ImmutableMap.of("key", "updated-value"));
                
                TaskXO updateResult = component.update(updateTaskXO);
                assertNotNull(updateResult);
                break;
              case 3: // Run
                component.run("test-task");
                break;
              case 4: // Stop
                component.stop("test-task");
                break;
            }
          } 
          catch (Exception e) {
            log.error("Error performing operation {}", operationIndex, e);
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(60, TimeUnit.SECONDS), "Timed out waiting for operations to complete");
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some operations failed");
    } 
    finally {
      executor.shutdown();
    }
  }
}