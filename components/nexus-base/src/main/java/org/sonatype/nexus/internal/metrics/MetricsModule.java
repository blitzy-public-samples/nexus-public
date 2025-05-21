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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import javax.inject.Named;

import com.google.inject.name.Names;
import org.sonatype.nexus.common.app.FeatureFlag;
import org.sonatype.nexus.security.FilterChainModule;
import org.sonatype.nexus.security.SecurityFilter;
import org.sonatype.nexus.security.anonymous.AnonymousFilter;
import org.sonatype.nexus.security.authc.AntiCsrfFilter;
import org.sonatype.nexus.security.authc.NexusAuthenticationFilter;
import org.sonatype.nexus.security.authz.PermissionsFilter;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import io.dropwizard.metrics.Clock;
import io.dropwizard.metrics.MetricRegistry;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.dropwizard.DropwizardExports;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.common.app.FeatureFlags.SESSION_ENABLED;

/**
 * <a href="http://metrics.dropwizard.io">Dropwizard Metrics</a> guice configuration.
 * 
 * Installs servlet endpoints:
 * 
 * <ul>
 * <li>/service/metrics/ping</li>
 * <li>/service/metrics/threads</li>
 * <li>/service/metrics/data</li>
 * <li>/service/metrics/healthcheck</li>
 * <li>/service/metrics/prometheus</li>
 * </ul>
 * 
 * Protected by {@code nexus:metrics:read} permission.
 * 
 * @since 2.5
 */
@Named
@FeatureFlag(name = SESSION_ENABLED)
public class MetricsModule
    extends AbstractModule
{
  private static final Logger log = LoggerFactory.getLogger(MetricsModule.class);

  protected static final String MOUNT_POINT = "/service/metrics";

  @Override
  protected void configure() {
    // NOTE: AdminServletModule (metrics-guice integration) generates invalid links, so wire up servlets ourselves

    final Clock clock = Clock.defaultClock();
    bind(Clock.class).toInstance(clock);

    final JsonFactory jsonFactory = new JsonFactory(new ObjectMapper());
    bind(JsonFactory.class).toInstance(jsonFactory);
    
    // Create a virtual thread-based executor for metrics processing
    final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    bind(Executor.class).annotatedWith(Names.named("metricsExecutor")).toInstance(virtualThreadExecutor);
    
    // Register the main metrics registry
    final MetricRegistry metricRegistry = new MetricRegistry();
    bind(MetricRegistry.class).toInstance(metricRegistry);
    
    // Register Java 21 specific metrics registry
    final MetricRegistry java21Registry = new MetricRegistry().register("jvm.21", new VirtualThreadMetrics());
    bind(MetricRegistry.class).annotatedWith(Names.named("java21Registry")).toInstance(java21Registry);
    
    // Configure Prometheus integration
    final CollectorRegistry collectorRegistry = CollectorRegistry.defaultRegistry;
    collectorRegistry.register(new DropwizardExports(metricRegistry));
    collectorRegistry.register(new DropwizardExports(java21Registry));
    bind(CollectorRegistry.class).toInstance(collectorRegistry);

    install(new MetricsServletModule(MOUNT_POINT)
    {
      @Override
      protected void bindSecurityFilter() {
        filter(MOUNT_POINT + "/*").through(SecurityFilter.class);
      }
    });

    // require permission to use endpoints
    install(new FilterChainModule()
    {
      @Override
      protected void configure() {
        addFilterChain(MOUNT_POINT + "/**",
            NexusAuthenticationFilter.NAME,
            AnonymousFilter.NAME,
            AntiCsrfFilter.NAME,
            PermissionsFilter.config("nexus:metrics:read"));
      }
    });

    log.info("Metrics support configured with Java 21 virtual thread capabilities");
  }
}