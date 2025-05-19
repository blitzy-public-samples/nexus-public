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
package org.sonatype.nexus.blobstore;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Provides metrics for monitoring Virtual Thread performance in BlobStore operations.
 * Tracks metrics including virtual thread creation rates, execution times, blocking (pinning) events,
 * and scheduling delays to provide insight into Virtual Thread behavior and identify optimization
 * opportunities for I/O-bound operations.
 *
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadMetrics
    extends ComponentSupport
{
  private final MetricRegistry metricRegistry;

  private final AtomicLong virtualThreadsCreated = new AtomicLong(0);
  private final AtomicLong virtualThreadsActive = new AtomicLong(0);
  private final AtomicLong virtualThreadsPinned = new AtomicLong(0);

  private Counter createdCounter;
  private Timer executionTimer;
  private Timer schedulingDelayTimer;
  private Counter pinnedCounter;

  private static final String METRIC_PREFIX = "jvm.virtualthreads";

  @Inject
  public VirtualThreadMetrics(final MetricRegistry metricRegistry) {
    this.metricRegistry = metricRegistry;
  }

  /**
   * Initialize metrics after construction.
   */
  @PostConstruct
  public void init() {
    log.info("Initializing Virtual Thread metrics for BlobStore operations");

    // Register counters
    createdCounter = metricRegistry.counter(name(METRIC_PREFIX, "created"));

    // Register gauges
    metricRegistry.register(name(METRIC_PREFIX, "active"), (Gauge<Long>) virtualThreadsActive::get);
    metricRegistry.register(name(METRIC_PREFIX, "pinned"), (Gauge<Long>) virtualThreadsPinned::get);

    // Register timers
    executionTimer = metricRegistry.timer(name(METRIC_PREFIX, "latency"));
    schedulingDelayTimer = metricRegistry.timer(name(METRIC_PREFIX, "scheduling.delay"));
    pinnedCounter = metricRegistry.counter(name(METRIC_PREFIX, "pinned.count"));

    log.debug("Virtual Thread metrics initialized");
  }

  /**
   * Records the creation of a new virtual thread.
   */
  public void recordThreadCreated() {
    virtualThreadsCreated.incrementAndGet();
    createdCounter.inc();
  }

  /**
   * Records the start of a virtual thread execution.
   */
  public void recordThreadStart() {
    virtualThreadsActive.incrementAndGet();
  }

  /**
   * Records the completion of a virtual thread execution.
   */
  public void recordThreadEnd() {
    virtualThreadsActive.decrementAndGet();
  }

  /**
   * Records the execution time of a virtual thread operation.
   *
   * @param durationNanos the duration of the operation in nanoseconds
   */
  public void recordExecutionTime(final long durationNanos) {
    executionTimer.update(durationNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Records the scheduling delay for a virtual thread.
   *
   * @param delayNanos the scheduling delay in nanoseconds
   */
  public void recordSchedulingDelay(final long delayNanos) {
    schedulingDelayTimer.update(delayNanos, TimeUnit.NANOSECONDS);
  }

  /**
   * Records the start of a virtual thread pinning event.
   * Pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically during synchronized blocks or native method calls.
   */
  public void recordThreadPinned() {
    virtualThreadsPinned.incrementAndGet();
    pinnedCounter.inc();
  }

  /**
   * Records the end of a virtual thread pinning event.
   */
  public void recordThreadUnpinned() {
    virtualThreadsPinned.decrementAndGet();
  }

  /**
   * Records both the pinning and unpinning of a virtual thread with the duration of the pinning.
   *
   * @param durationNanos the duration of the pinning in nanoseconds
   */
  public void recordPinningDuration(final long durationNanos) {
    recordThreadPinned();
    try {
      // We could add a specific timer for pinning duration if needed
      log.debug("Virtual thread pinned for {} ns", durationNanos);
    }
    finally {
      recordThreadUnpinned();
    }
  }

  /**
   * Creates a timer context for measuring virtual thread execution time.
   * Usage: try (Timer.Context context = metrics.timerContext()) { ... }
   *
   * @return a timer context that will record the execution time when stopped
   */
  public Timer.Context timerContext() {
    recordThreadStart();
    Timer.Context context = executionTimer.time();
    return new Timer.Context() {
      @Override
      public long stop() {
        try {
          return context.stop();
        }
        finally {
          recordThreadEnd();
        }
      }
    };
  }

  /**
   * Returns the total number of virtual threads created since the application started.
   *
   * @return the total count of virtual threads created
   */
  public long getVirtualThreadsCreated() {
    return virtualThreadsCreated.get();
  }

  /**
   * Returns the current number of active virtual threads.
   *
   * @return the count of currently active virtual threads
   */
  public long getVirtualThreadsActive() {
    return virtualThreadsActive.get();
  }

  /**
   * Returns the current number of pinned virtual threads.
   *
   * @return the count of currently pinned virtual threads
   */
  public long getVirtualThreadsPinned() {
    return virtualThreadsPinned.get();
  }

  /**
   * Returns the execution timer that measures virtual thread operation latency.
   *
   * @return the execution timer
   */
  public Timer getExecutionTimer() {
    return executionTimer;
  }

  /**
   * Returns the scheduling delay timer that measures virtual thread scheduling delays.
   *
   * @return the scheduling delay timer
   */
  public Timer getSchedulingDelayTimer() {
    return schedulingDelayTimer;
  }
}