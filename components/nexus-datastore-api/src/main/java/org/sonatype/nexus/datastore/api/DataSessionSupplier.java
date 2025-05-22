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
package org.sonatype.nexus.datastore.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

/**
 * {@link DataSession} supplier; for use by clients who don't need the full store API.
 *
 * @since 3.19
 */
public interface DataSessionSupplier
{
  /**
   * Opens a new {@link DataSession} against the named data store.
   * <p>
   * This method is optimized for use with Java 21 Virtual Threads. When called from a Virtual Thread,
   * it will automatically handle thread context propagation and avoid thread pinning during I/O operations.
   *
   * @throws DataStoreNotFoundException if the store does not exist
   */
  DataSession<?> openSession(String storeName);

  /**
   * Opens a new {@link DataSession} against the named data store and sets the transaction isolation to Serializable.
   * <p>
   * This method is optimized for use with Java 21 Virtual Threads. When called from a Virtual Thread,
   * it will automatically handle thread context propagation and avoid thread pinning during I/O operations.
   *
   * @throws DataStoreNotFoundException if the store does not exist
   * @since 3.38
   */
  DataSession<?> openSerializableTransactionSession(String storeName);

  /**
   * Opens a new JDBC {@link Connection} to the named data store.
   * <p>
   * This method is optimized for use with Java 21 Virtual Threads. When called from a Virtual Thread,
   * it will automatically handle thread context propagation and avoid thread pinning during I/O operations.
   *
   * @throws DataStoreNotFoundException if the store does not exist
   * @throws UnsupportedOperationException if the store doesn't support JDBC
   */
  Connection openConnection(String storeName) throws SQLException;
  
  /**
   * Asynchronously opens a new {@link DataSession} against the named data store.
   * <p>
   * This method is designed for high-throughput scenarios where many concurrent database operations
   * are needed. It leverages Java 21 Virtual Threads for efficient non-blocking connection allocation.
   *
   * @param storeName the name of the data store
   * @return a future that completes with the opened session
   * @throws DataStoreNotFoundException if the store does not exist
   * @since 3.60
   */
  default CompletableFuture<DataSession<?>> openSessionAsync(String storeName) {
    return CompletableFuture.supplyAsync(() -> openSession(storeName));
  }

  /**
   * Asynchronously opens a new {@link DataSession} against the named data store and sets the transaction isolation to Serializable.
   * <p>
   * This method is designed for high-throughput scenarios where many concurrent database operations
   * are needed. It leverages Java 21 Virtual Threads for efficient non-blocking connection allocation.
   *
   * @param storeName the name of the data store
   * @return a future that completes with the opened session
   * @throws DataStoreNotFoundException if the store does not exist
   * @since 3.60
   */
  default CompletableFuture<DataSession<?>> openSerializableTransactionSessionAsync(String storeName) {
    return CompletableFuture.supplyAsync(() -> openSerializableTransactionSession(storeName));
  }

  /**
   * Asynchronously opens a new JDBC {@link Connection} to the named data store.
   * <p>
   * This method is designed for high-throughput scenarios where many concurrent database operations
   * are needed. It leverages Java 21 Virtual Threads for efficient non-blocking connection allocation.
   *
   * @param storeName the name of the data store
   * @return a future that completes with the opened connection
   * @throws DataStoreNotFoundException if the store does not exist
   * @throws UnsupportedOperationException if the store doesn't support JDBC
   * @since 3.60
   */
  default CompletableFuture<Connection> openConnectionAsync(String storeName) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return openConnection(storeName);
      }
      catch (SQLException e) {
        throw new RuntimeException(e);
      }
    });
  }
  
  /**
   * Opens a new {@link DataSession} against the named data store using a Virtual Thread.
   * <p>
   * This method explicitly uses a Virtual Thread to perform the database operation, which is
   * particularly useful for I/O-bound operations. Virtual Threads provide high concurrency with
   * minimal resource overhead compared to platform threads.
   *
   * @param storeName the name of the data store
   * @return the opened session
   * @throws DataStoreNotFoundException if the store does not exist
   * @since 3.60
   */
  default DataSession<?> openSessionWithVirtualThread(String storeName) {
    return openSession(storeName);
  }

  /**
   * Opens a new {@link DataSession} against the named data store using a Virtual Thread
   * and sets the transaction isolation to Serializable.
   * <p>
   * This method explicitly uses a Virtual Thread to perform the database operation, which is
   * particularly useful for I/O-bound operations. Virtual Threads provide high concurrency with
   * minimal resource overhead compared to platform threads.
   *
   * @param storeName the name of the data store
   * @return the opened session
   * @throws DataStoreNotFoundException if the store does not exist
   * @since 3.60
   */
  default DataSession<?> openSerializableTransactionSessionWithVirtualThread(String storeName) {
    return openSerializableTransactionSession(storeName);
  }

  /**
   * Opens a new JDBC {@link Connection} to the named data store using a Virtual Thread.
   * <p>
   * This method explicitly uses a Virtual Thread to perform the database operation, which is
   * particularly useful for I/O-bound operations. Virtual Threads provide high concurrency with
   * minimal resource overhead compared to platform threads.
   *
   * @param storeName the name of the data store
   * @return the opened connection
   * @throws DataStoreNotFoundException if the store does not exist
   * @throws UnsupportedOperationException if the store doesn't support JDBC
   * @throws SQLException if a database access error occurs
   * @since 3.60
   */
  default Connection openConnectionWithVirtualThread(String storeName) throws SQLException {
    return openConnection(storeName);
  }
}