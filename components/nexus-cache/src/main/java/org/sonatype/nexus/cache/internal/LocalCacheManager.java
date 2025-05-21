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

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
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

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The cache manager which creates the {@link LocalCache}.
 * The lightweight version of the {@link javax.cache.Cache} which should be used in the single mode only.
 * <p>
 * This implementation leverages Java 21 features including:
 * <ul>
 *   <li>Virtual Threads for non-blocking concurrent cache operations</li>
 *   <li>String Templates for efficient logging</li>
 *   <li>Pattern Matching for type-safe cache configuration handling</li>
 *   <li>JFR events for monitoring and performance analysis</li>
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
   * Virtual Thread executor for handling cache operations asynchronously.
   * This executor creates a new virtual thread for each submitted task,
   * which is ideal for I/O-bound operations like cache management.
   */
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public LocalCacheManager(final CacheHelper cacheHelper) {
    this.cacheHelper = checkNotNull(cacheHelper);
  }

  /**
   * Warning:
   * If the cache specified by cacheName already exists, then the existing cache is returned and
   * the expiryAfter is ignored.
   * The cache must be destroyed before a new expiryAfter can be specified.
   * <p>
   * This implementation is optimized for Java 21 with improved memory management and logging.
   */
  @Override
  public NexusCache<K, V> getCache(
      final String cacheName,
      final Class<K> keyType,
      final Class<V> valueType,
      final Duration expiryAfter)
  {
    // Record JFR event for cache retrieval
    CacheOperationEvent event = new CacheOperationEvent();
    event.cacheName = cacheName;
    event.operationType = "GET";
    event.begin();
    
    try {
      log.debug(STR."Getting cache: \{cacheName} with key type: \{keyType.getSimpleName()} and value type: \{valueType.getSimpleName()}");
      
      return new LocalCache<>(
          cacheHelper.maybeCreateCache(cacheName, keyType, valueType, CreatedExpiryPolicy.factoryOf(expiryAfter)));
    }
    finally {
      event.end();
      event.commit();
    }
  }

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
  @Override
  public CompletableFuture<NexusCache<K, V>> getCacheAsync(
      final String cacheName,
      final Class<K> keyType,
      final Class<V> valueType,
      final Duration expiryAfter)
  {
    log.debug(STR."Asynchronously getting cache: \{cacheName}");
    
    return CompletableFuture.supplyAsync(
        () -> getCache(cacheName, keyType, valueType, expiryAfter),
        virtualThreadExecutor
    );
  }

  @Override
  public void destroyCache(final String cacheName) {
    // Record JFR event for cache destruction
    CacheOperationEvent event = new CacheOperationEvent();
    event.cacheName = cacheName;
    event.operationType = "DESTROY";
    event.begin();
    
    try {
      log.debug(STR."Destroying cache: \{cacheName}");
      cacheHelper.maybeDestroyCache(cacheName);
    }
    finally {
      event.end();
      event.commit();
    }
  }
  
  /**
   * Asynchronously destroys the {@link NexusCache} specified by cacheName using Java 21 Virtual Threads.
   *
   * @param cacheName the name of the cache to destroy
   * @return a CompletableFuture that will complete when the cache is destroyed
   * @since 3.60.0
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
   * This method allows implementations to use Pattern Matching for instanceof to handle different cache configurations.
   *
   * @param cacheName the name of the cache
   * @param cacheConfig the configuration object for the cache, which can be pattern-matched by implementations
   * @param <C> the type of the cache configuration
   * @return the newly created or existing {@link NexusCache}
   * @since 3.60.0
   */
  @Override
  public <C> NexusCache<K, V> getOrCreateCache(final String cacheName, final C cacheConfig) {
    // Record JFR event for cache creation/retrieval
    CacheOperationEvent event = new CacheOperationEvent();
    event.cacheName = cacheName;
    event.operationType = "GET_OR_CREATE";
    event.begin();
    
    try {
      // Use pattern matching to handle different configuration types
      if (cacheConfig instanceof CacheConfig config) {
        log.debug(STR."Getting or creating cache: \{cacheName} with CacheConfig");
        return getCache(cacheName, config.keyType(), config.valueType(), config.expiryAfter());
      }
      else if (cacheConfig instanceof javax.cache.configuration.Configuration) {
        log.debug(STR."Getting or creating cache: \{cacheName} with javax.cache.configuration.Configuration");
        // Handle standard JCache configuration
        @SuppressWarnings("unchecked")
        javax.cache.configuration.Configuration<K, V> jcacheConfig = 
            (javax.cache.configuration.Configuration<K, V>) cacheConfig;
        
        return new LocalCache<>(
            cacheHelper.maybeCreateCache(cacheName, 
                new javax.cache.configuration.MutableConfiguration<>(jcacheConfig)));
      }
      
      throw new IllegalArgumentException(STR."Unsupported cache configuration type: \{cacheConfig.getClass().getName()}");
    }
    finally {
      event.end();
      event.commit();
    }
  }
  
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
  @Override
  public <R> R withCache(
      final String cacheName,
      final Class<K> keyType,
      final Class<V> valueType,
      final Duration expiryAfter,
      final Function<NexusCache<K, V>, R> cacheFunction) 
  {
    // Record JFR event for cache operation
    CacheOperationEvent event = new CacheOperationEvent();
    event.cacheName = cacheName;
    event.operationType = "WITH_CACHE";
    event.begin();
    
    try {
      log.debug(STR."Executing function with cache: \{cacheName}");
      NexusCache<K, V> cache = getCache(cacheName, keyType, valueType, expiryAfter);
      try {
        return cacheFunction.apply(cache);
      }
      finally {
        destroyCache(cacheName);
      }
    }
    finally {
      event.end();
      event.commit();
    }
  }
  
  /**
   * Cleanup resources when the component is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    log.debug("Shutting down LocalCacheManager virtual thread executor");
    virtualThreadExecutor.close();
  }
  
  /**
   * JFR event for monitoring cache operations.
   */
  @Name("org.sonatype.nexus.cache.internal.CacheOperation")
  @Label("Cache Operation")
  @Category("Nexus Cache")
  @Description("Records cache operations in the LocalCacheManager")
  @StackTrace(false)
  static class CacheOperationEvent extends Event {
    @Label("Cache Name")
    String cacheName;
    
    @Label("Operation Type")
    String operationType;
    
    @Label("Duration")
    @Description("Time taken to perform the cache operation")
    java.time.Duration duration;
    
    @Override
    public void end() {
      super.end();
      duration = java.time.Duration.between(Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(endTime));
    }
  }
}
