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

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import org.sonatype.nexus.security.SecurityFilter;

import com.codahale.metrics.servlet.InstrumentedFilter;
import com.codahale.metrics.servlets.PingServlet;
import com.google.inject.servlet.ServletModule;
import io.prometheus.client.exporter.servlet.MetricsServlet;

// Java 21 Virtual Threads support for metrics endpoints
// Dropwizard Metrics 4.2.25 and Prometheus Client 0.16.0 integration

/**
 * Servlet module for Metrics module.
 *
 * @since 3.38
 */
public abstract class MetricsServletModule
    extends ServletModule
{
  private final String mountPoint;

  protected MetricsServletModule(final String mountPoint) {
    this.mountPoint = mountPoint;
  }

  @Override
  protected void configureServlets() {
    bind(MetricsServlet.class);
    bind(HealthCheckServlet.class);
    bind(VirtualThreadMetricsServlet.class);

    // Configure servlets with virtual threads for improved I/O performance
    serve(mountPoint + "/ping").with(new PingServlet(), createVirtualThreadServletConfig());
    serve(mountPoint + "/threads").with(new ThreadDumpServlet(), createVirtualThreadServletConfig());
    serve(mountPoint + "/data").with(MetricsServlet.class, createVirtualThreadServletConfig());
    serve(mountPoint + "/healthcheck").with(HealthCheckServlet.class, createVirtualThreadServletConfig());
    serve(mountPoint + "/prometheus").with(new MetricsServlet(), createVirtualThreadServletConfig());
    serve(mountPoint + "/virtualthreads").with(VirtualThreadMetricsServlet.class, createVirtualThreadServletConfig());

    // record metrics for all webapp access
    filter("/*").through(new InstrumentedFilter());

    bind(SecurityFilter.class);

    // configure security
    bindSecurityFilter();
  }

  /**
   * Creates a servlet configuration that uses Java 21 Virtual Threads for improved scalability.
   * Virtual threads are particularly effective for I/O-bound operations like metrics endpoints.
   * This allows the metrics endpoints to handle many concurrent requests with minimal resource usage.
   *
   * @return Map of servlet configuration parameters
   * @since 3.60
   */
  private Map<String, String> createVirtualThreadServletConfig() {
    Map<String, String> params = new HashMap<>();
    params.put("executor", "virtualThread");
    params.put("async-supported", "true");
    return params;
  }

  protected abstract void bindSecurityFilter();
}