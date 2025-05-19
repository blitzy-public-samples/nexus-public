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
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drop legacy format search views using Java 21 Virtual Threads for improved I/O performance.
 */
@Named
public class FormatSearchViewsMigrationStep_1_20
    implements DatabaseMigrationStep
{
  private static final Logger log = LoggerFactory.getLogger(FormatSearchViewsMigrationStep_1_20.class);
  
  private static final int VIRTUAL_THREAD_TIMEOUT_SECONDS = 60;

  private final List<Format> formats;

  @Inject
  public FormatSearchViewsMigrationStep_1_20(final List<Format> formats) {
    this.formats = formats;
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.20");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Use Java 21 Virtual Threads for improved I/O performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = formats.stream()
          .map(format -> executor.submit(() -> dropFormatSearchView(connection, format)))
          .toList();
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        try {
          future.get(VIRTUAL_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (Exception e) {
          log.error("Error dropping format search view", e);
          throw e;
        }
      }
    }
  }
  
  /**
   * Drops the search view for a specific format using a dedicated virtual thread.
   * 
   * @param connection the database connection
   * @param format the repository format
   */
  private void dropFormatSearchView(final Connection connection, final Format format) {
    // Use Java 21 String Templates for improved readability and performance
    String sql = STR"DROP VIEW IF EXISTS \{format.getValue()}_component_search CASCADE";
    
    log.debug("Executing SQL: {}", sql);
    
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
      log.debug("Successfully dropped search view for format: {}", format.getValue());
    }
    catch (SQLException e) {
      log.error(STR"Failed to drop search view for format: \{format.getValue()}", e);
      throw new RuntimeException(STR"Error dropping search view for format: \{format.getValue()}", e);
    }
  }
}