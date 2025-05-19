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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.RepositoryTaskSupport;
import org.sonatype.nexus.repository.browse.node.RebuildBrowseNodesTaskDescriptor;
import org.sonatype.nexus.scheduling.UpgradeTaskScheduler;
import org.sonatype.nexus.scheduling.TaskConfiguration;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Identifies repositories whose browse nodes were co-mingled with groups they were a member of and schedules them for
 * rebuilding.
 *
 * Also schedules all pypi groups for rebuilding due to a relocation of metadata.
 */
@Named
public class BrowseNodeMigrationStep_1_36
    extends ComponentSupport implements DatabaseMigrationStep
{
  private static final String ASSET = "asset";

  private static final String COMPONENT = "component";

  private static final String SELECT = STR."SELECT DISTINCT B.repository_id, A.repository_id \nFROM \{formatName}_browse_node B, \{formatName}_\{type} A \nWHERE B.repository_id <> A.repository_id AND A.\{type}_id = B.\{type}_id";

  private static final String SELECT_REPOSITORY_NAME = STR."SELECT R.name \nFROM repository R, \{formatName}_content_repository C \nWHERE R.id = C.config_repository_id AND C.repository_id = ?";

  private static final String SELECT_PYPI_GROUP = "SELECT name FROM repository WHERE recipe_name = 'pypi-group'";

  private final List<Format> formats;

  private final TaskScheduler taskScheduler;

  private final UpgradeTaskScheduler upgradeTaskScheduler;

  @Inject
  public BrowseNodeMigrationStep_1_36(
      final List<Format> formats,
      final TaskScheduler ts,
      final UpgradeTaskScheduler uts)
  {
    this.formats = checkNotNull(formats);
    this.taskScheduler = checkNotNull(ts);
    this.upgradeTaskScheduler = checkNotNull(uts);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.36");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    // Create a virtual thread executor for I/O-bound database operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      String names = formats.stream()
          .flatMap(format -> migrateFormat(connection, format, executor))
          .collect(Collectors.joining(","));

      if (!names.isEmpty()) {
        scheduleRebuildBrowseNodesTask(names);
      }
      else {
        log.debug("Found no repositories requiring rebuild");
      }
    }
  }

  private Stream<String> migrateFormat(final Connection connection, final Format format, final ExecutorService executor) {
    String formatName = format.getValue();
    // We use linked here as we want to rebuild group repositories first
    Set<Integer> repositoryIds = new LinkedHashSet<>();
    
    // Use CompletableFuture to execute database operations asynchronously with virtual threads
    CompletableFuture<Void> assetFuture = CompletableFuture.runAsync(
        () -> executeStatement(repositoryIds, connection, formatName, ASSET), executor);
    
    CompletableFuture<Void> componentFuture = CompletableFuture.runAsync(
        () -> executeStatement(repositoryIds, connection, formatName, COMPONENT), executor);
    
    // Wait for both operations to complete
    CompletableFuture.allOf(assetFuture, componentFuture).join();
    
    return repositoryIdToName(connection, formatName, repositoryIds, executor);
  }

  private void executeStatement(
      final Set<Integer> repositoryIds,
      final Connection connection,
      final String formatName,
      final String type)
  {
    try (PreparedStatement select = connection.prepareStatement(SELECT.formatted(formatName, formatName, type, type, type));
        ResultSet results = select.executeQuery()) {

      while (results.next()) {
        repositoryIds.add(results.getInt(1));
        repositoryIds.add(results.getInt(2));
      }
    }
    catch (SQLException e) {
      log.error(STR."Failed to identify browse node tables with repository mismatches for \{formatName}_\{type}", e);
    }
  }

  /*
   * Find repository names associated with the repository Ids.
   */
  private Stream<String> repositoryIdToName(
      final Connection connection,
      final String formatName,
      final Set<Integer> repositoryIds,
      final ExecutorService executor)
  {
    // Maintain order, we use a set still due to pypi below
    Set<String> repositoryNames = new LinkedHashSet<>();

    // Process repository IDs in parallel using virtual threads
    List<CompletableFuture<Void>> futures = repositoryIds.stream()
        .map(repositoryId -> CompletableFuture.runAsync(() -> {
          try (PreparedStatement select = connection.prepareStatement(SELECT_REPOSITORY_NAME.formatted(formatName))) {
            select.setInt(1, repositoryId);

            try (ResultSet results = select.executeQuery()) {
              if (!results.next()) {
                log.warn(STR."Unable to locate repository associated with \{formatName} id \{repositoryId}");
                return;
              }
              synchronized (repositoryNames) {
                repositoryNames.add(results.getString(1));
              }
            }
          }
          catch (SQLException e) {
            log.error(STR."Failed to locate repositories for \{formatName}", e);
          }
        }, executor))
        .collect(Collectors.toList());

    // Wait for all repository ID lookups to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

    // PyPi had an additional issue with group metadata created in an unexpected location
    if ("pypi".equals(formatName)) {
      try (PreparedStatement select = connection.prepareStatement(SELECT_PYPI_GROUP);
          ResultSet results = select.executeQuery()) {

        while (results.next()) {
          repositoryNames.add(results.getString(1));
        }
      }
      catch (SQLException e) {
        log.error("Failed to identify pypi-group repositories", e);
      }
    }

    return repositoryNames.stream();
  }

  /*
   * Ask the postStartupTaskscheduler to schedule a build after startup
   */
  private void scheduleRebuildBrowseNodesTask(final String repositories) {
    TaskConfiguration configuration =
        taskScheduler.createTaskConfigurationInstance(RebuildBrowseNodesTaskDescriptor.TYPE_ID);
    configuration.setString(RepositoryTaskSupport.REPOSITORY_NAME_FIELD_ID, repositories);
    upgradeTaskScheduler.schedule(configuration);
    log.info(STR."Scheduled post-startup task to rebuild browse nodes for repositories: \{repositories}");
  }
}