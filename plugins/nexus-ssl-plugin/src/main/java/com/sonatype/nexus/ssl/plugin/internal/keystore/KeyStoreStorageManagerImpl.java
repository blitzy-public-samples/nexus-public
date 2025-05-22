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
package com.sonatype.nexus.ssl.plugin.internal.keystore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.common.entity.EntityVersion;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;
import org.sonatype.nexus.transaction.Transactional;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.StringTemplate.STR;

/**
 * MyBatis {@link KeyStoreStorageManager} implementation.
 *
 * @since 3.21
 */
@Named(KeyStoreManagerImpl.NAME)
@Singleton
public class KeyStoreStorageManagerImpl
    extends ConfigStoreSupport<KeyStoreDAO>
    implements KeyStoreStorageManager
{
  private static final Logger log = LoggerFactory.getLogger(KeyStoreStorageManagerImpl.class);
  
  private final EventManager eventManager;

  @Inject
  public KeyStoreStorageManagerImpl(final DataSessionSupplier sessionSupplier, final EventManager eventManager) {
    super(sessionSupplier);
    this.eventManager = checkNotNull(eventManager);
  }

  @Override
  public KeyStoreStorage createStorage(final String keyStoreName) {
    return new KeyStoreStorageImpl(this, keyStoreName);
  }

  /**
   * Checks if a keystore with the given name exists.
   * Uses a virtual thread for database operation to improve I/O performance.
   */
  @Nullable
  public boolean exists(final String keyStoreName) {
    try {
      return runOnVirtualThread(() -> {
        log.debug(STR."Checking existence of keystore: \{keyStoreName}");
        return existsTransactional(keyStoreName);
      });
    }
    catch (Exception e) {
      log.error(STR."Error checking existence of keystore \{keyStoreName}: \{e.getMessage()}", e);
      return false;
    }
  }
  
  @Transactional
  protected boolean existsTransactional(final String keyStoreName) {
    return dao().load(keyStoreName).isPresent();
  }

  /**
   * Loads a keystore with the given name.
   * Uses a virtual thread for database operation to improve I/O performance.
   */
  public ByteArrayInputStream load(final String keyStoreName) {
    try {
      log.debug(STR."Loading keystore: \{keyStoreName}");
      Optional<KeyStoreData> data = runOnVirtualThread(() -> doLoadTransactional(keyStoreName));
      checkState(data.isPresent(), STR."Key store \{keyStoreName} does not exist");
      postEvent(keyStoreName);
      return new ByteArrayInputStream(data.get().getBytes());
    }
    catch (Exception e) {
      log.error(STR."Error loading keystore \{keyStoreName}: \{e.getMessage()}", e);
      throw e;
    }
  }

  @Transactional
  protected Optional<KeyStoreData> doLoadTransactional(final String keyStoreName) {
    return dao().load(keyStoreName);
  }

  /**
   * Saves a keystore with the given name.
   * Uses a virtual thread for database operation to improve I/O performance.
   */
  public void save(final String keyStoreName, final ByteArrayOutputStream out) {
    try {
      log.debug(STR."Saving keystore: \{keyStoreName}");
      KeyStoreData data = new KeyStoreData();
      data.setName(keyStoreName);
      data.setBytes(out.toByteArray());
      runOnVirtualThread(() -> {
        doSaveTransactional(data);
        return null; // Callable requires a return value
      });
      postEvent(keyStoreName);
    }
    catch (Exception e) {
      log.error(STR."Error saving keystore \{keyStoreName}: \{e.getMessage()}", e);
      throw new RuntimeException(STR."Failed to save keystore \{keyStoreName}", e);
    }
  }

  @Transactional
  protected void doSaveTransactional(final KeyStoreData data) {
    dao().save(data);
  }

  /**
   * Posts an event to notify listeners about keystore changes.
   * This helps invalidate caches and update security contexts.
   */
  private void postEvent(final String keyStoreName) {
    log.debug(STR."Posting keystore event for: \{keyStoreName}");
    // trigger invalidation of TrustStoreImpl context
    eventManager.post(new KeyStoreDataEvent()
    {
      @Override
      public boolean isLocal() {
        return true;
      }

      @Override
      public EntityVersion getVersion() {
        return null;
      }

      @Override
      public String getRemoteNodeId() {
        return null;
      }

      @Override
      public String getKeyStoreName() {
        return keyStoreName;
      }
    });
  }
  /**
   * Executes a database operation on a virtual thread for improved I/O performance.
   * This method leverages Java 21's Virtual Threads to handle database operations more efficiently.
   *
   * @param <T> The return type of the operation
   * @param operation The database operation to execute
   * @return The result of the operation
   * @throws Exception If an error occurs during execution
   */
  private <T> T runOnVirtualThread(Callable<T> operation) throws Exception {
    try {
      return Executors.newVirtualThreadPerTaskExecutor().submit(operation).get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Virtual thread operation was interrupted", e);
    }
  }
}