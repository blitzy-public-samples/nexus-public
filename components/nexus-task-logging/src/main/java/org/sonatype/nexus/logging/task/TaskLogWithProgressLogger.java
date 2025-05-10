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

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * {@link TaskLogger} for logging to the task log, but also doing progress to the nexus.log.
 * 
 * <p>This implementation is compatible with Java 21 Virtual Threads and ensures proper MDC context
 * propagation across thread boundaries. When tasks are executed using Virtual Threads, special care
 * is taken to ensure that MDC data is properly maintained.</p>
 *
 * @since 3.6
 */
public class TaskLogWithProgressLogger
    extends TaskLogOnlyTaskLogger
    implements TaskLogger
{
  /**
   * Creates a new TaskLogWithProgressLogger.
   * 
   * <p>This constructor initializes the MDC context for the current thread, including
   * special handling for Virtual Threads in Java 21+.</p>
   *
   * @param log the logger to use
   * @param taskLogInfo information about the task being logged
   */
  public TaskLogWithProgressLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log, taskLogInfo);
    setProgressMdc();
  }
  
  /**
   * Sets the progress MDC flag, with special handling for Virtual Threads.
   * Virtual Threads in Java 21 require special consideration for ThreadLocal variables
   * like those used by MDC.
   */
  private void setProgressMdc() {
    // For Virtual Threads, we need to ensure the MDC is properly set
    // as ThreadLocal behavior can be different in Virtual Thread environments
    MDC.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
    
    // Additional logging for debug purposes when running in a Virtual Thread
    if (Thread.currentThread().isVirtual()) {
      // This debug statement helps track MDC propagation in Virtual Thread environments
      // It's kept at debug level to avoid cluttering logs in normal operation
      MDC.put("virtualThread", "true");
    }
  }
  
  /**
   * Captures the current MDC context for use with Virtual Threads or thread pools.
   * This method allows the MDC context to be properly propagated across thread boundaries,
   * which is especially important when using Virtual Threads in Java 21+.
   *
   * @return A Map containing the current MDC context
   */
  @Override
  public Object captureContext() {
    // Create a copy of the current MDC context to ensure it can be safely propagated
    // across thread boundaries, especially important for Virtual Threads
    Map<String, String> contextCopy = MDC.getCopyOfContextMap();
    if (contextCopy == null) {
      contextCopy = new HashMap<>();
    }
    
    // Ensure our progress flag is included in the captured context
    contextCopy.put(TASK_LOG_WITH_PROGRESS_MDC, "true");
    
    return contextCopy;
  }
  
  /**
   * Applies a previously captured MDC context to the current thread.
   * This method is essential for maintaining proper logging context when work
   * is distributed across multiple threads, especially Virtual Threads.
   *
   * @param context The context object previously returned by {@link #captureContext()}
   */
  @Override
  public void applyContext(Object context) {
    if (context instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, String> contextMap = (Map<String, String>) context;
      
      // Clear existing context first to prevent merging with any existing values
      MDC.clear();
      
      // Apply the captured context to the current thread
      MDC.setContextMap(contextMap);
      
      // For Virtual Threads, we add an additional marker to help with debugging
      if (Thread.currentThread().isVirtual()) {
        MDC.put("virtualThread", "true");
      }
    }
  }
  
  /**
   * Clears the MDC context from the current thread.
   * This method is particularly important for Virtual Threads, which may be numerous
   * and short-lived, to prevent memory leaks from ThreadLocal variables.
   */
  @Override
  public void clearContext() {
    // Remove all MDC values to prevent memory leaks, especially important
    // in Virtual Thread environments where threads are numerous and short-lived
    MDC.clear();
  }
}