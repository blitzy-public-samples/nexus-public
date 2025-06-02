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
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;

import javax.cache.Cache.Entry;

import org.sonatype.goodies.common.ComponentSupport;

import com.google.common.collect.Iterables;
import org.apache.shiro.cache.Cache;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Shiro {@link javax.cache.Cache} to {@link Cache} adapter.
 * Updated for Java 21 and Apache Shiro 2.0.0 compatibility.
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
    // Use virtual thread for potentially blocking cache operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> cache.getAndPut(key, value)).join();
    }
  }

  @Override
  public V remove(final K key) {
    // Use virtual thread for potentially blocking cache operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return executor.submit(() -> cache.getAndRemove(key)).join();
    }
  }

  // NOTE: This appears unused in Shiro, but used by NX
  // Deprecated in Shiro 2.0.0 but maintained for backward compatibility
  @Override
  @Deprecated(since = "Java 21 / Shiro 2.0.0", forRemoval = true)
  public void clear() {
    // Use virtual thread for potentially blocking cache operation
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        cache.clear();
        return null;
      }).join();
    }
  }

  // NOTE: This appears unused in Shiro.
  // Deprecated in Shiro 2.0.0 but maintained for backward compatibility
  @Override
  @Deprecated(since = "Java 21 / Shiro 2.0.0", forRemoval = true)
  public int size() {
    return Iterables.size(cache);
  }

  // NOTE: This appears unused in Shiro.
  // Deprecated in Shiro 2.0.0 but maintained for backward compatibility
  @Override
  @Deprecated(since = "Java 21 / Shiro 2.0.0", forRemoval = true)
  public Set<K> keys() {
    // Using LinkedHashSet which implements SequencedSet in Java 21
    Set<K> keys = new LinkedHashSet<>();
    for (Entry<K, V> entry : cache) {
      keys.add(entry.getKey());
    }
    return Collections.unmodifiableSet(keys);
  }

  @Override
  public Collection<V> values() {
    // Using ConcurrentLinkedDeque which implements SequencedCollection in Java 21
    Collection<V> values = new ConcurrentLinkedDeque<>();
    
    // Use virtual thread for potentially blocking cache iteration
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        for (Entry<K, V> entry : cache) {
          values.add(entry.getValue());
        }
        return null;
      }).join();
    }
    
    return Collections.unmodifiableCollection(values);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{"
        + "cache=" + cache
        + ", name='" + name + '\''
        + '}';
  }
}