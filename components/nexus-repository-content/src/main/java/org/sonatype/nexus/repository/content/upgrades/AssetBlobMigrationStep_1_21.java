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
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static java.lang.StringTemplate.STR;
import static java.util.Objects.requireNonNull;

/**
 * Drops index for blob_created on asset_blob table using Virtual Threads for improved I/O performance.
 */
@Named
public class AssetBlobMigrationStep_1_21
    implements DatabaseMigrationStep
{
  private static final Logger log = Logger.getLogger(AssetBlobMigrationStep_1_21.class.getName());

  private final List<Format> formats;

  @Inject
  public AssetBlobMigrationStep_1_21(final List<Format> formats)
  {
    this.formats = requireNonNull(formats);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.21");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    log.info(STR."Starting migration to drop asset_blob indexes for \{formats.size()} formats");
    
    // Use Virtual Threads for I/O-bound SQL operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create tasks for each format to drop its index
      List<Future<?>> tasks = formats.stream()
          .map(format -> executor.submit(() -> dropIndexForFormat(connection, format)))
          .collect(Collectors.toList());
      
      // Wait for all tasks to complete
      for (Future<?> task : tasks) {
        try {
          task.get();
        }
        catch (Exception e) {
          log.log(Level.SEVERE, STR."Error during index drop operation: \{e.getMessage()}", e);
          throw new SQLException(STR."Failed to drop index: \{e.getMessage()}", e);
        }
      }
    }
    
    log.info("Migration completed successfully");
  }
  
  /**
   * Drops the index for a specific format using a dedicated Statement.
   * 
   * @param connection the database connection
   * @param format the repository format
   */
  private void dropIndexForFormat(final Connection connection, final Format format) {
    String formatValue = format.getValue();
    String sql = STR."DROP INDEX IF EXISTS idx_\{formatValue}_asset_blob_blob_created";
    
    try (Statement statement = connection.createStatement()) {
      log.fine(STR."Executing SQL: \{sql}");
      statement.execute(sql);
      log.info(STR."Successfully dropped index for format: \{formatValue}");
    }
    catch (SQLException e) {
      log.log(Level.SEVERE, STR."Failed to drop index for format \{formatValue}: \{e.getMessage()}", e);
      throw new RuntimeException(STR."Error dropping index for format \{formatValue}", e);
    }
  }
}