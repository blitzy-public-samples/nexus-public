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

import java.lang.Thread.State;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.annotation.Priority;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.Freezable;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskInfo;
import org.sonatype.nexus.scheduling.spi.SchedulerSPI;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;

/**
 * Manages activation/passivation of the scheduler.
 *
 * @since 3.0
 */
@Named
@ManagedLifecycle(phase = TASKS)
@Priority(Integer.MIN_VALUE) // start scheduler at the end of this phase
@Singleton
public class TaskActivation
    extends StateGuardLifecycleSupport
    implements Freezable
{
  private final SchedulerSPI scheduler;

  private volatile boolean frozen;
  
  // Timeout for task cancellation in milliseconds
  private static final long TASK_CANCELLATION_TIMEOUT_MS = 5000;

  @Inject
  public TaskActivation(final SchedulerSPI scheduler) {
    this.scheduler = checkNotNull(scheduler);
  }

  @Override
  protected void doStart() throws Exception {
    if (!isFrozen()) {
      scheduler.resume();
    }
  }

  @Override
  protected void doStop() throws Exception {
    scheduler.pause();
  }

  @Override
  public boolean isFrozen() {
    return frozen;
  }

  @Override
  public void freeze() {
    frozen = true;
    if (isStarted()) {
      scheduler.pause();
      scheduler.listsTasks().stream()
          .filter(this::cancelOnFreeze)
          .filter(taskInfo -> !maybeCancel(taskInfo))
          .forEach(taskInfo -> log.warn("Unable to cancel task: {}", taskInfo.getName()));
    }
  }

  private boolean cancelOnFreeze(final TaskInfo taskInfo) {
    return taskInfo.getConfiguration() == null
        || !taskInfo.getConfiguration().getBoolean(TaskConfiguration.RUN_WHEN_FROZEN, false);
  }

  @Override
  public void unfreeze() {
    frozen = false;
    if (isStarted()) {
      scheduler.resume();
    }
  }

  /**
   * Attempts to cancel a task, with special handling for virtual threads.
   * 
   * @param taskInfo the task to cancel
   * @return true if cancellation was successful or not needed, false otherwise
   */
  private boolean maybeCancel(final TaskInfo taskInfo) {
    Future<?> future = taskInfo.getCurrentState().getFuture();
    if (future == null) {
      return true; // No future to cancel
    }
    
    Thread taskThread = getTaskThread(taskInfo);
    boolean isVirtualThread = taskThread != null && taskThread.isVirtual();
    
    if (isVirtualThread) {
      // For virtual threads, log with thread ID and use enhanced cancellation approach
      String threadId = taskThread.toString();
      log.debug("Attempting to cancel virtual thread task: {} (thread: {})", taskInfo.getName(), threadId);
      
      // First try gentle cancellation
      boolean cancelled = future.cancel(false);
      
      // If gentle cancellation failed and thread is still alive, try interruption
      if (!cancelled && taskThread.getState() != State.TERMINATED) {
        log.debug("Using interruption for virtual thread task: {} (thread: {})", taskInfo.getName(), threadId);
        future.cancel(true); // Interrupt if running
        
        // Wait briefly for the virtual thread to respond to interruption
        try {
          taskThread.join(TASK_CANCELLATION_TIMEOUT_MS);
          cancelled = !taskThread.isAlive();
          if (!cancelled) {
            log.warn("Virtual thread task did not respond to interruption: {} (thread: {})", 
                taskInfo.getName(), threadId);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // Preserve interrupt status
          log.warn("Interrupted while waiting for virtual thread task to cancel: {} (thread: {})", 
              taskInfo.getName(), threadId);
        }
      }
      
      return cancelled;
    }
    else {
      // For platform threads, use the original approach
      return future.cancel(false);
    }
  }
  
  /**
   * Attempts to get the Thread object associated with a task.
   * 
   * @param taskInfo the task information
   * @return the Thread object if available, null otherwise
   */
  private Thread getTaskThread(final TaskInfo taskInfo) {
    try {
      // The task's thread might be accessible through the TaskInfo implementation
      if (taskInfo instanceof ThreadAwareTaskInfo) {
        return ((ThreadAwareTaskInfo) taskInfo).getThread();
      }
      
      // If not directly accessible, we can't reliably get the thread
      return null;
    }
    catch (Exception e) {
      log.debug("Unable to get thread for task: {}", taskInfo.getName(), e);
      return null;
    }
  }
  
  /**
   * Interface for TaskInfo implementations that can provide access to their execution thread.
   */
  public interface ThreadAwareTaskInfo {
    /**
     * @return the Thread that is executing this task, or null if not available
     */
    Thread getThread();
  }
}