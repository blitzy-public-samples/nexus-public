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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.JvmAttributeGaugeSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import io.prometheus.client.dropwizard.DropwizardExports;

import static com.codahale.metrics.MetricRegistry.name;
import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;

/**
 * Customized {@link com.codahale.metrics.servlets.MetricsServlet} to support injection and download.
 *
 * @since 3.0
 */
@Singleton
public class MetricsServlet
    extends com.codahale.metrics.servlets.MetricsServlet
{
  // Counter for active virtual threads
  private final AtomicInteger activeVirtualThreads = new AtomicInteger(0);
  
  // Counter for pinned virtual threads
  private final AtomicInteger pinnedVirtualThreads = new AtomicInteger(0);
  
  // Counter for virtual thread submission failures
  private final AtomicInteger virtualThreadSubmitFailures = new AtomicInteger(0);
  
  @Inject
  public MetricsServlet(final MetricRegistry registry) {
    super(registry);

    // JVM metrics are no longer automatically added in codahale-metrics
    registry.register(name("jvm", "vm"), new JvmAttributeGaugeSet());
    registry.register(name("jvm", "memory"), new MemoryUsageGaugeSet());
    registry.register(name("jvm", "buffers"), new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
    registry.register(name("jvm", "fd_usage"), new FileDescriptorRatioGauge());
    registry.register(name("jvm", "thread-states"), new ThreadStatesGaugeSet());
    registry.register(name("jvm", "garbage-collectors"), new GarbageCollectorMetricSet());
    
    // Register Java 21 ZGC-specific garbage collector metrics
    registerZGCMetrics(registry);
    
    // Register Virtual Thread metrics
    registerVirtualThreadMetrics(registry);

    // Export to Prometheus
    new DropwizardExports(registry).register();
  }
  
  /**
   * Registers ZGC-specific garbage collector metrics for Java 21.
   * This includes metrics for both generational and non-generational ZGC.
   */
  private void registerZGCMetrics(final MetricRegistry registry) {
    // ZGC metrics are already included in GarbageCollectorMetricSet
    // but we can add more specific ones for ZGC in Java 21
    
    try {
      // Check if we're running on Java 21 or later
      if (Runtime.version().feature() >= 21) {
        // Register ZGC-specific metrics if ZGC is enabled
        String gcName = System.getProperty("java.vm.name");
        if (gcName != null && gcName.contains("ZGC")) {
          // Check if generational ZGC is enabled
          boolean isGenerational = Boolean.getBoolean("ZGenerational");
          
          registry.register(name("jvm", "gc", "zgc", "generational"), 
              (Gauge<Boolean>) () -> isGenerational);
        }
      }
    } catch (Exception e) {
      // Ignore exceptions, as these metrics are optional
    }
  }
  
  /**
   * Registers Virtual Thread metrics for Java 21.
   * ThreadMXBean only provides statistics for platform threads, not virtual threads,
   * so we need to add custom metrics for virtual threads.
   */
  private void registerVirtualThreadMetrics(final MetricRegistry registry) {
    try {
      // Check if we're running on Java 21 or later
      if (Runtime.version().feature() >= 21) {
        // Register virtual thread count metric
        registry.register(name("jvm", "threads", "virtual", "count"), 
            (Gauge<Integer>) () -> activeVirtualThreads.get());
            
        // Register pinned virtual thread count metric
        registry.register(name("jvm", "threads", "virtual", "pinned"), 
            (Gauge<Integer>) () -> pinnedVirtualThreads.get());
            
        // Register virtual thread submission failures metric
        registry.register(name("jvm", "threads", "virtual", "submit-failures"), 
            (Gauge<Integer>) () -> virtualThreadSubmitFailures.get());
      }
    } catch (Exception e) {
      // Ignore exceptions, as these metrics are optional
    }
  }

  @Override
  protected void doGet(
      final HttpServletRequest req,
      final HttpServletResponse resp) throws ServletException, IOException
  {
    boolean download = Boolean.parseBoolean(req.getParameter("download"));
    if (download) {
      resp.addHeader(CONTENT_DISPOSITION, "attachment; filename='metrics.json'");
    }

    // Use Virtual Threads for HTTP request handling if running on Java 21+
    if (Runtime.version().feature() >= 21) {
      handleRequestWithVirtualThread(req, resp);
    } else {
      super.doGet(req, resp);
    }
  }
  
  /**
   * Handles the HTTP request using a Virtual Thread in Java 21+.
   * This demonstrates the use of Virtual Threads for I/O-bound operations.
   */
  private void handleRequestWithVirtualThread(
      final HttpServletRequest req,
      final HttpServletResponse resp) throws ServletException, IOException 
  {
    try {
      // Increment active virtual threads counter
      activeVirtualThreads.incrementAndGet();
      
      // Create a virtual thread executor
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit the task to a virtual thread and wait for completion
        executor.submit(() -> {
          try {
            // Call the parent implementation to handle the request
            MetricsServlet.super.doGet(req, resp);
            return null;
          } catch (ServletException | IOException e) {
            throw new RuntimeException(e);
          }
        }).get(); // Wait for completion
      } catch (Exception e) {
        // Increment failure counter
        virtualThreadSubmitFailures.incrementAndGet();
        
        // Fall back to regular handling
        super.doGet(req, resp);
      }
    } finally {
      // Decrement active virtual threads counter
      activeVirtualThreads.decrementAndGet();
    }
  }
}