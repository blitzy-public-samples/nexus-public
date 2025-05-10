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
import org.slf4j.MDC;

/**
 * {@link TaskLogger} for logging just to the task log. Stores a value in {@link MDC} for NexusLogFilter to find.
 * <p>
 * Enhanced for Java 21 Virtual Threads to ensure proper MDC propagation across thread boundaries.
 *
 * @since 3.5
 */
public class TaskLogOnlyTaskLogger
    extends SeparateTaskLogTaskLogger
{
  /**
   * Creates a new TaskLogOnlyTaskLogger and sets the MDC flag.
   * <p>
   * Ensures proper MDC flag propagation in Virtual Thread environments by setting the flag
   * after the parent constructor has completed its work.
   *
   * @param log the logger to use
   * @param taskLogInfo information about the task being logged
   */
  TaskLogOnlyTaskLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log, taskLogInfo);
    // Set the MDC flag for this task logger
    // This ensures the flag is properly set even if the thread is unmounted/remounted
    MDC.put(TASK_LOG_ONLY_MDC, "true");
  }

  /**
   * Writes the log file name to the Nexus log, temporarily removing the task-log-only flag.
   * <p>
   * Enhanced for Virtual Threads to ensure proper MDC context handling during thread unmounting/remounting.
   */
  @Override
  protected void writeLogFileNameToNexusLog() {
    // Store the current MDC flag value before removing it
    String previousValue = MDC.get(TASK_LOG_ONLY_MDC);
    try {
      // Remove the flag so the message goes to the Nexus log
      MDC.remove(TASK_LOG_ONLY_MDC);
      // Call the parent implementation to write the log file name
      super.writeLogFileNameToNexusLog();
    }
    finally {
      // Restore the flag in a finally block to ensure it's always restored,
      // even if an exception occurs or the thread is unmounted/remounted
      if (previousValue != null) {
        MDC.put(TASK_LOG_ONLY_MDC, previousValue);
      }
    }
  }
}