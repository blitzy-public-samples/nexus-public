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

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.systemchecks.ConditionallyAppliedHealthCheck;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import org.eclipse.sisu.BeanEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;

/**
 * Registrar for conditionally applied health checks that uses Virtual Threads for execution.
 * <p>
 * This implementation leverages Java 21 Virtual Threads to execute health checks concurrently
 * and efficiently, with configurable timeout handling to prevent hanging health checks.
 */
@Named
@Singleton
@ManagedLifecycle(phase = SERVICES)
public class ConditionallyAppliedHealthCheckRegistrar
    extends StateGuardLifecycleSupport
{
  private static final Logger log = LoggerFactory.getLogger(ConditionallyAppliedHealthCheckRegistrar.class);
  
  /**
   * Default timeout for health check execution in milliseconds.
   */
  private static final long DEFAULT_HEALTH_CHECK_TIMEOUT_MS = 5000;

  private final HealthCheckRegistry healthCheckRegistry;

  private final Iterable<BeanEntry<Named, ConditionallyAppliedHealthCheck>> conditionallyAppliedHealthChecks;

  @Inject
  public ConditionallyAppliedHealthCheckRegistrar(
      final HealthCheckRegistry healthCheckRegistry,
      final Iterable<BeanEntry<Named, ConditionallyAppliedHealthCheck>> conditionallyAppliedHealthChecks)
  {
    this.healthCheckRegistry = checkNotNull(healthCheckRegistry);
    this.conditionallyAppliedHealthChecks = checkNotNull(conditionallyAppliedHealthChecks);
  }

  @Override
  protected void doStart() throws Exception {
    conditionallyAppliedHealthChecks.forEach(healthCheckBeanEntry -> {
      String name = healthCheckBeanEntry.getKey().value();
      ConditionallyAppliedHealthCheck check = healthCheckBeanEntry.getValue();
      if (check.shouldApply()) {
        // Register a wrapper health check that executes the actual check using Virtual Threads
        healthCheckRegistry.register(name, new VirtualThreadHealthCheckWrapper(check, name));
        log.debug("Registered health check: {}", name);
      }
    });
  }

  @Override
  protected void doStop() throws Exception {
    conditionallyAppliedHealthChecks.forEach(healthCheckBeanEntry -> {
      String name = healthCheckBeanEntry.getKey().value();
      healthCheckRegistry.unregister(name);
      log.debug("Unregistered health check: {}", name);
    });
  }

  /**
   * Wrapper for health checks that executes them using Virtual Threads with timeout handling.
   */
  private static class VirtualThreadHealthCheckWrapper extends HealthCheck {
    private final ConditionallyAppliedHealthCheck delegate;
    private final String name;

    VirtualThreadHealthCheckWrapper(ConditionallyAppliedHealthCheck delegate, String name) {
      this.delegate = delegate;
      this.name = name;
    }

    @Override
    protected Result check() throws Exception {
      CompletableFuture<Result> future = new CompletableFuture<>();

      // Execute the health check in a virtual thread
      Thread.ofVirtual()
          .name("health-check-" + name)
          .start(() -> {
            try {
              Result result = delegate.execute();
              future.complete(result);
            }
            catch (Exception e) {
              future.completeExceptionally(e);
            }
          });

      try {
        // Wait for the health check to complete with a timeout
        return future.get(DEFAULT_HEALTH_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      }
      catch (TimeoutException e) {
        log.warn("Health check '{}' timed out after {} ms", name, DEFAULT_HEALTH_CHECK_TIMEOUT_MS);
        return Result.unhealthy("Health check timed out after " + DEFAULT_HEALTH_CHECK_TIMEOUT_MS + " ms");
      }
      catch (Exception e) {
        log.warn("Health check '{}' failed with exception: {}", name, e.getMessage());
        return Result.unhealthy(e);
      }
    }
  }
}