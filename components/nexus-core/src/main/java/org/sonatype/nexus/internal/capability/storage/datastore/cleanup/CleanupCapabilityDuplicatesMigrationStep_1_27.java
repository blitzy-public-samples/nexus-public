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
package org.sonatype.nexus.internal.capability.storage.datastore.cleanup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Cleanup duplicate capabilities migration step. - do cleanup capabilities duplicates (if they exist) - create unique
 * index on capability_storage_item table
 * 
 * This implementation is compatible with Java 21 and PostgreSQL JDBC driver 42.7.2, leveraging Virtual Threads
 * for improved performance with database operations.
 */
@Named
@Singleton
public class CleanupCapabilityDuplicatesMigrationStep_1_27
    implements DatabaseMigrationStep
{
  private static final String ADD_INDEX =
      "CREATE UNIQUE INDEX IF NOT EXISTS uk_capability_storage_item_type_props ON capability_storage_item(type, properties)";

  private static final String ADD_CONSTRAINT =
      "ALTER TABLE capability_storage_item ADD CONSTRAINT IF NOT EXISTS uk_capability_storage_item_type_props UNIQUE (type, properties)";

  private final CleanupCapabilityDuplicatesService cleanupService;

  @Inject
  public CleanupCapabilityDuplicatesMigrationStep_1_27(final CleanupCapabilityDuplicatesService cleanupService) {
    this.cleanupService = checkNotNull(cleanupService);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.27");
  }

  /**
   * Migrates the database by cleaning up duplicate capabilities and adding a unique index/constraint.
   * Uses Java 21's improved JDBC connection handling and Virtual Threads for concurrent operations.
   *
   * @param connection The database connection (compatible with PostgreSQL JDBC driver 42.7.2)
   * @throws Exception if any error occurs during migration
   */
  @Override
  public void migrate(final Connection connection) throws Exception {
    // Use Virtual Threads executor for concurrent cleanup operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit cleanup task to virtual thread executor
      executor.submit(() -> {
        try {
          cleanupService.doCleanup();
          return true;
        } catch (Exception e) {
          throw new RuntimeException("Failed to clean up capability duplicates", e);
        }
      });
      
      // Shutdown executor and wait for tasks to complete
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
        throw new SQLException("Cleanup operation timed out after 5 minutes");
      }
    }

    // Add index or constraint using try-with-resources for proper JDBC resource management
    if (isPostgresql(connection)) {
      executeStatement(connection, ADD_INDEX);
    } else {
      executeStatement(connection, ADD_CONSTRAINT);
    }
  }
  
  /**
   * Executes an SQL statement using Java 21's improved try-with-resources for JDBC operations.
   * This ensures proper resource management and compatibility with PostgreSQL JDBC driver 42.7.2.
   *
   * @param connection The database connection
   * @param sql The SQL statement to execute
   * @throws SQLException if a database access error occurs
   */
  private void executeStatement(final Connection connection, final String sql) throws SQLException {
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.execute();
    }
  }
}
