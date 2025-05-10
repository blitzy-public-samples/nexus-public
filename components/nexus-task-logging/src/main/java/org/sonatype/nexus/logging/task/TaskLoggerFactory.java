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
  /**
   * Private constructor to prevent instantiation of this utility class.
   */
  private TaskLoggerFactory() {
    throw new IllegalAccessError("Utility class");
  }

  /**
   * Creates a {@link TaskLogger} instance based on the task object's {@link TaskLogging} annotation.
   * Uses Java 21 pattern matching for switch expressions to determine the appropriate implementation.
   *
   * @param taskObject the task object that may have a {@link TaskLogging} annotation
   * @param log the logger to use
   * @param taskLogInfo information about the task
   * @return a {@link TaskLogger} implementation appropriate for the task
   */
  public static TaskLogger create(final Object taskObject, final Logger log, final TaskLogInfo taskLogInfo) {
    // Get the TaskLogging annotation from the task object's class, or use the default if not present
    TaskLogging taskLogging = taskObject.getClass().getAnnotation(TaskLogging.class);

    if (taskLogging == null) {
      taskLogging = TaskLoggingDefault.class.getAnnotation(TaskLogging.class);
    }

    // Use pattern matching with switch expression to determine the appropriate TaskLogger implementation
    // This leverages Java 21's pattern matching for switch to provide more concise and type-safe code
    return switch (taskLogging.value()) {
      // Pattern matching for each TaskLogType value, returning the appropriate TaskLogger implementation
      // The arrow syntax (->) eliminates the need for break statements and makes the code more concise
      case NEXUS_LOG_ONLY -> new ProgressTaskLogger(log);
      case TASK_LOG_ONLY -> new TaskLogOnlyTaskLogger(log, taskLogInfo);
      case REPLICATION_LOGGING -> new ReplicationTaskLogger(log, taskLogInfo);
      case TASK_LOG_ONLY_WITH_PROGRESS -> new TaskLogWithProgressLogger(log, taskLogInfo);
      // Combining cases with comma syntax for more concise code
      case BOTH, default -> new SeparateTaskLogTaskLogger(log, taskLogInfo);
    };
  }

  /**
   * Default implementation of TaskLogging annotation.
   * Used when a task object doesn't have its own TaskLogging annotation.
   */
  @TaskLogging(BOTH)
  private static final class TaskLoggingDefault
  {
  }
}