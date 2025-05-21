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

import java.util.Optional;

/**
 * The lightweight version of the {@link javax.cache.Cache}.
 *
 * <p>This interface is compatible with Java 21 and supports execution in Virtual Thread contexts.
 * Implementations should ensure non-blocking operations where possible to avoid Virtual Thread pinning.
 * Pattern Matching can be used in implementations for type-safe handling of cache entries.</p>
 *
 * @param <K> the type of key
 * @param <V> the type of value
 * @since 1.0
 */
public interface NexusCache<K, V>
{
  /**
   * Gets an entry from the cache.
   * 
   * <p>This operation is designed to be compatible with Virtual Thread execution context
   * and should not block the carrier thread when implemented properly.</p>
   *
   * @param key the key whose associated value is to be returned.
   * @return the element, or {@code Optional.empty()}, if it does not exist.
   */
  Optional<V> get(final K key);

  /**
   * Associates the specified value with the specified key in the cache. If the {@link NexusCache} previously
   * contained a mapping for the key, the old value is replaced by the specified value.
   * 
   * <p>This operation is designed to be compatible with Virtual Thread execution context
   * and should not block the carrier thread when implemented properly.</p>
   *
   * @param key key with which the specified value is to be associated
   * @param value value to be associated with the specified key
   */
  void put(final K key, final V value);

  /**
   * Removes the mapping for a key from this cache if it is present.
   * 
   * <p>This operation is designed to be compatible with Virtual Thread execution context
   * and should not block the carrier thread when implemented properly.</p>
   *
   * @param key key whose mapping is to be removed from the cache
   */
  void remove(final K key);

  /**
   * Removes all the mappings from this cache.
   * 
   * <p>This operation is designed to be compatible with Virtual Thread execution context
   * and should not block the carrier thread when implemented properly.</p>
   */
  void removeAll();
}