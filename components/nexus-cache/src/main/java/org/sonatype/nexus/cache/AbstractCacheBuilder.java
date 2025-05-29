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
import javax.cache.configuration.MutableConfiguration;
import javax.cache.expiry.ExpiryPolicy;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Abstracts cache builder to contain all of the getters/setters
 *
 * <p>This implementation leverages Java 21 features including Record Patterns for more concise
 * and type-safe configuration handling, improved type inference for fluent API methods.</p>
 *
 * @since 3.14
 */
@SuppressWarnings("unchecked") // Safe unchecked casts due to builder pattern with type parameters
public abstract class AbstractCacheBuilder<K, V>
    extends ComponentSupport
    implements CacheBuilder<K, V>
{
  /**
   * Record for encapsulating cache configuration properties.
   * This enables more concise and type-safe property handling using Record Patterns.
   */
  protected record CacheProperties<K, V>(
      String name,
      Factory<? extends ExpiryPolicy> expiryFactory,
      int cacheSize,
      boolean storeByValue,
      boolean managementEnabled,
      boolean statisticsEnabled,
      Class<K> keyType,
      Class<V> valueType,
      BiConsumer<K, V> persister
  ) {}

  /**
   * The cache configuration properties.
   */
  protected CacheProperties<K, V> properties;

  /**
   * Constructs a new AbstractCacheBuilder with default properties.
   */
  protected AbstractCacheBuilder() {
    this.properties = new CacheProperties<>(
        null,                // name
        null,                // expiryFactory
        10000,               // cacheSize
        false,               // storeByValue
        true,                // managementEnabled
        true,                // statisticsEnabled
        null,                // keyType
        null,                // valueType
        null                 // persister
    );
    log.debug(STR."Initialized cache builder with default properties: cacheSize=\{properties.cacheSize()}, "
        + STR."storeByValue=\{properties.storeByValue()}, "
        + STR."managementEnabled=\{properties.managementEnabled()}, "
        + STR."statisticsEnabled=\{properties.statisticsEnabled()}");
  }

  @Override
  public String getName() {
    return properties.name();
  }

  @Override
  public Class<K> getKeyType() {
    return properties.keyType();
  }

  @Override
  public Class<V> getValueType() {
    return properties.valueType();
  }

  @Override
  public CacheBuilder<K, V> name(final String name) {
    log.debug(STR."Setting cache name: \{name}");
    this.properties = new CacheProperties<>(
        name,
        properties.expiryFactory(),
        properties.cacheSize(),
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        properties.keyType(),
        properties.valueType(),
        properties.persister()
    );
    return this;
  }

  @Override
  public CacheBuilder<K, V> cacheSize(final int cacheSize) {
    log.debug("Setting cache size: {}", cacheSize);
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        cacheSize,
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        properties.keyType(),
        properties.valueType(),
        properties.persister()
    );
    return this;
  }

  @Override
  public CacheBuilder<K, V> expiryFactory(final Factory<? extends ExpiryPolicy> expiryFactory) {
    log.debug("Setting expiry factory: {}", expiryFactory != null ? expiryFactory.getClass().getSimpleName() : "null");
    this.properties = new CacheProperties<>(
        properties.name(),
        expiryFactory,
        properties.cacheSize(),
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        properties.keyType(),
        properties.valueType(),
        properties.persister()
    );
    return this;
  }

  @Override
  public CacheBuilder<K, V> managementEnabled(final boolean enabled) {
    log.debug("Setting managementEnabled: {}", enabled);
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        properties.cacheSize(),
        properties.storeByValue(),
        enabled,
        properties.statisticsEnabled(),
        properties.keyType(),
        properties.valueType(),
        properties.persister()
    );
    return this;
  }

  @Override
  public CacheBuilder<K, V> statisticsEnabled(final boolean enabled) {
    log.debug("Setting statisticsEnabled: {}", enabled);
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        properties.cacheSize(),
        properties.storeByValue(),
        properties.managementEnabled(),
        enabled,
        properties.keyType(),
        properties.valueType(),
        properties.persister()
    );
    return this;
  }

  @Override
  public CacheBuilder<K, V> storeByValue(final boolean storeByValue) {
    log.debug("Setting storeByValue: {}", storeByValue);
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        properties.cacheSize(),
        storeByValue,
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        properties.keyType(),
        properties.valueType(),
        properties.persister()
    );
    return this;
  }

  @Override
  public CacheBuilder<K, V> persister(final BiConsumer<K, V> persister) {
    log.debug("Setting persister: {}", persister != null ? persister.getClass().getSimpleName() : "null");
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        properties.cacheSize(),
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        properties.keyType(),
        properties.valueType(),
        persister
    );
    return this;
  }

  @Override
  public Cache<K, V> build(final CacheManager cacheManager) {
    validateConfiguration();

    MutableConfiguration<K, V> config = new MutableConfiguration<K, V>()
        .setTypes(properties.keyType(), properties.valueType())
        .setStoreByValue(properties.storeByValue())
        .setExpiryPolicyFactory(properties.expiryFactory())
        .setManagementEnabled(properties.managementEnabled())
        .setStatisticsEnabled(properties.statisticsEnabled());

    Cache<K, V> cache = cacheManager.createCache(properties.name(), config);
    log.debug("Built cache {} with configuration: keyType={}, valueType={}, storeByValue={}, managementEnabled={}, statisticsEnabled={}",
        properties.name(),
        properties.keyType().getSimpleName(),
        properties.valueType().getSimpleName(),
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled());

    return cache;
  }

  protected void validateConfiguration() {
    String name = properties.name();
    Class<K> keyType = properties.keyType();
    Class<V> valueType = properties.valueType();
    int cacheSize = properties.cacheSize();

    if (name == null || name.isEmpty()) {
      throw new IllegalStateException("Cache name must be specified");
    }

    if (keyType == null) {
      throw new IllegalStateException("Key type must be specified for cache: " + name);
    }

    if (valueType == null) {
      throw new IllegalStateException("Value type must be specified for cache: " + name);
    }

    if (cacheSize <= 0) {
      throw new IllegalStateException("Cache size must be positive for cache: " + name);
    }


  }
}
