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
package org.sonatype.nexus.scheduling;

import java.util.Date;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.scheduling.schedule.Manual;
import org.sonatype.nexus.scheduling.schedule.Now;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link TaskSchedulerImpl}.
 *
 * @since 3.60
 */
public class TaskSchedulerImplTest
    extends TestSupport
{
  @Mock
  private EventManager eventManager;

  @Mock
  private TaskFactory taskFactory;

  @Mock
  private SchedulerSPI schedulerSPI;

  private TaskSchedulerImpl underTest;

  @Before
  public void setUp() throws Exception {
    ScheduleFactory scheduleFactory = mock(ScheduleFactory.class);
    when(scheduleFactory.now()).thenReturn(new Now());
    when(scheduleFactory.manual()).thenReturn(new Manual());

    when(schedulerSPI.scheduleFactory()).thenReturn(scheduleFactory);
    when(schedulerSPI.scheduleTask(any(), any())).thenAnswer(invocation -> {
      TaskConfiguration config = invocation.getArgument(0);
      Schedule schedule = invocation.getArgument(1);
      TaskInfo taskInfo = mock(TaskInfo.class);
      when(taskInfo.getConfiguration()).thenReturn(config);
      when(taskInfo.getSchedule()).thenReturn(schedule);
      CurrentState currentState = mock(CurrentState.class);
      when(currentState.getState()).thenReturn(TaskState.WAITING);
      when(currentState.getRunStarted()).thenReturn(new Date());
      when(taskInfo.getCurrentState()).thenReturn(currentState);
      return taskInfo;
    });

    underTest = new TaskSchedulerImpl(eventManager, taskFactory, () -> schedulerSPI);
    underTest.useVirtualThreads = true;
    underTest.changeRepoBlobstoreTaskEnabled = true;
  }

  @Test
  public void shouldUseVirtualThreadsForIOBoundTasks() {
    // I/O-bound tasks should use virtual threads
    assertThat(underTest.shouldUseVirtualThreads("repository.docker.upload-purge"), is(true));
    assertThat(underTest.shouldUseVirtualThreads("blobstore.compact"), is(true));
    assertThat(underTest.shouldUseVirtualThreads("repository.move"), is(true));
  }

  @Test
  public void shouldUsePlatformThreadsForCPUBoundTasks() {
    // CPU-bound tasks should use platform threads
    assertThat(underTest.shouldUseVirtualThreads("repository.vulnerability.assessment"), is(false));
    assertThat(underTest.shouldUseVirtualThreads("analytics.compute"), is(false));
  }

  @Test
  public void shouldDefaultToSafeThreadTypeForUnknownTasks() {
    // Unknown tasks should default to platform threads for safety
    assertThat(underTest.shouldUseVirtualThreads("unknown.task.type"), is(false));
  }

  @Test
  public void shouldRespectGlobalVirtualThreadSetting() {
    // When virtual threads are disabled globally, all tasks should use platform threads
    underTest.useVirtualThreads = false;
    assertThat(underTest.shouldUseVirtualThreads("repository.docker.upload-purge"), is(false));
    assertThat(underTest.shouldUseVirtualThreads("blobstore.compact"), is(false));
  }

  @Test
  public void shouldSetThreadTypeInTaskConfiguration() {
    // Setup a task descriptor
    TaskDescriptor descriptor = mock(TaskDescriptor.class);
    when(descriptor.getId()).thenReturn("repository.docker.upload-purge");
    when(descriptor.getName()).thenReturn("Docker Upload Purge");
    when(descriptor.isVisible()).thenReturn(true);
    when(descriptor.isRecoverable()).thenReturn(true);
    when(descriptor.isExposed()).thenReturn(true);
    
    TaskConfiguration config = new TaskConfiguration();
    when(descriptor.createTaskConfiguration()).thenReturn(config);
    when(taskFactory.findDescriptor("repository.docker.upload-purge")).thenReturn(descriptor);

    // Create a task configuration
    TaskConfiguration taskConfig = underTest.createTaskConfigurationInstance("repository.docker.upload-purge");
    
    // Verify thread type is set correctly
    assertThat(taskConfig, notNullValue());
    assertThat(taskConfig.getString("threadType"), is("virtual"));
  }

  @Test
  public void shouldScheduleTaskWithThreadTypeSet() {
    // Create a task configuration
    TaskConfiguration config = new TaskConfiguration();
    config.setId("test-task");
    config.setName("Test Task");
    config.setTypeId("repository.docker.upload-purge");
    
    // Schedule the task
    Schedule schedule = new Manual();
    TaskInfo taskInfo = underTest.scheduleTask(config, schedule);
    
    // Verify thread type was set during scheduling
    assertThat(taskInfo, notNullValue());
    assertThat(taskInfo.getConfiguration().getString("threadType"), is("virtual"));
  }

  @Test
  public void shouldRespectExistingThreadTypeInConfiguration() {
    // Create a task configuration with thread type already set
    TaskConfiguration config = new TaskConfiguration();
    config.setId("test-task");
    config.setName("Test Task");
    config.setTypeId("repository.docker.upload-purge"); // Would normally use virtual threads
    config.setString("threadType", "platform"); // But we explicitly set platform
    
    // Schedule the task
    Schedule schedule = new Manual();
    TaskInfo taskInfo = underTest.scheduleTask(config, schedule);
    
    // Verify the existing thread type was respected
    assertThat(taskInfo, notNullValue());
    assertThat(taskInfo.getConfiguration().getString("threadType"), is("platform"));
  }
}