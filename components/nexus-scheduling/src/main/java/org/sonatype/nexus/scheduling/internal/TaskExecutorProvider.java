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
package org.sonatype.nexus.scheduling.internal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * Provides appropriate executor services for task execution based on task configuration.
 * Supports both platform threads and virtual threads (Java 21+).
 *
 * @since 3.60
 */
@Named
@Singleton
public class TaskExecutorProvider
    extends ComponentSupport
{
  /**
   * Controls whether to use Virtual Threads for suitable tasks.
   * This can be disabled for troubleshooting or in environments where Virtual Threads
   * might cause issues with certain JVM or system configurations.
   */
  @Inject
  @Named("${nexus.tasks.useVirtualThreads:-true}")
  protected boolean useVirtualThreads;

  /**
   * Creates an appropriate executor service for the given task configuration.
   * 
   * @param taskConfig the task configuration containing thread type preference
   * @return an executor service that propagates the current security context
   */
  public NexusExecutorService createExecutorService(final TaskConfiguration taskConfig) {
    String threadType = taskConfig.getString("threadType", "platform");
    Subject subject = SecurityUtils.getSubject();
    
    if (useVirtualThreads && "virtual".equals(threadType)) {
      log.debug("Using Virtual Threads for task: {}", taskConfig.getTaskLogName());
      return NexusExecutorService.forVirtualThreads(subject);
    } else {
      log.debug("Using Platform Threads for task: {}", taskConfig.getTaskLogName());
      ExecutorService executor = Executors.newSingleThreadExecutor();
      return NexusExecutorService.forFixedSubject(executor, subject);
    }
  }
  
  /**
   * Determines if the task is likely to experience thread pinning issues.
   * Thread pinning occurs when a Virtual Thread is blocked on a native method that doesn't
   * release the carrier thread, preventing the JVM from efficiently multiplexing Virtual Threads.
   * 
   * @param taskConfig the task configuration to check
   * @return true if the task might experience thread pinning, false otherwise
   */
  public boolean mightExperienceThreadPinning(final TaskConfiguration taskConfig) {
    // This is a simple heuristic that can be expanded as more information becomes available
    // about which tasks or operations might cause thread pinning
    String typeId = taskConfig.getTypeId();
    
    // Known task types that might cause thread pinning
    return typeId != null && (
        typeId.contains("vulnerability") || // Often uses native code for scanning
        typeId.contains("encryption") ||   // May use native crypto libraries
        typeId.contains("compress") ||     // May use native compression libraries
        typeId.contains("analytics")       // May perform CPU-intensive operations
    );
  }
  
  /**
   * Updates task configuration with expected duration based on historical data.
   * This information is used to detect potential thread pinning issues.
   * 
   * @param taskConfig the task configuration to update
   * @param durationMillis the duration of the task execution in milliseconds
   */
  public void updateTaskDurationMetrics(final TaskConfiguration taskConfig, final long durationMillis) {
    if (durationMillis <= 0) {
      return;
    }
    
    // Simple exponential moving average for expected duration
    long expectedDuration = taskConfig.getLong("expectedDurationMillis", 0);
    if (expectedDuration == 0) {
      // First execution, just use the actual duration
      taskConfig.setLong("expectedDurationMillis", durationMillis);
    } else {
      // Update the expected duration with a weighted average (0.7 * previous + 0.3 * current)
      long newExpectedDuration = (long)(0.7 * expectedDuration + 0.3 * durationMillis);
      taskConfig.setLong("expectedDurationMillis", newExpectedDuration);
    }
    
    // If using virtual threads and the task took significantly longer than expected,
    // consider switching to platform threads for future executions
    if ("virtual".equals(taskConfig.getString("threadType")) && 
        expectedDuration > 0 && 
        durationMillis > expectedDuration * 3 && 
        mightExperienceThreadPinning(taskConfig)) {
      
      log.warn("Task {} took {} ms, which is significantly longer than expected {} ms. " +
          "Switching to platform threads for future executions to avoid potential thread pinning.",
          taskConfig.getTaskLogName(), durationMillis, expectedDuration);
      
      taskConfig.setString("threadType", "platform");
    }
  }
}