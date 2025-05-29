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
import jakarta.inject.Named;

import org.sonatype.nexus.cache.AbstractCacheBuilder;
import org.sonatype.nexus.cache.CacheBuilder;

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
 * EhCache implementation of {@link org.sonatype.nexus.cache.CacheBuilder}.
 *
 * @since 3.14
 */
@Named("ehcache")
@SuppressWarnings("unused")  // Used by DI container
public class EhCacheBuilder<K, V>
    extends AbstractCacheBuilder<K, V>
    implements AutoCloseable
{
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private CacheEventListener<K, V> createListener() {
        return cacheEvent -> {
            try {
                executor.submit(() -> properties.persister().accept(cacheEvent.getKey(), cacheEvent.getOldValue()));
            }
            catch (RuntimeException e) {
                log.error("Error in cache event listener for {}: {}", properties.name(), e.getMessage(), e);
            }
        };
    }

    @Override
    public Cache<K, V> build(final CacheManager manager) {
        checkNotNull(manager);
        checkNotNull(properties.keyType());
        checkNotNull(properties.valueType());
        checkNotNull(properties.name());
        checkNotNull(properties.expiryFactory());

        Cache<K, V> cache = null;
        try {
            CacheConfigurationBuilder<K, V> builder = CacheConfigurationBuilder.newCacheConfigurationBuilder(
                properties.keyType(),
                properties.valueType(),
                ResourcePoolsBuilder.heap(properties.cacheSize()));

            builder.withExpiry(mapToEhCacheExpiry(properties.expiryFactory().create()));

            cache = manager.createCache(properties.name(),
                Eh107Configuration.fromEhcacheCacheConfiguration(builder));

            manager.enableStatistics(properties.name(), properties.statisticsEnabled());
            manager.enableManagement(properties.name(), properties.managementEnabled());

            if (properties.persister() != null) {
                log.debug("Registering event listener for cache {} with persister", properties.name());
                registerEventListener(cache);
            }

            return cache;
        }
        catch (Exception e) {
            log.error("Failed to build cache {}: {}", properties.name(), e.getMessage(), e);
            if (cache != null) {
                manager.destroyCache(properties.name());
            }
            throw e;
        }
        finally {
            // The executor is intentionally not shut down here since it's needed for the entire
            // cache lifecycle. It will be properly shut down in the close() method when the cache
            // is destroyed.
        }
    }

    private void registerEventListener(Cache<K, V> cache) {
        Eh107Configuration<K, V> configuration = cache.getConfiguration(Eh107Configuration.class);
        configuration.unwrap(CacheRuntimeConfiguration.class)
            .registerCacheEventListener(createListener(), EventOrdering.UNORDERED, EventFiring.ASYNCHRONOUS,
                EventType.EVICTED, EventType.REMOVED, EventType.EXPIRED);

        log.debug("Event listener registered for cache {} for events: EVICTED, REMOVED, EXPIRED", properties.name());
    }

    @Override
    public CacheBuilder<K, V> keyType(final Class<K> keyType) {
        setProperty("keyType", keyType);
        return this;
    }

    @Override
    public CacheBuilder<K, V> valueType(final Class<V> valueType) {
        setProperty("valueType", valueType);
        return this;
    }

    @SuppressWarnings("unchecked")
    protected void setProperty(String name, Object value) {
        // Use reflection to update the protected property
        try {
            java.lang.reflect.Field field = AbstractCacheBuilder.class.getDeclaredField("properties");
            field.setAccessible(true);
            CacheProperties<K, V> current = (CacheProperties<K, V>) field.get(this);

            // Create a copy of the record with the updated field
            java.lang.reflect.Constructor<?> constructor = current.getClass().getDeclaredConstructors()[0];
            constructor.setAccessible(true);

            Object[] args;
            if ("keyType".equals(name)) {
                args = new Object[] {
                    current.name(), current.expiryFactory(), current.cacheSize(),
                    current.storeByValue(), current.managementEnabled(), current.statisticsEnabled(),
                    value, current.valueType(), current.persister()
                };
            } else {
                args = new Object[] {
                    current.name(), current.expiryFactory(), current.cacheSize(),
                    current.storeByValue(), current.managementEnabled(), current.statisticsEnabled(),
                    current.keyType(), value, current.persister()
                };
            }

            field.set(this, constructor.newInstance(args));
        }
        catch (Exception e) {
            log.error("Failed to update property {}: {}", name, e.getMessage(), e);
            throw new RuntimeException("Failed to update property: " + name, e);
        }
    }

    private ExpiryPolicy<K, V> mapToEhCacheExpiry(final javax.cache.expiry.ExpiryPolicy policy) {
        return new ExpiryPolicy<>() {
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

                return switch (duration.getTimeUnit()) {
                    case DAYS -> Duration.of(duration.getDurationAmount(), ChronoUnit.DAYS);
                    case HOURS -> Duration.of(duration.getDurationAmount(), ChronoUnit.HOURS);
                    case MICROSECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.MICROS);
                    case MILLISECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.MILLIS);
                    case MINUTES -> Duration.of(duration.getDurationAmount(), ChronoUnit.MINUTES);
                    case NANOSECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.NANOS);
                    case SECONDS -> Duration.of(duration.getDurationAmount(), ChronoUnit.SECONDS);
                };
            }
        };
    }

    /**
     * Closes the cache event executor service.
     *
     * @throws InterruptedException if interrupted while waiting for executor shutdown
     */
    @Override
    public void close() throws InterruptedException {
        if (!executor.isShutdown()) {
            executor.shutdown();
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("Cache event executor did not terminate in time, forcing shutdown");
                executor.shutdownNow();
            }
        }
    }
}
