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
package org.sonatype.nexus.scheduling.spi;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.schedule.Schedule;
import org.sonatype.nexus.scheduling.schedule.ScheduleFactory;

/**
 * The underlying scheduler that provides scheduling.
 *
 * @since 3.0
 */
public interface SchedulerSPI
    extends Lifecycle
{
  /**
   * Thread type to use for task execution.
   * 
   * @since 3.60
   */
  enum ThreadType {
    /**
     * Traditional platform thread (heavyweight).
     */
    PLATFORM,
    
    /**
     * Java 21 virtual thread (lightweight).
     */
    VIRTUAL
  }
  
  /**
   * Returns the SPI specific {@link ScheduleFactory}.
   */
  ScheduleFactory scheduleFactory();

  /**
   * Returns status message.
   */
  String renderStatusMessage();

  /**
   * Returns verbose detail message.
   */
  String renderDetailMessage();

  /**
   * Pause the scheduler.
   */
  void pause();

  /**
   * Resume the scheduler.
   */
  void resume();

  /**
   * Returns the task for the given identifier; or null if missing.
   */
  @Nullable
  TaskInfo getTaskById(String id);

  /**
   * Returns a list of all tasks which have been scheduled.
   */
  List<TaskInfo> listsTasks();

  /**
   * Returns description of triggers that were recovered after an error caused them to be lost
   *
   * @since 3.17
   */
  List<String> getMissingTriggerDescriptions();

  /**
   * Schedule a task with the given scheduler.
   *
   * If a task already exists with the same task identifier, the task will be updated.
   *
   * Task must not be running.
   * 
   * The scheduler will determine the appropriate thread type (platform or virtual) based on the task configuration
   * and system settings. Tasks that are I/O-bound and compatible with virtual threads will benefit from improved
   * concurrency and reduced resource usage when virtual threads are enabled.
   */
  TaskInfo scheduleTask(TaskConfiguration config, Schedule schedule);

  /**
   * Returns the count of currently running tasks.
   */
  int getRunningTaskCount();

  /**
   * Returns the count of tasks executed so far.
   *
   * @since 3.7
   */
  int getExecutedTaskCount();

  /**
   * Attempts to cancel execution of the task ({@code id}).  This attempt will
   * fail if the task has already completed, has already been cancelled,
   *  or could not be cancelled for some other reason.
   *
   * @return {@code false} if the task could not be cancelled,
   * typically because it has already completed normally;
   * {@code true} otherwise
   *
   * @since 3.19
   */
  boolean cancel(String id, boolean mayInterruptIfRunning);

  /**
   * Returns the {@link TaskInfo} of the first task with type ID matching {@code typeId}, otherwise {@code null}.
   */
  @Nullable
  TaskInfo getTaskByTypeId(String typeId);

  /**
   * Returns the {@link TaskInfo} of the first task with type ID matching {@code typeId}
   * and {@link TaskConfiguration} matching {@code config}, otherwise {@code null}.
   * <p/>
   * All entries in {@code config} must match entries in the task's {@link TaskConfiguration} to be
   * considered a match. Any entries of {@code config} with either a null key or null value will be ignored.
   */
  @Nullable
  TaskInfo getTaskByTypeId(String typeId, Map<String, String> config);

  /**
   * Find the first task with type ID matching {@code typeId}.
   * <p/>
   * If found, submit the task for execution if it is not already running.
   *
   * @param typeId task type ID
   * @return {@code true} if a task is found, {@code false} otherwise
   */
  boolean findAndSubmit(String typeId);

  /**
   * Find the first task with type ID matching {@code typeId} and {@link TaskConfiguration} matching {@code config}.
   * <p/>
   * All entries in {@code config} must match entries in the task's {@link TaskConfiguration} to be
   * considered a match. Any entries of {@code config} with either a null key or null value will be ignored.
   * <p/>
   * If found, don't submit the task for execution just confirm the waiting/running state with a boolean value
   *
   * @param typeId task type ID
   * @return {@code true} if a task is found waiting or already running, {@code false} otherwise
   */
  boolean findWaitingTask(String typeId, Map<String, String> config);

  /**
   * Find the first task with type ID matching {@code typeId} and {@link TaskConfiguration} matching {@code config}.
   * <p/>
   * All entries in {@code config} must match entries in the task's {@link TaskConfiguration} to be
   * considered a match. Any entries of {@code config} with either a null key or null value will be ignored.
   * <p/>
   * If found, submit the task for execution if it is not already running.
   *
   * @param typeId task type ID
   * @return {@code true} if a task is found, {@code false} otherwise
   */
  boolean findAndSubmit(String typeId, Map<String, String> config);
  
  /**
   * Checks if virtual threads are enabled for task execution.
   * 
   * When enabled, compatible tasks will be executed using Java 21 virtual threads,
   * which provide significant concurrency benefits for I/O-bound operations with minimal
   * resource overhead compared to traditional platform threads.
   * 
   * @return {@code true} if virtual threads are enabled, {@code false} otherwise
   * @since 3.60
   */
  boolean isVirtualThreadsEnabled();
  
  /**
   * Controls whether virtual threads should be used for task execution when possible.
   * 
   * Virtual threads are lightweight threads that are particularly beneficial for I/O-bound tasks,
   * allowing thousands of concurrent operations with minimal overhead. However, not all tasks
   * are compatible with virtual threads, and the scheduler will automatically select the
   * appropriate thread type based on task compatibility.
   * 
   * @param enabled {@code true} to enable virtual threads, {@code false} to disable
   * @since 3.60
   */
  void setVirtualThreadsEnabled(boolean enabled);
  
  /**
   * Determines if a task is compatible with virtual threads based on its configuration.
   * 
   * Tasks that are I/O-bound (such as network operations, file system access, or database queries)
   * are typically good candidates for virtual threads. Tasks that are CPU-bound or use thread-local
   * variables extensively may not be suitable for virtual threads.
   * 
   * @param config the task configuration to evaluate
   * @return {@code true} if the task can safely use virtual threads, {@code false} otherwise
   * @since 3.60
   */
  boolean isTaskVirtualThreadCompatible(TaskConfiguration config);
  
  /**
   * Determines the preferred thread type for executing a task based on its configuration
   * and the current system settings.
   * 
   * This method considers both the task's compatibility with virtual threads and whether
   * virtual threads are enabled in the system. It returns the optimal thread type for
   * executing the task to maximize performance and resource efficiency.
   * 
   * @param config the task configuration to evaluate
   * @return the preferred {@link ThreadType} for executing the task
   * @since 3.60
   */
  ThreadType getPreferredThreadType(TaskConfiguration config);
}