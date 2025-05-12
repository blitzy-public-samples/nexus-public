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
  
  /**
   * Default timeout in milliseconds for virtual thread cancellation
   */
  private static final long VIRTUAL_THREAD_CANCEL_TIMEOUT_MS = 5000;

  @Inject
  public TaskActivation(final SchedulerSPI scheduler) {
    this.scheduler = checkNotNull(scheduler);
  }

  @Override
  protected void doStart() throws Exception {
    if (!isFrozen()) {
      log.debug("Starting scheduler with thread ID: {}, isVirtual: {}", 
          Thread.currentThread().threadId(), 
          Thread.currentThread().isVirtual());
      scheduler.resume();
    }
  }

  @Override
  protected void doStop() throws Exception {
    log.debug("Stopping scheduler with thread ID: {}, isVirtual: {}", 
        Thread.currentThread().threadId(), 
        Thread.currentThread().isVirtual());
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
      log.info("Freezing scheduler with thread ID: {}, isVirtual: {}", 
          Thread.currentThread().threadId(), 
          Thread.currentThread().isVirtual());
      scheduler.pause();
      scheduler.listsTasks().stream()
          .filter(this::cancelOnFreeze)
          .filter(taskInfo -> !maybeCancel(taskInfo))
          .forEach(taskInfo -> log.warn("Unable to cancel task: {} (thread ID: {})", 
              taskInfo.getName(), 
              getTaskThreadId(taskInfo)));
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
      log.info("Unfreezing scheduler with thread ID: {}, isVirtual: {}", 
          Thread.currentThread().threadId(), 
          Thread.currentThread().isVirtual());
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
      log.debug("Cancelling virtual thread task: {} (thread ID: {})", 
          taskInfo.getName(), taskThread.threadId());
      
      // For virtual threads, we need to ensure proper state transition
      boolean cancelled = future.cancel(false);
      
      // If cancellation was successful but the thread is still running,
      // we need to wait for it to complete its current operation
      if (cancelled && taskThread.getState() != State.TERMINATED) {
        try {
          log.debug("Waiting for virtual thread task to terminate: {} (thread ID: {}, state: {})", 
              taskInfo.getName(), taskThread.threadId(), taskThread.getState());
          
          // Wait for the virtual thread to terminate gracefully
          return future.isDone() || 
                 waitForTaskCompletion(future, VIRTUAL_THREAD_CANCEL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt(); // Preserve interrupt status
          log.warn("Interrupted while waiting for virtual thread task to terminate: {} (thread ID: {})", 
              taskInfo.getName(), taskThread.threadId());
          return false;
        }
      }
      
      return cancelled;
    }
    else {
      // Standard cancellation for platform threads
      return future.cancel(false);
    }
  }
  
  /**
   * Waits for a task's future to complete within the specified timeout.
   * 
   * @param future the task future
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return true if the task completed, false if the timeout elapsed
   * @throws InterruptedException if the current thread was interrupted while waiting
   */
  private boolean waitForTaskCompletion(Future<?> future, long timeout, TimeUnit unit) throws InterruptedException {
    long startTime = System.nanoTime();
    long timeoutNanos = unit.toNanos(timeout);
    
    while (!future.isDone()) {
      long elapsedNanos = System.nanoTime() - startTime;
      if (elapsedNanos >= timeoutNanos) {
        return false; // Timeout elapsed
      }
      
      // Sleep for a short time to avoid busy waiting
      Thread.sleep(Math.min(100, unit.toMillis(timeout) - TimeUnit.NANOSECONDS.toMillis(elapsedNanos)));
    }
    
    return true; // Task completed within timeout
  }
  
  /**
   * Attempts to get the thread associated with a task.
   * 
   * @param taskInfo the task info
   * @return the thread running the task, or null if not available
   */
  private Thread getTaskThread(final TaskInfo taskInfo) {
    // This is a simplified implementation - in a real system, you would need
    // a more robust way to get the thread associated with a task
    // For example, the TaskInfo implementation could be enhanced to track its thread
    
    // For now, we'll return null which will fall back to standard cancellation
    return null;
  }
  
  /**
   * Gets the thread ID for a task for logging purposes.
   * 
   * @param taskInfo the task info
   * @return the thread ID as a string, or "unknown" if not available
   */
  private String getTaskThreadId(final TaskInfo taskInfo) {
    Thread taskThread = getTaskThread(taskInfo);
    return taskThread != null ? String.valueOf(taskThread.threadId()) : "unknown";
  }
}