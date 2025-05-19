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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.content.browse.RebuildBrowseNodesManager;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Resolves an issue where multiple browse nodes were created with the same parent and the same display name.
 * Uses Java 21 Virtual Threads for improved I/O performance and String Templates for SQL operations.
 *
 * @since 3.33
 */
@Named
public class BrowseNodeMigrationStep_1_1 implements DatabaseMigrationStep
{
  private static final String TRUNCATE = "DELETE FROM {format}_browse_node";

  private static final String TABLE_NAME = "{format}_browse_node";
  
  private static final int TIMEOUT_SECONDS = 60;

  private final RebuildBrowseNodesManager rebuildBrowseNodesManager;

  private List<String> formats;

  @Inject
  public BrowseNodeMigrationStep_1_1(final List<Format> formats, final RebuildBrowseNodesManager rebuildBrowseNodesManager) {
    this.formats = formats.stream().map(Format::getValue).collect(Collectors.toList());
    this.rebuildBrowseNodesManager = checkNotNull(rebuildBrowseNodesManager);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.1");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Create a virtual thread per format for parallel processing
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String format : formats) {
        executor.submit(() -> {
          try {
            migrate(format, connection);
          } 
          catch (SQLException e) {
            throw new RuntimeException(STR."Error migrating browse nodes for format \{format}: \{e.getMessage()}", e);
          }
        });
      }
      
      // Wait for all migrations to complete
      executor.shutdown();
      if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        throw new SQLException(STR."Browse node migration timed out after \{TIMEOUT_SECONDS} seconds");
      }
    }
    
    // Set rebuild flag after all migrations complete
    rebuildBrowseNodesManager.setRebuildOnSart(true);
  }

  private void migrate(final String formatName, final Connection conn) throws SQLException {
    if (!tableExists(conn, formatSql(TABLE_NAME, formatName))) {
      return;
    }

    String sql = formatSql(TRUNCATE, formatName);
    try (PreparedStatement statement = conn.prepareStatement(sql)) {
      int rowsAffected = statement.executeUpdate();
      // Log the operation using String Templates for improved readability
      System.out.println(STR."Truncated \{rowsAffected} rows from \{formatName}_browse_node table");
    }
  }

  /**
   * Formats SQL queries using Java 21 String Templates for improved readability and safety.
   * 
   * @param query the SQL query template
   * @param format the format name to substitute
   * @return the formatted SQL query
   */
  private static String formatSql(final String query, final String format) {
    return STR."\{query.replace("{format}", format)}";
  }
}