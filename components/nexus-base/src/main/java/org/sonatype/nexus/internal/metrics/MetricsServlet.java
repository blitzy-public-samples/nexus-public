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
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import io.dropwizard.metrics.MetricRegistry;
import io.dropwizard.metrics.jvm.BufferPoolMetricSet;
import io.dropwizard.metrics.jvm.FileDescriptorRatioGauge;
import io.dropwizard.metrics.jvm.GarbageCollectorMetricSet;
import io.dropwizard.metrics.jvm.JvmAttributeGaugeSet;
import io.dropwizard.metrics.jvm.MemoryUsageGaugeSet;
import io.dropwizard.metrics.jvm.ThreadStatesGaugeSet;

import static io.dropwizard.metrics.MetricRegistry.name;
import static com.google.common.net.HttpHeaders.CONTENT_DISPOSITION;

/**
 * Customized {@link io.dropwizard.metrics.servlets.MetricsServlet} to support injection and download.
 * Updated for Java 21 with virtual thread metrics.
 *
 * @since 3.0
 */
@Singleton
public class MetricsServlet
    extends io.dropwizard.metrics.servlets.MetricsServlet
{
  @Inject
  public MetricsServlet(final MetricRegistry registry, 
                        @Named("metricsExecutor") final Executor executor,
                        final VirtualThreadMetrics virtualThreadMetrics) {
    super(registry);

    // JVM metrics are no longer automatically added in dropwizard-metrics
    registry.register(name("jvm", "vm"), new JvmAttributeGaugeSet());
    registry.register(name("jvm", "memory"), new MemoryUsageGaugeSet());
    registry.register(name("jvm", "buffers"), new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));
    registry.register(name("jvm", "fd_usage"), new FileDescriptorRatioGauge());
    registry.register(name("jvm", "thread-states"), new ThreadStatesGaugeSet());
    registry.register(name("jvm", "garbage-collectors"), new GarbageCollectorMetricSet());
    
    // Register Java 21 virtual thread metrics
    registry.register(name("jvm", "virtual-threads"), virtualThreadMetrics);
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

    super.doGet(req, resp);
  }
}