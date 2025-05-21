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
package org.sonatype.nexus.internal.system;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.log.LogManager;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.common.system.FileDescriptorProvider;

import org.elasticsearch.monitor.process.ProcessProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * {@link FileDescriptorProvider} that returns the file descriptor count using the Elastic {@link ProcessProbe}.
 * <p>
 * This implementation includes caching with Virtual Threads for improved performance and compatibility with Java 21.
 *
 * @since 3.5
 */
@Named
@Singleton
public class ProcessProbeFileDescriptorProvider
    extends StateGuardLifecycleSupport
    implements FileDescriptorProvider
{
  private static final Logger log = LoggerFactory.getLogger(ProcessProbeFileDescriptorProvider.class);
  
  private static final Duration CACHE_REFRESH_INTERVAL = Duration.ofMinutes(1);
  
  private final AtomicLong cachedFileDescriptorCount = new AtomicLong(-1);
  
  private ScheduledExecutorService cacheRefreshExecutor;

  @PostConstruct
  public void init() {
    // Initialize the cache with the current value
    refreshCache();
  }
  
  @Override
  protected void doStart() {
    // Use Virtual Threads for cache refresh operations
    cacheRefreshExecutor = Executors.newScheduledThreadPool(1, 
        task -> Thread.ofVirtual().name("file-descriptor-cache-refresh").unstarted(task));
    
    // Schedule periodic cache refresh
    cacheRefreshExecutor.scheduleAtFixedRate(
        this::refreshCache,
        CACHE_REFRESH_INTERVAL.toMinutes(), 
        CACHE_REFRESH_INTERVAL.toMinutes(), 
        TimeUnit.MINUTES);
  }

  @Override
  protected void doStop() {
    if (cacheRefreshExecutor != null) {
      cacheRefreshExecutor.shutdown();
      try {
        if (!cacheRefreshExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
          cacheRefreshExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        cacheRefreshExecutor.shutdownNow();
      }
      cacheRefreshExecutor = null;
    }
  }
  
  /**
   * Refreshes the cached file descriptor count using the Elasticsearch ProcessProbe.
   */
  private void refreshCache() {
    try {
      long maxFileDescriptorCount = ProcessProbe.getInstance().getMaxFileDescriptorCount();
      cachedFileDescriptorCount.set(maxFileDescriptorCount);
      log.debug("Refreshed file descriptor count cache: {}", maxFileDescriptorCount);
    }
    catch (Exception e) {
      log.error("Failed to refresh file descriptor count cache", e);
      // Keep the previous value if available, otherwise set to -1 to indicate error
      if (cachedFileDescriptorCount.get() == -1) {
        cachedFileDescriptorCount.set(-1);
      }
    }
  }

  @Override
  @Guarded(by = STARTED)
  public long getFileDescriptorCount() {
    long count = cachedFileDescriptorCount.get();
    
    // If cache is not initialized or has an error value, try to get a fresh value
    if (count == -1) {
      try {
        count = ProcessProbe.getInstance().getMaxFileDescriptorCount();
        cachedFileDescriptorCount.set(count);
      }
      catch (Exception e) {
        log.warn("Error getting file descriptor count from ProcessProbe", e);
        // Return a fallback value
        return 1024; // Common default minimum value
      }
    }
    
    return count;
  }
}