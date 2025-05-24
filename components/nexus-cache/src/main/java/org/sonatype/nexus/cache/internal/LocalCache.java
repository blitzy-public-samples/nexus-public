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

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.cache.Cache;
import javax.cache.CacheException;
import javax.cache.integration.CacheLoaderException;
import javax.cache.integration.CacheWriterException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.cache.NexusCache;

import static java.lang.StringTemplate.STR;
import static java.util.Objects.requireNonNull;

/**
 * The implementation of the {@link NexusCache}.
 * <p>
 * This implementation is compatible with Java 21 and supports execution in Virtual Thread contexts.
 * It uses pattern matching for error handling and String Templates for logging.
 * </p>
 *
 * @param <K> the type of key
 * @param <V> the type of value
 */
public class LocalCache<K, V>
    implements NexusCache<K, V>
{
  private static final Logger log = LoggerFactory.getLogger(LocalCache.class);
  
  private final Cache<K, V> cache;

  /**
   * Constructs a new LocalCache wrapping the provided JCache implementation.
   * 
   * @param cache the JCache implementation to wrap (must not be null)
   * @throws NullPointerException if cache is null
   */
  public LocalCache(final Cache<K, V> cache) {
    this.cache = requireNonNull(cache, "Cache cannot be null");
    log.debug(STR."Initialized LocalCache with JCache implementation: \{cache.getClass().getName()}");
  }

  @Override
  public Optional<V> get(final K key) {
    try {
      if (key == null) {
        log.debug("Attempted to get value with null key");
        return Optional.empty();
      }
      
      V value = cache.get(key);
      if (value != null) {
        log.trace(STR."Cache hit for key: \{key}");
      } else {
        log.trace(STR."Cache miss for key: \{key}");
      }
      
      return Optional.ofNullable(value);
    } catch (Exception e) {
      handleException(e, STR."Error retrieving value for key: \{key}");
      return Optional.empty();
    }
  }

  @Override
  public void put(final K key, final V value) {
    try {
      if (key == null) {
        log.debug("Attempted to put value with null key");
        return;
      }
      
      if (value == null) {
        log.debug(STR."Attempted to put null value for key: \{key}");
        return;
      }
      
      cache.put(key, value);
      log.trace(STR."Cached value for key: \{key}");
    } catch (Exception e) {
      handleException(e, STR."Error putting value for key: \{key}");
    }
  }

  @Override
  public void remove(final K key) {
    try {
      if (key == null) {
        log.debug("Attempted to remove value with null key");
        return;
      }
      
      cache.remove(key);
      log.trace(STR."Removed value for key: \{key}");
    } catch (Exception e) {
      handleException(e, STR."Error removing value for key: \{key}");
    }
  }

  @Override
  public void removeAll() {
    try {
      cache.removeAll();
      log.debug("Removed all values from cache");
    } catch (Exception e) {
      handleException(e, "Error removing all values from cache");
    }
  }
  
  /**
   * Handles exceptions from cache operations using pattern matching for switch.
   * 
   * @param exception the exception to handle
   * @param message the error message context
   */
  private void handleException(Exception exception, String message) {
    switch (exception) {
      case CacheLoaderException e -> log.error(STR."\{message}: Cache loader error", e);
      case CacheWriterException e -> log.error(STR."\{message}: Cache writer error", e);
      case IllegalStateException e -> log.error(STR."\{message}: Cache is in illegal state", e);
      case ExecutionException e -> log.error(STR."\{message}: Execution error", e);
      case CacheException e -> log.error(STR."\{message}: General cache error", e);
      case null -> log.error(STR."\{message}: Unknown error (null exception)");
      default -> log.error(STR."\{message}: Unexpected error", exception);
    }
  }
}
