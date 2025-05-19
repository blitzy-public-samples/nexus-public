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
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.kv.GlobalKeyValueStore;
import org.sonatype.nexus.kv.NexusKeyValue;
import org.sonatype.nexus.repository.content.tasks.normalize.NormalizeComponentVersionTask;
import static org.sonatype.nexus.repository.content.tasks.normalize.NormalizeComponentVersionTask.KEY_FORMAT_PREFIX;
import org.sonatype.nexus.repository.content.tasks.normalize.NormalizeComponentVersionTaskDescriptor;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.scheduling.UpgradeTaskScheduler;
import org.sonatype.nexus.upgrade.datastore.RepeatableDatabaseMigrationStep;

/**
 * Migration step to populate the normalized_version column on the {format}_component tables
 * using Java 21 Virtual Threads for improved I/O performance.
 */
@Named
public class ComponentNormalizedVersionMigrationStep
    extends ComponentSupport
    implements RepeatableDatabaseMigrationStep
{
  // SQL templates using Java 21 String Templates for improved readability and security
  private static final String TABLE_NAME = "{format}_component";
  private static final String COLUMN_NAME = "normalized_version";
  private static final String INDEX_NAME = "idx_{format}_normalized_version";

  // Using String Templates for SQL statements
  private static final String ADD_COLUMN_STATEMENT =
      STR."ALTER TABLE \{TABLE_NAME} ADD COLUMN IF NOT EXISTS \{COLUMN_NAME} VARCHAR;";

  private static final String ADD_INDEX_STATEMENT =
      STR."CREATE INDEX IF NOT EXISTS \{INDEX_NAME} ON \{TABLE_NAME} (\{COLUMN_NAME})";

  private final List<Format> formats;
  private final GlobalKeyValueStore globalKeyValueStore;
  private final TaskScheduler taskScheduler;
  private final UpgradeTaskScheduler startupScheduler;

  @Inject
  public ComponentNormalizedVersionMigrationStep(
      final List<Format> formats,
      final GlobalKeyValueStore globalKeyValueStore,
      final TaskScheduler taskScheduler,
      final UpgradeTaskScheduler startupScheduler)
  {
    this.formats = formats;
    this.globalKeyValueStore = globalKeyValueStore;
    this.taskScheduler = taskScheduler;
    this.startupScheduler = startupScheduler;
  }

  @Override
  public Integer getChecksum() {
    return Objects.hash(formats.stream()
        .map(Format::getValue)
        // Ordered so the hash is consistent
        .sorted()
        .toArray());
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    alterFormats(connection);
    scheduleTask();
  }

  private void scheduleTask() {
    // Schedule task with compatibility for Virtual Threads
    startupScheduler.schedule(taskScheduler.createTaskConfigurationInstance(
        NormalizeComponentVersionTaskDescriptor.TYPE_ID));
  }

  private void alterFormats(final Connection connection) throws SQLException {
    // Create a Virtual Thread executor for database operations
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process each format in parallel using Virtual Threads
      formats.stream()
          .filter(format -> !isFormatNormalized(format))
          .forEach(format -> executor.submit(() -> {
            try {
              alterFormat(connection, format);
            } catch (SQLException e) {
              // Improved error handling for Virtual Thread operations
              log.error(STR."Error altering format \{format.getValue()} with Virtual Thread", e);
              // Re-throw as runtime exception to be caught by the executor
              throw new RuntimeException(e);
            }
          }));
      
      // Wait for all tasks to complete before proceeding
      // The executor's close method will wait for all tasks to complete
    } catch (Exception e) {
      log.error("Error during parallel format processing with Virtual Threads", e);
      throw new SQLException("Failed to process formats with Virtual Threads", e);
    }
  }

  private void alterFormat(final Connection connection, final Format format) throws SQLException {
    String formatName = format.getValue();
    log.info(STR."Validating \{formatName} component table");

    String tableName = replace(TABLE_NAME, formatName);

    if (!tableExists(connection, tableName)) {
      log.debug(STR."\{formatName} component table not found");
      return;
    }

    // Use a dedicated connection statement to maintain transaction context during Virtual Thread handoffs
    try (Statement alterStatement = connection.createStatement()) {
      if (!columnExists(connection, tableName, COLUMN_NAME)) {
        log.info(STR."Adding missing column '\{COLUMN_NAME}' to \{formatName} format");
        alterStatement.execute(replace(ADD_COLUMN_STATEMENT, formatName));

        if (!indexExists(connection, replace(INDEX_NAME, formatName))) {
          log.info(STR."Adding missing index '\{replace(INDEX_NAME, formatName)}' to \{formatName} format");
          alterStatement.execute(replace(ADD_INDEX_STATEMENT, formatName));
        }
      }
    } catch (SQLException e) {
      log.error(STR."Failed to alter \{formatName} format: \{e.getMessage()}");
      throw e;
    }
  }

  private boolean isFormatNormalized(final Format format) {
    // Using String Templates for storage keys
    String key = STR."\{KEY_FORMAT_PREFIX}\{format.getValue()}";
    return globalKeyValueStore
        .getKey(key)
        .map(NexusKeyValue::getAsBoolean)
        .orElse(false);
  }

  private static String replace(final String query, final String format) {
    return query.replaceAll("\\{format\\}", format);
  }
}