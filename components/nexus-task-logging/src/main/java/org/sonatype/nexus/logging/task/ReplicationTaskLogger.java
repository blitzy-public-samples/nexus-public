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
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.NEXUS_LOG_ONLY;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

public class ReplicationTaskLogger
    extends ProgressTaskLogger
{
  public static final String REPLICATION_LOG_LOCATION_PREFIX =
      "running replication task for repository '{}' , replication log : {}";

  public static final String REPLICATION_DISCRIMINATOR_ID = "repositoryName";

  private final TaskLogInfo taskLogInfo;

  private final String repositoryName;
  
  // Store MDC context for propagation to Virtual Threads
  private Map<String, String> mdcContext;

  ReplicationTaskLogger(final Logger log, final TaskLogInfo taskLogInfo) {
    super(log);

    this.taskLogInfo = checkNotNull(taskLogInfo);
    this.repositoryName = taskLogInfo.getString(REPLICATION_DISCRIMINATOR_ID);
    
    // Initialize MDC context
    MDC.put(TASK_LOG_ONLY_MDC, "true");
    MDC.put(REPLICATION_DISCRIMINATOR_ID, repositoryName);
    
    // Capture the initial MDC context for later use with Virtual Threads
    this.mdcContext = MDC.getCopyOfContextMap();
  }

  private void logReplicationRunInfo() {
    // Ensure MDC context is properly set before logging
    Map<String, String> originalMdc = applyMdcContext();
    try {
      // show task details on replication log
      log.info(TASK_LOG_ONLY, "Replication run info:");
      log.info(TASK_LOG_ONLY, STR." Task ID: \{taskLogInfo.getId()}");
      log.info(TASK_LOG_ONLY, STR." Type: \{taskLogInfo.getTypeId()}");
      log.info(TASK_LOG_ONLY, STR." Name: \{taskLogInfo.getName()}");
      log.info(TASK_LOG_ONLY, STR." Description: \{taskLogInfo.getMessage()}");

      writeReplicationRunOnNexusLog();
    } finally {
      // Restore original MDC context
      restoreMdcContext(originalMdc);
    }
  }

  private void writeReplicationRunOnNexusLog() {
    // Temporarily remove TASK_LOG_ONLY_MDC to allow logging to nexus.log
    MDC.remove(TASK_LOG_ONLY_MDC);

    TaskLogHome.getReplicationLogsHome()
        .ifPresent((home) -> {
          String identifier = STR."replication-\{repositoryName}.log";
          String filename = STR."\{home}/\{identifier}";
          log.info(NEXUS_LOG_ONLY, REPLICATION_LOG_LOCATION_PREFIX, repositoryName, filename);
        });

    // Restore TASK_LOG_ONLY_MDC
    MDC.put(TASK_LOG_ONLY_MDC, "true");
  }

  @Override
  public final void start() {
    // Capture MDC context before starting
    mdcContext = MDC.getCopyOfContextMap();
    super.start();
    logReplicationRunInfo();
  }

  @Override
  public final void finish() {
    // Ensure MDC context is properly set before logging
    Map<String, String> originalMdc = applyMdcContext();
    try {
      super.finish();
      log.info(TASK_LOG_ONLY, "Task complete");
    } finally {
      // Clean up MDC context
      MDC.remove(TASK_LOG_ONLY_MDC);
      MDC.remove(REPLICATION_DISCRIMINATOR_ID);
      restoreMdcContext(originalMdc);
    }
  }

  @Override
  public void flush() {
    // Ensure MDC context is properly set before flushing
    Map<String, String> originalMdc = applyMdcContext();
    try {
      if (lastProgressEvent != null) {
        Logger logger = Optional.ofNullable(lastProgressEvent.getLogger()).orElse(log);
        logger.info(PROGRESS, lastProgressEvent.getMessage(), lastProgressEvent.getArgumentArray());
      }
      super.flush();
    } finally {
      // Restore original MDC context
      restoreMdcContext(originalMdc);
    }
  }
  
  /**
   * Captures the current MDC context for use with Virtual Threads or thread pools.
   * 
   * @return The current MDC context as a Map
   */
  @Override
  public Object captureContext() {
    return mdcContext;
  }
  
  /**
   * Applies a previously captured MDC context to the current thread.
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
   */
  @Override
  public void clearContext() {
    MDC.clear();
  }
  
  /**
   * Applies the stored MDC context to the current thread and returns the original context.
   * This is useful for ensuring consistent MDC context across thread boundaries, especially
   * with Virtual Threads which may be suspended and resumed on different carrier threads.
   * 
   * @return The original MDC context before applying the stored context
   */
  private Map<String, String> applyMdcContext() {
    Map<String, String> originalMdc = MDC.getCopyOfContextMap();
    if (mdcContext != null) {
      MDC.setContextMap(mdcContext);
    }
    return originalMdc;
  }
  
  /**
   * Restores the original MDC context after an operation.
   * 
   * @param originalMdc The original MDC context to restore
   */
  private void restoreMdcContext(Map<String, String> originalMdc) {
    if (originalMdc != null) {
      MDC.setContextMap(originalMdc);
    } else {
      MDC.clear();
    }
  }
}