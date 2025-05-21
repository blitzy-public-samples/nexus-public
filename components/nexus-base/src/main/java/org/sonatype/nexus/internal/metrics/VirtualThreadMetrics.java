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
package org.sonatype.nexus.internal.metrics;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import com.google.common.collect.ImmutableMap;

/**
 * {@link MetricSet} providing information about Java 21 Virtual Threads.
 * 
 * This class collects and exposes metrics related to Virtual Threads, including:
 * - Total number of virtual threads created
 * - Currently active virtual threads
 * - Number of pinned virtual threads (which can impact performance)
 * - Execution time distribution for virtual threads
 * 
 * It also implements a health check that monitors for excessive thread pinning,
 * which can negatively impact the performance benefits of virtual threads.
 *
 * @since 3.60
 */
@Named("virtual-threads")
@Singleton
public class VirtualThreadMetrics
    extends HealthCheck
    implements MetricSet
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadMetrics.class);
  
  // Threshold for when to consider pinned thread ratio unhealthy
  private static final double PINNED_RATIO_THRESHOLD = 0.1; // 10%
  
  // Threshold for when to log pinned thread count at debug level
  private static final int PINNED_COUNT_LOG_THRESHOLD = 10;
  private final AtomicLong pinnedCount = new AtomicLong(0);
  private final AtomicLong totalCreatedCount = new AtomicLong(0);
  private final Timer executionTimer = new Timer();
  
  /**
   * Constructor.
   */
  public VirtualThreadMetrics() {
    log.info("Initializing Virtual Thread metrics for Java 21");
  }

  @Override
  public Map<String, Metric> getMetrics() {
    return ImmutableMap.of(
        "created", (Gauge<Long>) this::getCreatedThreadCount,
        "totalCreated", (Gauge<Long>) this::getTotalCreatedCount,
        "active", (Gauge<Long>) this::getActiveThreadCount,
        "pinned", (Gauge<Long>) this::getPinnedThreadCount,
        "execution", executionTimer
    );
  }

  @Override
  protected Result check() {
    long pinnedThreads = getPinnedThreadCount();
    long activeThreads = getActiveThreadCount();
    
    if (pinnedThreads > 0 && activeThreads > 0) {
      // Check if pinned threads exceed a threshold that might impact performance
      double pinnedRatio = (double) pinnedThreads / activeThreads;
      if (pinnedRatio > PINNED_RATIO_THRESHOLD) { // More than threshold % of active threads are pinned
        log.warn("High number of pinned virtual threads detected: {} ({}% of active)", 
            pinnedThreads, String.format("%.2f", pinnedRatio * 100));
        return Result.unhealthy("High number of pinned virtual threads detected: %d (%.2f%% of active)", 
            pinnedThreads, pinnedRatio * 100);
      }
    }
    return Result.healthy();
  }

  /**
   * Records a virtual thread execution with the given duration.
   *
   * @param durationNanos the execution duration in nanoseconds
   */
  public void recordExecution(final long durationNanos) {
    executionTimer.update(durationNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
  }
  
  /**
   * Increments the count of total virtual threads created.
   * This should be called when a new virtual thread is created.
   */
  public void incrementCreatedCount() {
    totalCreatedCount.incrementAndGet();
  }

  /**
   * Increments the count of pinned virtual threads.
   * This should be called when a virtual thread becomes pinned to its carrier thread.
   * 
   * @return the new count of pinned threads
   */
  public long incrementPinnedCount() {
    long newCount = pinnedCount.incrementAndGet();
    if (newCount > PINNED_COUNT_LOG_THRESHOLD) { // Threshold for logging
      log.debug("Virtual thread pinning detected, current pinned count: {}", newCount);
    }
    return newCount;
  }

  /**
   * Decrements the count of pinned virtual threads.
   * This should be called when a virtual thread is no longer pinned to its carrier thread.
   * 
   * @return the new count of pinned threads
   */
  public long decrementPinnedCount() {
    return pinnedCount.decrementAndGet();
  }

  /**
   * Returns the current count of pinned virtual threads.
   */
  private long getPinnedThreadCount() {
    return pinnedCount.get();
  }
  
  /**
   * Returns the total count of virtual threads created since the application started.
   * This is more efficient than getCreatedThreadCount() as it doesn't need to scan all threads.
   */
  private long getTotalCreatedCount() {
    return totalCreatedCount.get();
  }

  /**
   * Returns the total number of virtual threads created in the JVM.
   * 
   * Note: This method uses Thread.getAllStackTraces() which can be expensive.
   * Consider using a more efficient approach in high-throughput environments.
   */
  private long getCreatedThreadCount() {
    try {
      return Thread.getAllStackTraces().keySet().stream()
          .filter(Thread::isVirtual)
          .count();
    }
    catch (Exception e) {
      log.warn("Error counting virtual threads", e);
      return 0;
    }
  }

  /**
   * Returns the number of currently active virtual threads.
   */
  private long getActiveThreadCount() {
    try {
      return Thread.activeCount(Thread.ofVirtual().factory());
    }
    catch (Exception e) {
      log.warn("Error counting active virtual threads", e);
      return 0;
    }
  }
}