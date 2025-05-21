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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.Time;
import org.sonatype.goodies.lifecycle.Lifecycle;
import org.sonatype.nexus.httpclient.SSLContextSelector;

import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.impl.conn.DefaultHttpClientConnectionOperator;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycleManager.isShuttingDown;
import static org.sonatype.nexus.httpclient.HttpSchemes.HTTP;
import static org.sonatype.nexus.httpclient.HttpSchemes.HTTPS;

/**
 * Shared {@link PoolingHttpClientConnectionManager} optimized for Java 21 Virtual Threads.
 *
 * @since 3.0
 */
@Named("shared")
@Singleton
public class SharedHttpClientConnectionManager
    extends PoolingHttpClientConnectionManager
    implements Lifecycle
{
  private static final Logger log = LoggerFactory.getLogger(SharedHttpClientConnectionManager.class);

  private final Time connectionPoolIdleTime;

  private final Time connectionPoolEvictingDelayTime;

  private ExecutorService virtualThreadExecutor;
  
  private ScheduledExecutorService connectionEvictionExecutor;

  @Inject
  public SharedHttpClientConnectionManager(
      final List<SSLContextSelector> sslContextSelectors,
      @Named("${nexus.httpclient.connectionpool.size:-20}") final int connectionPoolSize,
      @Named("${nexus.httpclient.connectionpool.maxSize:-200}") final int connectionPoolMaxSize,
      @Named("${nexus.httpclient.connectionpool.idleTime:-30s}") final Time connectionPoolIdleTime,
      @Named("${nexus.httpclient.connectionpool.evictingDelayTime:-5s}") final Time connectionPoolEvictingDelayTime,
      @Named("${nexus.httpclient.connectionpool.validateAfterInactivityTime:-2s}") final Time connectionPoolValidateAfterInactivityTime,
      @Named("${nexus.httpclient.connectionpool.default.requestTimeout:-20s}") final Time defaultSocketTimeout)
  {
    super(
        new DefaultHttpClientConnectionOperator(createRegistry(sslContextSelectors), null, null),
        null,
        connectionPoolIdleTime.toMillis(),
        TimeUnit.MILLISECONDS
    );

    setMaxTotal(connectionPoolMaxSize);
    log.debug("Connection pool max-size: {}", connectionPoolMaxSize);

    setDefaultMaxPerRoute(Math.min(connectionPoolSize, connectionPoolMaxSize));
    log.debug("Connection pool size: {}", connectionPoolSize);

    this.connectionPoolIdleTime = checkNotNull(connectionPoolIdleTime);
    this.connectionPoolEvictingDelayTime = checkNotNull(connectionPoolEvictingDelayTime);
    setValidateAfterInactivity(connectionPoolValidateAfterInactivityTime.toMillisI());
    log.debug("Connection pool idle-time: {}, evicting delay: {}, validate after inactivity: {}",
        connectionPoolIdleTime, connectionPoolEvictingDelayTime, connectionPoolValidateAfterInactivityTime);

    setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(defaultSocketTimeout.toMillisI()).build());
    log.debug("Default socket timeout {}", defaultSocketTimeout);
  }

  private static Registry<ConnectionSocketFactory> createRegistry(final List<SSLContextSelector> sslContextSelectors) {
    RegistryBuilder<ConnectionSocketFactory> builder = RegistryBuilder.create();
    builder.register(HTTP, PlainConnectionSocketFactory.getSocketFactory());
    builder.register(HTTPS, new NexusSSLConnectionSocketFactory(sslContextSelectors));
    return builder.build();
  }

  /**
   * Do nothing in order to avoid unwanted shutdown of shared connection manager.
   *
   * @see #stop()
   */
  @Override
  public void shutdown() {
    // empty
  }

  //
  // Lifecycle
  //

  @Override
  public void start() throws Exception {
    // Create a virtual thread executor for connection operations
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    log.debug("Created virtual thread executor for connection operations");
    
    // Schedule connection eviction using virtual threads
    connectionEvictionExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = Thread.ofVirtual().name("nexus-httpclient-eviction-scheduler").unstarted(r);
      t.setDaemon(true);
      return t;
    });
    
    connectionEvictionExecutor.scheduleWithFixedDelay(
        this::evictConnections,
        connectionPoolEvictingDelayTime.toMillis(),
        connectionPoolEvictingDelayTime.toMillis(),
        TimeUnit.MILLISECONDS
    );
    
    log.debug("Started connection eviction scheduler with delay {} ms", connectionPoolEvictingDelayTime.toMillis());
  }

  /**
   * Evicts expired and idle connections using virtual threads for non-blocking operation
   */
  private void evictConnections() {
    virtualThreadExecutor.execute(() -> {
      try {
        log.debug("Closing expired connections");
        closeExpiredConnections();
      }
      catch (Exception e) {
        log.warn("Failed to close expired connections", e);
      }
    });
    
    virtualThreadExecutor.execute(() -> {
      try {
        log.debug("Closing idle connections (idle time: {})", connectionPoolIdleTime);
        closeIdleConnections(connectionPoolIdleTime.toMillis(), TimeUnit.MILLISECONDS);
      }
      catch (Exception e) {
        log.warn("Failed to close idle connections", e);
      }
    });
  }

  @Override
  public void stop() throws Exception {
    if (connectionEvictionExecutor != null) {
      connectionEvictionExecutor.shutdown();
      try {
        if (!connectionEvictionExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          connectionEvictionExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        connectionEvictionExecutor.shutdownNow();
      }
      connectionEvictionExecutor = null;
      log.debug("Stopped connection eviction scheduler");
    }
    
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        virtualThreadExecutor.shutdownNow();
      }
      virtualThreadExecutor = null;
      log.debug("Stopped virtual thread executor");
    }

    // underlying pool cannot be restarted, so avoid shutting it down when bouncing the service
    if (isShuttingDown()) {
      super.shutdown();
    }
  }
}