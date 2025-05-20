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
package org.sonatype.nexus.repository.replication;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import com.google.common.base.Preconditions;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * Manages Virtual Thread executors for replication operations.
 * <p>
 * This class provides a utility for submitting replication tasks to Java 21 Virtual Threads,
 * which are lightweight threads that are particularly well-suited for I/O-bound operations
 * like replication. Virtual Threads provide high concurrency with minimal overhead, making
 * them ideal for handling many simultaneous replication operations.
 *
 * @since 3.60
 */
@Named
@Singleton
@ManagedLifecycle(phase = SERVICES)
public class ReplicationVirtualThreadManager
    extends StateGuardLifecycleSupport
{
  private ExecutorService virtualThreadExecutor;
  
  private final AtomicLong taskCounter = new AtomicLong(0);
  private final AtomicLong activeTaskCount = new AtomicLong(0);
  private final AtomicLong completedTaskCount = new AtomicLong(0);
  private final AtomicLong failedTaskCount = new AtomicLong(0);

  /**
   * Start the virtual thread executor service.
   */
  @Override
  protected void doStart() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    log.info("Started ReplicationVirtualThreadManager with Virtual Thread executor");
  }

  /**
   * Stop the virtual thread executor service.
   */
  @Override
  protected void doStop() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor = null;
      log.info("Stopped ReplicationVirtualThreadManager");
    }
  }

  /**
   * Submit a runnable task to be executed by a Virtual Thread.
   * <p>
   * The task will be wrapped to propagate the MDC context and will be executed with the
   * {@link FakeAlmightySubject#TASK_SUBJECT} security subject.
   *
   * @param task the task to execute
   * @return a Future representing the task execution
   */
  @Guarded(by = STARTED)
  public Future<?> submit(@Nonnull final Runnable task) {
    Preconditions.checkNotNull(task, "Task cannot be null");
    
    long taskId = taskCounter.incrementAndGet();
    activeTaskCount.incrementAndGet();
    
    log.debug("Submitting replication task {} to Virtual Thread", taskId);
    
    return virtualThreadExecutor.submit(new MDCAwareRunnable(() -> {
      try {
        task.run();
        completedTaskCount.incrementAndGet();
        log.debug("Replication task {} completed successfully", taskId);
      }
      catch (Exception e) {
        failedTaskCount.incrementAndGet();
        log.error("Replication task {} failed with exception", taskId, e);
        throw e;
      }
      finally {
        activeTaskCount.decrementAndGet();
      }
    }));
  }

  /**
   * Submit a callable task to be executed by a Virtual Thread.
   * <p>
   * The task will be wrapped to propagate the MDC context and will be executed with the
   * {@link FakeAlmightySubject#TASK_SUBJECT} security subject.
   *
   * @param <T> the type of the callable's result
   * @param task the task to execute
   * @return a Future representing the pending result of the task
   */
  @Guarded(by = STARTED)
  public <T> Future<T> submit(@Nonnull final Callable<T> task) {
    Preconditions.checkNotNull(task, "Task cannot be null");
    
    long taskId = taskCounter.incrementAndGet();
    activeTaskCount.incrementAndGet();
    
    log.debug("Submitting replication callable task {} to Virtual Thread", taskId);
    
    return virtualThreadExecutor.submit(new MDCAwareCallable<>(() -> {
      try {
        T result = task.call();
        completedTaskCount.incrementAndGet();
        log.debug("Replication callable task {} completed successfully", taskId);
        return result;
      }
      catch (Exception e) {
        failedTaskCount.incrementAndGet();
        log.error("Replication callable task {} failed with exception", taskId, e);
        throw e;
      }
      finally {
        activeTaskCount.decrementAndGet();
      }
    }));
  }

  /**
   * Execute a runnable task on a Virtual Thread and wait for it to complete.
   * <p>
   * This is a convenience method that submits the task and waits for it to complete.
   *
   * @param task the task to execute
   * @throws RuntimeException if the task throws an exception
   */
  @Guarded(by = STARTED)
  public void execute(@Nonnull final Runnable task) {
    try {
      submit(task).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing replication task", e);
    }
  }

  /**
   * Execute a callable task on a Virtual Thread and return its result.
   * <p>
   * This is a convenience method that submits the task and waits for it to complete.
   *
   * @param <T> the type of the callable's result
   * @param task the task to execute
   * @return the result of the callable
   * @throws RuntimeException if the task throws an exception
   */
  @Guarded(by = STARTED)
  public <T> T execute(@Nonnull final Callable<T> task) {
    try {
      return submit(task).get();
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing replication callable task", e);
    }
  }

  /**
   * Get the number of tasks that have been submitted to this manager.
   *
   * @return the total task count
   */
  public long getTaskCount() {
    return taskCounter.get();
  }

  /**
   * Get the number of tasks that are currently active.
   *
   * @return the active task count
   */
  public long getActiveTaskCount() {
    return activeTaskCount.get();
  }

  /**
   * Get the number of tasks that have completed successfully.
   *
   * @return the completed task count
   */
  public long getCompletedTaskCount() {
    return completedTaskCount.get();
  }

  /**
   * Get the number of tasks that have failed.
   *
   * @return the failed task count
   */
  public long getFailedTaskCount() {
    return failedTaskCount.get();
  }
}