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
package org.sonatype.nexus.repository.content.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.inject.Inject;

import org.sonatype.nexus.datastore.api.ContentDataAccess;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Manages content stores for a particular format. To get the {@link FormatStoreManager} for format "example", inject
 * <p>
 * {@code @Named("example") FormatStoreManager}
 * <p>
 * Content stores can exist in different datastores (unlike config stores that only exist in the config datastore)
 * so you need to pass in the datastore name. The manager knows which DAO and store classes to use for the format.
 *
 * @since 3.24
 */
@SuppressWarnings("unchecked")
public class FormatStoreManager
{
  // Using ConcurrentHashMap for better performance with Java 21 and Virtual Threads
  // This provides thread safety without the overhead of synchronized blocks that can cause pinning
  private final ConcurrentHashMap<String, ContentStoreSupport<?>> cachedStores = new ConcurrentHashMap<>();
  
  // Lock for cache operations to ensure thread safety during concurrent access
  // Using ReentrantReadWriteLock to allow multiple concurrent reads but exclusive writes
  private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

  private final String formatClassPrefix;

  private Map<String, FormatStoreFactory> formatStoreFactories;

  FormatStoreManager(final String formatClassPrefix) {
    this.formatClassPrefix = checkNotNull(formatClassPrefix);
  }

  @Inject
  void setFormatStoreFactories(final Map<String, FormatStoreFactory> formatStoreFactories) {
    this.formatStoreFactories = checkNotNull(formatStoreFactories);
  }

  /**
   * Gets the {@link ContentRepositoryStore} for this format in the named datastore.
   */
  public <T extends ContentRepositoryStore<?>> T contentRepositoryStore(final String contentStoreName) {
    return (T) formatStore(contentStoreName, ContentRepositoryDAO.class);
  }

  /**
   * Gets the {@link ComponentStore} for this format in the named datastore.
   */
  public <T extends ComponentStore<?>> T componentStore(final String contentStoreName) {
    return (T) formatStore(contentStoreName, ComponentDAO.class);
  }

  /**
   * Gets the {@link AssetStore} for this format in the named datastore.
   */
  public <T extends AssetStore<?>> T assetStore(final String contentStoreName) {
    return (T) formatStore(contentStoreName, AssetDAO.class);
  }

  /**
   * Gets the {@link AssetBlobStore} for this format in the named datastore.
   */
  public <T extends AssetBlobStore<?>> T assetBlobStore(final String contentStoreName) {
    return (T) formatStore(contentStoreName, AssetBlobDAO.class);
  }

  /**
   * Gets the format-specific store for the named datastore and type of DAO (component/asset/etc...)
   * If the store doesn't exist it is created and cached for other repositories in the same datastore.
   * 
   * This implementation is optimized for Java 21 Virtual Threads, avoiding thread pinning
   * by using non-blocking operations and minimizing synchronization.
   */
  public <T extends ContentStoreSupport<D>, D extends ContentDataAccess> T formatStore(
      final String contentStoreName,
      final Class<? extends D> daoClass)
  {
    String cacheKey = contentStoreName + '/' + formatDaoName(daoClass);
    
    // Fast path - check if the store is already in the cache
    ContentStoreSupport<?> store = cachedStores.get(cacheKey);
    if (store != null) {
      return (T) store;
    }
    
    // Slow path - create the store if it doesn't exist
    // Using read lock for the check to allow concurrent reads
    cacheLock.readLock().lock();
    try {
      store = cachedStores.get(cacheKey);
      if (store != null) {
        return (T) store;
      }
    } finally {
      cacheLock.readLock().unlock();
    }
    
    // Need to create the store - acquire write lock
    cacheLock.writeLock().lock();
    try {
      // Double-check to avoid race conditions
      store = cachedStores.get(cacheKey);
      if (store == null) {
        store = createFormatStore(contentStoreName, daoClass);
        cachedStores.put(cacheKey, store);
      }
      return (T) store;
    } finally {
      cacheLock.writeLock().unlock();
    }
  }

  /**
   * Creates a format-specific store for the named datastore and type of DAO (component/asset/etc...)
   */
  private ContentStoreSupport<?> createFormatStore(final String contentStoreName, final Class<?> daoClass) {
    FormatStoreFactory factory = formatStoreFactories.get(formatDaoName(daoClass));
    if (factory != null) {
      return factory.createFormatStore(contentStoreName);
    }
    else {
      throw new IllegalArgumentException("Unexpected DAO class: " + daoClass);
    }
  }

  /**
   * Returns the format-specific name for the requested DAO type.
   */
  private String formatDaoName(final Class<?> daoClass) {
    String daoName = daoClass.getSimpleName();
    if (daoName.startsWith(formatClassPrefix)) {
      return daoName; // requested DAO is already format-specific
    }
    return formatClassPrefix + daoName;
  }
}