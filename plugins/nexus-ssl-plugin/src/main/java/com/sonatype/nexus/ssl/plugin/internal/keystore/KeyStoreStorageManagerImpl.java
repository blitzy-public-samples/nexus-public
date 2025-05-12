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
import java.util.concurrent.Executors;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.common.entity.EntityVersion;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.ssl.spi.KeyStoreStorage;
import org.sonatype.nexus.ssl.spi.KeyStoreStorageManager;
import org.sonatype.nexus.transaction.Transactional;

import static java.lang.StringTemplate.STR;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * MyBatis {@link KeyStoreStorageManager} implementation.
 * 
 * Updated for Java 21 with the following enhancements:
 * <ul>
 *   <li>Virtual threads for I/O operations to improve scalability</li>
 *   <li>String templates for more readable error messages</li>
 *   <li>Records for simplified event handling</li>
 *   <li>Jakarta EE 9+ compatible annotations</li>
 * </ul>
 *
 * @since 3.21
 */
@Named(KeyStoreManagerImpl.NAME)
@Singleton
public class KeyStoreStorageManagerImpl
    extends ConfigStoreSupport<KeyStoreDAO>
    implements KeyStoreStorageManager
{
  private final EventManager eventManager;

  @Inject
  public KeyStoreStorageManagerImpl(final DataSessionSupplier sessionSupplier, final EventManager eventManager) {
    super(sessionSupplier);
    this.eventManager = checkNotNull(eventManager);
  }

  /**
   * Creates a new KeyStoreStorage instance for the given key store name.
   * 
   * @param keyStoreName the name of the key store
   * @return a new KeyStoreStorage instance
   */
  @Override
  public KeyStoreStorage createStorage(final String keyStoreName) {
    return new KeyStoreStorageImpl(this, keyStoreName);
  }

  /**
   * Checks if a key store with the given name exists.
   * 
   * @param keyStoreName the name of the key store to check
   * @return true if the key store exists, false otherwise
   */
  @Transactional
  @Nullable
  public boolean exists(final String keyStoreName) {
    return dao().load(keyStoreName).isPresent();
  }

  /**
   * Loads the key store data using a virtual thread for improved I/O performance.
   * 
   * Virtual threads in Java 21 are lightweight and managed by the JVM, making them ideal
   * for I/O-bound operations like database access and file operations.
   */
  public ByteArrayInputStream load(final String keyStoreName) {
    try {
      return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        Optional<KeyStoreData> data = doLoad(keyStoreName);
        checkState(data.isPresent(), STR."key store \{keyStoreName} does not exist");
        postEvent(keyStoreName);
        return new ByteArrayInputStream(data.get().getBytes());
      }).get();
    }
    catch (Exception e) {
      throw new RuntimeException(STR."Failed to load key store \{keyStoreName}", e);
    }
  }

  /**
   * Loads key store data from the database.
   * 
   * @param keyStoreName the name of the key store to load
   * @return an Optional containing the key store data if found
   */
  @Transactional
  protected Optional<KeyStoreData> doLoad(final String keyStoreName) {
    return dao().load(keyStoreName);
  }

  /**
   * Saves the key store data using a virtual thread for improved I/O performance.
   * 
   * Using virtual threads allows the application to handle many concurrent operations
   * without the overhead of traditional platform threads, resulting in better scalability
   * for I/O-bound operations like database writes.
   */
  public void save(final String keyStoreName, final ByteArrayOutputStream out) {
    try {
      Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
        KeyStoreData data = new KeyStoreData();
        data.setName(keyStoreName);
        data.setBytes(out.toByteArray());
        doSave(data);
        postEvent(keyStoreName);
        return null;
      }).get();
    }
    catch (Exception e) {
      throw new RuntimeException(STR."Failed to save key store \{keyStoreName}", e);
    }
  }

  /**
   * Saves key store data to the database.
   * 
   * @param data the key store data to save
   */
  @Transactional
  protected void doSave(final KeyStoreData data) {
    dao().save(data);
  }

  /**
   * Record for KeyStoreDataEvent to simplify event creation.
   * 
   * Java 21 records provide a concise way to create immutable data carriers,
   * reducing boilerplate code compared to the previous anonymous inner class approach.
   */
  private record KeyStoreDataEventRecord(String keyStoreName) implements KeyStoreDataEvent {
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
  }

  /**
   * Posts a KeyStoreDataEvent to trigger invalidation of TrustStoreImpl context.
   * 
   * @param keyStoreName the name of the key store that was modified
   */
  private void postEvent(final String keyStoreName) {
    // trigger invalidation of TrustStoreImpl context using Java 21 record
    eventManager.post(new KeyStoreDataEventRecord(keyStoreName));
  }
}