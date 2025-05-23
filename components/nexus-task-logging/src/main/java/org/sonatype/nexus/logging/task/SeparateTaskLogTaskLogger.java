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

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.rolling.RollingFileAppender;
import org.slf4j.Logger;
import org.slf4j.MDC;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.slf4j.Logger.ROOT_LOGGER_NAME;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

/**
 * {@link TaskLogger} implementation which handles the logic for creating separate task log files per task execution.
 * Extends {@link ProgressTaskLogger} to also include progress functionality.
 * Note logback handles most of the work (see logback.xml, TaskLogsFilter, and NexusLogFilter in nexus-pax-logging).
 *
 * @since 3.5
 */
public class SeparateTaskLogTaskLogger
    extends ProgressTaskLogger
{
  protected static final String TASK_LOG_LOCATION_PREFIX = "Task log: ";

  private final TaskLogInfo taskLogInfo;

  private final String taskLogIdentifier;
  
  // Store MDC context for Virtual Thread compatibility
  private final Map<String, String> mdcContext;

  SeparateTaskLogTaskLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log);
    this.taskLogInfo = checkNotNull(taskLogInfo);

    // Set per-thread logback property via MDC (see logback.xml)
    taskLogIdentifier = format("%s-%s", taskLogInfo.getTypeId(),
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")));
    MDC.put(LOGBACK_TASK_DISCRIMINATOR_ID, taskLogIdentifier);
    
    // Store MDC context for Virtual Thread compatibility
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
    Map<String, String> previousContext = MDC.getCopyOfContextMap();
    try {
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      return task.call();
    } finally {
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
    Map<String, String> previousContext = MDC.getCopyOfContextMap();
    try {
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      runnable.run();
    } finally {
      if (previousContext != null) {
        MDC.setContextMap(previousContext);
      } else {
        MDC.clear();
      }
    }
  }

  private void logTaskInfo() {
    // dump task details to task log
    withMdcContext(() -> {
      log.info(TASK_LOG_ONLY, "Task information:");
      log.info(TASK_LOG_ONLY, " ID: {}", taskLogInfo.getId());
      log.info(TASK_LOG_ONLY, " Type: {}", taskLogInfo.getTypeId());
      log.info(TASK_LOG_ONLY, " Name: {}", taskLogInfo.getName());
      log.info(TASK_LOG_ONLY, " Description: {}", taskLogInfo.getMessage());
      log.debug(TASK_LOG_ONLY, "Task configuration: {}", taskLogInfo);
    });

    writeLogFileNameToNexusLog();
  }

  /**
   * Writes the log file name to the Nexus log, ensuring proper MDC context propagation
   * in Virtual Thread environments.
   */
  protected void writeLogFileNameToNexusLog() {
    withMdcContext(() -> {
      String taskLogsHome = TaskLogHome.getTaskLogsHome();
      if (taskLogsHome != null) {
        String filename = format("%s/%s", taskLogsHome, getTaskLogIdentifier());
        log.info(NEXUS_LOG_ONLY, TASK_LOG_LOCATION_PREFIX + filename);
      }
    });
  }

  /**
   * Gets the task log identifier, ensuring proper context access across thread boundaries.
   * This method handles both platform threads and virtual threads correctly.
   *
   * @return the task log identifier
   */
  private String getTaskLogIdentifier() {
    try {
      return withMdcContext(() -> {
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Appender<ILoggingEvent> appender = loggerContext.getLogger(ROOT_LOGGER_NAME).getAppender("tasklogfile");
        if (appender instanceof RollingFileAppender) {
          File file = new File(((RollingFileAppender<ILoggingEvent>) appender).getFile());
          return file.getName();
        }
        return taskLogIdentifier + ".log";
      });
    } catch (Exception e) {
      // Fallback in case of any issues
      log.debug("Error getting task log identifier", e);
      return taskLogIdentifier + ".log";
    }
  }

  @Override
  public final void start() {
    // Check if running in a Virtual Thread and log for debugging purposes
    if (Thread.currentThread().isVirtual()) {
      log.debug("Task starting on Virtual Thread: {}", Thread.currentThread().getName());
    }
    
    withMdcContext(() -> super.start());
    logTaskInfo();
  }

  @Override
  public final void finish() {
    withMdcContext(() -> {
      super.finish();
      log.info(TASK_LOG_ONLY, "Task complete");
    });
    
    // Clear MDC context after task completion
    MDC.remove(LOGBACK_TASK_DISCRIMINATOR_ID);
    MDC.remove(TASK_LOG_ONLY_MDC);
    MDC.remove(TASK_LOG_WITH_PROGRESS_MDC);
  }

  @Override
  public void flush() {
    withMdcContext(() -> {
      if (lastProgressEvent != null) {
        Logger logger = Optional.ofNullable(lastProgressEvent.getLogger()).orElse(log);
        logger.info(PROGRESS, lastProgressEvent.getMessage(), lastProgressEvent.getArgumentArray());
      }
      super.flush();
    });
  }
}