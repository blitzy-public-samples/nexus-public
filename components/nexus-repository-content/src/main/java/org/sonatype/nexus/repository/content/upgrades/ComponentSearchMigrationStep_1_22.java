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
import java.sql.Statement;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import javax.inject.Named;

import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes the component_search table using Virtual Threads for improved performance.
 * 
 * Note: This class uses Java 21 features including Virtual Threads and String Templates.
 * Compilation requires JDK 21 with preview features enabled (--enable-preview flag).
 */
@Named
public class ComponentSearchMigrationStep_1_22
    implements DatabaseMigrationStep
{
  private static final Logger log = LoggerFactory.getLogger(ComponentSearchMigrationStep_1_22.class);
  
  // Using Java 21 String Template for SQL statement (requires --enable-preview)
  private static final String DROP_COMPONENT_SEARCH = STR."DROP TABLE IF EXISTS component_search";

  @Override
  public Optional<String> version() {
    return Optional.of("1.22");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    log.info("Starting migration to drop component_search table");
    
    // Create a virtual thread executor for improved I/O performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit the SQL operation to a virtual thread while maintaining transaction context
      Future<?> task = executor.submit(() -> executeDropTable(connection));
      
      try {
        // Wait for the task to complete
        task.get();
        log.info("Successfully dropped component_search table");
      } 
      catch (ExecutionException e) {
        log.error("Failed to drop component_search table", e.getCause());
        throw new Exception("Failed to drop component_search table", e.getCause());
      }
      catch (InterruptedException e) {
        log.error("Migration was interrupted while dropping component_search table", e);
        Thread.currentThread().interrupt(); // Preserve interrupt status
        throw new Exception("Migration was interrupted", e);
      }
    }
  }
  
  /**
   * Executes the DROP TABLE statement within the virtual thread context.
   * 
   * @param connection The database connection with active transaction context
   */
  private void executeDropTable(final Connection connection) {
    try (Statement st = connection.createStatement()) {
      log.debug("Executing SQL: {}", DROP_COMPONENT_SEARCH);
      st.execute(DROP_COMPONENT_SEARCH);
    }
    catch (Exception e) {
      log.error("Error executing SQL statement: {}", DROP_COMPONENT_SEARCH, e);
      throw new RuntimeException("Error executing SQL statement", e);
    }
  }
}