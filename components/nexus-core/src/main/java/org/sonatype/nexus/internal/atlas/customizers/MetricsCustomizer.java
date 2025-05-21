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
package org.sonatype.nexus.internal.atlas.customizers;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.util.SortedMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.supportzip.GeneratedContentSourceSupport;
import org.sonatype.nexus.supportzip.SupportBundle;
import org.sonatype.nexus.supportzip.SupportBundleCustomizer;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck.Result;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.codahale.metrics.json.HealthCheckModule;
import com.codahale.metrics.json.MetricsModule;
import com.codahale.metrics.jvm.ThreadDump;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.HIGH;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Priority.OPTIONAL;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.METRICS;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.SYSINFO;
import static org.sonatype.nexus.supportzip.SupportBundle.ContentSource.Type.THREAD;

/**
 * Adds metrics (threads,metrics,healthcheck) to support bundle.
 * 
 * Uses Java 21 features including Virtual Threads for improved performance,
 * Record Patterns for handling structured data, and String Templates for logging.
 *
 * @since 2.7
 */
@Named
@Singleton
public class MetricsCustomizer
    extends ComponentSupport
    implements SupportBundleCustomizer
{
  private final MetricRegistry metricRegistry;

  private final HealthCheckRegistry healthCheckRegistry;

  private final ThreadDump threadDump;

  private final ObjectMapper healthCheckObjectMapper;

  private final ObjectMapper metricsObjectMapper;
  
  // Virtual Thread executor for I/O-bound operations
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public MetricsCustomizer(final MetricRegistry metricRegistry, final HealthCheckRegistry healthCheckRegistry) {
    this.metricRegistry = checkNotNull(metricRegistry);
    this.healthCheckRegistry = checkNotNull(healthCheckRegistry);
    this.threadDump = new ThreadDump(ManagementFactory.getThreadMXBean());
    this.healthCheckObjectMapper = new ObjectMapper()
        .registerModule(new HealthCheckModule())
        .enable(SerializationFeature.INDENT_OUTPUT);
    this.metricsObjectMapper = new ObjectMapper()
        .registerModule(new MetricsModule(
            TimeUnit.SECONDS, // rate-unit
            TimeUnit.SECONDS, // duration-unit
            false // show-samples
        ))
        .enable(SerializationFeature.INDENT_OUTPUT);
    
    // Create a virtual thread per task executor for I/O-bound operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    log.info(STR."Initialized MetricsCustomizer with virtual thread support");
  }

  @Override
  public void customize(final SupportBundle supportBundle) {
    // add thread-dump (now aware of virtual threads)
    supportBundle.add(new GeneratedContentSourceSupport(THREAD, "info/threads.txt", HIGH)
    {
      @Override
      protected void generate(final File file) {
        log.debug(STR."Generating thread dump to \{file.getAbsolutePath()}");
        try (FileOutputStream fos = new FileOutputStream(file)) {
          threadDump.dump(fos);
        }
        catch (IOException e) {
          log.error(STR."Failed to generate thread dump: \{e.getMessage()}", e);
          throw new UncheckedIOException(e);
        }
      }
    });

    // add healthchecks using virtual threads and record patterns
    supportBundle.add(new GeneratedContentSourceSupport(SYSINFO, "info/healthcheck.json", OPTIONAL)
    {
      @Override
      protected void generate(final File file) {
        log.debug(STR."Running health checks and writing results to \{file.getAbsolutePath()}");
        
        // Run health checks and get results
        SortedMap<String, Result> results = healthCheckRegistry.runHealthChecks();
        
        // Use virtual threads for I/O operations
        Future<?> future = virtualThreadExecutor.submit(() -> {
          try (FileOutputStream fos = new FileOutputStream(file)) {
            healthCheckObjectMapper.writeValue(fos, results);
            
            // Use record patterns to process results for logging
            results.forEach((name, result) -> {
              if (result instanceof Result(boolean healthy, String message, Throwable error)) {
                if (!healthy) {
                  if (error != null) {
                    log.warn(STR."Health check '\{name}' failed: \{message}", error);
                  } else {
                    log.warn(STR."Health check '\{name}' failed: \{message}");
                  }
                } else {
                  log.debug(STR."Health check '\{name}' passed: \{message}");
                }
              }
            });
          } catch (IOException e) {
            log.error(STR."Failed to write health check results: \{e.getMessage()}", e);
            throw new UncheckedIOException(e);
          }
        });
        
        try {
          // Wait for the virtual thread to complete
          future.get();
        } catch (Exception e) {
          log.error(STR."Error while processing health check results: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }
    });

    // add metrics using virtual threads
    supportBundle.add(new GeneratedContentSourceSupport(METRICS, "info/metrics.json", OPTIONAL)
    {
      @Override
      protected void generate(final File file) {
        log.debug(STR."Collecting metrics and writing to \{file.getAbsolutePath()}");
        
        // Use virtual threads for I/O operations
        Future<?> future = virtualThreadExecutor.submit(() -> {
          try (FileOutputStream fos = new FileOutputStream(file)) {
            metricsObjectMapper.writeValue(fos, metricRegistry);
            log.debug(STR."Successfully wrote metrics to \{file.getAbsolutePath()}");
          } catch (IOException e) {
            log.error(STR."Failed to write metrics: \{e.getMessage()}", e);
            throw new UncheckedIOException(e);
          }
        });
        
        try {
          // Wait for the virtual thread to complete
          future.get();
        } catch (Exception e) {
          log.error(STR."Error while processing metrics: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }
    });
    
    log.info(STR."Added metrics customizations to support bundle");
  }
}