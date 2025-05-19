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
package org.sonatype.nexus.repository.content.upgrades;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static java.util.Objects.requireNonNull;

/**
 * Creates index for blob_created on each asset_blob table. Drops index for last_updated on asset table.
 * 
 * This implementation uses Java 21 Virtual Threads for improved throughput and String Templates for better readability.
 */
@Named
public class AssetBlobMigrationStep_1_13
    implements DatabaseMigrationStep
{
  // No static SQL templates needed as we use inline String Templates in the methods

  private final List<Format> formats;
  
  // Thread-local context for transaction propagation
  private static final ThreadLocal<Connection> TRANSACTION_CONTEXT = new ThreadLocal<>();

  @Inject
  public AssetBlobMigrationStep_1_13(final List<Format> formats)
  {
    this.formats = requireNonNull(formats);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.13");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Store connection in ThreadLocal for transaction context propagation
    TRANSACTION_CONTEXT.set(connection);
    
    try {
      // Create a virtual thread per task executor for SQL operations
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit each format's migration as a separate virtual thread task and collect futures
        List<Future<?>> futures = new ArrayList<>();
        for (Format format : formats) {
          String formatValue = format.getValue();
          futures.add(executor.submit(() -> executeFormatMigration(formatValue)));
        }
        
        // Wait for all tasks to complete and check for exceptions
        for (Future<?> future : futures) {
          try {
            future.get(); // This will throw an exception if the task failed
          } catch (Exception e) {
            // Unwrap the exception to get the original cause
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
              throw (Exception) cause;
            } else {
              throw new RuntimeException("Error during migration", cause);
            }
          }
        }
      } // ExecutorService is auto-closed, which waits for all tasks to complete
    } finally {
      // Clean up ThreadLocal to prevent memory leaks
      TRANSACTION_CONTEXT.remove();
    }
  }
  
  /**
   * Executes the migration for a specific format using the transaction context.
   * 
   * @param formatValue the format value to use in SQL statements
   */
  private void executeFormatMigration(String formatValue) {
    // Retrieve connection from ThreadLocal to maintain transaction context
    Connection connection = TRANSACTION_CONTEXT.get();
    if (connection == null) {
      throw new IllegalStateException("Transaction context not available");
    }
    
    try {
      // Create index for blob_created
      String createIndexSql = STR."CREATE INDEX IF NOT EXISTS idx_\{formatValue}_asset_blob_blob_created ON \{formatValue}_asset_blob (blob_created)";
      executeStatement(connection, createIndexSql);
      
      // Drop index for last_updated
      String dropIndexSql = STR."DROP INDEX IF EXISTS idx_\{formatValue}_asset_last_updated";
      executeStatement(connection, dropIndexSql);
    } catch (SQLException e) {
      // Properly propagate exceptions from virtual threads
      throw new RuntimeException(STR."Error executing migration for format \{formatValue}", e);
    }
  }
  
  /**
   * Executes a SQL statement with proper resource management.
   * 
   * @param connection the database connection
   * @param sql the SQL statement to execute
   * @throws SQLException if a database error occurs
   */
  private void executeStatement(Connection connection, String sql) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }
}