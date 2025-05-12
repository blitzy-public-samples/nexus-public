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

import java.util.Optional;

import org.sonatype.nexus.datastore.api.DataAccess;

/**
 * {@link KeyStoreData} access interface for CRUD operations on SSL keystores.
 * <p>
 * This interface provides a data access layer for KeyStoreData records, allowing
 * persistence operations to be performed in a type-safe manner. It is designed to work
 * with Java 21 record patterns and the MyBatis 3.5.15 SQL mapping framework.
 * <p>
 * Implementation is provided by the Nexus DataStore framework at runtime and is compatible
 * with Karaf 4.4.4 OSGi container and Guice 7.0.0 dependency injection.
 *
 * @since 3.21
 * @see KeyStoreData The record type managed by this DAO
 * @see org.sonatype.nexus.datastore.api.DataAccess The base interface for all data access objects
 */
public interface KeyStoreDAO
    extends DataAccess
{
  /**
   * Load a {@link KeyStoreData} record by name.
   *
   * @param name the unique identifier of the keystore to load
   * @return an Optional containing the KeyStoreData if found, or empty if not found
   */
  Optional<KeyStoreData> load(String name);

  /**
   * Save a {@link KeyStoreData} record.
   * <p>
   * If a record with the same name already exists, it will be updated.
   * Otherwise, a new record will be created.
   *
   * @param entity the KeyStoreData record to save
   * @return true if the operation was successful, false otherwise
   */
  boolean save(KeyStoreData entity);

  /**
   * Delete a {@link KeyStoreData} record by name.
   *
   * @param name the unique identifier of the keystore to delete
   * @return true if a record was deleted, false if no matching record was found
   */
  boolean delete(String name);
}