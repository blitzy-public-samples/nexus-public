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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.browse.node.RebuildBrowseNodesTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.UpgradeTaskScheduler;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Migration step to rebuild browse nodes for Conan repositories.
 * Uses Java 21 Virtual Threads for improved I/O performance during database operations.
 */
@Named
public class BrowseNodeMigrationStep_2_3
    extends ComponentSupport
    implements DatabaseMigrationStep
{
  private final TaskScheduler taskScheduler;

  private final UpgradeTaskScheduler upgradeTaskScheduler;

  private static final String CONTENT_REPOSITORY_TABLE = "conan_content_repository";

  private static final String SELECT_REPOSITORY_NAMES = STR."""
      SELECT R.name 
      FROM repository R, 
      """+CONTENT_REPOSITORY_TABLE+""" C 
      WHERE R.id = C.config_repository_id
      """;

  @Inject
  public BrowseNodeMigrationStep_2_3(
      final TaskScheduler taskScheduler,
      final UpgradeTaskScheduler upgradeTaskScheduler)
  {
    this.taskScheduler = checkNotNull(taskScheduler);
    this.upgradeTaskScheduler = checkNotNull(upgradeTaskScheduler);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("2.3");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    if (!tableExists(connection, CONTENT_REPOSITORY_TABLE)) {
      log.debug("No Conan table present to rebuild browse nodes");
      return;
    }

    // Use a Virtual Thread to perform the database operation
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<String> repositoryNamesFuture = executor.submit(() -> getRepositoryNames(connection));
      
      String repositoryNames = repositoryNamesFuture.get();

      if (!repositoryNames.isEmpty()) {
        scheduleRebuildBrowseNodesTask(repositoryNames);
      }
      else {
        log.debug("No Conan repositories found to rebuild browse nodes");
      }
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof SQLException) {
        log.error("SQL error during repository name retrieval", cause);
        throw (SQLException) cause;
      } else if (cause instanceof RuntimeException) {
        log.error("Runtime error during repository name retrieval", cause);
        throw (RuntimeException) cause;
      } else {
        log.error("Unexpected error during repository name retrieval", e);
        throw new RuntimeException("Failed to get repository names", e);
      }
    }
  }

  /**
   * Retrieves repository names from the database using a prepared statement.
   * Optimized for Virtual Thread execution with proper resource management.
   */
  protected String getRepositoryNames(final Connection connection)
      throws SQLException
  {
    StringJoiner repositoryNames = new StringJoiner(",");
    
    try (PreparedStatement statement = connection.prepareStatement(SELECT_REPOSITORY_NAMES)) {
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          repositoryNames.add(resultSet.getString(1));
        }
      } catch (SQLException e) {
        log.error(STR."Error executing query: \{SELECT_REPOSITORY_NAMES}", e);
        throw e;
      }
    } catch (SQLException e) {
      log.error(STR."Error preparing statement: \{SELECT_REPOSITORY_NAMES}", e);
      throw new SQLException("Failed to get repository names", e);
    }
    
    return repositoryNames.toString();
  }

  /**
   * Schedules a task to rebuild browse nodes for the specified repositories.
   * Configured to work correctly with Virtual Threads.
   */
  private void scheduleRebuildBrowseNodesTask(final String repositories) {
    TaskConfiguration configuration =
        taskScheduler.createTaskConfigurationInstance(RebuildBrowseNodesTaskDescriptor.TYPE_ID);
    configuration.setName(STR."Rebuild browse nodes for Conan repositories: \{repositories}");
    configuration.setString(RepositoryTaskSupport.REPOSITORY_NAME_FIELD_ID, repositories);
    
    // Schedule the task with the upgrade task scheduler
    upgradeTaskScheduler.schedule(configuration);
    
    log.info(STR."Scheduled post-startup task to rebuild browse nodes for Conan repositories: \{repositories}");
  }
}