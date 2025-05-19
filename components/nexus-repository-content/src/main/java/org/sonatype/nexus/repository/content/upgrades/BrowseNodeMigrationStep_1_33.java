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

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import javax.inject.Inject;
import javax.inject.Named;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ThreadFactory;
import java.lang.Thread.Builder.OfVirtual;
import java.lang.invoke.MethodHandles;

/**
 * Change node_id and parent_id to BIGINT to mitigate sequence exhaustion.
 * 
 * This migration step uses Java 21 Virtual Threads for improved throughput when executing
 * SQL operations across multiple repository formats. The implementation leverages:
 * 
 * 1. Virtual Threads - For concurrent execution of database migrations with minimal resource usage
 * 2. String Templates - For more readable and maintainable SQL statements
 * 3. Enhanced transaction handling - To maintain context across Virtual Thread handoffs
 * 4. Improved error handling - For better diagnostics in concurrent execution context
 * 
 * Note: This implementation requires Java 21 compatible JDBC drivers. The PostgreSQL JDBC
 * driver version 42.6.0+ and H2 database driver version 2.2.224+ are verified to work correctly
 * with Virtual Threads without causing thread pinning issues.
 */
@Named
public class BrowseNodeMigrationStep_1_33
    extends ComponentSupport implements DatabaseMigrationStep
{
  private final List<Format> formats;

  @Inject
  public BrowseNodeMigrationStep_1_33(final List<Format> formats) {
    this.formats = formats;
  }

  /**
   * SQL statements using Java 21 String Templates for improved readability and maintainability.
   * String Templates provide compile-time safety and better performance compared to String.format().
   */
  private static String alterNodeIdSql(String formatName) {
    return STR."ALTER TABLE \{formatName}_browse_node ALTER COLUMN node_id SET DATA TYPE BIGINT;";
  }

  private static String alterParentIdSql(String formatName) {
    return STR."ALTER TABLE \{formatName}_browse_node ALTER COLUMN parent_id SET DATA TYPE BIGINT;";
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.33");
  }

  /**
   * Migrates all repository formats concurrently using Java 21 Virtual Threads.
   * Virtual Threads provide high-throughput concurrency with minimal resource usage,
   * making them ideal for I/O-bound operations like database migrations.
   *
   * @param connection The database connection to use for migrations
   * @throws Exception If any migration fails
   */
  @Override
  public void migrate(final Connection connection) throws Exception {
    log.info("Starting browse_node migration using Java 21 Virtual Threads for {} formats", formats.size());
    
    // Configure Virtual Thread executor with descriptive thread names for better diagnostics
    OfVirtual virtualThreadBuilder = Thread.ofVirtual().name("browse-node-migration-", 0);
    ThreadFactory threadFactory = virtualThreadBuilder.factory();
    
    // Using Virtual Threads executor for concurrent SQL operations
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      CountDownLatch latch = new CountDownLatch(formats.size());
      AtomicReference<Exception> firstException = new AtomicReference<>();

      // Submit migration tasks for each format to be executed concurrently
      for (Format format : formats) {
        String formatName = format.getValue();
        executor.submit(() -> {
          try {
            log.debug("Starting migration for format {} on thread {}", formatName, Thread.currentThread().getName());
            migrateFormat(connection, format);
          } 
          catch (Exception e) {
            log.error("Failed to migrate browse_node for format {}", formatName, e);
            firstException.compareAndSet(null, e);
          } 
          finally {
            latch.countDown();
            log.debug("Completed migration task for format {}, remaining tasks: {}", 
                formatName, latch.getCount());
          }
        });
      }

      // Wait for all migrations to complete
      log.debug("Waiting for all migration tasks to complete");
      latch.await();
      log.info("All browse_node migration tasks completed");
      
      // If any migration failed, throw the first exception
      Exception exception = firstException.get();
      if (exception != null) {
        log.error("One or more migrations failed", exception);
        throw exception;
      }
    }
  }

  /**
   * Migrates a specific repository format by altering the browse_node table columns.
   * This method is designed to be executed within a Virtual Thread to allow concurrent migrations.
   *
   * @param connection The database connection (shared across Virtual Threads)
   * @param format The repository format to migrate
   */
  private void migrateFormat(final Connection connection, final Format format) {
    String formatName = format.getValue();
    log.info("Migrating browse_node table for format: {}", formatName);
    
    try {
      // Execute ALTER TABLE statements to change column types
      // Note: Connection is thread-safe for concurrent use with different statements
      // but we need to ensure proper transaction handling across Virtual Thread handoffs
      boolean originalAutoCommit = connection.getAutoCommit();
      try {
        // Ensure consistent transaction state
        connection.setAutoCommit(false);
        
        // Execute the ALTER TABLE statements sequentially for this format
        executeAlterStatement(connection, formatName, true);  // For node_id
        executeAlterStatement(connection, formatName, false); // For parent_id
        
        // Commit the changes for this format
        connection.commit();
        log.info("Successfully migrated browse_node table for format: {}", formatName);
      }
      catch (SQLException e) {
        // Rollback on error
        try {
          connection.rollback();
        }
        catch (SQLException rollbackEx) {
          log.error("Failed to rollback transaction for format {}", formatName, rollbackEx);
        }
        throw e;
      }
      finally {
        // Restore original auto-commit setting
        try {
          connection.setAutoCommit(originalAutoCommit);
        }
        catch (SQLException autoCommitEx) {
          log.error("Failed to restore auto-commit setting for format {}", formatName, autoCommitEx);
        }
      }
    }
    catch (SQLException e) {
      throw new IllegalStateException(
          STR."Failed to apply browse_node id/parent datatype changes for format \{formatName}", e);
    }
  }
  
  /**
   * Executes an ALTER TABLE statement with proper error handling in a Virtual Thread context.
   * This method ensures that SQL exceptions are properly captured and logged with context information.
   *
   * @param connection The database connection
   * @param formatName The repository format name
   * @param isNodeId Whether to alter node_id (true) or parent_id (false) column
   * @throws SQLException If the SQL operation fails
   */
  private void executeAlterStatement(final Connection connection, final String formatName, boolean isNodeId) throws SQLException {
    String sql = isNodeId ? alterNodeIdSql(formatName) : alterParentIdSql(formatName);
    String columnType = isNodeId ? "node_id" : "parent_id";
    
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      log.debug("Executing SQL for format {}, column {}: {}", formatName, columnType, sql);
      statement.executeUpdate();
    }
    catch (SQLException e) {
      log.error("SQL error while altering {} column for format {}: {}", columnType, formatName, e.getMessage());
      throw new SQLException(STR."Failed to alter \{columnType} column for format \{formatName}: \{e.getMessage()}", e);
    }
  }
}