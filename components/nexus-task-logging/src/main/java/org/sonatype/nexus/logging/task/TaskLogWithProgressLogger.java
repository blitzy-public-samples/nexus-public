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

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * {@link TaskLogger} for logging to the task log, but also doing progress to the nexus.log.
 * <p>
 * Enhanced for Java 21 Virtual Threads to ensure proper MDC propagation across thread boundaries.
 * This implementation ensures that the TASK_LOG_WITH_PROGRESS_MDC flag is properly maintained
 * even when Virtual Threads are unmounted and remounted on different carrier threads.
 *
 * @since 3.6
 */
public class TaskLogWithProgressLogger
    extends TaskLogOnlyTaskLogger
    implements TaskLogger
{
  // Store MDC context for Virtual Thread compatibility
  private final Map<String, String> mdcContext;

  /**
   * Creates a new TaskLogWithProgressLogger and sets the MDC flag.
   * <p>
   * Ensures proper MDC flag propagation in Virtual Thread environments by setting the flag
   * after the parent constructor has completed its work and storing the context for later use.
   *
   * @param log the logger to use
   * @param taskLogInfo information about the task being logged
   */
  public TaskLogWithProgressLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log, taskLogInfo);
    
    // Set the MDC flag for this task logger
    // This ensures the flag is properly set even if the thread is unmounted/remounted
    MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
    
    // Store MDC context for Virtual Thread compatibility
    this.mdcContext = MDC.getCopyOfContextMap();
    
    // Log if we're running in a Virtual Thread for debugging purposes
    if (Thread.currentThread().isVirtual()) {
      log.debug("TaskLogWithProgressLogger initialized on Virtual Thread: {}", Thread.currentThread().getName());
    }
  }
  
  /**
   * Captures the current MDC context for use with Virtual Threads or thread pools.
   * This method allows the MDC context to be propagated to other threads or Virtual Threads.
   *
   * @return The current MDC context as a Map
   */
  @Override
  public Object captureContext() {
    return mdcContext;
  }
  
  /**
   * Applies a previously captured MDC context to the current thread.
   * This method ensures that the TASK_LOG_WITH_PROGRESS_MDC flag is properly set
   * when switching between threads or Virtual Threads.
   *
   * @param context The context object previously returned by {@link #captureContext()}
   */
  @Override
  public void applyContext(Object context) {
    if (context instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, String> contextMap = (Map<String, String>) context;
      MDC.setContextMap(contextMap);
    }
  }
  
  /**
   * Clears the MDC context from the current thread.
   * This method ensures that the TASK_LOG_WITH_PROGRESS_MDC flag is properly cleared
   * when a thread or Virtual Thread completes its work.
   */
  @Override
  public void clearContext() {
    MDC.remove(TASK_LOG_WITH_PROGRESS_MDC);
  }
}