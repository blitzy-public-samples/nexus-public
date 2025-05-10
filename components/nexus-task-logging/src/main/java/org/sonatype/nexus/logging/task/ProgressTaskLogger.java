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
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.INTERNAL_PROGRESS;

/**
 * {@link TaskLogger} implementation which handles the logic for logging progress within scheduled tasks.
 * Additionally this class has starts a thread which will log regular (1 minute) progress update back to the main
 * nexus.log.
 * 
 * Uses Java 21 Virtual Threads for background logging operations to improve performance and resource utilization.
 *
 * @since 3.6
 */
public class ProgressTaskLogger
    implements TaskLogger
{
  static final String PROGRESS_LINE = "---- %s ----";

  private static final long INTERVAL_MINUTES = 10L;

  /**
   * Scheduler service for periodic logging tasks.
   * We use a small ScheduledThreadPoolExecutor for scheduling, but delegate the actual work to Virtual Threads.
   */
  private static final ScheduledExecutorService executorService = createExecutorService();

  protected final Logger log;

  private Future<?> progressLoggingThread;

  private Map<String, String> mdcMap;

  private final long initialDelay;

  private final long progressInterval;

  private final TimeUnit timeUnit;

  TaskLoggingEvent lastProgressEvent;

  ProgressTaskLogger(final Logger log) {
    this(log, INTERVAL_MINUTES, INTERVAL_MINUTES, MINUTES);
  }

  ProgressTaskLogger(
      final Logger log,
      final long initialDelay,
      final long progressInterval,
      final TimeUnit timeUnit)
  {
    this.log = checkNotNull(log);
    this.initialDelay = initialDelay;
    checkArgument(progressInterval > 0, "progressInterval must be greater than 0");
    this.progressInterval = progressInterval;
    this.timeUnit = checkNotNull(timeUnit);
  }

  @Override
  public void start() {
    // Capture the current MDC context to propagate it to the logging threads
    mdcMap = MDC.getCopyOfContextMap();
    startProgressThread();
  }

  @Override
  public void finish() {
    // Cancel the scheduled progress logging task
    if (progressLoggingThread != null && !progressLoggingThread.isDone()) {
      progressLoggingThread.cancel(true);
    }
  }

  @Override
  public void progress(final TaskLoggingEvent event) {
    lastProgressEvent = event;
  }

  @Override
  public void flush() {
    // If we're in a virtual thread, log directly; otherwise create a new virtual thread
    if (Thread.currentThread().isVirtual()) {
      logProgress();
    } else {
      // Use a virtual thread for the flush operation to avoid blocking the caller
      Executors.newVirtualThreadPerTaskExecutor().execute(this::logProgress);
    }
  }

  /**
   * Shuts down the executor service used for scheduling progress logging tasks.
   * This should be called during application shutdown to ensure proper cleanup of resources.
   * 
   * @since 3.16
   */
  public static void shutdown() {
    executorService.shutdown();
    try {
      // Wait for orderly shutdown with a reasonable timeout
      if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      // Restore the interrupted status
      Thread.currentThread().interrupt();
      // Force immediate shutdown if current thread is interrupted
      executorService.shutdownNow();
    }
  }

  /**
   * Logs the progress of the current task.
   * This method is designed to be called from both platform and virtual threads.
   */
  @VisibleForTesting
  void logProgress() {
    if (lastProgressEvent != null) {
      // Capture the event to avoid race conditions
      TaskLoggingEvent event = lastProgressEvent;
      lastProgressEvent = null;
      
      // If we're already in a virtual thread, execute directly
      // Otherwise, submit to a new virtual thread to avoid blocking the scheduler thread
      if (Thread.currentThread().isVirtual()) {
        doLogProgress(event);
      } else {
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> doLogProgress(event));
      }
    }
  }
  
  /**
   * Performs the actual logging operation within a virtual thread context.
   * This ensures proper MDC context propagation across thread boundaries.
   */
  private void doLogProgress(TaskLoggingEvent event) {
    // Set MDC context for this virtual thread
    Map<String, String> originalMdc = MDC.getCopyOfContextMap();
    try {
      if (mdcMap != null) {
        MDC.setContextMap(mdcMap);
      }

      Logger logger = Optional.ofNullable(event.getLogger()).orElse(log);
      logger.info(INTERNAL_PROGRESS, format(PROGRESS_LINE, event.getMessage()),
          event.getArgumentArray());
    } finally {
      // Restore original MDC context or clear it
      if (originalMdc != null) {
        MDC.setContextMap(originalMdc);
      } else {
        MDC.clear();
      }
    }
  }

  /**
   * Creates a scheduler service for periodic logging tasks.
   * We use a small ScheduledThreadPoolExecutor for scheduling, but the actual work is delegated to Virtual Threads.
   * 
   * @return A ScheduledExecutorService for scheduling periodic logging tasks
   */
  private static ScheduledExecutorService createExecutorService() {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1,
        new ThreadFactoryBuilder().setNameFormat("task-logging-scheduler-%d").build());
    executor.setRemoveOnCancelPolicy(true);
    return executor;
  }

  /**
   * Starts a scheduled task that periodically logs progress information.
   * The scheduling is done with a platform thread, but the actual logging work is delegated to Virtual Threads.
   */
  private void startProgressThread() {
    progressLoggingThread = executorService
        .scheduleAtFixedRate(this::logProgress, initialDelay, progressInterval, timeUnit);
  }
}