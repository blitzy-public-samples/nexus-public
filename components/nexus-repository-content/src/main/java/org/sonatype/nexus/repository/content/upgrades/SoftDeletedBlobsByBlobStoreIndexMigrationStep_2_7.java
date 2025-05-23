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
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

/**
 * Migration step to create an index for idx_soft_deleted_blobs_by_source_blob_store_name_record_id
 * using Java 21 Virtual Threads for improved I/O performance.
 */
@Named
public class SoftDeletedBlobsByBlobStoreIndexMigrationStep_2_7
    extends ComponentSupport
    implements DatabaseMigrationStep
{
  // Using Java 21 String Template for SQL statement
  private static final String INDEX_NAME = "idx_soft_deleted_blobs_by_source_blob_store_name_record_id";
  private static final String TABLE_NAME = "soft_deleted_blobs";
  private static final String COLUMNS = "source_blob_store_name, record_id";
  
  private final String ADD_INDEX_STATEMENT = STR."CREATE INDEX IF NOT EXISTS \{INDEX_NAME} ON \{TABLE_NAME} (\{COLUMNS});";

  @Override
  public Integer getChecksum() {
    return Objects.hash(ADD_INDEX_STATEMENT);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("2.7");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    long startTime = System.currentTimeMillis();
    log.info(STR."Creating index \{INDEX_NAME}");

    // Using Virtual Threads for I/O-bound SQL operation
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the SQL execution task to a virtual thread
      Future<?> future = executor.submit(() -> executeIndexCreation(connection));
      
      // Wait for the task to complete
      try {
        future.get();
        log.info(STR."Index \{INDEX_NAME} created successfully in \{(System.currentTimeMillis() - startTime) * 0.001d} seconds.");
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof SQLException) {
          throw (SQLException) cause;
        } else {
          throw new SQLException(STR."Failed to create index \{INDEX_NAME}: \{cause.getMessage()}", cause);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new SQLException(STR."Index creation for \{INDEX_NAME} was interrupted", e);
      }
    }
  }
  
  /**
   * Executes the SQL statement to create the index.
   * This method is designed to run in a Virtual Thread context.
   *
   * @param connection the database connection
   * @throws SQLException if an SQL error occurs
   */
  private void executeIndexCreation(final Connection connection) throws SQLException {
    // Ensure we're using the same connection to maintain transaction context
    try (Statement statement = connection.createStatement()) {
      statement.execute(ADD_INDEX_STATEMENT);
    } catch (SQLException e) {
      log.error(STR."Error creating index \{INDEX_NAME}: \{e.getMessage()}", e);
      throw e;
    }
  }
}