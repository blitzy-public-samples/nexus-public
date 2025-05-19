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
package org.sonatype.nexus.repository.content.internal;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.kv.ValueType;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.content.tasks.normalize.NormalizeComponentVersionTask;
import org.sonatype.nexus.repository.content.tasks.normalize.NormalizeComponentVersionTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.UpgradeTaskScheduler;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationUtility;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toSet;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Check if the {format}_component table has the normalized_version column. If not schedule the
 * NormalizeComponentVersionTask to run after startup is complete.
 * 
 * Updated for Java 21 to leverage Virtual Threads for improved performance in database operations
 * and component normalization.
 */
@Named
@Singleton
public class ComponentNormalizationIntegrityChecker
    extends ComponentSupport
    implements DatabaseIntegrityChecker
{
  // Using String Templates (JEP 430) for SQL statements and table/column names
  private static final String TABLE_NAME = STR."%s_component";
  private static final String COLUMN_NAME = "normalized_version";
  private static final String INDEX_NAME = STR."idx_%s_normalized_version";
  
  private static final String ADD_COLUMN_STATEMENT =
      STR."ALTER TABLE %TABLE_NAME% ADD COLUMN IF NOT EXISTS %COLUMN_NAME% VARCHAR;";

  private static final String ADD_INDEX_STATEMENT =
      STR."CREATE INDEX IF NOT EXISTS %s ON %s (%COLUMN_NAME%)";

  private final List<Format> formats;

  private final TaskScheduler taskScheduler;

  private final UpgradeTaskScheduler startupScheduler;

  private final Map<String, FormatStoreManager> managersByFormat;

  private final GlobalKeyValueStore globalKeyValueStore;

  private final DatabaseMigrationUtility databaseMigrationUtility;

  @Inject
  public ComponentNormalizationIntegrityChecker(
      final List<Format> formats,
      final TaskScheduler taskScheduler,
      final UpgradeTaskScheduler startupScheduler,
      final Map<String, FormatStoreManager> managersByFormat,
      final GlobalKeyValueStore globalKeyValueStore,
      final DatabaseMigrationUtility databaseMigrationUtility)
  {
    this.formats = checkNotNull(formats);
    this.taskScheduler = checkNotNull(taskScheduler);
    this.startupScheduler = checkNotNull(startupScheduler);
    this.managersByFormat = checkNotNull(managersByFormat);
    this.globalKeyValueStore = checkNotNull(globalKeyValueStore);
    this.databaseMigrationUtility = checkNotNull(databaseMigrationUtility);
  }

  @Override
  public void checkAndRepair(Connection connection) throws SQLException {
    log.info("Validating normalized_version columns using Virtual Threads");
    
    // Use Virtual Threads for database operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Execute database alterations using Virtual Threads
      CompletableFuture.runAsync(() -> {
        try {
          alterFormats(connection);
        } 
        catch (SQLException e) {
          log.error(STR."Error altering formats: %{e.getMessage()}", e);
          throw new RuntimeException(e);
        }
      }, executor).join();
      
      // Use Virtual Threads to parallelize the streaming of unnormalized components
      Set<Format> formatsNeedingNormalization = formats.stream()
          .map(format -> CompletableFuture.supplyAsync(() -> {
            boolean needsNormalization = !managersByFormat.get(format.getValue())
                .componentStore(DEFAULT_DATASTORE_NAME)
                .browseUnnormalized(1, null)
                .isEmpty();
            return needsNormalization ? format : null;
          }, executor))
          .map(CompletableFuture::join)
          .filter(format -> format != null)
          .collect(toSet());
      
      boolean needsNormalization = !formatsNeedingNormalization.isEmpty();

      if (needsNormalization) {
        log.info(STR."Formats detected needing normalization: %{formatsNeedingNormalization}");
        
        // Use Virtual Threads to mark formats in parallel
        CompletableFuture.allOf(
            formatsNeedingNormalization.stream()
                .map(format -> CompletableFuture.runAsync(
                    () -> markFormatAsNeedingNormalization(format), executor))
                .toArray(CompletableFuture[]::new)
        ).join();
        
        // Schedule the normalization task using Virtual Threads
        CompletableFuture.runAsync(this::scheduleTask, executor).join();
      }
    }
  }

  private void markFormatAsNeedingNormalization(final Format format) {
    log.debug(STR."Marking format %{format.getValue()} as needing normalization");
    NexusKeyValue kv = new NexusKeyValue();
    kv.setKey(getFormatKey(format));
    kv.setType(ValueType.BOOLEAN);
    kv.setValue(false);

    globalKeyValueStore.setKey(kv);
  }

  private void scheduleTask() {
    log.debug("Scheduling NormalizeComponentVersionTask to run after startup");
    // The task itself will use Virtual Threads for its execution
    startupScheduler.schedule(taskScheduler.createTaskConfigurationInstance(
        NormalizeComponentVersionTaskDescriptor.TYPE_ID));
  }

  private void alterFormats(final Connection connection) throws SQLException {
    try (Statement alterStmt = connection.createStatement()) {
      for (Format format : formats) {
        alter(connection, alterStmt, format);
      }
    }
  }

  private void alter(final Connection connection, final Statement alterStatement, final Format format)
      throws SQLException
  {
    String formatName = format.getValue();
    String tableName = STR."%{formatName}_component".toUpperCase();

    if (!databaseMigrationUtility.tableExists(connection, tableName)) {
      log.debug(STR."Table %{tableName} not found for format %{formatName}");
      throw new SQLException(STR."Unable to repair %{tableName} because it wasn't yet created");
    }

    if (!databaseMigrationUtility.columnExists(connection, tableName, COLUMN_NAME)) {
      log.info(STR."Adding missing column '%{COLUMN_NAME}' to %{formatName} format");
      alterStatement.execute(STR."ALTER TABLE %{formatName}_component ADD COLUMN IF NOT EXISTS %{COLUMN_NAME} VARCHAR;");

      String indexName = STR."idx_%{formatName}_normalized_version";
      if (!databaseMigrationUtility.indexExists(connection, indexName)) {
        log.info(STR."Adding missing index '%{indexName}' to %{formatName} format");
        alterStatement.execute(STR."CREATE INDEX IF NOT EXISTS %{indexName} ON %{tableName} (%{COLUMN_NAME});");
      }
    }
  }

  private String getFormatKey(final Format format) {
    return STR."%{NormalizeComponentVersionTask.KEY_FORMAT}%{format.getValue()}";
  }
}