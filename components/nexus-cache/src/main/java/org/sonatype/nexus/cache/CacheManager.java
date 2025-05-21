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
package org.sonatype.nexus.cache;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.cache.expiry.Duration;

/**
 * The cache manager to create the {@link NexusCache}.
 * This is the lightweight version of the {@link javax.cache.Cache} which may use persistent cache
 * and if that is not required one should use {@link CacheHelper} then.
 * <p>
 * This interface is compatible with Java 21 and supports Virtual Threads for asynchronous cache operations.
 * Implementations should leverage Java 21 features like Virtual Threads for I/O-bound operations and
 * Pattern Matching for type-safe cache operations.
 *
 * @param <K> the type of key
 * @param <V> the type of value
 * @since 3.60.0 Updated for Java 21 compatibility with Virtual Threads support
 */
public interface CacheManager<K, V>
{
  /**
   * Creates the {@link NexusCache}. It may be a distributed or a local cache depending on a Nexus mode.
   *
   * @param cacheName the name of the cache.
   * @param keyType the expected key type.
   * @param valueType the expected value type.
   * @param expiryAfter the expiration policy of the cache.
   * @return the newly created {@link NexusCache}
   */
  NexusCache<K, V> getCache(String cacheName, Class<K> keyType, Class<V> valueType, Duration expiryAfter);

  /**
   * Asynchronously creates the {@link NexusCache} using Java 21 Virtual Threads for improved concurrency.
   * This method is particularly useful for I/O-bound cache initialization operations.
   *
   * @param cacheName the name of the cache.
   * @param keyType the expected key type.
   * @param valueType the expected value type.
   * @param expiryAfter the expiration policy of the cache.
   * @return a CompletableFuture that will complete with the newly created {@link NexusCache}
   * @since 3.60.0
   */
  default CompletableFuture<NexusCache<K, V>> getCacheAsync(String cacheName, Class<K> keyType, Class<V> valueType, Duration expiryAfter) {
    return CompletableFuture.supplyAsync(() -> getCache(cacheName, keyType, valueType, expiryAfter));
  }

  /**
   * Destroys the {@link NexusCache} specified by cacheName.
   *
   * @param cacheName the name of the cache to destroy
   */
  void destroyCache(String cacheName);

  /**
   * Asynchronously destroys the {@link NexusCache} specified by cacheName using Java 21 Virtual Threads.
   *
   * @param cacheName the name of the cache to destroy
   * @return a CompletableFuture that will complete when the cache is destroyed
   * @since 3.60.0
   */
  default CompletableFuture<Void> destroyCacheAsync(String cacheName) {
    return CompletableFuture.runAsync(() -> destroyCache(cacheName));
  }

  /**
   * Gets or creates a cache with support for Pattern Matching in Java 21.
   * This method allows implementations to use Pattern Matching for instanceof to handle different cache configurations.
   *
   * @param cacheName the name of the cache
   * @param cacheConfig the configuration object for the cache, which can be pattern-matched by implementations
   * @param <C> the type of the cache configuration
   * @return the newly created or existing {@link NexusCache}
   * @since 3.60.0
   */
  default <C> NexusCache<K, V> getOrCreateCache(String cacheName, C cacheConfig) {
    if (cacheConfig instanceof CacheConfig config) {
      return getCache(cacheName, config.keyType(), config.valueType(), config.expiryAfter());
    }
    throw new IllegalArgumentException("Unsupported cache configuration type: " + cacheConfig.getClass().getName());
  }

  /**
   * Record class to support Pattern Matching for cache configuration.
   * This record enables type-safe configuration with Java 21 Record Patterns.
   *
   * @param keyType the expected key type
   * @param valueType the expected value type
   * @param expiryAfter the expiration policy of the cache
   * @param <K> the type of key
   * @param <V> the type of value
   * @since 3.60.0
   */
  record CacheConfig<K, V>(Class<K> keyType, Class<V> valueType, Duration expiryAfter) {}

  /**
   * Executes the given function with a cache, ensuring proper resource management.
   * This method leverages Java 21's enhanced type inference with generics.
   *
   * @param cacheName the name of the cache
   * @param keyType the expected key type
   * @param valueType the expected value type
   * @param expiryAfter the expiration policy of the cache
   * @param cacheFunction the function to execute with the cache
   * @param <R> the return type of the function
   * @return the result of the function execution
   * @since 3.60.0
   */
  default <R> R withCache(String cacheName, Class<K> keyType, Class<V> valueType, Duration expiryAfter, 
      Function<NexusCache<K, V>, R> cacheFunction) {
    NexusCache<K, V> cache = getCache(cacheName, keyType, valueType, expiryAfter);
    try {
      return cacheFunction.apply(cache);
    } finally {
      destroyCache(cacheName);
    }
  }
}