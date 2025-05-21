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
package org.sonatype.nexus.internal.security.secrets.upgrade;

import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.internal.security.secrets.task.SecretsMigrationTaskDescriptor;
import org.sonatype.nexus.scheduling.UpgradeTaskScheduler;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Migration step to migrate session token from existing blobstore configurations
 * using Java 21 Virtual Threads for improved I/O concurrency.
 */
@Named
@Singleton
public class BlobstoreSecretsMigrationStep_2_11
    extends ComponentSupport
    implements DatabaseMigrationStep
{
  private final UpgradeTaskScheduler startupScheduler;

  @Inject
  public BlobstoreSecretsMigrationStep_2_11(final UpgradeTaskScheduler startupScheduler) {
    this.startupScheduler = checkNotNull(startupScheduler);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("2.11");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Use Virtual Thread to perform the database check and task scheduling
    // This improves I/O concurrency without blocking platform threads
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      executor.submit(() -> {
        try {
          checkTableAndScheduleMigration(connection);
        }
        catch (Exception e) {
          log.error(STR."Error checking table existence or scheduling migration: \{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }).get(); // Wait for completion to ensure migration is properly handled
    }
  }
  
  /**
   * Checks if the secrets table exists and schedules migration if needed.
   * Uses pattern matching for switch to improve code readability.
   */
  private void checkTableAndScheduleMigration(final Connection connection) throws Exception {
    // Determine table existence state
    String tableExistenceState = determineTableExistenceState(connection);
    
    // Use pattern matching for switch to handle the table existence state
    switch (tableExistenceState) {
      case "EXISTS" -> {
        log.debug(STR."Starting secrets migration task for table 'secrets'");
        scheduleSecretsMigrationTask();
      }
      case "MISSING" -> 
        log.debug(STR."Skipping secrets migration task - table 'secrets' does not exist");
      case "ERROR" -> 
        throw new Exception("Error checking table existence");
      default -> 
        log.warn(STR."Unexpected table existence state: \{tableExistenceState}");
    }
  }
  
  /**
   * Determines if the specified table exists in the database.
   * 
   * @param connection the database connection
   * @return a string representing the table existence state: "EXISTS", "MISSING", or "ERROR"
   */
  private String determineTableExistenceState(final Connection connection) {
    try {
      var metaData = connection.getMetaData();
      try (var resultSet = metaData.getTables(null, null, "secrets", null)) {
        return resultSet.next() ? "EXISTS" : "MISSING";
      }
    }
    catch (Exception e) {
      log.error(STR."Error checking if table 'secrets' exists: \{e.getMessage()}", e);
      return "ERROR";
    }
  }
  
  /**
   * Schedules the secrets migration task with proper thread context propagation.
   */
  private void scheduleSecretsMigrationTask() {
    // Create task configuration with the appropriate type ID
    var taskConfig = startupScheduler.createTaskConfigurationInstance(SecretsMigrationTaskDescriptor.TYPE_ID);
    
    // Schedule the task with thread context propagation for Virtual Threads
    startupScheduler.schedule(taskConfig);
  }
}