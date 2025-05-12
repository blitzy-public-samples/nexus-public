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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.scheduling.PeriodicJobService;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.NexusExecutorService;
import org.sonatype.nexus.thread.NexusThreadFactory;
import org.sonatype.nexus.thread.internal.MDCUtils;

import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * Default implementation of {@link PeriodicJobService}, based on a ScheduledExecutorService.
 * <p>
 * This implementation supports both platform threads and virtual threads (Java 21+).
 * It can be configured to use virtual threads for task execution, which is beneficial
 * for I/O-bound operations, while using platform threads for scheduling.
 *
 * @since 3.0
 */
@Named
@Singleton
@ManagedLifecycle(phase = SERVICES)
public class PeriodicJobServiceImpl
    extends StateGuardLifecycleSupport
    implements PeriodicJobService
{
  /**
   * Configuration property to enable/disable virtual threads for task execution.
   */
  private static final String USE_VIRTUAL_THREADS_PROPERTY = "nexus.scheduling.useVirtualThreads";
  
  /**
   * Default value for using virtual threads (enabled by default).
   */
  private static final boolean DEFAULT_USE_VIRTUAL_THREADS = true;
  
  /**
   * Configuration property for the number of platform threads in the scheduler pool.
   */
  private static final String SCHEDULER_POOL_SIZE_PROPERTY = "nexus.scheduling.poolSize";
  
  /**
   * Default number of platform threads in the scheduler pool.
   */
  private static final int DEFAULT_SCHEDULER_POOL_SIZE = 2;
  
  private final boolean useVirtualThreads;
  
  private final int schedulerPoolSize;
  
  private ScheduledExecutorService executor;
  
  private NexusExecutorService taskExecutor;

  private int activeClients;

  @Inject
  public PeriodicJobServiceImpl(
      @Named("${" + USE_VIRTUAL_THREADS_PROPERTY + ":-" + DEFAULT_USE_VIRTUAL_THREADS + "}") boolean useVirtualThreads,
      @Named("${" + SCHEDULER_POOL_SIZE_PROPERTY + ":-" + DEFAULT_SCHEDULER_POOL_SIZE + "}") int schedulerPoolSize)
  {
    this.useVirtualThreads = useVirtualThreads;
    this.schedulerPoolSize = schedulerPoolSize;
    log.info("Periodic Job Service configured with useVirtualThreads={}, schedulerPoolSize={}", 
        useVirtualThreads, schedulerPoolSize);
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
    // Create a platform thread pool for scheduling with consistent thread naming
    ThreadFactory threadFactory = new NexusThreadFactory("periodic", "scheduling");
    executor = Executors.newScheduledThreadPool(schedulerPoolSize, threadFactory);
    
    // Create the appropriate executor for task execution based on configuration
    if (useVirtualThreads) {
      // Use virtual threads for task execution (better for I/O-bound operations)
      taskExecutor = NexusExecutorService.forVirtualThreads(FakeAlmightySubject.TASK_SUBJECT);
      log.info("Using virtual threads for periodic job execution");
    } else {
      // Use platform threads for task execution (better for CPU-bound operations)
      taskExecutor = NexusExecutorService.forFixedSubject(
          Executors.newCachedThreadPool(new NexusThreadFactory("periodic-task", "execution")),
          FakeAlmightySubject.TASK_SUBJECT);
      log.info("Using platform threads for periodic job execution");
    }
  }

  @Override
  protected void doStop() throws Exception {
    // Shutdown the task executor first
    if (taskExecutor != null) {
      taskExecutor.shutdown();
      if (!taskExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
        log.warn("Failed to terminate task executor in allotted time");
      }
      taskExecutor = null;
    }
    
    // Then shutdown the scheduler executor
    if (executor != null) {
      executor.shutdown();
      if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
        log.warn("Failed to terminate scheduler thread pool in allotted time");
      }
      executor = null;
    }
  }

  @Override
  public void runOnce(final Runnable runnable, final int delaySeconds) {
    startUsing();
    executor.schedule(() -> {
      try {
        // Execute the task using the task executor to leverage virtual threads if configured
        taskExecutor.submit(wrap(runnable)).get();
        return null;
      }
      catch (Exception e) {
        log.error("Error executing one-time job", e);
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
    ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(
        () -> taskExecutor.submit(wrap(runnable)),
        repeatPeriodSeconds,
        repeatPeriodSeconds,
        TimeUnit.SECONDS);

    return () -> scheduledFuture.cancel(false);
  }

  @Override
  @Guarded(by = STARTED)
  public PeriodicJob schedule(final Runnable runnable, final Duration delay, final Duration repeatPeriod) {
    ScheduledFuture<?> scheduledFuture = executor.scheduleAtFixedRate(
        () -> taskExecutor.submit(wrap(runnable)),
        delay.toMillis(),
        repeatPeriod.toMillis(),
        TimeUnit.MILLISECONDS);

    return () -> scheduledFuture.cancel(false);
  }

  /**
   * Wraps a runnable to ensure exception handling and MDC context propagation.
   * <p>
   * This method captures the current MDC context and ensures it's properly restored
   * when the task executes, even across thread boundaries (important for virtual threads).
   *
   * @param inner the runnable to wrap
   * @return a wrapped runnable with exception handling and MDC context propagation
   */
  private Runnable wrap(final Runnable inner) {
    // Capture the current MDC context
    final Map<String, String> mdcContext = MDCUtils.getCopyOfContextMap();
    
    return () -> {
      // Store the original MDC context that might exist in the executing thread
      Map<String, String> originalContext = MDCUtils.getCopyOfContextMap();
      
      try {
        // Set our captured MDC context
        MDCUtils.setContextMap(mdcContext);
        
        // Execute the inner runnable
        inner.run();
      }
      catch (Exception e) {
        // Do not propagate as this will cancel the recurring job
        log.error("Periodic job threw exception", e);
      }
      finally {
        // Restore the original context
        MDCUtils.setContextMap(originalContext);
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
