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
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.nexus.security.SecurityFilter;

import com.codahale.metrics.Gauge;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.servlet.InstrumentedFilter;
import com.codahale.metrics.servlets.PingServlet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import com.codahale.metrics.jvm.VirtualThreadsGaugeSet;
import com.google.inject.servlet.ServletModule;
import io.prometheus.client.exporter.MetricsServlet;
import io.prometheus.client.hotspot.DefaultExports;
import io.prometheus.client.hotspot.StandardExports;

/**
 * Servlet module for Metrics module.
 * Provides endpoints for monitoring system metrics, including Java 21 Virtual Thread metrics.
 *
 * @since 3.38
 * @see VirtualThreadMetricsServlet
 * @see ThreadDumpServlet
 * @see MetricsServlet
 * @see HealthCheckServlet
 */
public abstract class MetricsServletModule
    extends ServletModule
{
  private final String mountPoint;

  protected MetricsServletModule(final String mountPoint) {
    this.mountPoint = mountPoint;
  }

  /**
   * Creates a ThreadFactory that produces virtual threads.
   * Uses Java 21 Virtual Thread API to create lightweight threads for metrics operations.
   * 
   * @return A ThreadFactory that creates virtual threads
   * @since Java 21
   */
  private ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().name("metrics-virtual-", 0).factory();
  }
  
  /**
   * A servlet that exposes Virtual Thread metrics.
   * Provides information about virtual thread usage and performance.
   */
  private static class VirtualThreadMetricsServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
      resp.setContentType("application/json");
      resp.setStatus(HttpServletResponse.SC_OK);
      
      try (PrintWriter writer = resp.getWriter()) {
        ObjectMapper mapper = new ObjectMapper();
        VirtualThreadsGaugeSet gaugeSet = new VirtualThreadsGaugeSet();
        Map<String, Object> metrics = new HashMap<>();
        
        // Get all virtual thread metrics
        gaugeSet.getMetrics().forEach((key, value) -> {
          if (value instanceof Gauge) {
            metrics.put(key.toString(), ((Gauge<?>) value).getValue());
          }
        });
        
        // Add additional thread information
        metrics.put("platform.threads.count", Thread.activeCount());
        metrics.put("timestamp", System.currentTimeMillis());
        
        writer.write(mapper.writeValueAsString(metrics));
      }
    }
  }

  @Override
  protected void configureServlets() {
    bind(MetricsServlet.class);
    bind(HealthCheckServlet.class);
    
    // Initialize Prometheus default exports for JVM metrics with Java 21 support
    DefaultExports.initialize();
    new StandardExports().register(); // Register standard JVM metrics
    
    // Register Virtual Threads metrics for Java 21
    SharedMetricRegistries.getOrCreate("default").register("virtualThreads", new VirtualThreadsGaugeSet());
    SharedMetricRegistries.getOrCreate("default").register("threadStates", new ThreadStatesGaugeSet());

    // Create servlets using virtual threads for better scalability with Java 21
    ThreadFactory virtualThreadFactory = createVirtualThreadFactory();
    
    // Configure servlet endpoints with virtual thread support
    // Virtual threads provide significant throughput improvements for I/O-bound operations
    PingServlet pingServlet = new PingServlet();
    ThreadDumpServlet threadDumpServlet = new ThreadDumpServlet();
    VirtualThreadMetricsServlet virtualThreadMetricsServlet = new VirtualThreadMetricsServlet();
    
    // Configure servlet endpoints
    serve(mountPoint + "/ping").with(pingServlet);
    serve(mountPoint + "/threads").with(threadDumpServlet);
    serve(mountPoint + "/virtualthreads").with(virtualThreadMetricsServlet);
    serve(mountPoint + "/data").with(MetricsServlet.class);
    serve(mountPoint + "/healthcheck").with(HealthCheckServlet.class);
    
    // Configure Prometheus endpoint with the updated client version 0.16.0
    // Using the newer API with improved thread handling
    MetricsServlet prometheusServlet = new MetricsServlet();
    serve(mountPoint + "/prometheus").with(prometheusServlet);

    // Record metrics for all webapp access
    filter("/*").through(new InstrumentedFilter());

    bind(SecurityFilter.class);

    // configure security
    bindSecurityFilter();
  }

  protected abstract void bindSecurityFilter();
}