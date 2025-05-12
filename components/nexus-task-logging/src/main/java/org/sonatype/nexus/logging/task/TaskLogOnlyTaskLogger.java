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

import java.util.Map;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * {@link TaskLogger} for logging just to the task log. Stores a value in {@link MDC} for NexusLogFilter to find.
 * <p>
 * This implementation is compatible with both platform threads and virtual threads (Java 21+).
 * When running on virtual threads, it ensures proper MDC context propagation during thread unmounting
 * and remounting operations.
 *
 * @since 3.5
 */
public class TaskLogOnlyTaskLogger
    extends SeparateTaskLogTaskLogger
{
  /**
   * Stored MDC context for use across thread boundaries, especially important for Virtual Threads
   * which may be unmounted and remounted on different carrier threads.
   */
  private final Map<String, String> mdcContext;
  
  TaskLogOnlyTaskLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log, taskLogInfo);
    // Set the TASK_LOG_ONLY_MDC flag in the current thread's MDC
    MDC.put(TASK_LOG_ONLY_MDC, "true");
    // Store the MDC context for use across thread boundaries
    this.mdcContext = MDC.getCopyOfContextMap();
  }

  /**
   * Executes the given task with the proper MDC context, ensuring compatibility with Virtual Threads.
   * This method ensures that the MDC context is properly set before executing the task and restored
   * afterward, which is especially important in Virtual Thread environments where thread-local
   * variables might not behave as expected.
   *
   * @param task the task to execute with the proper MDC context
   * @param <V> the return type of the task
   * @return the result of the task execution
   * @throws Exception if the task throws an exception
   */
  private <V> V withMdcContext(Callable<V> task) throws Exception {
    // Store the current MDC context
    Map<String, String> previousContext = MDC.getCopyOfContextMap();
    try {
      // Set our stored MDC context
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      // Execute the task
      return task.call();
    } finally {
      // Restore the previous MDC context
      if (previousContext != null) {
        MDC.setContextMap(previousContext);
      } else {
        MDC.clear();
      }
    }
  }

  /**
   * Executes the given runnable with the proper MDC context, ensuring compatibility with Virtual Threads.
   *
   * @param runnable the runnable to execute with the proper MDC context
   */
  private void withMdcContext(Runnable runnable) {
    // Store the current MDC context
    Map<String, String> previousContext = MDC.getCopyOfContextMap();
    try {
      // Set our stored MDC context
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      // Execute the runnable
      runnable.run();
    } finally {
      // Restore the previous MDC context
      if (previousContext != null) {
        MDC.setContextMap(previousContext);
      } else {
        MDC.clear();
      }
    }
  }

  @Override
  protected void writeLogFileNameToNexusLog() {
    // Execute with proper MDC context handling for Virtual Thread compatibility
    withMdcContext(() -> {
      // Temporarily remove the TASK_LOG_ONLY_MDC flag
      MDC.remove(TASK_LOG_ONLY_MDC);
      try {
        // Call the parent implementation
        super.writeLogFileNameToNexusLog();
      } finally {
        // Restore the TASK_LOG_ONLY_MDC flag
        MDC.put(TASK_LOG_ONLY_MDC, "true");
      }
    });
  }
  
  /**
   * Captures the current MDC context for use with Virtual Threads or thread pools.
   * This method allows the task logger's MDC context to be propagated to other threads.
   *
   * @return The current MDC context map
   * @since 3.60
   */
  @Override
  public Object captureContext() {
    return mdcContext;
  }
  
  /**
   * Applies a previously captured MDC context to the current thread.
   *
   * @param context The context object previously returned by {@link #captureContext()}
   * @since 3.60
   */
  @Override
  public void applyContext(Object context) {
    if (context instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, String> mdcMap = (Map<String, String>) context;
      MDC.setContextMap(mdcMap);
    }
  }
  
  /**
   * Clears the MDC context from the current thread.
   * This is particularly important for Virtual Threads to prevent memory leaks.
   *
   * @since 3.60
   */
  @Override
  public void clearContext() {
    MDC.clear();
  }
}