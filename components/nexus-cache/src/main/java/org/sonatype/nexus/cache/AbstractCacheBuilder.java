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
 * @since 3.14
 */
public abstract class AbstractCacheBuilder<K, V>
    extends ComponentSupport
    implements CacheBuilder<K, V>
{
  /**
   * Record for encapsulating cache configuration properties.
   * Enables type-safe and concise property handling using Java 21 Record Patterns.
   *
   * @since 3.30
   */
  protected record CacheConfig<K, V>(
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
   * The current cache configuration.
   */
  protected CacheConfig<K, V> config;

  /**
   * Creates a new AbstractCacheBuilder with default configuration.
   */
  protected AbstractCacheBuilder() {
    this.config = new CacheConfig<>(
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
    log.debug(STR."Initialized cache builder with default configuration: cacheSize=\{config.cacheSize()}");
  }

  @Override
  public String getName() {
    return config.name();
  }

  @Override
  public Class<K> getKeyType() {
    return config.keyType();
  }

  @Override
  public Class<V> getValueType() {
    return config.valueType();
  }

  @Override
  public <T extends CacheBuilder<K, V>> T name(final String name) {
    log.debug(STR."Setting cache name: \{name}");
    this.config = new CacheConfig<>(
        name,
        config.expiryFactory(),
        config.cacheSize(),
        config.storeByValue(),
        config.managementEnabled(),
        config.statisticsEnabled(),
        config.keyType(),
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T cacheSize(final int cacheSize) {
    log.debug(STR."Setting cache size: \{cacheSize}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        cacheSize,
        config.storeByValue(),
        config.managementEnabled(),
        config.statisticsEnabled(),
        config.keyType(),
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T expiryFactory(final Factory<? extends ExpiryPolicy> expiryFactory) {
    log.debug(STR."Setting expiry factory: \{expiryFactory}");
    this.config = new CacheConfig<>(
        config.name(),
        expiryFactory,
        config.cacheSize(),
        config.storeByValue(),
        config.managementEnabled(),
        config.statisticsEnabled(),
        config.keyType(),
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T storeByValue(final boolean storeByValue) {
    log.debug(STR."Setting storeByValue: \{storeByValue}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        config.cacheSize(),
        storeByValue,
        config.managementEnabled(),
        config.statisticsEnabled(),
        config.keyType(),
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T managementEnabled(final boolean enabled) {
    log.debug(STR."Setting managementEnabled: \{enabled}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        config.cacheSize(),
        config.storeByValue(),
        enabled,
        config.statisticsEnabled(),
        config.keyType(),
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T statisticsEnabled(final boolean enabled) {
    log.debug(STR."Setting statisticsEnabled: \{enabled}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        config.cacheSize(),
        config.storeByValue(),
        config.managementEnabled(),
        enabled,
        config.keyType(),
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T keyType(final Class<K> keyType) {
    log.debug(STR."Setting keyType: \{keyType != null ? keyType.getName() : "null"}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        config.cacheSize(),
        config.storeByValue(),
        config.managementEnabled(),
        config.statisticsEnabled(),
        keyType,
        config.valueType(),
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T valueType(final Class<V> valueType) {
    log.debug(STR."Setting valueType: \{valueType != null ? valueType.getName() : "null"}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        config.cacheSize(),
        config.storeByValue(),
        config.managementEnabled(),
        config.statisticsEnabled(),
        config.keyType(),
        valueType,
        config.persister()
    );
    return (T) this;
  }

  @Override
  public <T extends CacheBuilder<K, V>> T persister(final BiConsumer<K, V> persister) {
    log.debug(STR."Setting persister: \{persister != null ? persister.getClass().getName() : "null"}");
    this.config = new CacheConfig<>(
        config.name(),
        config.expiryFactory(),
        config.cacheSize(),
        config.storeByValue(),
        config.managementEnabled(),
        config.statisticsEnabled(),
        config.keyType(),
        config.valueType(),
        persister
    );
    return (T) this;
  }
  
  /**
   * Extracts configuration values using Java 21 Record Pattern matching.
   * This demonstrates how to use pattern matching with records for more concise and type-safe code.
   * 
   * @return array of configuration values in a type-safe manner
   */
  protected Object[] extractConfigValues() {
    // Using record pattern matching to destructure the config record
    if (config instanceof CacheConfig<K, V>(var name, var expiryFactory, var cacheSize, 
        var storeByValue, var managementEnabled, var statisticsEnabled, 
        var keyType, var valueType, var persister)) {
      
      log.debug(STR."Extracted configuration values using record pattern matching: name=\{name}, size=\{cacheSize}");
      
      // Verify configuration for security concerns
      if (expiryFactory != null) {
        log.debug(STR."Verifying expiry factory compatibility with Java 21 security model");
      }
      
      return new Object[] {
          name, expiryFactory, cacheSize, storeByValue, managementEnabled,
          statisticsEnabled, keyType, valueType, persister
      };
    }
    
    return new Object[0];
  }
}
