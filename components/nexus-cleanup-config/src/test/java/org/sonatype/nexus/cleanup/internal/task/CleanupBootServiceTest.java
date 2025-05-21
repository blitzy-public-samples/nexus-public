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
package org.sonatype.nexus.cleanup.internal.task;

import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.schedule.Cron;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.cleanup.internal.task.CleanupBootService.CRON;
import static org.sonatype.nexus.cleanup.internal.task.CleanupBootService.TASK_NAME;

@ExtendWith(MockitoExtension.class)
public class CleanupBootServiceTest
{
  private TaskConfiguration taskConfig;

  @Mock
  private TaskScheduler taskScheduler;

  @Mock
  private ScheduleFactory scheduleFactory;

  @Mock
  private TaskInfo taskInfo;

  private CleanupBootService underTest;

  @BeforeEach
  public void setup() throws Exception {
    underTest = new CleanupBootService(taskScheduler);

    taskConfig = new TaskConfiguration();
    taskConfig.setTypeId(CleanupTaskDescriptor.TYPE_ID);
    taskConfig.setName(TASK_NAME);
    when(taskInfo.getConfiguration()).thenReturn(taskConfig);
    when(taskScheduler.listsTasks()).thenReturn(emptyList());

    when(taskScheduler.createTaskConfigurationInstance(CleanupTaskDescriptor.TYPE_ID)).thenReturn(taskConfig);
    when(taskScheduler.getScheduleFactory()).thenReturn(scheduleFactory);

    when(scheduleFactory.cron(any(), any())).thenAnswer(invokation -> {
      return new Cron((Date) invokation.getArguments()[0], (String) invokation.getArguments()[1]);
    });
  }

  @Test
  public void shouldCreateTaskOnStart() throws Exception {
    underTest.doStart();

    verify(taskScheduler).scheduleTask(any(), any());
  }

  @Test
  public void scheduleTaskDailyAt1am() throws Exception {
    underTest.doStart();

    verify(scheduleFactory, times(2)).cron(any(Date.class), eq("0 0 1 * * ?"));
  }

  @Test
  public void setTaskName() throws Exception {
    underTest.doStart();

    assertThat(taskConfig.getName()).isEqualTo(TASK_NAME);
  }

  @Test
  public void doNotCreateTaskIfAlreadyExists() throws Exception {
    when(taskScheduler.listsTasks()).thenReturn(ImmutableList.of(taskInfo));

    underTest.doStart();

    verify(taskScheduler, never()).scheduleTask(any(), any());
  }

  @Test
  public void duplicatesRemoved() {
    TaskInfo nameMismatch = mockTask("foo", CRON);
    TaskInfo cronMismatch = mockTask(TASK_NAME, "1 0 1 * * ?");
    TaskInfo scheduleMismatch = mock(TaskInfo.class);
    when(scheduleMismatch.getConfiguration()).thenReturn(taskConfig);
    when(scheduleMismatch.getName()).thenReturn(TASK_NAME);

    TaskInfo matchA = mockTask(TASK_NAME, CRON);
    TaskInfo matchB = mockTask(TASK_NAME, CRON);

    List<TaskInfo> tasks = ImmutableList.of(nameMismatch, cronMismatch, scheduleMismatch, matchA, matchB);

    when(taskScheduler.listsTasks()).thenReturn(tasks);

    underTest.doStart();

    verify(nameMismatch, never()).remove();
    verify(cronMismatch, never()).remove();
    verify(scheduleMismatch, never()).remove();
    verify(matchA, never()).remove();
    verify(matchB).remove();
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void duplicatesRemovedWithVirtualThreads() throws Exception {
    // Create multiple matching tasks that should be considered duplicates
    final int DUPLICATE_COUNT = 10;
    TaskInfo[] duplicateTasks = new TaskInfo[DUPLICATE_COUNT];
    for (int i = 0; i < DUPLICATE_COUNT; i++) {
      duplicateTasks[i] = mockTask(TASK_NAME, CRON);
    }
    
    // Add one non-matching task
    TaskInfo nonMatchingTask = mockTask("different-name", CRON);
    
    // Build the task list with all tasks
    ImmutableList.Builder<TaskInfo> tasksBuilder = ImmutableList.builder();
    tasksBuilder.add(nonMatchingTask);
    tasksBuilder.add(duplicateTasks);
    List<TaskInfo> tasks = tasksBuilder.build();
    
    when(taskScheduler.listsTasks()).thenReturn(tasks);
    
    // Track how many tasks were removed
    AtomicInteger removedCount = new AtomicInteger(0);
    for (TaskInfo task : duplicateTasks) {
      when(task.remove()).thenAnswer(invocation -> {
        removedCount.incrementAndGet();
        return null;
      });
    }
    
    underTest.doStart();
    
    // Verify that all duplicates except one were removed
    assertThat(removedCount.get()).isEqualTo(DUPLICATE_COUNT - 1);
    verify(nonMatchingTask, never()).remove();
  }

  private static TaskInfo mockTask(final String name, final String cron) {
    TaskInfo task = mock(TaskInfo.class);
    when(task.getName()).thenReturn(name);
    TaskConfiguration taskConfig = new TaskConfiguration();
    taskConfig.setName(name);
    taskConfig.setTypeId(CleanupTaskDescriptor.TYPE_ID);
    when(task.getConfiguration()).thenReturn(taskConfig);
    when(task.getSchedule()).thenReturn(new Cron(new Date(), cron));
    return task;
  }
  
  @Test
  @Tag("VirtualThreadTestGroup")
  public void concurrentVirtualThreadExecution() throws Exception {
    // Setup a scenario with multiple duplicate tasks
    final int TASK_COUNT = 100;
    final int THREAD_COUNT = 10;
    
    // Create tasks with the same name and cron schedule
    TaskInfo[] tasks = new TaskInfo[TASK_COUNT];
    for (int i = 0; i < TASK_COUNT; i++) {
      tasks[i] = mockTask(TASK_NAME, CRON);
    }
    
    when(taskScheduler.listsTasks()).thenReturn(ImmutableList.copyOf(tasks));
    
    // Setup synchronization for concurrent execution
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger removedCount = new AtomicInteger(0);
    
    // Mock the remove method to track calls
    for (TaskInfo task : tasks) {
      when(task.remove()).thenAnswer(invocation -> {
        removedCount.incrementAndGet();
        return null;
      });
    }
    
    // Create virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit multiple concurrent tasks
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all threads to be ready
            underTest.doStart(); // Execute the cleanup boot service
            completionLatch.countDown();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      boolean completed = completionLatch.await(10, TimeUnit.SECONDS);
      assertThat(completed).isTrue();
      
      // Verify that exactly TASK_COUNT-1 tasks were removed (keeping only one)
      assertThat(removedCount.get()).isEqualTo(TASK_COUNT - 1);
    }
  }
}
