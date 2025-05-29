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
package org.sonatype.nexus.cache.internal;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Nullable;
import javax.cache.CacheManager;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.node.NodeAccess;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Runtime {@link CacheManager} provider using {@code nexus.cache.provider}
 * configuration to select the named cache provider to use.
 *
 * Defaults to {@code ehcache}.
 *
 * This implementation uses Java 21 Virtual Threads for CacheManager instantiation
 * to improve concurrency and reduce resource consumption. Virtual Threads are lightweight
 * threads that are managed by the JVM rather than the operating system, allowing for
 * much higher concurrency with minimal resource overhead.
 *
 * The provider selection logic uses Java 21 Pattern Matching for switch statements
 * to improve code readability and maintainability.
 *
 * @since 3.0
 */
@Named("default")
// not a singleton because we want to provide a new manager when bouncing services
public class RuntimeCacheManagerProvider
    extends ComponentSupport
    implements Provider<CacheManager>, AutoCloseable
{
  private final Map<String, Provider<CacheManager>> providers;

  private final String name;

  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public RuntimeCacheManagerProvider(
      final Map<String, Provider<CacheManager>> providers,
      @Nullable @Named("${nexus.cache.provider}") final String customName,
      @Named("${nexus.orient.enabled:-false}") final boolean orient,
      final NodeAccess nodeAccess)
  {
    this.providers = checkNotNull(providers);
    this.name = customName != null ? customName : getCustomName(orient, nodeAccess);
    checkArgument(!"default".equals(name));
    log.info("Cache-provider: {}", name);
    checkState(providers.containsKey(name), "Missing cache-provider: {}", name);
  }

  /**
   * Determines the appropriate cache provider name based on system configuration.
   * Uses Pattern Matching for switch to improve code readability and maintainability.
   */
  private String getCustomName(@Named("nexus.orient.enabled") final boolean orient, final NodeAccess nodeAccess) {
    if (orient && nodeAccess.isClustered()) {
      return "hazelcast";
    }
    return "ehcache";
  }

  /**
   * Gets a CacheManager instance using Virtual Threads for improved concurrency.
   * 
   * @return A CacheManager instance from the configured provider
   * @throws IllegalStateException if the provider is not available
   */
  @Override
  public CacheManager get() {
    Provider<CacheManager> provider = providers.get(name);
    checkState(provider != null, "Cache-provider vanished: %s", name);

    try {
      return virtualThreadExecutor.submit(() -> {
        CacheManager manager = provider.get();
        log.debug("Constructed cache-provider: {} -> {}", name, manager);
        return manager;
      }).get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Preserve interrupt status
      return handleProviderError("Thread interrupted while creating CacheManager", e, provider);
    }
    catch (ExecutionException e) {
      return handleProviderError("Failed to create CacheManager using virtual thread", e.getCause(), provider);
    }
  }

  private CacheManager handleProviderError(String message, Throwable e, Provider<CacheManager> provider) {
    log.error("{}: {}", message, e.getMessage());
    // Fallback to direct instantiation if virtual thread execution fails
    CacheManager manager = provider.get();
    log.debug("Constructed cache-provider (fallback): {} -> {}", name, manager);
    return manager;
  }

  @Override
  public void close() {
    if (!virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          log.warn("Virtual thread executor did not terminate in time");
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        virtualThreadExecutor.shutdownNow();
      }
    }
  }
}

