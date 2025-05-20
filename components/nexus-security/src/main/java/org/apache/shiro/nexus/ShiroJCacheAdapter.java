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

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

import javax.cache.Cache.Entry;

import org.sonatype.goodies.common.ComponentSupport;

import com.google.common.collect.Iterables;
import org.apache.shiro.cache.Cache;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Shiro {@link javax.cache.Cache} to {@link Cache} adapter.
 * Updated for Java 21 and Shiro 2.0.0 compatibility with optimized collections and Virtual Threads support.
 *
 * @since 3.0
 */
public class ShiroJCacheAdapter<K, V>
  extends ComponentSupport
  implements Cache<K, V>
{
  private final javax.cache.Cache<K, V> cache;

  private final String name;

  public ShiroJCacheAdapter(final javax.cache.Cache<K, V> cache) {
    this.cache = checkNotNull(cache);
    this.name = cache.getName();
  }

  @Override
  public V get(final K key) {
    return cache.get(key);
  }

  @Override
  public V put(final K key, final V value) {
    // Use Virtual Thread for potentially blocking cache operations
    if (isLikelyToBlock()) {
      return Executors.newVirtualThreadPerTaskExecutor().submit(() -> cache.getAndPut(key, value)).join();
    }
    return cache.getAndPut(key, value);
  }

  @Override
  public V remove(final K key) {
    // Use Virtual Thread for potentially blocking cache operations
    if (isLikelyToBlock()) {
      return Executors.newVirtualThreadPerTaskExecutor().submit(() -> cache.getAndRemove(key)).join();
    }
    return cache.getAndRemove(key);
  }

  // NOTE: This appears unused in Shiro, but used by NX
  @Override
  public void clear() {
    // Use Virtual Thread for potentially blocking cache operations
    if (isLikelyToBlock()) {
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        cache.clear();
        return null;
      }).join();
    } else {
      cache.clear();
    }
  }

  // NOTE: This appears unused in Shiro.
  @Override
  public int size() {
    return Iterables.size(cache);
  }

  // NOTE: This appears unused in Shiro.
  @Override
  public Set<K> keys() {
    // Use LinkedHashSet (a Sequenced Collection) for better performance in Java 21
    Set<K> keys = new LinkedHashSet<>();
    for (Entry<K, V> entry : cache) {
      keys.add(entry.getKey());
    }
    return Collections.unmodifiableSet(keys);
  }

  @Override
  public Collection<V> values() {
    // Use ConcurrentLinkedQueue for better concurrent performance in Java 21
    Collection<V> values = new ConcurrentLinkedQueue<>();
    for (Entry<K, V> entry : cache) {
      values.add(entry.getValue());
    }
    return Collections.unmodifiableCollection(values);
  }
  
  /**
   * Determines if a cache operation is likely to block based on cache size or other heuristics.
   * This helps decide when to use Virtual Threads for potentially blocking operations.
   *
   * @return true if the operation is likely to block
   */
  private boolean isLikelyToBlock() {
    // Simple heuristic: if cache is large, operations might block
    // This could be enhanced with more sophisticated detection
    try {
      int cacheSize = Iterables.size(cache);
      return cacheSize > 1000; // Threshold for considering an operation potentially blocking
    } catch (Exception e) {
      log.debug("Error determining cache size, assuming non-blocking", e);
      return false;
    }
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "cache=" + cache +
        ", name='" + name + '\'' +
        "}";
  }
}