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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

/**
 * Remove duplicate, unnecessary index
 * Add additional index for asset querying (similar to component)
 * 
 * Updated for Java 21 to use Virtual Threads for improved I/O performance
 * and String Templates for better SQL statement readability.
 */
@Named
public class BrowseNodeMigrationStep_1_34
    extends ComponentSupport implements DatabaseMigrationStep
{
  private final List<Format> formats;

  @Inject
  public BrowseNodeMigrationStep_1_34(final List<Format> formats) {
    this.formats = formats;
  }

  // These constants are no longer needed as we're using inline String Templates

  @Override
  public Optional<String> version() {
    return Optional.of("1.34");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Using Virtual Threads for improved I/O performance with database operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = formats.stream()
          .map(format -> executor.submit(() -> {
              try {
                  migrateFormat(connection, format);
              } catch (Exception e) {
                  log.error(STR."Error migrating format \{format.getValue()}", e);
                  throw new RuntimeException(e);
              }
          }))
          .toList();
      
      // Wait for all migrations to complete and handle any exceptions
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.error(STR."Migration interrupted: \{e.getMessage()}", e);
          throw new RuntimeException("Migration interrupted", e);
        } catch (ExecutionException e) {
          log.error(STR."Migration execution failed: \{e.getCause().getMessage()}", e.getCause());
          throw new RuntimeException("Migration failed", e.getCause());
        }
      }
      
      log.info("Successfully completed browse_node index migration for all formats");
    }
  }

  private void migrateFormat(final Connection connection, final Format format) {
    String formatName = format.getValue();
    try {
      // Execute DROP INDEX statement
      String dropSql = STR."DROP INDEX IF EXISTS idx_\{formatName}_browse_node_tree";
      executeStatement(connection, dropSql);
      
      // Execute CREATE INDEX statement
      String createSql = STR."CREATE INDEX IF NOT EXISTS idx_\{formatName}_browse_node_asset_id ON \{formatName}_browse_node (asset_id);";
      executeStatement(connection, createSql);
      
      log.info(STR."Successfully migrated browse_node indexes for format: \{formatName}");
    } catch (SQLException e) {
      log.error(STR."Failed to migrate browse_node indexes for format: \{formatName}", e);
      throw new RuntimeException(e);
    }
  }
  
  private void executeStatement(final Connection connection, final String sqlStatement) 
      throws SQLException {
    // Using try-with-resources for proper JDBC resource management
    try (PreparedStatement statement = connection.prepareStatement(sqlStatement)) {
      statement.executeUpdate();
      
      // Log the executed statement for better diagnostics
      log.debug(STR."Executed SQL statement: \{sqlStatement}");
    } catch (SQLException e) {
      log.error(STR."Failed to execute SQL statement: '\{sqlStatement}'", e);
      throw e;
    }
  }

  // This method is no longer used as we've refactored to use the single-parameter version
}