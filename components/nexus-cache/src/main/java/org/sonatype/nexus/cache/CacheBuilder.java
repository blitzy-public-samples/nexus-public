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

import java.util.function.BiConsumer;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.cache.configuration.Factory;
import javax.cache.expiry.ExpiryPolicy;

/**
 * Abstracts cache configurations to make using different underlying implementations easier.
 * 
 * <p>This interface is fully compatible with Java 21, including support for Virtual Threads,
 * Record Patterns, and the Java Platform Module System.</p>
 * 
 * @since 3.14
 */
public interface CacheBuilder<K, V>
{
  /**
   * Returns the name of the cache.
   * 
   * @return the cache name
   */
  String getName();

  /**
   * Returns the key type of the cache.
   * 
   * @return the key type class
   */
  Class<K> getKeyType();

  /**
   * Returns the value type of the cache.
   * 
   * @return the value type class
   */
  Class<V> getValueType();

  /**
   * Sets the name of the cache.
   * 
   * @param name the cache name
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> name(String name);

  /**
   * Sets the cache size.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param size the cache size
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> cacheSize(int size);

  /**
   * Sets the expiry policy factory.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param expiryFactory the expiry policy factory
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> expiryFactory(Factory<? extends ExpiryPolicy> expiryFactory);

  /**
   * Sets whether the cache should store by value.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param storeByValue true to store by value, false otherwise
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> storeByValue(boolean storeByValue);

  /**
   * Sets whether management is enabled.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param enabled true to enable management, false otherwise
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> managementEnabled(boolean enabled);

  /**
   * Sets whether statistics are enabled.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param enabled true to enable statistics, false otherwise
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> statisticsEnabled(boolean enabled);

  /**
   * Sets the key type of the cache.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param keyType the key type class
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> keyType(Class<K> keyType);

  /**
   * Sets the value type of the cache.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning.</p>
   * 
   * @param valueType the value type class
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> valueType(Class<V> valueType);

  /**
   * Sets the persister for the cache.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning. The persister itself should
   * be designed to work efficiently with Virtual Threads, avoiding operations that
   * could cause thread pinning such as synchronized blocks or native methods.</p>
   * 
   * <p>When implementing with Record Patterns, the persister can efficiently destructure
   * record-based keys and values using pattern matching.</p>
   * 
   * @param persister the bi-consumer that handles persistence
   * @return this builder instance for method chaining
   */
  CacheBuilder<K, V> persister(BiConsumer<K, V> persister);

  /**
   * Builds the cache with the configured settings.
   * 
   * <p>Implementations should ensure this method is thread-safe and can be called
   * from Virtual Threads without causing thread pinning. The resulting cache should
   * be optimized for use with Virtual Threads, particularly for I/O operations.</p>
   * 
   * <p>When implementing with Java 21, consider using Virtual Threads for asynchronous
   * cache operations and Record Patterns for efficient data handling. Implementations
   * should be compatible with the Java Platform Module System.</p>
   * 
   * @param manager the cache manager
   * @return the built cache
   */
  Cache<K, V> build(CacheManager manager);
}