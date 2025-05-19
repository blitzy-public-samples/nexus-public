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
import java.util.ArrayList;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

/**
 * Database migration step to create parent_id indexes on browse_node tables for all formats.
 * Uses Java 21 Virtual Threads for concurrent execution and String Templates for SQL generation.
 */
@Named
public class BrowseNodeMigrationStep_1_38
    extends ComponentSupport
    implements DatabaseMigrationStep
{
  private final List<Format> formats;

  @Inject
  public BrowseNodeMigrationStep_1_38(final List<Format> formats) {
    this.formats = formats;
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.38");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Use Virtual Threads executor for concurrent SQL operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit each format's index creation as a separate virtual thread task
      for (Format format : formats) {
        String formatValue = format.getValue();
        futures.add(executor.submit(() -> {
          // Using Java 21 String Template for SQL statement
          String sqlStatement = STR."CREATE INDEX IF NOT EXISTS idx_\{formatValue}_browse_node_parent_id ON \{formatValue}_browse_node (parent_id);";
          executeStatement(connection, sqlStatement, formatValue);
          return null;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        } 
        catch (ExecutionException e) {
          // Unwrap and rethrow the actual exception
          Throwable cause = e.getCause();
          if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
          }
          else if (cause instanceof Error) {
            throw (Error) cause;
          }
          else {
            throw new RuntimeException("Error during index creation", cause);
          }
        }
      }
    }
  }

  /**
   * Executes a SQL statement with proper error handling.
   * 
   * @param connection The database connection
   * @param sqlStatement The SQL statement to execute
   * @param formatValue The repository format value for context in error messages
   */
  private void executeStatement(final Connection connection, final String sqlStatement, final String formatValue) {
    try (PreparedStatement statement = connection.prepareStatement(sqlStatement)) {
      statement.executeUpdate();
      log.debug(STR."Successfully created index for format '\{formatValue}'.");
    }
    catch (SQLException e) {
      log.error(STR."Failed to apply browse_node index change for format '\{formatValue}'. SQL: '\{sqlStatement}'", e);
      throw new RuntimeException(STR."Failed to create index for format '\{formatValue}'", e);
    }
  }
}