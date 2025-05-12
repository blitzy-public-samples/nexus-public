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
package org.sonatype.nexus.tasklog;

import java.util.Date;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.schedule.Schedule;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;

/**
 * Adds the {@link TaskLogCleanupTask} to the quartz cron definition in the database.
 * Updated for Java 21 compatibility with Virtual Threads support for improved performance
 * and reduced resource consumption during task execution.
 * 
 * @since 3.5
 */
@Named
@ManagedLifecycle(phase = TASKS)
@Singleton
public class TaskLogCleanupQuartz
    extends StateGuardLifecycleSupport
{
  // Logger is typically inherited from StateGuardLifecycleSupport/ComponentSupport, but we'll ensure it's available
  private static final Logger log = LoggerFactory.getLogger(TaskLogCleanupQuartz.class);
  
  private final TaskScheduler taskScheduler;

  private final String taskLogCleanupCron;
  
  private final boolean useVirtualThreads;

  @Inject
  public TaskLogCleanupQuartz(
      final TaskScheduler taskScheduler,
      @Named("${nexus.tasks.log.cleanup.cron:-0 0 0 * * ?}") final String taskLogCleanupCron,
      @Named("${nexus.tasks.log.cleanup.virtual.threads:-true}") final boolean useVirtualThreads)
  {
    this.taskScheduler = checkNotNull(taskScheduler);
    this.taskLogCleanupCron = checkNotNull(taskLogCleanupCron);
    this.useVirtualThreads = useVirtualThreads;
    log.info("Task log cleanup scheduler initialized with virtual threads {}", useVirtualThreads ? "enabled" : "disabled");
  }

  @Override
  protected void doStart() throws Exception {
    // Use Virtual Threads for task scheduling if enabled (Java 21 feature)
    // This improves performance for I/O-bound operations like log cleanup
    if (useVirtualThreads) {
      // Run the task scheduling in a virtual thread to avoid blocking the startup thread
      Executors.newVirtualThreadPerTaskExecutor().execute(this::scheduleTaskIfNeeded);
    }
    else {
      // Fall back to traditional execution if virtual threads are disabled
      scheduleTaskIfNeeded();
    }
  }
  
  /**
   * Schedules the task log cleanup task if it doesn't already exist.
   * Compatible with Quartz Scheduler 2.3.2 and Java 21.
   */
  private void scheduleTaskIfNeeded() {
    try {
      if (!taskScheduler.listsTasks()
          .stream()
          .anyMatch((info) -> TaskLogCleanupTaskDescriptor.TYPE_ID.equals(info.getConfiguration().getTypeId()))) {
        log.info("Scheduling task log cleanup task with cron expression: {}", taskLogCleanupCron);
        
        TaskConfiguration configuration = taskScheduler.createTaskConfigurationInstance(
            TaskLogCleanupTaskDescriptor.TYPE_ID);
        
        // Set a configuration attribute to indicate if this task should use virtual threads
        // This allows the task implementation to decide whether to use virtual threads
        configuration.setString("useVirtualThreads", String.valueOf(useVirtualThreads));
        
        Schedule schedule = taskScheduler.getScheduleFactory().cron(new Date(), taskLogCleanupCron);
        taskScheduler.scheduleTask(configuration, schedule);
        
        log.debug("Task log cleanup task scheduled successfully");
      }
      else {
        log.debug("Task log cleanup task already exists, skipping scheduling");
      }
    }
    catch (Exception e) {
      log.error("Failed to schedule task log cleanup task", e);
    }
  }
}