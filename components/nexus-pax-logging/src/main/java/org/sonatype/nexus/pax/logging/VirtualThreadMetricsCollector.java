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
package org.sonatype.nexus.pax.logging;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;

/**
 * Metrics collector for Java 21 Virtual Threads that captures runtime statistics on Virtual Thread
 * creation, execution, and pinning events. Integrates with Dropwizard Metrics to expose counters,
 * gauges, and histograms for Virtual Thread metrics.
 *
 * This collector enables operational visibility into Virtual Thread performance characteristics,
 * which is critical for monitoring the health and performance of Virtual Thread-based operations in Nexus.
 *
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadMetricsCollector
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadMetricsCollector.class);
  
  private static final String METRIC_PREFIX = "virtualthread";
  
  private final MetricRegistry metricRegistry;
  
  private final AtomicLong activeVirtualThreads = new AtomicLong(0);
  private final AtomicLong pinnedVirtualThreads = new AtomicLong(0);
  
  // Track pinning reasons for diagnostic purposes
  private final Map<String, AtomicLong> pinningReasonCounts = new ConcurrentHashMap<>();
  
  private Counter virtualThreadCreatedCounter;
  private Counter virtualThreadTerminatedCounter;
  private Counter virtualThreadPinnedCounter;
  private Counter virtualThreadMountedCounter;
  private Counter virtualThreadUnmountedCounter;
  
  private Histogram virtualThreadExecutionTimeHistogram;
  private Histogram virtualThreadPinningDurationHistogram;
  private Histogram virtualThreadSchedulingDelayHistogram;
  
  /**
   * Constructor that uses the shared "nexus" metric registry.
   */
  public VirtualThreadMetricsCollector() {
    this(SharedMetricRegistries.getOrCreate("nexus"));
  }
  
  /**
   * Constructor that accepts a specific metric registry.
   *
   * @param metricRegistry the metric registry to use
   */
  public VirtualThreadMetricsCollector(final MetricRegistry metricRegistry) {
    this.metricRegistry = metricRegistry;
  }
  
  /**
   * Initialize metrics and register them with the metric registry.
   */
  @PostConstruct
  public void initialize() {
    log.info("Initializing Virtual Thread metrics collector");
    
    // Register counters
    virtualThreadCreatedCounter = metricRegistry.counter(MetricRegistry.name(METRIC_PREFIX, "created"));
    virtualThreadTerminatedCounter = metricRegistry.counter(MetricRegistry.name(METRIC_PREFIX, "terminated"));
    virtualThreadPinnedCounter = metricRegistry.counter(MetricRegistry.name(METRIC_PREFIX, "pinned"));
    virtualThreadMountedCounter = metricRegistry.counter(MetricRegistry.name(METRIC_PREFIX, "mounted"));
    virtualThreadUnmountedCounter = metricRegistry.counter(MetricRegistry.name(METRIC_PREFIX, "unmounted"));
    
    // Register histograms
    virtualThreadExecutionTimeHistogram = metricRegistry.histogram(MetricRegistry.name(METRIC_PREFIX, "execution-time"));
    virtualThreadPinningDurationHistogram = metricRegistry.histogram(MetricRegistry.name(METRIC_PREFIX, "pinning-duration"));
    virtualThreadSchedulingDelayHistogram = metricRegistry.histogram(MetricRegistry.name(METRIC_PREFIX, "scheduling-delay"));
    
    // Register gauges
    metricRegistry.register(MetricRegistry.name(METRIC_PREFIX, "active"), (Gauge<Long>) activeVirtualThreads::get);
    metricRegistry.register(MetricRegistry.name(METRIC_PREFIX, "pinned"), (Gauge<Long>) pinnedVirtualThreads::get);
    metricRegistry.register(MetricRegistry.name(METRIC_PREFIX, "pinned-ratio"), (Gauge<Double>) this::getPinnedRatio);
    metricRegistry.register(MetricRegistry.name(METRIC_PREFIX, "carrier-utilization"), (Gauge<Double>) this::getCarrierThreadUtilization);
    
    // Register common pinning reasons as separate gauges for easy monitoring
    registerPinningReasonGauge("synchronized");
    registerPinningReasonGauge("native-method");
    registerPinningReasonGauge("foreign-function");
    registerPinningReasonGauge("other");
    
    log.debug("Virtual Thread metrics collector initialized");
  }
  
  /**
   * Clean up and unregister metrics when the collector is being destroyed.
   */
  /**
   * Register a gauge for tracking a specific pinning reason.
   *
   * @param reason the pinning reason to track
   */
  private void registerPinningReasonGauge(final String reason) {
    pinningReasonCounts.putIfAbsent(reason, new AtomicLong(0));
    metricRegistry.register(
        MetricRegistry.name(METRIC_PREFIX, "pinned-by-reason", reason),
        (Gauge<Long>) () -> pinningReasonCounts.getOrDefault(reason, new AtomicLong(0)).get());
  }
  
  @PreDestroy
  public void shutdown() {
    log.info("Shutting down Virtual Thread metrics collector");
    
    // Remove standard gauges
    metricRegistry.remove(MetricRegistry.name(METRIC_PREFIX, "active"));
    metricRegistry.remove(MetricRegistry.name(METRIC_PREFIX, "pinned"));
    metricRegistry.remove(MetricRegistry.name(METRIC_PREFIX, "pinned-ratio"));
    metricRegistry.remove(MetricRegistry.name(METRIC_PREFIX, "carrier-utilization"));
    
    // Remove pinning reason gauges
    for (String reason : pinningReasonCounts.keySet()) {
      metricRegistry.remove(MetricRegistry.name(METRIC_PREFIX, "pinned-by-reason", reason));
    }
    
    log.debug("Virtual Thread metrics collector shut down");
  }
  
  /**
   * Record the creation of a new Virtual Thread.
   * 
   * @param threadId the ID of the Virtual Thread that was created
   * @param name the name of the Virtual Thread, if available
   */
  public void recordVirtualThreadCreated(long threadId, String name) {
    virtualThreadCreatedCounter.inc();
    activeVirtualThreads.incrementAndGet();
    
    if (log.isTraceEnabled()) {
      log.trace("Virtual Thread created: id={}, name={}", threadId, name);
    }
  }
  
  /**
   * Record the termination of a Virtual Thread.
   * 
   * @param threadId the ID of the Virtual Thread that was terminated
   * @param lifetimeNanos the total lifetime of the thread in nanoseconds
   */
  public void recordVirtualThreadTerminated(long threadId, long lifetimeNanos) {
    virtualThreadTerminatedCounter.inc();
    activeVirtualThreads.decrementAndGet();
    
    if (log.isTraceEnabled()) {
      log.trace("Virtual Thread terminated: id={}, lifetime={} ms", 
          threadId, TimeUnit.NANOSECONDS.toMillis(lifetimeNanos));
    }
  }
  
  /**
   * Record a Virtual Thread being pinned to its carrier thread.
   * 
   * @param threadId the ID of the Virtual Thread that was pinned
   * @param reason the reason why the thread was pinned (e.g., "synchronized", "native method")
   */
  public void recordVirtualThreadPinned(long threadId, String reason) {
    virtualThreadPinnedCounter.inc();
    pinnedVirtualThreads.incrementAndGet();
    
    // Track pinning reason for metrics
    String normalizedReason = normalizeReason(reason);
    AtomicLong count = pinningReasonCounts.computeIfAbsent(normalizedReason, k -> new AtomicLong(0));
    count.incrementAndGet();
    
    if (log.isDebugEnabled()) {
      log.debug("Virtual Thread {} pinned due to: {}", threadId, reason);
    }
  }
  
  /**
   * Normalize the pinning reason to a standard category.
   *
   * @param reason the raw pinning reason
   * @return a normalized category for the reason
   */
  private String normalizeReason(String reason) {
    if (reason == null) {
      return "unknown";
    }
    
    String lowerReason = reason.toLowerCase();
    if (lowerReason.contains("synchronized")) {
      return "synchronized";
    } else if (lowerReason.contains("native")) {
      return "native-method";
    } else if (lowerReason.contains("foreign") || lowerReason.contains("jni")) {
      return "foreign-function";
    } else {
      return "other";
    }
  }
  
  /**
   * Record a Virtual Thread being unpinned from its carrier thread.
   * 
   * @param threadId the ID of the Virtual Thread that was unpinned
   * @param pinningDurationNanos the duration in nanoseconds that the thread was pinned
   * @param reason the reason why the thread was pinned
   */
  public void recordVirtualThreadUnpinned(long threadId, long pinningDurationNanos, String reason) {
    pinnedVirtualThreads.decrementAndGet();
    virtualThreadPinningDurationHistogram.update(pinningDurationNanos);
    
    // Decrement the count for this reason
    String normalizedReason = normalizeReason(reason);
    AtomicLong count = pinningReasonCounts.get(normalizedReason);
    if (count != null) {
      count.decrementAndGet();
    }
    
    if (log.isDebugEnabled()) {
      log.debug("Virtual Thread {} unpinned after {} ns (reason: {})", 
          threadId, pinningDurationNanos, reason);
    }
    
    // Log warning for long pinning durations (over 100ms)
    if (pinningDurationNanos > TimeUnit.MILLISECONDS.toNanos(100)) {
      log.warn("Virtual Thread {} was pinned for {} ms due to {}", 
          threadId, TimeUnit.NANOSECONDS.toMillis(pinningDurationNanos), reason);
    }
  }
  
  /**
   * Record a Virtual Thread being mounted on a carrier thread.
   * 
   * @param threadId the ID of the Virtual Thread being mounted
   * @param carrierId the ID of the carrier thread
   */
  public void recordVirtualThreadMounted(long threadId, long carrierId) {
    virtualThreadMountedCounter.inc();
    
    if (log.isTraceEnabled()) {
      log.trace("Virtual Thread {} mounted on carrier thread {}", threadId, carrierId);
    }
  }
  
  /**
   * Record a Virtual Thread being unmounted from a carrier thread.
   * 
   * @param threadId the ID of the Virtual Thread being unmounted
   * @param carrierId the ID of the carrier thread
   * @param mountDurationNanos the duration in nanoseconds that the thread was mounted
   */
  public void recordVirtualThreadUnmounted(long threadId, long carrierId, long mountDurationNanos) {
    virtualThreadUnmountedCounter.inc();
    
    if (log.isTraceEnabled()) {
      log.trace("Virtual Thread {} unmounted from carrier thread {} after {} ns", 
          threadId, carrierId, mountDurationNanos);
    }
  }
  
  /**
   * Record the execution time of a Virtual Thread task.
   * 
   * @param threadId the ID of the Virtual Thread
   * @param executionTimeNanos the execution time in nanoseconds
   */
  public void recordExecutionTime(long threadId, long executionTimeNanos) {
    virtualThreadExecutionTimeHistogram.update(executionTimeNanos);
    
    // Log warning for very long-running virtual threads (over 1 second)
    // This could indicate a task that's not suitable for virtual threads
    if (executionTimeNanos > TimeUnit.SECONDS.toNanos(1)) {
      log.warn("Virtual Thread {} ran for {} ms, which is longer than recommended for virtual threads",
          threadId, TimeUnit.NANOSECONDS.toMillis(executionTimeNanos));
    }
  }
  
  /**
   * Record the scheduling delay for a Virtual Thread.
   * 
   * @param threadId the ID of the Virtual Thread
   * @param delayNanos the scheduling delay in nanoseconds
   */
  public void recordSchedulingDelay(long threadId, long delayNanos) {
    virtualThreadSchedulingDelayHistogram.update(delayNanos);
    
    // Log warning for significant scheduling delays (over 50ms)
    // This could indicate scheduler contention or resource constraints
    if (delayNanos > TimeUnit.MILLISECONDS.toNanos(50)) {
      log.warn("Virtual Thread {} experienced a scheduling delay of {} ms",
          threadId, TimeUnit.NANOSECONDS.toMillis(delayNanos));
    }
  }
  
  /**
   * Get the ratio of pinned Virtual Threads to total active Virtual Threads.
   * 
   * @return the ratio as a value between 0.0 and 1.0, or 0.0 if there are no active threads
   */
  private double getPinnedRatio() {
    long active = activeVirtualThreads.get();
    if (active <= 0) {
      return 0.0;
    }
    return (double) pinnedVirtualThreads.get() / active;
  }
  
  /**
   * Calculate an estimate of carrier thread utilization based on Virtual Thread activity.
   * 
   * @return the estimated carrier thread utilization as a value between 0.0 and 1.0
   */
  private double getCarrierThreadUtilization() {
    // This is a simplified estimation based on the ratio of mounted to unmounted operations
    // A more accurate implementation would require JFR events or JMX data from the JVM
    long mounted = virtualThreadMountedCounter.getCount();
    long unmounted = virtualThreadUnmountedCounter.getCount();
    
    if (mounted + unmounted == 0) {
      return 0.0;
    }
    
    double utilization = (double) mounted / (mounted + unmounted);
    
    // Log warning if carrier thread utilization is very high (over 90%)
    // This could indicate insufficient carrier threads or excessive pinning
    if (utilization > 0.9 && (mounted + unmounted) > 1000) {
      log.warn("High carrier thread utilization detected: {}%. This may indicate excessive thread pinning or insufficient carrier threads.",
          String.format("%.2f", utilization * 100));
    }
    
    return utilization;
  }
  
  /**
   * Get the current count of active Virtual Threads.
   * 
   * @return the number of active Virtual Threads
   */
  public long getActiveVirtualThreadCount() {
    return activeVirtualThreads.get();
  }
  
  /**
   * Get the current count of pinned Virtual Threads.
   * 
   * @return the number of pinned Virtual Threads
   */
  public long getPinnedVirtualThreadCount() {
    return pinnedVirtualThreads.get();
  }
  
  /**
   * Get the total number of Virtual Threads created since the collector was initialized.
   * 
   * @return the total count of created Virtual Threads
   */
  public long getVirtualThreadCreatedCount() {
    return virtualThreadCreatedCounter.getCount();
  }
  
  /**
   * Get the total number of Virtual Threads terminated since the collector was initialized.
   * 
   * @return the total count of terminated Virtual Threads
   */
  public long getVirtualThreadTerminatedCount() {
    return virtualThreadTerminatedCounter.getCount();
  }
  
  /**
   * Get the total number of Virtual Thread pinning events since the collector was initialized.
   * 
   * @return the total count of Virtual Thread pinning events
   */
  public long getVirtualThreadPinnedCount() {
    return virtualThreadPinnedCounter.getCount();
  }
  
  /**
   * Get the metric registry used by this collector.
   * 
   * @return the metric registry
   */
  public MetricRegistry getMetricRegistry() {
    return metricRegistry;
  }
  
  /**
   * Get a snapshot of pinning reasons and their counts.
   * 
   * @return a map of pinning reasons to their counts
   */
  public Map<String, Long> getPinningReasonCounts() {
    Map<String, Long> result = new ConcurrentHashMap<>();
    pinningReasonCounts.forEach((reason, count) -> result.put(reason, count.get()));
    return result;
  }
  
  /**
   * Log a summary of virtual thread statistics.
   * This is useful for periodic reporting or when troubleshooting performance issues.
   */
  public void logStatisticsSummary() {
    if (!log.isInfoEnabled()) {
      return;
    }
    
    StringBuilder summary = new StringBuilder("Virtual Thread Statistics Summary:\n");
    summary.append(String.format("  Active threads: %d\n", activeVirtualThreads.get()));
    summary.append(String.format("  Pinned threads: %d (%.2f%% of active)\n", 
        pinnedVirtualThreads.get(), getPinnedRatio() * 100));
    summary.append(String.format("  Total created: %d\n", virtualThreadCreatedCounter.getCount()));
    summary.append(String.format("  Total terminated: %d\n", virtualThreadTerminatedCounter.getCount()));
    summary.append(String.format("  Total pinning events: %d\n", virtualThreadPinnedCounter.getCount()));
    summary.append("  Pinning reasons:\n");
    
    pinningReasonCounts.forEach((reason, count) -> {
      summary.append(String.format("    %s: %d\n", reason, count.get()));
    });
    
    log.info(summary.toString());
  }
}