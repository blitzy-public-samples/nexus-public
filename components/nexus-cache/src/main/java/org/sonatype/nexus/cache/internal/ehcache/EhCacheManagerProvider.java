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

import java.io.File;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.cache.CacheManager;
import javax.cache.Caching;
import javax.cache.spi.CachingProvider;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Provider;
import jakarta.annotation.Nullable;

import org.sonatype.goodies.lifecycle.LifecycleSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.app.ManagedLifecycle;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.AbstractModule;
import org.ehcache.jsr107.EhcacheCachingProvider;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.STORAGE;

/**
 * EhCache JCache {@link CacheManager} provider.
 *
 * Loads configuration from {@code etc/fabric/ehcache.xml} if missing will use {@code ehcache-default.xml} resource.
 *
 * @since 3.0
 */
@Named("ehcache")
@ManagedLifecycle(phase = STORAGE)
public class EhCacheManagerProvider
    extends LifecycleSupport
    implements Provider<CacheManager>
{
    private static final String CONFIG_FILE = "ehcache.xml";

    private final URI configUri;

    // provide same manager instance until bounced
    private volatile CacheManager cacheManager;

    // future to track asynchronous initialization
    private volatile CompletableFuture<CacheManager> initializationFuture;

    @Inject
    public EhCacheManagerProvider(final ApplicationDirectories directories) {
        checkNotNull(directories);
        File file = new File(directories.getConfigDirectory("fabric"), CONFIG_FILE);
        if (file.exists()) {
            log.debug("Found configuration file: {}", file.getAbsolutePath());
            this.configUri = file.toURI();
        }
        else {
            log.warn("Missing configuration: {}", file.getAbsolutePath());
            this.configUri = null;
        }
    }

    @VisibleForTesting
    public EhCacheManagerProvider(@Nullable final URI uri) {
        this.configUri = uri;
    }

    private CacheManager create(@Nullable final URI config) {
        CachingProvider provider = Caching.getCachingProvider(
            EhcacheCachingProvider.class.getName(),
            EhcacheCachingProvider.class.getClassLoader());

        log.info("Creating cache-manager with configuration: {}", config);
        CacheManager manager = provider.getCacheManager(config, getClass().getClassLoader());
        log.debug("Created cache-manager: {}", manager);
        return manager;
    }

    /**
     * Initializes the CacheManager asynchronously using a Virtual Thread.
     * This improves startup performance by allowing the initialization to happen in parallel.
     */
    private synchronized void initializeAsync() {
        if (initializationFuture == null) {
            initializationFuture = CompletableFuture.supplyAsync(() -> {
                log.debug("Starting asynchronous CacheManager initialization with config: {}", configUri);
                CacheManager manager = create(configUri);
                log.debug("Completed asynchronous CacheManager initialization");
                return manager;
            }, Thread::startVirtualThread);
        }
    }

    @Override
    public synchronized CacheManager get() {
        checkState(!isStopped(), "Cache-manager destroyed");

        if (cacheManager == null) {
            if (initializationFuture == null) {
                // Start async initialization if not already started
                initializeAsync();
            }

            try {
                // Wait for the initialization to complete
                cacheManager = initializationFuture.get();
                log.info("Cache-manager initialized and ready for use");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("CacheManager initialization interrupted", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("Failed to initialize CacheManager", e.getCause());
            }
        }

        return cacheManager;
    }

    @Override
    protected void doStart() {
        // Eagerly initialize the cache manager
        get();
    }

    @Override
    protected void doStop() {
        if (cacheManager != null) {
            cacheManager.close();
            log.info("Cache-manager closed successfully");
            cacheManager = null;
        }

        if (initializationFuture != null) {
            initializationFuture.cancel(true);
            initializationFuture = null;
            log.debug("Cancelled any pending CacheManager initialization");
        }
    }

    /**
     * Module to bind this provider as a managed lifecycle.
     */
    @Named
    public static class Module
        extends AbstractModule
    {
        @Override
        protected void configure() {
            bind(EhCacheManagerProvider.class).asEagerSingleton();
        }
    }
}
