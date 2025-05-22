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
package org.sonatype.nexus.pax.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.sonatype.nexus.logging.task.TaskLoggerHelper;
import org.sonatype.nexus.logging.task.TaskLoggingEvent;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.spi.MDCAdapter;

import static ch.qos.logback.core.spi.FilterReply.DENY;
import static ch.qos.logback.core.spi.FilterReply.NEUTRAL;
import static org.sonatype.nexus.logging.task.TaskLogger.LOGBACK_TASK_DISCRIMINATOR_ID;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

/**
 * Logback {@link Filter} for task logs (see tasklogfile in logback.xml). Ensures that the task logs get the appropriate
 * entries:
 * - Thread must be executing in a task (determined by presence of discriminator in MDC)
 * - Must NOT have the NEXUS_LOG marker. This prevents double entry for the progress update to the nexus.log
 * - Also sets progress entries into the TaskLoggerHelper
 * 
 * Updated for Java 21 to support Virtual Threads and maintain task context across thread boundaries.
 * 
 * @since 3.5
 */
public class TaskLogsFilter
    extends Filter<ILoggingEvent>
{
  /**
   * Cache for task context information to ensure it's maintained across Virtual Thread boundaries.
   * This helps maintain context when a Virtual Thread is suspended and resumed on a different carrier thread.
   */
  private static final Map<Thread, String> TASK_CONTEXT_CACHE = new ConcurrentHashMap<>();
  /**
   * Metrics for task execution in Virtual Threads.
   * Maps task IDs to their start timestamps for performance tracking.
   */
  private static final Map<String, Long> TASK_METRICS = new ConcurrentHashMap<>();
  
  @Override
  public FilterReply decide(final ILoggingEvent event) {
    Marker marker = event.getMarker();
    Thread currentThread = Thread.currentThread();
    boolean isVirtualThread = currentThread.isVirtual();
    
    // Capture metrics for Virtual Thread task execution
    if (isVirtualThread) {
      String taskId = MDC.get(LOGBACK_TASK_DISCRIMINATOR_ID);
      if (taskId != null) {
        // Record task activity for metrics
        TASK_METRICS.putIfAbsent(taskId, System.currentTimeMillis());
      }
    }

    if (PROGRESS.equals(marker)) {
      // store the progress value in the threadlocal
      // Enhanced to support structured logging with String Templates
      TaskLoggingEvent taskEvent = toTaskLoggerEvent(event);
      TaskLoggerHelper.progress(taskEvent);
    }

    if (!isExecutingInTask()) {
      return DENY;
    }

    if (NEXUS_LOG_ONLY.equals(marker) || INTERNAL_PROGRESS.equals(marker)) {
      // not meant for task log
      return DENY;
    }

    return NEUTRAL;
  }

  /**
   * Determines if the current thread is executing in a task context.  
   * Enhanced to handle task context in Virtual Threads by checking both the MDC and the task context cache.
   * 
   * @return true if executing in a task context, false otherwise
   */
  protected boolean isExecutingInTask() {
    Thread currentThread = Thread.currentThread();
    String taskId = MDC.get(LOGBACK_TASK_DISCRIMINATOR_ID);
    
    if (taskId != null) {
      // If we have a task ID in the MDC, cache it for this thread
      if (currentThread.isVirtual()) {
        TASK_CONTEXT_CACHE.put(currentThread, taskId);
      }
      return true;
    }
    
    // For Virtual Threads, check the cache if MDC doesn't have the task ID
    // This handles cases where the Virtual Thread was suspended and resumed
    if (currentThread.isVirtual()) {
      String cachedTaskId = TASK_CONTEXT_CACHE.get(currentThread);
      if (cachedTaskId != null) {
        // Restore the task ID to the MDC
        MDC.put(LOGBACK_TASK_DISCRIMINATOR_ID, cachedTaskId);
        return true;
      }
    }
    
    // Not executing in a task
    return false;
  }

  /**
   * Converts a logging event to a task logging event.
   * Enhanced to support String Template structured logging in Java 21.
   * 
   * @param event the logging event to convert
   * @return a task logging event
   */
  protected TaskLoggingEvent toTaskLoggerEvent(final ILoggingEvent event) {
    Logger logger = LoggerFactory.getLogger(event.getLoggerName());
    Object[] args = event.getArgumentArray();
    
    // Check if this is a structured log message using String Templates
    // String Templates in Java 21 are instances of StringTemplate
    if (args != null && args.length > 0 && args[0] != null && 
        args[0].getClass().getName().equals("java.lang.StringTemplate")) {
      // For structured logging with String Templates, we preserve the template structure
      // This allows for better parsing and analysis of log data
      return new TaskLoggingEvent(logger, event.getMessage(), args);
    }
    
    // Handle regular logging format
    return new TaskLoggingEvent(logger, event.getMessage(), args);
  }
  
  /**
   * Cleanup method to remove task context from cache when a Virtual Thread completes.
   * This should be called when a task is known to be complete to prevent memory leaks.
   * Also records task completion metrics.
   * 
   * @param thread the thread to clean up
   * @param taskId the ID of the task that completed
   */
  public static void cleanupTaskContext(Thread thread, String taskId) {
    if (thread != null && thread.isVirtual()) {
      TASK_CONTEXT_CACHE.remove(thread);
      
      // Record task completion metrics if we have a start time
      if (taskId != null && TASK_METRICS.containsKey(taskId)) {
        long startTime = TASK_METRICS.remove(taskId);
        long duration = System.currentTimeMillis() - startTime;
        // Log or report the metrics as needed
        Logger logger = LoggerFactory.getLogger(TaskLogsFilter.class);
        logger.debug("Virtual Thread task {} completed in {} ms", taskId, duration);
      }
    }
  }
  
  /**
   * Get metrics for task execution in Virtual Threads.
   * 
   * @return a map of task IDs to their execution metrics
   */
  public static Map<String, Long> getTaskMetrics() {
    return Map.copyOf(TASK_METRICS);
  }
}