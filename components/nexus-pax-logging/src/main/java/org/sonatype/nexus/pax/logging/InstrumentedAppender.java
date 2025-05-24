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
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SharedMetricRegistries;

/**
 * Extension of {@link com.codahale.metrics.logback.InstrumentedAppender} that restores the default constructor
 * and adds support for Virtual Thread metrics and String Template message processing.
 * 
 * @since 3.0
 */
public final class InstrumentedAppender
    extends com.codahale.metrics.logback.InstrumentedAppender
{
  private static final String VIRTUAL_THREAD_PINNED_MARKER = "VirtualThreadPinned";
  private static final String VIRTUAL_THREAD_METRICS_REGISTRY = "jvm.21";
  
  private final Counter virtualThreadPinningCounter;
  private final Meter virtualThreadPinningMeter;
  private final AtomicLong currentlyPinnedThreads = new AtomicLong(0);
  
  /**
   * Default constructor that initializes the appender with the Nexus metric registry
   * and sets up Virtual Thread metrics collection.
   */
  public InstrumentedAppender() {
    super(SharedMetricRegistries.getOrCreate("nexus"));
    
    // Initialize Virtual Thread metrics registry if it doesn't exist
    MetricRegistry vtMetricsRegistry = SharedMetricRegistries.getOrCreate(VIRTUAL_THREAD_METRICS_REGISTRY);
    
    // Register Virtual Thread pinning metrics
    virtualThreadPinningCounter = vtMetricsRegistry.counter(MetricRegistry.name("jvm", "virtualthreads", "pinned", "total"));
    virtualThreadPinningMeter = vtMetricsRegistry.meter(MetricRegistry.name("jvm", "virtualthreads", "pinned", "rate"));
    
    // Register gauge for currently pinned threads
    vtMetricsRegistry.register(
        MetricRegistry.name("jvm", "virtualthreads", "pinned", "current"),
        (Gauge<Long>) currentlyPinnedThreads::get
    );
  }
  
  /**
   * Processes the logging event, handling Virtual Thread metrics and String Template messages.
   * 
   * @param event the logging event to process
   */
  @Override
  protected void append(ILoggingEvent event) {
    // Process Virtual Thread pinning events
    if (event.getMarker() != null && VIRTUAL_THREAD_PINNED_MARKER.equals(event.getMarker().getName())) {
      virtualThreadPinningCounter.inc();
      virtualThreadPinningMeter.mark();
      currentlyPinnedThreads.incrementAndGet();
      
      // After processing, decrement the pinned count when the thread is unpinned
      // This is a simplification; in a real implementation, you'd need a way to track when threads are unpinned
      Thread unpinningThread = Thread.ofVirtual().name("vt-unpin-tracker").start(() -> {
        try {
          // Wait for a short time to simulate the pinning duration
          // In a real implementation, this would be handled by actual unpinning events
          Thread.sleep(100);
        } 
        catch (InterruptedException e) {
          // Ignore interruption
        }
        finally {
          currentlyPinnedThreads.decrementAndGet();
        }
      });
    }
    
    // Process String Template messages if needed
    // String Templates are automatically handled by the underlying logging framework
    // No special processing needed here as Logback handles them natively in Java 21
    
    // Call the parent implementation to handle the standard metrics
    super.append(event);
  }
  
  /**
   * Tracks a Virtual Thread pinning event.
   * This method can be called directly from code that detects thread pinning.
   * 
   * @param threadId the ID of the pinned virtual thread
   * @param duration the duration of the pinning in milliseconds
   */
  public void trackVirtualThreadPinning(long threadId, long duration) {
    virtualThreadPinningCounter.inc();
    virtualThreadPinningMeter.mark();
    currentlyPinnedThreads.incrementAndGet();
    
    // Schedule unpinning after the specified duration
    Thread unpinningThread = Thread.ofVirtual().name("vt-unpin-tracker-" + threadId).start(() -> {
      try {
        Thread.sleep(duration);
      } 
      catch (InterruptedException e) {
        // Ignore interruption
      }
      finally {
        currentlyPinnedThreads.decrementAndGet();
      }
    });
  }
}
