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
package org.sonatype.nexus.logging.task;

import org.slf4j.Logger;

import static org.sonatype.nexus.logging.task.TaskLogType.BOTH;

/**
 * Factory to create {@link TaskLogger} instances
 *
 * @since 3.5
 */
public class TaskLoggerFactory
{
  private TaskLoggerFactory() {
    throw new IllegalAccessError("Utility class");
  }

  /**
   * Creates a TaskLogger instance based on the TaskLogging annotation of the task object.
   * Uses pattern matching to determine the appropriate TaskLogger implementation.
   *
   * @param taskObject the task object that will be logged
   * @param log the logger to use
   * @param taskLogInfo information about the task being logged
   * @return a TaskLogger instance appropriate for the task
   */
  public static TaskLogger create(final Object taskObject, final Logger log, final TaskLogInfo taskLogInfo) {
    // Get the TaskLogging annotation from the task object's class
    TaskLogging taskLogging = taskObject.getClass().getAnnotation(TaskLogging.class);

    // If no annotation is present, use the default
    if (taskLogging == null) {
      taskLogging = TaskLoggingDefault.class.getAnnotation(TaskLogging.class);
    }

    // Use pattern matching with switch to determine the appropriate TaskLogger implementation
    return switch (taskLogging.value()) {
      // Pattern match each case to the appropriate TaskLogger implementation
      case TaskLogType t when t == TaskLogType.NEXUS_LOG_ONLY -> 
          new ProgressTaskLogger(log);
          
      case TaskLogType t when t == TaskLogType.TASK_LOG_ONLY -> 
          new TaskLogOnlyTaskLogger(log, taskLogInfo);
          
      case TaskLogType t when t == TaskLogType.REPLICATION_LOGGING -> 
          new ReplicationTaskLogger(log, taskLogInfo);
          
      case TaskLogType t when t == TaskLogType.TASK_LOG_ONLY_WITH_PROGRESS -> 
          new TaskLogWithProgressLogger(log, taskLogInfo);
          
      // Default case handles BOTH and any future enum values
      default -> 
          new SeparateTaskLogTaskLogger(log, taskLogInfo);
    };
  }

  @TaskLogging(BOTH)
  private static final class TaskLoggingDefault
  {
  }
}