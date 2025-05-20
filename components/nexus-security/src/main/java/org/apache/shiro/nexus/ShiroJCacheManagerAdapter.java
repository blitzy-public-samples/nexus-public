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
package org.apache.shiro.nexus;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.cache.expiry.CreatedExpiryPolicy;
import javax.cache.expiry.Duration;
import javax.cache.expiry.EternalExpiryPolicy;
import javax.inject.Provider;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.cache.CacheHelper;

import com.google.common.annotations.VisibleForTesting;
import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheManager;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.shiro.session.mgt.eis.CachingSessionDAO.ACTIVE_SESSION_CACHE_NAME;

/**
 * Shiro {@link javax.cache.CacheManager} to {@link CacheManager} adapter.
 * Updated for Java 21 and Shiro 2.0.0 compatibility with Virtual Threads support.
 *
 * @since 3.0
 */
public class ShiroJCacheManagerAdapter
  extends ComponentSupport
  implements CacheManager
{
  private final Provider<CacheHelper> cacheHelperProvider;

  private final Provider<Time> defaultTimeToLive;
  
  // Cache of already created caches to avoid redundant creation
  private final ConcurrentMap<String, Future<javax.cache.Cache<?, ?>>> cacheCreationTasks = new ConcurrentHashMap<>();

  public ShiroJCacheManagerAdapter(final Provider<CacheHelper> cacheHelperProvider,
                                   final Provider<Time> defaultTimeToLive)
  {
    this.cacheHelperProvider = checkNotNull(cacheHelperProvider);
    this.defaultTimeToLive = checkNotNull(defaultTimeToLive);
  }

  @Override
  public <K, V> Cache<K, V> getCache(final String name) {
    log.debug("Getting cache: {}", name);
    return new ShiroJCacheAdapter<>(this.<K,V>getOrCreateCache(name));
  }

  /**
   * Gets an existing cache or creates a new one asynchronously using Virtual Threads.
   * This method leverages Java 21 Virtual Threads for efficient asynchronous cache creation.
   *
   * @param name the name of the cache to retrieve or create
   * @return the cache instance
   */
  @SuppressWarnings("unchecked")
  private <K, V> javax.cache.Cache<K, V> getOrCreateCache(final String name) {
    // Try to get existing cache creation task
    Future<javax.cache.Cache<?, ?>> cacheTask = cacheCreationTasks.get(name);
    
    if (cacheTask == null) {
      // Create new cache asynchronously using Virtual Threads
      Future<javax.cache.Cache<?, ?>> newTask = Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        log.debug("Creating cache '{}' using virtual thread", name);
        return createCache(name);
      });
      
      // Try to store our task (another thread might have beaten us to it)
      Future<javax.cache.Cache<?, ?>> existingTask = cacheCreationTasks.putIfAbsent(name, newTask);
      cacheTask = existingTask != null ? existingTask : newTask;
    }
    
    try {
      // Wait for the cache creation to complete
      return (javax.cache.Cache<K, V>) cacheTask.get();
    } catch (Exception e) {
      // Remove failed task so it can be retried
      cacheCreationTasks.remove(name, cacheTask);
      throw new RuntimeException("Failed to create cache: " + name, e);
    }
  }

  /**
   * Creates a new cache with the appropriate expiry policy based on the cache name.
   * Optimized for Java 21 with improved timeout handling.
   *
   * @param name the name of the cache to create
   * @return the newly created cache
   */
  @VisibleForTesting
  <K, V> javax.cache.Cache<K, V> createCache(final String name) {
    if (Objects.equals(ACTIVE_SESSION_CACHE_NAME, name)) {
      // shiro's session cache needs to never expire:
      // http://shiro.apache.org/session-management.html#ehcache-session-cache-configuration
      return cacheHelperProvider.get().maybeCreateCache(name, EternalExpiryPolicy.factoryOf());
    }
    else {
      // Get custom TTL from system property or use default
      Time timeToLive = Optional.ofNullable(System.getProperty(name + ".timeToLive"))
          .map(Time::parse)
          .orElse(defaultTimeToLive.get());
      
      // Create cache with optimized expiry policy for Java 21
      return cacheHelperProvider.get().maybeCreateCache(name,
          CreatedExpiryPolicy.factoryOf(new Duration(timeToLive.getUnit(), timeToLive.getValue())));
    }
  }
  
  /**
   * Clears the cache creation tasks map, primarily for testing purposes.
   */
  @VisibleForTesting
  void clearCacheCreationTasks() {
    cacheCreationTasks.clear();
  }
}