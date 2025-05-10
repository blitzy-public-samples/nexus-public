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
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

/**
 * Logger for replication tasks that handles MDC context propagation across Virtual Thread boundaries.
 */
public class ReplicationTaskLogger
    extends ProgressTaskLogger
{
  public static final String REPLICATION_LOG_LOCATION_PREFIX =
      "running replication task for repository '{}' , replication log : {}";

  public static final String REPLICATION_DISCRIMINATOR_ID = "repositoryName";

  private final TaskLogInfo taskLogInfo;

  private final String repositoryName;

  // Store MDC context for propagation across Virtual Thread boundaries
  private final Map<String, String> mdcContext;

  ReplicationTaskLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log);

    this.taskLogInfo = checkNotNull(taskLogInfo);
    this.repositoryName = taskLogInfo.getString(REPLICATION_DISCRIMINATOR_ID);
    
    // Set up MDC context
    MDC.put(TASK_LOG_ONLY_MDC, "true");
    MDC.put(REPLICATION_DISCRIMINATOR_ID, repositoryName);
    
    // Capture MDC context for Virtual Thread propagation
    this.mdcContext = MDC.getCopyOfContextMap();
  }

  /**
   * Applies the stored MDC context to the current thread.
   * This is useful when executing in Virtual Thread environments where context may be lost.
   */
  private void applyMdcContext() {
    if (mdcContext != null) {
      MDC.setContextMap(mdcContext);
    }
  }

  private void logReplicationRunInfo() {
    // Ensure MDC context is applied before logging
    applyMdcContext();
    
    // show task details on replication log using String Templates
    log.info(TASK_LOG_ONLY, "Replication run info:");
    log.info(TASK_LOG_ONLY, STR."Task ID: \{taskLogInfo.getId()}");
    log.info(TASK_LOG_ONLY, STR."Type: \{taskLogInfo.getTypeId()}");
    log.info(TASK_LOG_ONLY, STR."Name: \{taskLogInfo.getName()}");
    log.info(TASK_LOG_ONLY, STR."Description: \{taskLogInfo.getMessage()}");

    writeReplicationRunOnNexusLog();
  }

  private void writeReplicationRunOnNexusLog() {
    // Save current MDC context
    Map<String, String> previousContext = MDC.getCopyOfContextMap();
    
    // Remove task log only flag for nexus log
    MDC.remove(TASK_LOG_ONLY_MDC);

    TaskLogHome.getReplicationLogsHome()
        .ifPresent((home) -> {
          // Using String Templates for cleaner string formatting
          String identifier = STR."replication-\{repositoryName}.log";
          String filename = STR."\{home}/\{identifier}";
          
          // Note: Not using String Templates for the log message itself as it uses SLF4J placeholders
          log.info(NEXUS_LOG_ONLY, REPLICATION_LOG_LOCATION_PREFIX, repositoryName, filename);
        });

    // Restore MDC context
    if (previousContext != null) {
      MDC.setContextMap(previousContext);
    } else {
      MDC.clear();
      applyMdcContext();
    }
  }

  @Override
  public final void start() {
    // Ensure MDC context is applied before starting
    applyMdcContext();
    
    super.start();
    logReplicationRunInfo();
  }

  @Override
  public final void finish() {
    // Ensure MDC context is applied before finishing
    applyMdcContext();
    
    super.finish();
    log.info(TASK_LOG_ONLY, "Task complete");
    
    // Clean up MDC context
    MDC.remove(TASK_LOG_ONLY_MDC);
    MDC.remove(REPLICATION_DISCRIMINATOR_ID);
  }

  public void flush() {
    // Ensure MDC context is applied before flushing
    applyMdcContext();
    
    if (lastProgressEvent != null) {
      Logger logger = Optional.ofNullable(lastProgressEvent.getLogger()).orElse(log);
      logger.info(PROGRESS, lastProgressEvent.getMessage(), lastProgressEvent.getArgumentArray());
    }
    super.flush();
  }
}