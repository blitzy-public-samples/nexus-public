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
package org.sonatype.nexus.cache.internal.ehcache;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import javax.cache.Cache;
import javax.cache.CacheManager;
import javax.inject.Named;

import org.sonatype.nexus.cache.AbstractCacheBuilder;

import org.ehcache.config.CacheRuntimeConfiguration;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.event.CacheEventListener;
import org.ehcache.event.EventFiring;
import org.ehcache.event.EventOrdering;
import org.ehcache.event.EventType;
import org.ehcache.expiry.ExpiryPolicy;
import org.ehcache.jsr107.Eh107Configuration;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * EhCache JCache {@link CacheBuilder}.
 *
 * @since 3.14
 */
@Named("ehcache")
public class EhCacheBuilder<K, V>
    extends AbstractCacheBuilder<K, V>
{
  @Override
  @SuppressWarnings("unchecked")
  public Cache<K, V> build(final CacheManager manager) {
    checkNotNull(manager);
    checkNotNull(keyType);
    checkNotNull(valueType);
    checkNotNull(name);
    checkNotNull(expiryFactory);

    CacheConfigurationBuilder<K, V> builder = CacheConfigurationBuilder.newCacheConfigurationBuilder(
        keyType,
        valueType,
        ResourcePoolsBuilder.heap(cacheSize));

    builder.withExpiry(mapToEhCacheExpiry(expiryFactory.create()));

    log.debug(STR."Creating cache \{name} with key type \{keyType.getSimpleName()} and value type \{valueType.getSimpleName()}");
    Cache<K, V> cache = manager.createCache(name, Eh107Configuration.fromEhcacheCacheConfiguration(builder));

    manager.enableStatistics(name, statisticsEnabled);
    manager.enableManagement(name, managementEnabled);

    if (persister != null) {
      log.debug(STR."Registering event listener for cache \{name} with persister");
      
      // Create a virtual thread executor for asynchronous event processing
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      
      CacheEventListener<K, V> listener = cacheEvent -> {
        try {
          executor.submit(() -> persister.accept(cacheEvent.getKey(), cacheEvent.getOldValue()));
        } 
        catch (Exception e) {
          // Use pattern matching to handle different types of exceptions
          switch (e) {
            case RuntimeException re -> log.error(STR."Runtime error in cache event listener for \{name}: \{re.getMessage()}", re);
            case InterruptedException ie -> {
              log.warn(STR."Cache event listener for \{name} was interrupted", ie);
              Thread.currentThread().interrupt();
            }
            default -> log.error(STR."Error in cache event listener for \{name}: \{e.getMessage()}", e);
          }
        }
      };

      Eh107Configuration<K, V> configuration = cache.getConfiguration(Eh107Configuration.class);
      configuration.unwrap(CacheRuntimeConfiguration.class)
          .registerCacheEventListener(listener, EventOrdering.UNORDERED, EventFiring.ASYNCHRONOUS,
              EventType.EVICTED, EventType.REMOVED, EventType.EXPIRED);
      
      log.debug(STR."Event listener registered for cache \{name} for events: EVICTED, REMOVED, EXPIRED");
    }

    return cache;
  }

  private ExpiryPolicy<K, V> mapToEhCacheExpiry(final javax.cache.expiry.ExpiryPolicy policy) {
    return new ExpiryPolicy<K, V>()
    {
      @Override
      public Duration getExpiryForCreation(final K key, final V value) {
        return toJavaDuration(policy.getExpiryForCreation());
      }

      @Override
      public Duration getExpiryForAccess(final K key, final Supplier<? extends V> value) {
        return toJavaDuration(policy.getExpiryForAccess());
      }

      @Override
      public Duration getExpiryForUpdate(final K key, final Supplier<? extends V> oldValue, final V newValue) {
        return toJavaDuration(policy.getExpiryForUpdate());
      }

      private Duration toJavaDuration(final javax.cache.expiry.Duration duration) {
        if (duration == null) {
          return null;
        }
        
        if (duration.isEternal()) {
          return Duration.of(1, ChronoUnit.FOREVER);
        }
        
        // Use switch expression with pattern matching for more concise code
        return switch (duration.getTimeUnit()) {
          case DAYS -> Duration.of(duration.getDurationAmount(), ChronoUnit.DAYS);
          case HOURS -> Duration.of(duration.getDurationAmount(), ChronoUnit.HOURS);
          case MICROSECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.MICROS);
          case MILLISECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.MILLIS);
          case MINUTES -> Duration.of(duration.getDurationAmount(), ChronoUnit.MINUTES);
          case NANOSECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.NANOS);
          case SECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.SECONDS);
          default -> {
            log.warn(STR."Unknown time unit \{duration.getTimeUnit()}, defaulting to SECONDS");
            yield Duration.of(duration.getDurationAmount(), ChronoUnit.SECONDS);
          }
        };
      }
    };
  }
}