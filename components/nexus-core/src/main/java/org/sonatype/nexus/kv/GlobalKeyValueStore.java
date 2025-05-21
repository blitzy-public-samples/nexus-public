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
package org.sonatype.nexus.kv;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.datastore.ConfigStoreSupport;
import org.sonatype.nexus.datastore.api.DataSessionSupplier;
import org.sonatype.nexus.internal.kv.NexusKeyValueDAO;
import org.sonatype.nexus.transaction.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Iterables;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * MyBatis nexus_key_value access interface
 */
@Named("mybatis")
@Singleton
public class GlobalKeyValueStore
    extends ConfigStoreSupport<NexusKeyValueDAO>
{
  private final ObjectMapper mapper;
  private final ExecutorService virtualThreadExecutor;

  @Inject
  public GlobalKeyValueStore(final DataSessionSupplier sessionSupplier, final ObjectMapper mapper) {
    super(sessionSupplier, NexusKeyValueDAO.class);
    this.mapper = checkNotNull(mapper);
    // Create a virtual thread executor for I/O-bound database operations
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Gets a value by the given key using a virtual thread for improved I/O throughput.
   * This method uses a virtual thread to execute the database operation, which allows
   * the carrier thread to be released during I/O wait, improving overall system throughput.
   *
   * @param key a string key
   * @return {@link Optional<NexusKeyValue>}
   */
  @Transactional
  public Optional<NexusKeyValue> getKey(final String key) {
    try {
      // Execute the database operation in a virtual thread
      return CompletableFuture.supplyAsync(() -> dao().get(key), virtualThreadExecutor).join();
    } catch (Exception e) {
      // Fall back to direct execution if virtual thread execution fails
      return dao().get(key);
    }
  }

  /**
   * Gets a value by the given key and deserializes it to the specified class.
   * Uses virtual threads for both database access and JSON deserialization for large objects.
   *
   * @param key a string key
   * @param clazz the class to deserialize to
   * @return {@link Optional<E>}
   */
  public <E> Optional<E> get(final String key, final Class<E> clazz) {
    Optional<NexusKeyValue> val = getKey(key);
    if (!val.isPresent()) {
      return Optional.empty();
    }

    // For large objects, use a virtual thread for deserialization
    return val.map(o -> {
      try {
        return CompletableFuture.supplyAsync(() -> o.getAsObject(mapper, clazz), virtualThreadExecutor).join();
      } catch (Exception e) {
        // Fall back to direct execution if virtual thread execution fails
        return o.getAsObject(mapper, clazz);
      }
    });
  }

  public Optional<Boolean> getBoolean(final String key) {
    return getKey(key)
        .map(NexusKeyValue::getAsBoolean);
  }

  public Optional<String> getString(final String key) {
    return getKey(key)
        .map(NexusKeyValue::getAsString);
  }

  public Optional<Integer> getInt(final String key) {
    return getKey(key)
        .map(NexusKeyValue::getAsInt);
  }

  /**
   * Sets a key_value record using a virtual thread for improved I/O throughput.
   * This method uses a virtual thread to execute the database operation and publishes
   * events asynchronously for better performance.
   *
   * @param keyValue record to be created/updated
   */
  @Transactional
  public void setKey(final NexusKeyValue keyValue) {
    // Publish event asynchronously using a virtual thread
    CompletableFuture.runAsync(() -> 
        super.postCommitEvent(() -> new KeyValueEvent(keyValue.key(), 
            Iterables.getOnlyElement(keyValue.value().values()))),
        virtualThreadExecutor);
    
    try {
      // Execute the database operation in a virtual thread
      CompletableFuture.runAsync(() -> dao().set(keyValue), virtualThreadExecutor).join();
    } catch (Exception e) {
      // Fall back to direct execution if virtual thread execution fails
      dao().set(keyValue);
    }
  }

  public void setBoolean(final String key, final boolean value) {
    setKey(new NexusKeyValue(key, ValueType.BOOLEAN, value));
  }

  public void setInt(final String key, final int value) {
    setKey(new NexusKeyValue(key, ValueType.NUMBER, value));
  }

  public void setString(final String key, final String value) {
    setKey(new NexusKeyValue(key, ValueType.CHARACTER, value));
  }

  public void setString(final String key, final Object value) {
    setKey(new NexusKeyValue(key, ValueType.OBJECT, value));
  }

  /**
   * Removes a value by the given key using a virtual thread for improved I/O throughput.
   * This method uses a virtual thread to execute the database operation, which allows
   * the carrier thread to be released during I/O wait, improving overall system throughput.
   *
   * @param key a string key
   * @return a primitive boolean indicating if the record was deleted successfully or not
   */
  @Transactional
  public boolean removeKey(final String key) {
    try {
      // Execute the database operation in a virtual thread
      return CompletableFuture.supplyAsync(() -> dao().remove(key), virtualThreadExecutor).join();
    } catch (Exception e) {
      // Fall back to direct execution if virtual thread execution fails
      return dao().remove(key);
    }
  }
  
  /**
   * Closes the virtual thread executor when the store is no longer needed.
   * This method should be called when the application is shutting down.
   */
  public void close() {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdown();
    }
  }
}