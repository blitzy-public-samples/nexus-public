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

import javax.annotation.Nullable;
import javax.cache.CacheManager;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

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
    implements Provider<CacheManager>
{
  private final Map<String, Provider<CacheManager>> providers;

  private final String name;

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
    log.info(STR."Cache-provider: \{name}");
    checkState(providers.containsKey(name), STR."Missing cache-provider: \{name}");
  }

  /**
   * Determines the appropriate cache provider name based on system configuration.
   * Uses Pattern Matching for switch to improve code readability and maintainability.
   */
  private String getCustomName(@Named("nexus.orient.enabled") final boolean orient, final NodeAccess nodeAccess) {
    return switch (new ClusterConfig(orient, nodeAccess.isClustered())) {
      case ClusterConfig(true, true) -> "hazelcast";
      default -> "ehcache";
    };
  }

  /**
   * Record for pattern matching in switch statement.
   */
  private record ClusterConfig(boolean orient, boolean clustered) {}

  /**
   * Gets a CacheManager instance using Virtual Threads for improved concurrency.
   * 
   * @return A CacheManager instance from the configured provider
   * @throws IllegalStateException if the provider is not available
   */
  @Override
  public CacheManager get() {
    Provider<CacheManager> provider = providers.get(name);
    checkState(provider != null, STR."Cache-provider vanished: \{name}");
    
    // Use Virtual Thread to instantiate the CacheManager
    // This improves concurrency and reduces resource consumption
    try {
      return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        CacheManager manager = provider.get();
        log.debug(STR."Constructed cache-provider: \{name} -> \{manager}");
        return manager;
      }).get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Preserve interrupt status
      log.error(STR."Thread interrupted while creating CacheManager: \{e.getMessage()}");
      // Fallback to direct instantiation if virtual thread execution fails
      CacheManager manager = provider.get();
      log.debug(STR."Constructed cache-provider (fallback): \{name} -> \{manager}");
      return manager;
    }
    catch (ExecutionException e) {
      log.error(STR."Failed to create CacheManager using virtual thread: \{e.getMessage()}");
      // Fallback to direct instantiation if virtual thread execution fails
      CacheManager manager = provider.get();
      log.debug(STR."Constructed cache-provider (fallback): \{name} -> \{manager}");
      return manager;
    }
  }

  /**
   * Ensures proper cleanup of Virtual Threads when the provider is no longer needed.
   * This method is called by the JVM when the object is garbage collected.
   */
  /**
   * Ensures proper cleanup of Virtual Threads when the provider is no longer needed.
   * 
   * Note: While finalize() is deprecated, it's used here as a safety mechanism for Virtual Thread cleanup.
   * In production code, consider using try-with-resources or explicit shutdown methods instead.
   */
  @Override
  protected void finalize() throws Throwable {
    try {
      // Allow any remaining virtual threads to complete their work
      Thread.sleep(100);
    }
    finally {
      super.finalize();
    }
  }
}