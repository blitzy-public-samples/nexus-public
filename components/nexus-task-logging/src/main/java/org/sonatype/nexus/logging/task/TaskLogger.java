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

/**
 * Interface for task-specific logging operations.
 * 
 * <p>Implementations must ensure proper handling of logging context across thread boundaries,
 * including compatibility with Java 21 Virtual Threads. When tasks are executed using Virtual Threads,
 * special care must be taken to ensure that Mapped Diagnostic Context (MDC) data is properly propagated
 * and cleaned up.</p>
 *
 * @since 3.5
 */
public interface TaskLogger
{
  // id used in discriminator. See logback.xml
  String LOGBACK_TASK_DISCRIMINATOR_ID = "taskIdAndDate";

  // constant for MDC use
  String TASK_LOG_ONLY_MDC = "TASK_LOG_ONLY_MDC";

  // constant for MDC use
  String TASK_LOG_WITH_PROGRESS_MDC = "TASK_LOG_WITH_PROGRESS_MDC";

  /**
   * Required to start the task logging. See {@link TaskLoggerHelper#start(TaskLogger)}
   * 
   * <p>When implementing this method, ensure that any MDC context is properly initialized.
   * If the task may be executed on a Virtual Thread (Java 21+), implementers must ensure
   * that MDC context is correctly established for the thread executing the task.</p>
   */
  void start();

  /**
   * Required to close out the task logging. This involves cleaning up MDC and ThreadLocal variables. See {@link
   * TaskLoggerHelper#finish()}
   * 
   * <p>When implementing this method, ensure that all MDC context and ThreadLocal variables
   * are properly cleaned up to prevent memory leaks, especially when using Virtual Threads which
   * may be numerous and short-lived.</p>
   */
  void finish();

  /**
   * Log a progress event, which are always logged to the task log, but only periodically to the nexus.log
   *
   * <p>When implementing this method for environments using Virtual Threads (Java 21+),
   * ensure that the MDC context is properly maintained during the logging operation,
   * as Virtual Threads may be suspended and resumed on different carrier threads.</p>
   *
   * @param event log event containing progress
   */
  void progress(TaskLoggingEvent event);

  /**
   * Flush any pending progress messages so they are logged immediately
   * 
   * <p>When implementing this method for environments using Virtual Threads (Java 21+),
   * ensure that any asynchronous logging operations properly maintain the MDC context
   * across thread boundaries.</p>
   */
  void flush();
  
  /**
   * Captures the current MDC context for use with Virtual Threads or thread pools.
   * This is an optional helper method that implementations may provide to assist with
   * context propagation across thread boundaries.
   *
   * <p>When tasks spawn additional threads or Virtual Threads, this method can be used
   * to capture the current MDC context for propagation to the new threads.</p>
   *
   * @return An object representing the current MDC context, or null if not supported
   * @since 3.60
   */
  default Object captureContext() {
    return null;
  }
  
  /**
   * Applies a previously captured MDC context to the current thread.
   * This is an optional helper method that implementations may provide to assist with
   * context propagation across thread boundaries.
   *
   * <p>When tasks spawn additional threads or Virtual Threads, this method can be used
   * to apply a previously captured MDC context to the new thread.</p>
   *
   * @param context The context object previously returned by {@link #captureContext()}
   * @since 3.60
   */
  default void applyContext(Object context) {
    // Default implementation does nothing
  }
  
  /**
   * Clears the MDC context from the current thread.
   * This is an optional helper method that implementations may provide to assist with
   * context cleanup across thread boundaries.
   *
   * <p>When tasks spawn additional threads or Virtual Threads, this method should be called
   * when the thread's work is complete to prevent memory leaks, especially important with
   * Virtual Threads which may be numerous and short-lived.</p>
   *
   * @since 3.60
   */
  default void clearContext() {
    // Default implementation does nothing
  }
}