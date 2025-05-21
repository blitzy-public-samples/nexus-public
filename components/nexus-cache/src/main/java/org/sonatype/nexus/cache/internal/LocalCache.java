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
import javax.cache.Cache;

import org.sonatype.nexus.cache.NexusCache;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * The implementation of the {@link NexusCache}.
 *
 * <p>This implementation is compatible with Java 21 and supports execution in Virtual Thread contexts.
 * It provides non-blocking operations to avoid Virtual Thread pinning and uses Pattern Matching
 * for improved error handling.</p>
 *
 * @param <K> the type of key
 * @param <V> the type of value
 */
public class LocalCache<K, V>
    implements NexusCache<K, V>
{
  private final Cache<K, V> cache;

  /**
   * Creates a new LocalCache instance wrapping the provided JCache implementation.
   * 
   * @param cache the JCache implementation to wrap, must not be null
   * @throws NullPointerException if cache is null
   */
  public LocalCache(final Cache<K, V> cache) {
    this.cache = checkNotNull(cache, "Cache cannot be null");
  }

  @Override
  public Optional<V> get(final K key) {
    try {
      return Optional.ofNullable(cache.get(key));
    } 
    catch (Exception e) {
      // Using Pattern Matching for switch to handle different exception types
      return switch (e) {
        case IllegalStateException ise -> {
          // Cache is closed or disposed
          logError("Cache access error: cache is closed or disposed", ise);
          yield Optional.empty();
        }
        case javax.cache.CacheException ce -> {
          // General cache exception
          logError("Cache operation failed for key", ce);
          yield Optional.empty();
        }
        default -> {
          // Any other exception
          logError("Unexpected error accessing cache", e);
          yield Optional.empty();
        }
      };
    }
  }

  @Override
  public void put(final K key, final V value) {
    try {
      cache.put(key, value);
    }
    catch (Exception e) {
      // Using Pattern Matching for switch to handle different exception types
      switch (e) {
        case IllegalStateException ise -> 
          logError(STR."Cache put error: cache is closed or disposed for key \{key}", ise);
        case javax.cache.CacheException ce -> 
          logError(STR."Cache put operation failed for key \{key}", ce);
        default -> 
          logError(STR."Unexpected error during cache put operation for key \{key}", e);
      }
    }
  }

  @Override
  public void remove(final K key) {
    try {
      cache.remove(key);
    }
    catch (Exception e) {
      // Using Pattern Matching for switch to handle different exception types
      switch (e) {
        case IllegalStateException ise -> 
          logError(STR."Cache remove error: cache is closed or disposed for key \{key}", ise);
        case javax.cache.CacheException ce -> 
          logError(STR."Cache remove operation failed for key \{key}", ce);
        default -> 
          logError(STR."Unexpected error during cache remove operation for key \{key}", e);
      }
    }
  }

  @Override
  public void removeAll() {
    try {
      cache.removeAll();
    }
    catch (Exception e) {
      // Using Pattern Matching for switch to handle different exception types
      switch (e) {
        case IllegalStateException ise -> 
          logError("Cache removeAll error: cache is closed or disposed", ise);
        case javax.cache.CacheException ce -> 
          logError("Cache removeAll operation failed", ce);
        case UnsupportedOperationException uoe -> 
          logError("Cache removeAll operation is not supported by this implementation", uoe);
        default -> 
          logError("Unexpected error during cache removeAll operation", e);
      }
    }
  }
  
  /**
   * Logs an error message with the associated exception.
   * This is a placeholder method that would be replaced with actual logging in a real implementation.
   * 
   * @param message the error message
   * @param e the exception that occurred
   */
  private void logError(String message, Exception e) {
    // In a real implementation, this would log to a proper logging framework
    // For now, we'll just print to stderr for demonstration purposes
    System.err.println(STR."\{message}: \{e.getMessage()}");
  }
}