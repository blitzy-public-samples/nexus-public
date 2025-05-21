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

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Factory;
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.ExpiryPolicy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper for working with caches.
 * 
 * @since 3.6
 */
@SuppressWarnings("rawtypes")
@Named
@Singleton
public class CacheHelper
    extends ComponentSupport
{
  private final Provider<CacheManager> cacheManagerProvider;

  private final Provider<CacheBuilder> cacheBuilderProvider;
  
  private final ConcurrentMap<String, ReentrantLock> cacheLocks = new ConcurrentHashMap<>();

  @Inject
  public CacheHelper(
      final Provider<CacheManager> cacheManagerProvider,
      final Provider<CacheBuilder> cacheBuilderProvider)
  {
    this.cacheManagerProvider = checkNotNull(cacheManagerProvider);
    this.cacheBuilderProvider = checkNotNull(cacheBuilderProvider);
  }

  private CacheManager manager() {
    return cacheManagerProvider.get();
  }

  @SuppressWarnings("unchecked")
  public <K, V> CacheBuilder<K, V> builder() {
    return cacheBuilderProvider.get();
  }

  /**
   * Creates a cache if it doesn't exist, or returns the existing cache.
   * This method uses a per-cache lock to ensure thread safety without blocking other cache operations.
   *
   * @param name the cache name
   * @param mutableConfiguration the cache configuration
   * @return the cache instance
   */
  public <K, V> Cache<K, V> maybeCreateCache(
      final String name,
      final MutableConfiguration<K, V> mutableConfiguration)
  {
    checkNotNull(name);
    checkNotNull(mutableConfiguration);

    // Record JFR event for cache creation attempt
    CacheCreationEvent event = new CacheCreationEvent();
    event.cacheName = name;
    event.begin();
    
    try {
      // Check if cache already exists
      Cache<K, V> cache = manager()
          .getCache(name, mutableConfiguration.getKeyType(), mutableConfiguration.getValueType());

      if (cache == null) {
        // Get or create a lock for this specific cache name
        ReentrantLock lock = cacheLocks.computeIfAbsent(name, k -> new ReentrantLock());
        
        // Only lock when we need to create the cache
        if (lock.tryLock()) {
          try {
            // Double-check after acquiring the lock
            cache = manager().getCache(name, mutableConfiguration.getKeyType(), mutableConfiguration.getValueType());
            
            if (cache == null) {
              // Create the cache using a virtual thread to handle potential I/O operations
              CompletableFuture<Cache<K, V>> future = CompletableFuture.supplyAsync(() -> {
                Cache<K, V> newCache = manager().createCache(name, mutableConfiguration);
                log.debug(STR."Created cache: \{newCache}");
                return newCache;
              }, runnable -> Thread.startVirtualThread(runnable));
              
              cache = future.join(); // Wait for completion
              event.wasCreated = true;
            }
            else {
              log.debug(STR."Re-using existing cache: \{cache}");
            }
          }
          finally {
            lock.unlock();
          }
        }
        else {
          // Another thread is creating the cache, wait for it to complete
          lock.lock();
          try {
            cache = manager().getCache(name, mutableConfiguration.getKeyType(), mutableConfiguration.getValueType());
          }
          finally {
            lock.unlock();
          }
        }
      }
      else {
        log.debug(STR."Re-using existing cache: \{cache}");
      }

      return cache;
    }
    finally {
      event.end();
      event.commit();
    }
  }

  /**
   * Gets an existing cache or creates a new one using the provided builder.
   * This method uses a per-cache lock to ensure thread safety without blocking other cache operations.
   *
   * @param builder the cache builder
   * @return the cache instance
   */
  public <K, V> Cache<K, V> getOrCreate(final CacheBuilder<K, V> builder) {
    checkNotNull(builder);
    
    String name = builder.getName();
    
    // Record JFR event for cache retrieval/creation
    CacheCreationEvent event = new CacheCreationEvent();
    event.cacheName = name;
    event.begin();
    
    try {
      // Check if cache already exists
      Cache<K, V> cache = manager().getCache(builder.getName(), builder.getKeyType(), builder.getValueType());

      if (cache == null) {
        // Get or create a lock for this specific cache name
        ReentrantLock lock = cacheLocks.computeIfAbsent(name, k -> new ReentrantLock());
        
        // Only lock when we need to create the cache
        if (lock.tryLock()) {
          try {
            // Double-check after acquiring the lock
            cache = manager().getCache(builder.getName(), builder.getKeyType(), builder.getValueType());
            
            if (cache == null) {
              // Create the cache using a virtual thread to handle potential I/O operations
              CompletableFuture<Cache<K, V>> future = CompletableFuture.supplyAsync(() -> {
                Cache<K, V> newCache = builder.build(manager());
                log.debug(STR."Created cache: \{newCache}");
                return newCache;
              }, runnable -> Thread.startVirtualThread(runnable));
              
              cache = future.join(); // Wait for completion
              event.wasCreated = true;
            }
            else {
              log.debug(STR."Re-using existing cache: \{cache}");
            }
          }
          finally {
            lock.unlock();
          }
        }
        else {
          // Another thread is creating the cache, wait for it to complete
          lock.lock();
          try {
            cache = manager().getCache(builder.getName(), builder.getKeyType(), builder.getValueType());
          }
          finally {
            lock.unlock();
          }
        }
      }
      else {
        log.debug(STR."Re-using existing cache: \{cache}");
      }

      return cache;
    }
    finally {
      event.end();
      event.commit();
    }
  }

  /**
   * Creates a cache with the specified name and expiry policy if it doesn't exist.
   *
   * @param name the cache name
   * @param expiryPolicyFactory the expiry policy factory
   * @return the cache instance
   */
  public <K, V> Cache<K, V> maybeCreateCache(
      final String name,
      final Factory<? extends ExpiryPolicy> expiryPolicyFactory)
  {
    return maybeCreateCache(name, null, null, expiryPolicyFactory);
  }

  /**
   * Creates a cache with the specified name, key/value types, and expiry policy if it doesn't exist.
   *
   * @param name the cache name
   * @param keyType the key type or null for unspecified
   * @param valueType the value type or null for unspecified
   * @param expiryPolicyFactory the expiry policy factory
   * @return the cache instance
   */
  public <K, V> Cache<K, V> maybeCreateCache(
      final String name,
      @Nullable final Class<K> keyType,
      @Nullable final Class<V> valueType,
      final Factory<? extends ExpiryPolicy> expiryPolicyFactory)
  {
    return maybeCreateCache(name, createCacheConfig(keyType, valueType, expiryPolicyFactory));
  }

  /**
   * Creates a cache configuration with the specified key/value types and expiry policy.
   *
   * @param keyType the key type or null for unspecified
   * @param valueType the value type or null for unspecified
   * @param expiryPolicyFactory the expiry policy factory
   * @return the cache configuration
   */
  public static <K, V> MutableConfiguration<K, V> createCacheConfig(
      @Nullable final Class<K> keyType,
      @Nullable final Class<V> valueType,
      final Factory<? extends ExpiryPolicy> expiryPolicyFactory)
  {
    MutableConfiguration<K, V> config = new MutableConfiguration<K, V>()
        .setStoreByValue(false)
        .setExpiryPolicyFactory(expiryPolicyFactory)
        .setManagementEnabled(true)
        .setStatisticsEnabled(true);

    if (keyType != null && valueType != null) {
      config.setTypes(keyType, valueType);
    }
    return config;
  }

  /**
   * Destroys the cache with the specified name if it exists.
   * This operation is performed using a virtual thread to avoid blocking the calling thread.
   *
   * @param name the cache name
   */
  public void maybeDestroyCache(final String name) {
    // Record JFR event for cache destruction
    CacheDestructionEvent event = new CacheDestructionEvent();
    event.cacheName = name;
    event.begin();
    
    try {
      // Use a virtual thread for potentially I/O-bound cache destruction
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        manager().destroyCache(name);
        log.debug(STR."Destroyed cache: \{name}");
        
        // Remove the lock for this cache if it exists
        cacheLocks.remove(name);
      }, runnable -> Thread.startVirtualThread(runnable));
      
      future.join(); // Wait for completion
    }
    finally {
      event.end();
      event.commit();
    }
  }
  
  /**
   * JFR event for monitoring cache creation operations.
   */
  @Name("org.sonatype.nexus.cache.CacheCreation")
  @Label("Cache Creation")
  @Category("Nexus Cache")
  @Description("Records cache creation or retrieval operations")
  @StackTrace(false)
  static class CacheCreationEvent extends Event {
    @Label("Cache Name")
    String cacheName;
    
    @Label("Was Created")
    boolean wasCreated;
    
    @Label("Duration")
    @Description("Time taken to create or retrieve the cache")
    Duration duration;
    
    @Override
    public void end() {
      super.end();
      duration = Duration.between(Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(endTime));
    }
  }
  
  /**
   * JFR event for monitoring cache destruction operations.
   */
  @Name("org.sonatype.nexus.cache.CacheDestruction")
  @Label("Cache Destruction")
  @Category("Nexus Cache")
  @Description("Records cache destruction operations")
  @StackTrace(false)
  static class CacheDestructionEvent extends Event {
    @Label("Cache Name")
    String cacheName;
    
    @Label("Duration")
    @Description("Time taken to destroy the cache")
    Duration duration;
    
    @Override
    public void end() {
      super.end();
      duration = Duration.between(Instant.ofEpochMilli(startTime), Instant.ofEpochMilli(endTime));
    }
  }
}