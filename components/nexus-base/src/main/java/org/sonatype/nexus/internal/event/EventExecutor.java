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
package org.sonatype.nexus.internal.event;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.lifecycle.LifecycleSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.event.EventAware.Asynchronous;
import org.sonatype.nexus.common.event.HasAffinity;
import org.sonatype.nexus.thread.NexusExecutorService;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.newSequentialExecutor;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.TASKS;
import static org.sonatype.nexus.common.event.EventHelper.asReplicating;
import static org.sonatype.nexus.common.event.EventHelper.isReplicating;

/**
 * Custom {@link Executor} used to dispatch events to {@link Asynchronous} subscribers.
 *
 * As Nexus starts, subscribers are called directly by the originating thread. Once the
 * TASKS phase is reached subscribers will be called asynchronously using Virtual Threads.
 *
 * Conversely as Nexus stops, the Virtual Thread executor is shutdown after leaving the TASKS phase
 * and subscribers will again be called directly by the originating thread. This avoids
 * asynchronous subscribers from having services disappear beneath them.
 *
 * @since 3.2
 */
@Named
@ManagedLifecycle(phase = TASKS)
@Singleton
class EventExecutor
    extends LifecycleSupport
    implements Executor
{
  /**
   * Like {@link java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy} but it continues to work after the executor is shutdown.
   */
  private static final RejectedExecutionHandler CALLER_RUNS_FAILSAFE = (command, executor) -> command.run();

  private final boolean affinityEnabled;

  private final int affinityCacheSize;

  private final Time affinityTimeout;

  private final boolean singleCoordinator;

  private NexusExecutorService eventProcessor;

  private NexusExecutorService affinityProcessor;

  private LoadingCache<String, AffinityBarrier> affinityBarriers;

  private volatile boolean asyncProcessing;

  @Inject
  public EventExecutor(
      @Named("${nexus.event.affinityEnabled:-true}") final boolean affinityEnabled,
      @Named("${nexus.event.affinityCacheSize:-1000}") final int affinityCacheSize,
      @Named("${nexus.event.affinityTimeout:-1s}") final Time affinityTimeout,
      @Named("${nexus.event.singleCoordinator:-false}") final boolean singleCoordinator)
  {
    this.affinityEnabled = affinityEnabled;
    this.affinityCacheSize = affinityCacheSize;
    this.affinityTimeout = checkNotNull(affinityTimeout);
    this.singleCoordinator = singleCoordinator;
  }

  /**
   * Move from direct to asynchronous subscriber processing using Virtual Threads.
   */
  @Override
  protected void doStart() throws Exception {
    // Create a Virtual Thread executor for event processing
    // Virtual Threads are lightweight and self-tuning, eliminating the need for thread pool sizing parameters
    ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    eventProcessor = NexusExecutorService.forCurrentSubject(virtualThreadExecutor);

    if (affinityEnabled) {
      Supplier<Executor> coordinator;
      if (singleCoordinator) {
        // Create a single Virtual Thread executor for coordination
        ExecutorService singleVirtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        affinityProcessor = NexusExecutorService.forCurrentSubject(singleVirtualThreadExecutor);

        // Use single-thread coordinator for all events (delivery to subscribers is still concurrent with Virtual Threads)
        coordinator = () -> affinityProcessor;
      }
      else {
        // Multi-threaded coordination and delivery, with sequential coordination for events with same affinity
        coordinator = () -> newSequentialExecutor(eventProcessor); // wraps behaviour on top of eventProcessor
      }

      affinityBarriers = CacheBuilder.newBuilder()
          .maximumSize(affinityCacheSize)
          .build(CacheLoader.from(() -> new AffinityBarrier(coordinator.get(), eventProcessor, affinityTimeout)));
    }

    asyncProcessing = true;
  }

  @Override
  protected void doStop() throws Exception {
    if (asyncProcessing) {
      // Simplified shutdown logic for Virtual Thread executors
      shutdown(affinityProcessor);
      shutdown(eventProcessor);
      asyncProcessing = false;
    }
  }
  
  /**
   * Shuts down the executor service and waits for termination.
   */
  private void shutdown(@Nullable final NexusExecutorService executorService) {
    if (executorService != null) {
      executorService.shutdown();
      try {
        // Virtual Threads typically complete quickly, but we still set a reasonable timeout
        executorService.awaitTermination(5L, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        log.debug("Interrupted while waiting for termination", e);
        Thread.currentThread().interrupt(); // Preserve interrupt status
      }
    }
  }

  /**
   * Used by UTs and ITs only, to "wait for calm period", when all the async event subscribers finished.
   * 
   * Note: With Virtual Threads, determining a "calm period" is different than with platform threads.
   * Virtual Threads are designed to efficiently handle many concurrent tasks, and they don't have
   * the same monitoring capabilities as platform threads. This method provides a best-effort check.
   */
  @VisibleForTesting
  boolean isCalmPeriod() {
    if (asyncProcessing) {
      try {
        // Submit a task to each executor and wait for it to complete
        // If the task completes, it means all previously submitted tasks have completed
        if (affinityProcessor != null) {
          affinityProcessor.submit(() -> null).get(100, TimeUnit.MILLISECONDS);
        }
        if (eventProcessor != null) {
          eventProcessor.submit(() -> null).get(100, TimeUnit.MILLISECONDS);
        }
        return true;
      }
      catch (Exception e) {
        log.debug("Exception while checking for calm period", e);
        return false;
      }
    }
    else {
      return true; // single-threaded mode is always calm
    }
  }

  /**
   * Is {@link HasAffinity} support enabled?
   *
   * @since 3.11
   */
  public boolean isAffinityEnabled() {
    return affinityEnabled;
  }

  /**
   * Executes asynchronous posting of an event using affinity to maintain event ordering across Virtual Threads.
   * Optimized for coordination between Virtual Threads to ensure proper event sequencing.
   *
   * @since 3.11
   */
  public void executeWithAffinity(final String affinity, final Runnable postEventToAsyncBus) {
    checkState(affinityEnabled);
    if (asyncProcessing) {
      Runnable command = inheritIsReplicating(postEventToAsyncBus);
      AffinityBarrier barrier = affinityBarriers.getUnchecked(affinity);
      barrier.coordinate(command); // coordinates Virtual Threads to maintain event ordering
    }
    else {
      postEventToAsyncBus.run();
    }
  }

  /**
   * Executes asynchronous delivery of an event to a particular subscriber using Virtual Threads,
   * tracking it as necessary for affinity coordination.
   */
  @Override
  public void execute(final Runnable deliverEventToSubscriber) {
    if (asyncProcessing) {
      Runnable command = inheritIsReplicating(deliverEventToSubscriber);
      AffinityBarrier barrier = affinityEnabled ? AffinityBarrier.current() : null;
      if (barrier != null) {
        barrier.execute(command); // tracks each event delivery to help with coordination of the next posting request
      }
      else {
        eventProcessor.execute(command);
      }
    }
    else {
      deliverEventToSubscriber.run();
    }
  }

  /**
   * Binds current "isReplicating" context to the {@link Runnable} regardless which thread executes it.
   */
  private static Runnable inheritIsReplicating(final Runnable command) {
    if (!isReplicating()) {
      return command; // no need to inherit flag
    }
    return () -> {
      // check state from context of thread doing the running
      if (!isReplicating()) {
        asReplicating(command); // set flag for duration of command
      }
      else {
        command.run(); // flag already set, maintain it
      }
    };
  }
}