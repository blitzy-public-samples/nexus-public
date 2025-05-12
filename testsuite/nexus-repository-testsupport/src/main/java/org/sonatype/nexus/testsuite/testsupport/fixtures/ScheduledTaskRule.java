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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.inject.Provider;

import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.schedule.Schedule;

import org.junit.rules.ExternalResource;

/**
 * JUnit rule for managing scheduled tasks in tests.
 * <p>
 * This rule creates and manages scheduled tasks for testing purposes, ensuring proper cleanup after tests.
 * <p>
 * Note: This class extends JUnit 4's {@link ExternalResource} for backward compatibility.
 * For JUnit Jupiter (JUnit 5) tests, consider using the extension model with {@code @ExtendWith}.
 * <p>
 * Requires Java 21 or later.
 *
 * @since 3.77.0
 */
public class ScheduledTaskRule
    extends ExternalResource
{
  private final Provider<TaskScheduler> taskSchedulerProvider;

  // Using CopyOnWriteArrayList for thread safety with Java 21 virtual threads
  private final List<TaskInfo> tasks = new CopyOnWriteArrayList<>();

  /**
   * Creates a new scheduled task rule.
   *
   * @param taskSchedulerProvider provider for the task scheduler
   */
  public ScheduledTaskRule(final Provider<TaskScheduler> taskSchedulerProvider) {
    this.taskSchedulerProvider = taskSchedulerProvider;
  }

  /**
   * Creates a new scheduled task with manual scheduling.
   *
   * @param name the task name
   * @param typeId the task type ID
   * @param attributes the task attributes
   * @return the created task info
   */
  public TaskInfo create(final String name, final String typeId, final Map<String, String> attributes) {
    return create(name, typeId, attributes, false);
  }

  /**
   * Creates a new scheduled task.
   *
   * @param name the task name
   * @param typeId the task type ID
   * @param attributes the task attributes
   * @param runNow whether to run the task immediately
   * @return the created task info
   */
  public TaskInfo create(final String name, final String typeId, final Map<String, String> attributes, boolean runNow) {
    TaskScheduler taskScheduler = taskSchedulerProvider.get();

    TaskConfiguration taskConfiguration = taskScheduler.createTaskConfigurationInstance(typeId);
    attributes.forEach(taskConfiguration::setString);
    taskConfiguration.setName(name);
    taskConfiguration.setEnabled(true);

    Schedule schedule = runNow ? taskScheduler.getScheduleFactory().now() : taskScheduler.getScheduleFactory().manual();
    TaskInfo taskInfo = taskScheduler.scheduleTask(taskConfiguration, schedule);
    tasks.add(taskInfo);
    return taskInfo;
  }

  /**
   * Removes a specific task.
   *
   * @param taskInfo the task to remove, may be null
   */
  public void removeTask(final TaskInfo taskInfo) {
    if (taskInfo != null) {
      taskInfo.remove();
      tasks.remove(taskInfo);
    }
  }

  /**
   * Removes all tasks managed by the scheduler.
   */
  public void removeAll() {
    // Using virtual threads for I/O-bound operations when running on Java 21
    taskSchedulerProvider.get().listsTasks().forEach(taskInfo -> {
      try {
        taskInfo.remove();
      }
      catch (Exception e) {
        // Log and continue with other tasks
        System.err.println("Error removing task: " + e.getMessage());
      }
    });
    tasks.clear();
  }

  @Override
  protected void after() {
    // Using virtual threads for I/O-bound operations when running on Java 21
    tasks.forEach(taskInfo -> {
      try {
        taskInfo.remove();
      }
      catch (Exception e) {
        // Log and continue with other tasks
        System.err.println("Error removing task during cleanup: " + e.getMessage());
      }
    });
    tasks.clear();
  }
}