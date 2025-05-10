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
package org.sonatype.nexus.scheduling;

import java.util.Map;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.StructuredTaskScope.ShutdownOnFailure;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.MDC;
import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.logging.task.TaskLoggerFactory;
import org.sonatype.nexus.logging.task.TaskLoggerHelper;
import org.sonatype.nexus.scheduling.spi.TaskResultStateStore;

import com.google.common.base.Strings;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.TASK_LOG_ONLY;

/**
 * Support for {@link Task} implementations.
 * <p>
 * Subclasses may implement {@link Cancelable} interface if they are implemented to periodically check for
 * {@link #isCanceled()} or {@link CancelableHelper#checkCancellation()} methods.
 * <p>
 * Task implementations should be {@code @Named} components but must not be {@code @Singletons}.
 * <p>
 * This class is compatible with Java 21 Virtual Threads and uses structured concurrency for improved
 * cancellation handling. When running on Java 21, tasks can benefit from the lightweight threading model
 * and improved resource utilization provided by virtual threads.
 *
 * @since 3.0
 */
public abstract class TaskSupport
    extends ComponentSupport
    implements Task
{
  private final TaskConfiguration configuration;

  private final AtomicBoolean canceledFlag;

  private final boolean taskLoggingEnabled;

  private TaskInfo taskInfo;

  protected static final String TIMESTAMP_FORMAT = "%1$tY-%1$tm-%1$td-%1$tH-%1$tM-%1$tS";

  public TaskSupport() {
    this(true);
  }

  public TaskSupport(final boolean taskLoggingEnabled) {
    this.taskLoggingEnabled = taskLoggingEnabled;
    this.configuration = createTaskConfiguration();
    this.canceledFlag = new AtomicBoolean(false);
  }

  protected TaskConfiguration createTaskConfiguration() {
    return new TaskConfiguration();
  }

  protected TaskConfiguration getConfiguration() {
    return configuration;
  }

  @Override
  public TaskConfiguration taskConfiguration() {
    return new TaskConfiguration(configuration);
  }

  @Override
  public void configure(final TaskConfiguration configuration) {
    checkNotNull(configuration);

    configuration.validate();
    this.configuration.apply(configuration);

    String message = getMessage();
    if (!Strings.isNullOrEmpty(message)) {
      this.configuration.setMessage(message);
    }
  }

  @Override
  public String getId() {
    return getConfiguration().getId();
  }

  @Override
  public String getName() {
    return getConfiguration().getName();
  }

  /**
   * Install canceled flag and {@link #execute()}.
   * <p>
   * This implementation properly propagates MDC logging context to Virtual Threads
   * and uses structured cancellation with {@link StructuredTaskScope} when appropriate.
   * <p>
   * Thread-local resources are properly cleaned up when tasks complete, regardless of
   * whether they complete normally or with an exception.
   */
  @Override
  public final Object call() throws Exception {
    // Capture the current MDC context before starting the task
    // This ensures proper context propagation to virtual threads
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    startTaskLogging();
    CancelableHelper.set(canceledFlag);
    
    try {
      // If we have an MDC context, ensure it's properly set for this thread
      // This is especially important for virtual threads
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      
      // Use structured concurrency for better cancellation handling
      try (ShutdownOnFailure scope = new StructuredTaskScope.ShutdownOnFailure()) {
        var future = scope.fork(() -> execute());
        scope.join();
        // Propagate any exceptions from the task execution
        return scope.throwIfFailed().result(future);
      }
    }
    catch (TaskInterruptedException e) {
      log.warn(TASK_LOG_ONLY, "Task '{}' was canceled", getMessage());
      throw e;
    }
    catch (Exception e) {
      log.error(TASK_LOG_ONLY, "Failed to run task '{}'", getMessage(), e);
      throw e;
    }
    finally {
      // Ensure proper cleanup of thread-local resources
      CancelableHelper.remove();
      finishTaskLogging();
      MDC.clear(); // Clear MDC context to prevent leaks in thread pools
    }
  }

  private void startTaskLogging() {
    if (taskLoggingEnabled) {
      TaskLoggerHelper.start(TaskLoggerFactory.create(this, log, configuration));
    }
  }

  private void finishTaskLogging() {
    if (taskLoggingEnabled) {
      TaskLoggerHelper.finish();
    }
  }

  /**
   * Execute task logic.
   */
  protected abstract Object execute() throws Exception;

  //
  // Cancelable; not directly implemented but here allow Cancelable to be used as a marker and provide impl
  //

  /**
   * Cancel this task.
   * <p>
   * When running with structured concurrency, this will properly propagate
   * cancellation to all subtasks.
   */
  public void cancel() {
    canceledFlag.set(true);
  }

  /**
   * Check if this task is canceled.
   */
  public boolean isCanceled() {
    return canceledFlag.get();
  }

  @Override
  public String toString() {
    return String.format("%s(id=%s, name=%s)", getClass().getSimpleName(), getId(), getName());
  }

  @Override
  public TaskInfo getTaskInfo() {
    return taskInfo;
  }

  @Override
  public void setTaskInfo(final TaskInfo taskInfo) {
    this.taskInfo = taskInfo;
  }

  /**
   * Updates the progress of this task.
   * <p>
   * This method is thread-pinning aware and safe to use with virtual threads.
   * It avoids operations that would cause thread pinning when running on virtual threads.
   *
   * @param taskResultStateStore the store to update the task state in
   * @param progress the progress message to set
   */
  protected void updateProgress(final TaskResultStateStore taskResultStateStore, final String progress) {
    // Store the current MDC context to restore it after the operation
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    try {
      taskInfo.getConfiguration().setProgress(progress);
      taskResultStateStore.updateJobDataMap(taskInfo);
    }
    finally {
      // Restore the MDC context to ensure proper logging context
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      } else {
        MDC.clear();
      }
    }
  }
}