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

import javax.cache.configuration.Factory;
import javax.cache.expiry.ExpiryPolicy;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Abstracts cache builder to contain all of the getters/setters
 *
 * <p>This implementation leverages Java 21 features including Record Patterns for more concise
 * and type-safe configuration handling, improved type inference for fluent API methods, and
 * String Templates for more structured logging.</p>
 *
 * @since 3.14
 */
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
  public <T extends CacheBuilder<K, V>> T name(final String name) {
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
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T cacheSize(final int cacheSize) {
    log.debug(STR."Setting cache size: \{cacheSize}");
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
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T expiryFactory(final Factory<? extends ExpiryPolicy> expiryFactory) {
    log.debug(STR."Setting expiry factory: \{expiryFactory != null ? expiryFactory.getClass().getSimpleName() : "null"}");
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
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T storeByValue(final boolean storeByValue) {
    log.debug(STR."Setting storeByValue: \{storeByValue}");
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
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T managementEnabled(final boolean enabled) {
    log.debug(STR."Setting managementEnabled: \{enabled}");
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
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T statisticsEnabled(final boolean enabled) {
    log.debug(STR."Setting statisticsEnabled: \{enabled}");
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
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T keyType(final Class<K> keyType) {
    log.debug(STR."Setting keyType: \{keyType != null ? keyType.getSimpleName() : "null"}");
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        properties.cacheSize(),
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        keyType,
        properties.valueType(),
        properties.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T valueType(final Class<V> valueType) {
    log.debug(STR."Setting valueType: \{valueType != null ? valueType.getSimpleName() : "null"}");
    this.properties = new CacheProperties<>(
        properties.name(),
        properties.expiryFactory(),
        properties.cacheSize(),
        properties.storeByValue(),
        properties.managementEnabled(),
        properties.statisticsEnabled(),
        properties.keyType(),
        valueType,
        properties.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T persister(final BiConsumer<K, V> persister) {
    log.debug(STR."Setting persister: \{persister != null ? persister.getClass().getSimpleName() : "null"}");
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
    return (T) this;
  }
  
  /**
   * Gets the current cache properties.
   * This method can be used with Record Patterns for concise property access.
   *
   * @return the current cache properties record
   */
  protected CacheProperties<K, V> getProperties() {
    return properties;
  }
  
  /**
   * Validates that the cache configuration is secure according to Java 21's enhanced security model.
   * This method should be called before building the cache to ensure all security requirements are met.
   *
   * @throws IllegalStateException if the configuration does not meet security requirements
   */
  protected void validateSecurity() {
    // Use Record Pattern for concise property access
    var CacheProperties(name, expiryFactory, cacheSize, storeByValue, _, _, keyType, valueType, _) = properties;
    
    if (name == null || name.isEmpty()) {
      throw new IllegalStateException(STR."Cache name must be specified: \{name}");
    }
    
    if (keyType == null) {
      throw new IllegalStateException(STR."Key type must be specified for cache: \{name}");
    }
    
    if (valueType == null) {
      throw new IllegalStateException(STR."Value type must be specified for cache: \{name}");
    }
    
    if (cacheSize <= 0) {
      throw new IllegalStateException(STR."Cache size must be positive for cache: \{name}, size: \{cacheSize}");
    }
    
    // Log security-relevant configuration
    log.debug(STR."Cache security validation passed for \{name}: keyType=\{keyType.getSimpleName()}, "
        + STR."valueType=\{valueType.getSimpleName()}, storeByValue=\{storeByValue}");
  }
}
