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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.thread.NexusThreadFactory;

import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * Default implementation of {@link PeriodicJobService}, based on a ScheduledExecutorService.
 * Enhanced with Java 21 Virtual Threads support for task execution.
 *
 * @since 3.0
 */
@Named
@Singleton
public class PeriodicJobServiceImpl
    extends StateGuardLifecycleSupport
    implements PeriodicJobService
{
  /**
   * Property to control whether to use virtual threads for task execution.
   */
  private static final String USE_VIRTUAL_THREADS_PROPERTY = "nexus.scheduler.useVirtualThreads";
  
  /**
   * Default setting for virtual threads usage.
   */
  private static final boolean DEFAULT_USE_VIRTUAL_THREADS = true;

  private ScheduledExecutorService schedulerExecutor;
  
  private Executor taskExecutor;

  private int activeClients;
  
  private final boolean useVirtualThreads;

  public PeriodicJobServiceImpl() {
    this.useVirtualThreads = Boolean.parseBoolean(
        System.getProperty(USE_VIRTUAL_THREADS_PROPERTY, String.valueOf(DEFAULT_USE_VIRTUAL_THREADS)));
    log.info("PeriodicJobService configured to use {} threads for task execution", 
        useVirtualThreads ? "virtual" : "platform");
  }

  @Override
  public synchronized void startUsing() {
    if (activeClients == 0) {
      try {
        start();
      }
      catch (Exception e) {
        throw new PeriodicJobStartException(e);
      }
    }
    activeClients++;
  }

  @Override
  public synchronized void stopUsing() {
    checkState(activeClients > 0, "Not started");
    activeClients--;
    if (activeClients == 0) {
      try {
        stop();
      }
      catch (Exception e) {
        throw new PeriodicJobShutdownException(e);
      }
    }
  }

  @Override
  protected void doStart() throws Exception {
    // Create a platform thread pool for scheduling tasks
    schedulerExecutor = Executors.newScheduledThreadPool(1, new NexusThreadFactory("periodic", "scheduling"));
    
    // Create an executor for task execution - either virtual or platform threads based on configuration
    if (useVirtualThreads) {
      taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
      log.debug("Using virtual threads for task execution");
    } else {
      taskExecutor = Executors.newCachedThreadPool(new NexusThreadFactory("periodic", "task"));
      log.debug("Using platform threads for task execution");
    }
  }

  @Override
  protected void doStop() throws Exception {
    schedulerExecutor.shutdown();
    if (!schedulerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
      log.warn("Failed to terminate scheduler thread pool in allotted time");
    }
    schedulerExecutor = null;
    
    // If taskExecutor is a platform thread pool, shut it down
    if (!useVirtualThreads && taskExecutor instanceof java.util.concurrent.ExecutorService) {
      java.util.concurrent.ExecutorService executorService = (java.util.concurrent.ExecutorService) taskExecutor;
      executorService.shutdown();
      if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
        log.warn("Failed to terminate task thread pool in allotted time");
      }
    }
    taskExecutor = null;
  }

  @Override
  public void runOnce(final Runnable runnable, final int delaySeconds) {
    startUsing();
    schedulerExecutor.schedule(() -> {
      try {
        // Execute the task on the task executor
        taskExecutor.execute(wrap(runnable));
        return null;
      }
      finally {
        stopUsing();
      }
    }, delaySeconds, TimeUnit.SECONDS);
  }

  @Override
  @Guarded(by = STARTED)
  public PeriodicJob schedule(final Runnable runnable, final int repeatPeriodSeconds) {
    ScheduledFuture<?> scheduledFuture = schedulerExecutor.scheduleAtFixedRate(
        () -> taskExecutor.execute(wrap(runnable)),
        repeatPeriodSeconds,
        repeatPeriodSeconds,
        TimeUnit.SECONDS);

    return () -> scheduledFuture.cancel(false);
  }

  @Override
  @Guarded(by = STARTED)
  public PeriodicJob schedule(final Runnable runnable, final Duration delay, final Duration repeatPeriod) {
    ScheduledFuture<?> scheduledFuture = schedulerExecutor.scheduleAtFixedRate(
        () -> taskExecutor.execute(wrap(runnable)),
        delay.toMillis(),
        repeatPeriod.toMillis(),
        TimeUnit.MILLISECONDS);

    return () -> scheduledFuture.cancel(false);
  }

  /**
   * Wraps a runnable to catch exceptions and propagate MDC context.
   * This ensures that logging context is preserved when tasks are executed on virtual threads.
   */
  private Runnable wrap(final Runnable inner) {
    // Capture the current MDC context
    final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    return () -> {
      // Set up MDC context for this thread
      Map<String, String> previousMdc = MDC.getCopyOfContextMap();
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      } else {
        MDC.clear();
      }
      
      try {
        inner.run();
      }
      catch (Exception e) {
        // Do not propagate as this will cancel the recurring job
        log.error("Periodic job threw exception", e);
      }
      finally {
        // Restore the previous MDC context or clear it
        if (previousMdc != null) {
          MDC.setContextMap(previousMdc);
        } else {
          MDC.clear();
        }
      }
    };
  }

  public static class PeriodicJobShutdownException
      extends RuntimeException
  {
    private PeriodicJobShutdownException(final Exception e) {
      super(e);
    }
  }

  public static class PeriodicJobStartException
      extends RuntimeException
  {
    private PeriodicJobStartException(final Exception e) {
      super(e);
    }
  }
}