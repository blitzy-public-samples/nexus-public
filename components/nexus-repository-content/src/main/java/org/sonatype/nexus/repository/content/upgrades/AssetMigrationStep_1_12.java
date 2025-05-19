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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static java.lang.StringTemplate.STR;
import static java.util.Objects.requireNonNull;

/**
 * Creates index for last_updated on each asset table.
 * 
 * This implementation uses Java 21 Virtual Threads for improved scalability
 * and String Templates for more readable SQL generation.
 */
@Named
public class AssetMigrationStep_1_12
    implements DatabaseMigrationStep
{
  private static final String CREATE_INDEX_TEMPLATE = "CREATE INDEX IF NOT EXISTS idx_%s_asset_last_updated ON %s_asset (last_updated)";

  private final List<Format> formats;

  @Inject
  public AssetMigrationStep_1_12(final List<Format> formats)
  {
    this.formats = requireNonNull(formats);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.12");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Create a virtual thread per task executor for optimal I/O performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Submit each index creation as a separate virtual thread task
      for (Format format : formats) {
        futures.add(executor.submit(() -> createIndexForFormat(connection, format)));
      }
      
      // Wait for all tasks to complete and handle any exceptions
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (ExecutionException e) {
          Throwable cause = e.getCause();
          if (cause instanceof Exception) {
            throw (Exception) cause;
          } else {
            throw new RuntimeException("Error during index creation", cause);
          }
        }
      }
    }
  }
  
  /**
   * Creates an index for the specified format using a dedicated virtual thread.
   * Uses Java 21 String Templates for SQL generation.
   *
   * @param connection the database connection
   * @param format the repository format
   */
  private void createIndexForFormat(Connection connection, Format format) {
    String formatValue = format.getValue();
    try (Statement stmt = connection.createStatement()) {
      // Use String Template for more readable SQL generation
      String sql = STR."CREATE INDEX IF NOT EXISTS idx_\{formatValue}_asset_last_updated ON \{formatValue}_asset (last_updated)";
      
      // Execute the index creation statement
      stmt.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(STR."Failed to create index for format \{formatValue}: \{e.getMessage()}", e);
    }
  }
}