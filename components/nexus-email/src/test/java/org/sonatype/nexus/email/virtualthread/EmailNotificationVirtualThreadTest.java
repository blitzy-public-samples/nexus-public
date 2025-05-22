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
package org.sonatype.nexus.email.virtualthread;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.email.EmailManager;
import org.sonatype.nexus.internal.scheduling.NexusTaskNotificationEmailSender;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskNotificationCondition;
import org.sonatype.nexus.scheduling.TaskNotificationMessageGenerator;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedDone;
import org.sonatype.nexus.scheduling.events.TaskEventStoppedFailed;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link NexusTaskNotificationEmailSender} with Java 21 Virtual Threads.
 * 
 * Validates that task notifications function correctly with the Virtual Thread execution model,
 * even when many concurrent task completion notifications trigger emails.
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationVirtualThreadTest
    extends TestSupport
{
  @Mock
  private EmailManager emailManager;

  @Mock
  private TaskNotificationMessageGenerator defaultTaskNotificationMessageGenerator;

  @Mock
  private TaskNotificationMessageGenerator customTaskNotificationMessageGenerator;
  
  @Captor
  private ArgumentCaptor<Email> emailCaptor;

  private NexusTaskNotificationEmailSender underTest;

  @BeforeEach
  void setup() {
    Map<String, TaskNotificationMessageGenerator> taskNotificationMessageGenerators = new HashMap<>();
    taskNotificationMessageGenerators.put("DEFAULT", defaultTaskNotificationMessageGenerator);
    taskNotificationMessageGenerators.put("CUSTOM", customTaskNotificationMessageGenerator);

    when(defaultTaskNotificationMessageGenerator.completed(isNotNull())).thenReturn("completed message");
    when(defaultTaskNotificationMessageGenerator.failed(isNotNull(), isNotNull())).thenReturn("failure message");
    when(emailManager.constructMessage("completed message")).thenReturn("completed message");
    when(emailManager.constructMessage("failure message")).thenReturn("failure message");

    underTest = new NexusTaskNotificationEmailSender(() -> emailManager, taskNotificationMessageGenerators);
  }

  @Test
  void generatesEmailIfTaskFailed() throws EmailException {
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
    when(taskInfo.getId()).thenReturn("taskId");
    when(taskInfo.getName()).thenReturn("test task");
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(taskConfiguration.getAlertEmail()).thenReturn("foo@example.com");
    TaskEventStoppedFailed event = new TaskEventStoppedFailed(taskInfo, new RuntimeException());

    underTest.on(event);

    verify(emailManager).send(isNotNull());
  }

  @Test
  void generatesNoEmailIfTaskCompletesAndConfigurationConditionIsFailedOnly() throws EmailException {
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
    when(taskInfo.getId()).thenReturn("taskId");
    when(taskInfo.getName()).thenReturn("test task");
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(taskConfiguration.getAlertEmail()).thenReturn("foo@example.com");
    when(taskConfiguration.getNotificationCondition()).thenReturn(TaskNotificationCondition.FAILURE);
    TaskEventStoppedDone event = new TaskEventStoppedDone(taskInfo);

    underTest.on(event);

    verify(emailManager, never()).send(isNotNull());
  }

  @Test
  void generatesEmailIfTaskCompletesAndConfigurationConditionIsCompleted() throws EmailException {
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
    when(taskInfo.getId()).thenReturn("taskId");
    when(taskInfo.getName()).thenReturn("test task");
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(taskConfiguration.getAlertEmail()).thenReturn("foo@example.com");
    when(taskConfiguration.getNotificationCondition()).thenReturn(TaskNotificationCondition.SUCCESS_FAILURE);
    TaskEventStoppedDone event = new TaskEventStoppedDone(taskInfo);

    underTest.on(event);

    verify(emailManager).send(isNotNull());
  }

  @Test
  void usesCustomMessageGeneratorIfAvailableForTaskType() throws EmailException {
    TaskInfo taskInfo = mock(TaskInfo.class);
    TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
    when(taskInfo.getId()).thenReturn("taskId");
    when(taskInfo.getTypeId()).thenReturn("CUSTOM");
    when(taskInfo.getName()).thenReturn("test task");
    when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
    when(taskConfiguration.getAlertEmail()).thenReturn("foo@example.com");
    when(taskConfiguration.getNotificationCondition()).thenReturn(TaskNotificationCondition.SUCCESS_FAILURE);
    when(customTaskNotificationMessageGenerator.completed(isNotNull())).thenReturn("custom body");
    when(emailManager.constructMessage("custom body")).thenReturn("custom body");
    TaskEventStoppedDone event = new TaskEventStoppedDone(taskInfo);

    underTest.on(event);

    verify(customTaskNotificationMessageGenerator).completed(isNotNull());
    verify(emailManager).send(isNotNull());
  }
  
  @Test
  void handlesConcurrentTaskNotificationsWithVirtualThreads() throws Exception {
    // Use Virtual Threads for concurrent task notifications
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger emailCount = new AtomicInteger(0);
    
    // Configure email manager to count emails
    try {
      when(emailManager.send(any(Email.class))).thenAnswer(invocation -> {
        emailCount.incrementAndGet();
        return null;
      });
      
      // Submit multiple concurrent task notifications using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a task that will generate a notification email
            TaskInfo taskInfo = mock(TaskInfo.class);
            TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
            
            when(taskInfo.getId()).thenReturn("task-" + taskId);
            when(taskInfo.getName()).thenReturn("Virtual Thread Test Task " + taskId);
            when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
            when(taskConfiguration.getAlertEmail()).thenReturn("test-" + taskId + "@example.com");
            when(taskConfiguration.getNotificationCondition()).thenReturn(TaskNotificationCondition.SUCCESS_FAILURE);
            
            // Create and process a task completion event
            TaskEventStoppedDone event = new TaskEventStoppedDone(taskInfo);
            underTest.on(event);
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete within timeout", completed, is(true));
      
      // Verify all emails were sent
      verify(emailManager, times(taskCount)).send(emailCaptor.capture());
      assertThat(emailCount.get(), is(taskCount));
      
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  void handlesFailedTaskNotificationsWithVirtualThreads() throws Exception {
    // Use Virtual Threads for concurrent task notifications
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger emailCount = new AtomicInteger(0);
    
    // Configure email manager to count emails
    try {
      when(emailManager.send(any(Email.class))).thenAnswer(invocation -> {
        emailCount.incrementAndGet();
        return null;
      });
      
      // Submit multiple concurrent task notifications using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[taskCount];
      
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a task that will generate a notification email
            TaskInfo taskInfo = mock(TaskInfo.class);
            TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
            
            when(taskInfo.getId()).thenReturn("failed-task-" + taskId);
            when(taskInfo.getName()).thenReturn("Failed Virtual Thread Test Task " + taskId);
            when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
            when(taskConfiguration.getAlertEmail()).thenReturn("failed-test-" + taskId + "@example.com");
            
            // Create and process a task failure event
            Exception taskException = new RuntimeException("Task failed: " + taskId);
            TaskEventStoppedFailed event = new TaskEventStoppedFailed(taskInfo, taskException);
            underTest.on(event);
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete within timeout", completed, is(true));
      
      // Verify all emails were sent
      verify(emailManager, times(taskCount)).send(emailCaptor.capture());
      assertThat(emailCount.get(), is(taskCount));
      
    } finally {
      executor.shutdown();
    }
  }
  
  @Test
  void handlesMixedTaskNotificationsWithVirtualThreads() throws Exception {
    // Use Virtual Threads for concurrent task notifications
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int successTaskCount = 30;
    int failureTaskCount = 20;
    int totalTaskCount = successTaskCount + failureTaskCount;
    CountDownLatch latch = new CountDownLatch(totalTaskCount);
    AtomicInteger emailCount = new AtomicInteger(0);
    
    // Configure email manager to count emails
    try {
      when(emailManager.send(any(Email.class))).thenAnswer(invocation -> {
        emailCount.incrementAndGet();
        return null;
      });
      
      // Submit multiple concurrent task notifications using virtual threads
      CompletableFuture<?>[] futures = new CompletableFuture[totalTaskCount];
      
      // Create success tasks
      for (int i = 0; i < successTaskCount; i++) {
        final int taskId = i;
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a task that will generate a notification email
            TaskInfo taskInfo = mock(TaskInfo.class);
            TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
            
            when(taskInfo.getId()).thenReturn("success-task-" + taskId);
            when(taskInfo.getName()).thenReturn("Success Task " + taskId);
            when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
            when(taskConfiguration.getAlertEmail()).thenReturn("success-" + taskId + "@example.com");
            when(taskConfiguration.getNotificationCondition()).thenReturn(TaskNotificationCondition.SUCCESS_FAILURE);
            
            // Create and process a task completion event
            TaskEventStoppedDone event = new TaskEventStoppedDone(taskInfo);
            underTest.on(event);
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Create failure tasks
      for (int i = 0; i < failureTaskCount; i++) {
        final int taskId = i;
        futures[successTaskCount + i] = CompletableFuture.runAsync(() -> {
          try {
            // Create a task that will generate a notification email
            TaskInfo taskInfo = mock(TaskInfo.class);
            TaskConfiguration taskConfiguration = mock(TaskConfiguration.class);
            
            when(taskInfo.getId()).thenReturn("failure-task-" + taskId);
            when(taskInfo.getName()).thenReturn("Failure Task " + taskId);
            when(taskInfo.getConfiguration()).thenReturn(taskConfiguration);
            when(taskConfiguration.getAlertEmail()).thenReturn("failure-" + taskId + "@example.com");
            
            // Create and process a task failure event
            Exception taskException = new RuntimeException("Task failed: " + taskId);
            TaskEventStoppedFailed event = new TaskEventStoppedFailed(taskInfo, taskException);
            underTest.on(event);
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertThat("All tasks should complete within timeout", completed, is(true));
      
      // Verify all emails were sent
      verify(emailManager, times(totalTaskCount)).send(emailCaptor.capture());
      assertThat(emailCount.get(), is(totalTaskCount));
      
    } finally {
      executor.shutdown();
    }
  }
}