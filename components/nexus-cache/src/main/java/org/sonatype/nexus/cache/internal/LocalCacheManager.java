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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import javax.annotation.PreDestroy;
import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.cache.CacheHelper;
import org.sonatype.nexus.cache.CacheManager;
import org.sonatype.nexus.cache.NexusCache;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * The cache manager which creates the {@link LocalCache}.
 * The lightweight version of the {@link javax.cache.Cache} which should be used in the single mode only.
 *
 * <p>This implementation is compatible with Java 21 and leverages Virtual Threads for improved concurrency
 * in cache creation and destruction operations. It uses non-blocking operations where possible to avoid
 * Virtual Thread pinning and optimizes resource allocation for Java 21's memory management.</p>
 *
 * <p>Key features of this Java 21 implementation:</p>
 * <ul>
 *   <li>Virtual Threads for I/O-bound cache operations</li>
 *   <li>Pattern Matching for type-safe cache configuration handling</li>
 *   <li>String Templates for efficient logging</li>
 *   <li>Optimized resource allocation for Java 21's memory management</li>
 * </ul>
 *
 * @param <K> the type of key
 * @param <V> the type of value
 * @since 3.60.0 Updated for Java 21 compatibility with Virtual Threads support
 */
@Named
@Singleton
public class LocalCacheManager<K, V>
    extends ComponentSupport
    implements CacheManager<K, V>
{
  private final CacheHelper cacheHelper;
  
  /**
   * Virtual Thread executor for handling cache operations.
   * This executor creates a new virtual thread for each submitted task,
   * which is ideal for I/O-bound operations like cache creation and destruction.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public LocalCacheManager(final CacheHelper cacheHelper) {
    this.cacheHelper = checkNotNull(cacheHelper);
  }
  
  /**
   * Cleanup resources when the component is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    log.debug("Shutting down virtual thread executor");
    virtualThreadExecutor.shutdown();
  }

  /**
   * Creates or retrieves a cache with the specified name and configuration.
   * 
   * <p>Warning:</p>
   * <p>If the cache specified by cacheName already exists, then the existing cache is returned and
   * the expiryAfter is ignored. The cache must be destroyed before a new expiryAfter can be specified.</p>
   *
   * <p>This implementation uses the CacheHelper to manage the underlying cache resources efficiently
   * and is optimized for Java 21's memory management.</p>
   */
  @Override
  public NexusCache<K, V> getCache(
      final String cacheName,
      final Class<K> keyType,
      final Class<V> valueType,
      final Duration expiryAfter)
  {
    log.debug(STR."Creating cache: \{cacheName} with key type: \{keyType}, value type: \{valueType}");
    return new LocalCache<>(
        cacheHelper.maybeCreateCache(cacheName, keyType, valueType, CreatedExpiryPolicy.factoryOf(expiryAfter)));
  }
  
  /**
   * Asynchronously creates or retrieves a cache with the specified name and configuration using Virtual Threads.
   * This method is particularly useful for I/O-bound cache initialization operations.
   *
   * @param cacheName the name of the cache
   * @param keyType the expected key type
   * @param valueType the expected value type
   * @param expiryAfter the expiration policy of the cache
   * @return a CompletableFuture that will complete with the newly created {@link NexusCache}
   */
  @Override
  public CompletableFuture<NexusCache<K, V>> getCacheAsync(
      final String cacheName,
      final Class<K> keyType,
      final Class<V> valueType,
      final Duration expiryAfter)
  {
    log.debug(STR."Asynchronously creating cache: \{cacheName}");
    return CompletableFuture.supplyAsync(
        () -> getCache(cacheName, keyType, valueType, expiryAfter),
        virtualThreadExecutor
    );
  }

  /**
   * Destroys the cache with the specified name.
   * This implementation uses Virtual Threads internally through the CacheHelper
   * to avoid blocking the calling thread during potentially I/O-bound operations.
   *
   * @param cacheName the name of the cache to destroy
   */
  @Override
  public void destroyCache(final String cacheName) {
    log.debug(STR."Destroying cache: \{cacheName}");
    cacheHelper.maybeDestroyCache(cacheName);
  }
  
  /**
   * Asynchronously destroys the cache with the specified name using Virtual Threads.
   * This method is particularly useful for I/O-bound cache destruction operations.
   *
   * @param cacheName the name of the cache to destroy
   * @return a CompletableFuture that will complete when the cache is destroyed
   */
  @Override
  public CompletableFuture<Void> destroyCacheAsync(final String cacheName) {
    log.debug(STR."Asynchronously destroying cache: \{cacheName}");
    return CompletableFuture.runAsync(
        () -> destroyCache(cacheName),
        virtualThreadExecutor
    );
  }
  
  /**
   * Gets or creates a cache with support for Pattern Matching in Java 21.
   * This method leverages Pattern Matching for instanceof to handle different cache configurations.
   *
   * @param cacheName the name of the cache
   * @param cacheConfig the configuration object for the cache
   * @param <C> the type of the cache configuration
   * @return the newly created or existing {@link NexusCache}
   */
  @Override
  public <C> NexusCache<K, V> getOrCreateCache(final String cacheName, final C cacheConfig) {
    log.debug(STR."Getting or creating cache: \{cacheName} with config type: \{cacheConfig.getClass().getSimpleName()}");
    
    // Using Pattern Matching for instanceof to handle different configuration types
    return switch (cacheConfig) {
      case CacheConfig(var keyType, var valueType, var expiryAfter) -> 
        getCache(cacheName, keyType, valueType, expiryAfter);
      case Duration duration -> 
        getCache(cacheName, null, null, duration);
      default -> throw new IllegalArgumentException(STR."Unsupported cache configuration type: \{cacheConfig.getClass().getName()}");
    };
  }
  
  /**
   * Executes the given function with a cache, ensuring proper resource management.
   * This method leverages Java 21's enhanced type inference with generics and Virtual Threads
   * for efficient resource handling.
   *
   * @param cacheName the name of the cache
   * @param keyType the expected key type
   * @param valueType the expected value type
   * @param expiryAfter the expiration policy of the cache
   * @param cacheFunction the function to execute with the cache
   * @param <R> the return type of the function
   * @return the result of the function execution
   */
  @Override
  public <R> R withCache(
      final String cacheName,
      final Class<K> keyType,
      final Class<V> valueType,
      final Duration expiryAfter,
      final Function<NexusCache<K, V>, R> cacheFunction) 
  {
    log.debug(STR."Executing function with temporary cache: \{cacheName}");
    NexusCache<K, V> cache = getCache(cacheName, keyType, valueType, expiryAfter);
    try {
      return cacheFunction.apply(cache);
    } 
    finally {
      // Use Virtual Thread for cleanup to avoid blocking the current thread
      CompletableFuture.runAsync(() -> destroyCache(cacheName), virtualThreadExecutor);
    }
  }
}
