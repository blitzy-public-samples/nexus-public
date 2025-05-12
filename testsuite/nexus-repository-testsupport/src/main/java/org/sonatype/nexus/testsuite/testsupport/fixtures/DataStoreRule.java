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
package org.sonatype.nexus.testsuite.testsupport.fixtures;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Provider;

import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.datastore.api.DataStoreManager;

import org.junit.rules.ExternalResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit rule for managing DataStore instances in tests.
 * 
 * @since 3.20
 * @see org.junit.rules.ExternalResource
 */
public class DataStoreRule
    extends ExternalResource
{
  private static final Logger log = LoggerFactory.getLogger(DataStoreRule.class);

  private final Provider<DataStoreManager> dataStoreManagerProvider;

  private final Set<String> managedDataStores = new HashSet<>();

  /**
   * Constructs a new DataStoreRule with the given DataStoreManager provider.
   *
   * @param dataStoreManagerProvider the provider for the DataStoreManager
   */
  public DataStoreRule(final Provider<DataStoreManager> dataStoreManagerProvider) {
    this.dataStoreManagerProvider = dataStoreManagerProvider;
  }

  @Override
  protected void after() {
    // Use virtual threads for cleanup operations to improve performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      managedDataStores.forEach(storeName -> {
        executor.submit(() -> {
          try {
            dataStoreManagerProvider.get().delete(storeName);
            log.debug(STR."Removed data store: \{storeName}");
          }
          catch (Exception e) { // NOSONAR
            log.info(STR."Unable to clean up data store \{storeName}", e);
          }
        });
      });
    }
  }

  /**
   * Creates a DataStore with the given configuration.
   *
   * @param configuration the DataStore configuration
   * @return the created DataStore
   * @throws RuntimeException if creation fails
   */
  public DataStore<?> createDataStore(final DataStoreConfiguration configuration) {
    try {
      DataStore<?> dataStore = dataStoreManagerProvider.get().create(configuration);
      managedDataStores.add(configuration.getName());
      return dataStore;
    }
    catch (Exception e) {
      throw new RuntimeException(STR."Failed to create data store with configuration: \{configuration.getName()}", e);
    }
  }

  /**
   * Creates a DataStore with the given name using default settings.
   *
   * @param storeName the name of the DataStore
   * @return the created DataStore
   */
  public DataStore<?> createDataStore(final String storeName) {
    return createDataStore(storeName, null, null, "jdbc:h2:file:${karaf.data}/db/${storeName}");
  }

  /**
   * Creates a DataStore with the given name, credentials, and JDBC URL.
   *
   * @param storeName the name of the DataStore
   * @param username the username for database access (may be null)
   * @param password the password for database access (may be null)
   * @param jdbcUrl the JDBC URL for the database
   * @return the created DataStore
   * @throws RuntimeException if creation fails
   */
  public DataStore<?> createDataStore(
      final String storeName,
      final String username,
      final String password,
      final String jdbcUrl)
  {
    try {
      DataStoreConfiguration configuration = new DataStoreConfiguration();

      configuration.setName(storeName);
      configuration.setSource("local");
      configuration.setType("jdbc");

      Map<String, String> attributes = new HashMap<>();
      attributes.put("jdbcUrl", jdbcUrl);
      if (username != null) {
        attributes.put("username", username);
      }
      if (password != null) {
        attributes.put("password", password);
      }
      configuration.setAttributes(attributes);

      DataStore<?> dataStore = dataStoreManagerProvider.get().create(configuration);
      managedDataStores.add(configuration.getName());
      return dataStore;
    }
    catch (Exception e) {
      throw new RuntimeException(STR."Failed to create data store: \{storeName}", e);
    }
  }

  /**
   * Add a data store to automatically cleanup upon test completion.
   *
   * @param storeName the name of the DataStore to manage
   */
  public void manageDataStore(final String storeName) {
    managedDataStores.add(storeName);
  }

  /**
   * Remove a data store from automatic cleanup.
   *
   * @param storeName the name of the DataStore to unmanage
   */
  public void unmanageAlias(final String storeName) {
    managedDataStores.remove(storeName);
  }
}