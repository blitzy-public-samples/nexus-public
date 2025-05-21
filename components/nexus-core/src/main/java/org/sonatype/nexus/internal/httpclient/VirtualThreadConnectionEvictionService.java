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
package org.sonatype.nexus.internal.httpclient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.Time;

import org.apache.http.conn.HttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Service responsible for periodically evicting expired and idle HTTP client connections
 * using Java 21 Virtual Threads for efficient resource utilization.
 *
 * <p>This service replaces the traditional thread-based {@link ConnectionEvictionThread} with
 * a more scalable and efficient implementation using Virtual Threads.</p>
 *
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadConnectionEvictionService
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadConnectionEvictionService.class);

  private final HttpClientConnectionManager connectionManager;

  private final long idleTimeMillis;

  private final long evictingDelayMillis;

  private final AtomicBoolean running = new AtomicBoolean(false);

  private ExecutorService executor;

  @Inject
  public VirtualThreadConnectionEvictionService(final HttpClientConnectionManager connectionManager,
                                               final @Named("${nexus.httpclient.connection.idle.time:-30s}") Time idleTime,
                                               final @Named("${nexus.httpclient.connection.eviction.delay:-5s}") Time evictingDelayTime)
  {
    this(connectionManager, idleTime.toMillis(), evictingDelayTime.toMillis());
  }

  VirtualThreadConnectionEvictionService(final HttpClientConnectionManager connectionManager,
                                        final long idleTimeMillis,
                                        final long evictingDelayMillis)
  {
    checkArgument(idleTimeMillis > -1, "Keep alive period in milliseconds cannot be negative");
    checkArgument(evictingDelayMillis > 0, "Evicting delay period in milliseconds must be greater than 0");
    this.connectionManager = checkNotNull(connectionManager);
    this.idleTimeMillis = idleTimeMillis;
    this.evictingDelayMillis = evictingDelayMillis;
  }

  /**
   * Starts the connection eviction service.
   */
  @PostConstruct
  public void start() {
    if (running.compareAndSet(false, true)) {
      log.debug("Starting connection eviction service (delay {} millis)", evictingDelayMillis);
      
      // Create a virtual thread executor for the eviction task
      executor = Executors.newVirtualThreadPerTaskExecutor();
      
      // Submit the eviction task to run periodically
      executor.submit(this::evictionLoop);
    }
  }

  /**
   * Stops the connection eviction service.
   */
  @PreDestroy
  public void stop() {
    if (running.compareAndSet(true, false)) {
      log.debug("Stopping connection eviction service");
      
      if (executor != null) {
        executor.shutdown();
        try {
          // Wait for a short time for the executor to shut down gracefully
          if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
            executor.shutdownNow();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          executor.shutdownNow();
        }
        executor = null;
      }
    }
  }

  /**
   * Main eviction loop that runs in a virtual thread.
   */
  private void evictionLoop() {
    log.debug("Connection eviction loop started");
    
    try {
      while (running.get()) {
        try {
          // Sleep for the configured delay
          Thread.sleep(evictingDelayMillis);
          
          // Only proceed if the service is still running
          if (!running.get()) {
            break;
          }
          
          // Close expired connections
          try {
            connectionManager.closeExpiredConnections();
          }
          catch (Exception e) {
            log.warn("Failed to close expired connections", e);
          }
          
          // Close idle connections
          try {
            connectionManager.closeIdleConnections(idleTimeMillis, TimeUnit.MILLISECONDS);
          }
          catch (Exception e) {
            log.warn("Failed to close idle connections", e);
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        catch (Exception e) {
          log.error("Unexpected error in connection eviction loop", e);
        }
      }
    }
    finally {
      log.debug("Connection eviction loop stopped");
    }
  }
}