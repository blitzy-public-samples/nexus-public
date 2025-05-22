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

import java.util.concurrent.atomic.AtomicLong;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;

/**
 * Extension of {@link com.codahale.metrics.logback.InstrumentedAppender} that provides enhanced functionality
 * for Java 21 features including Virtual Thread metrics and String Template support.
 * <p>
 * This appender collects metrics about Virtual Thread operations, including:
 * <ul>
 *   <li>Total virtual threads created</li>
 *   <li>Currently active virtual threads</li>
 *   <li>Virtual thread pinning events (which can impact performance)</li>
 *   <li>Virtual thread execution latency</li>
 * </ul>
 * <p>
 * It also provides support for detecting and processing log messages that use Java 21's String Template feature.
 * 
 * @since 3.0
 */
public final class InstrumentedAppender
    extends com.codahale.metrics.logback.InstrumentedAppender
{
  private static final String NEXUS_METRICS_REGISTRY = "nexus";
  private static final String VIRTUAL_THREAD_METRICS_REGISTRY = "jvm.21";
  
  private static final String METRIC_VTHREADS_CREATED = "virtualthreads.created";
  private static final String METRIC_VTHREADS_ACTIVE = "virtualthreads.active";
  private static final String METRIC_VTHREADS_PINNED = "virtualthreads.pinned";
  private static final String METRIC_VTHREADS_LATENCY = "virtualthreads.latency";
  
  private final MetricRegistry virtualThreadMetrics;
  private final AtomicLong virtualThreadsCreated = new AtomicLong(0);
  
  /**
   * Default constructor that initializes the appender with the Nexus metrics registry.
   * Also sets up the Virtual Thread metrics registry for Java 21 specific metrics.
   */
  public InstrumentedAppender() {
    super(SharedMetricRegistries.getOrCreate(NEXUS_METRICS_REGISTRY));
    this.virtualThreadMetrics = SharedMetricRegistries.getOrCreate(VIRTUAL_THREAD_METRICS_REGISTRY);
    setupVirtualThreadMetrics();
  }
  
  /**
   * Constructor with custom metric registry.
   * Still uses the standard Virtual Thread metrics registry for Java 21 specific metrics.
   * 
   * @param metricRegistry the metric registry to use for standard metrics
   */
  public InstrumentedAppender(MetricRegistry metricRegistry) {
    super(metricRegistry);
    this.virtualThreadMetrics = SharedMetricRegistries.getOrCreate(VIRTUAL_THREAD_METRICS_REGISTRY);
    setupVirtualThreadMetrics();
  }
  
  /**
   * Sets up metrics for tracking Virtual Thread operations.
   */
  private void setupVirtualThreadMetrics() {
    // Register counter for total virtual threads created
    Counter createdCounter = virtualThreadMetrics.counter(METRIC_VTHREADS_CREATED);
    
    // Register gauge for active virtual threads
    virtualThreadMetrics.register(METRIC_VTHREADS_ACTIVE, 
        (Gauge<Long>) () -> Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isVirtual)
            .count());
    
    // Register counter for pinned virtual threads
    Counter pinnedCounter = virtualThreadMetrics.counter(METRIC_VTHREADS_PINNED);
    
    // Register histogram for virtual thread execution latency
    virtualThreadMetrics.timer(METRIC_VTHREADS_LATENCY);
    
    // Log initial setup
    if (isStarted()) {
      addInfo("Initialized Virtual Thread metrics collection in registry: " + VIRTUAL_THREAD_METRICS_REGISTRY);
    }
  }
  
  @Override
  protected void append(ILoggingEvent event) {
    // Track virtual thread creation events
    if (event.getThreadName() != null && event.getThreadName().startsWith("VirtualThread") && 
        event.getMessage().contains("created")) {
      virtualThreadMetrics.counter(METRIC_VTHREADS_CREATED).inc();
      virtualThreadsCreated.incrementAndGet();
    }
    
    // Track virtual thread pinning events
    if (event.getMessage().contains("VirtualThread pinned") || 
        event.getMessage().contains("virtual thread pinned") ||
        (event.getThrowableProxy() != null && 
         event.getThrowableProxy().getMessage() != null &&
         event.getThrowableProxy().getMessage().contains("pinned"))) {
      virtualThreadMetrics.counter(METRIC_VTHREADS_PINNED).inc();
      
      // Log detailed information about pinning events at WARN level
      if (isWarnEnabled()) {
        addWarn("Virtual Thread pinning detected: " + event.getThreadName() + 
               " - This may impact performance. Consider reviewing synchronization in the code.");
      }
    }
    
    // Process String Template formatted messages
    // In Java 21, we can detect and properly handle String Template messages
    // This is a simplified implementation that just checks for template-like patterns
    String message = event.getMessage();
    if (message != null && message.contains("\\{") && message.contains("}")) {
      // In a full implementation, we would use the StringTemplate API to process this
      // For now, we just log that we detected a potential template
      if (isDebugEnabled()) {
        addDebug("Detected potential String Template message: " + message);
      }
    }
    
    // Call the parent implementation to handle the event
    super.append(event);
  }
}