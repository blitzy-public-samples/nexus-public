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

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptyList;
import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;
import static org.apache.commons.lang.exception.ExceptionUtils.getFullStackTrace;
import static org.sonatype.nexus.blobstore.file.FileBlobStore.BASEDIR;
import static org.sonatype.nexus.blobstore.file.FileBlobStore.CONFIG_KEY;
import static org.sonatype.nexus.blobstore.file.FileBlobStore.PATH_KEY;

/**
 * Moves each reconciliation log from ${karaf.data}/log/blobstore/${blobstore}/%date.log to &lt;blobstore
 * root&gt;/reconciliation/%date.log
 *
 * @since 3.41
 */
@Named
@Singleton
public class DatastoreBlobReconciliationLogMigrator_1_11
    extends ComponentSupport
    implements DatabaseMigrationStep
{
  public static final String RECONCILIATION_DIRECTORY_NAME = "reconciliation";

  protected static final String BLOBSTORE = "blobstore";

  protected static final String BLOBSTORE_LOG_PATH = "log" + File.separator + BLOBSTORE;

  protected static final String ATTRIBUTES = "attributes";

  protected static final String TABLE_NAME = "blob_store_configuration";

  // Using String Template for SQL query
  private static final String QUERY = STR."SELECT * FROM \{TABLE_NAME} WHERE name = ?";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final TypeReference<Map<String, Map<String, Object>>>
      ATTRIBUTES_TYPE_REF = new TypeReference<Map<String, Map<String, Object>>>() { };

  private final ApplicationDirectories applicationDirectories;

  // Executor service for Virtual Threads
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public DatastoreBlobReconciliationLogMigrator_1_11(final ApplicationDirectories applicationDirectories) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
  }

  @Override
  public Optional<String> version() {
    return Optional.of("1.11");
  }

  @Override
  public void migrate(final Connection connection) throws Exception {
    if (!tableExists(connection, TABLE_NAME)) {
      log.info("Table {} does not exist. Skipping upgrade step.", TABLE_NAME);
      return;
    }

    File reconciliationLogsBaseDirectory = applicationDirectories.getWorkDirectory(BLOBSTORE_LOG_PATH);
    ofNullable(reconciliationLogsBaseDirectory)
        .map(File::listFiles)
        .ifPresent(blobStoreReconciliationLogDirs -> copyFilesWithVirtualThreads(blobStoreReconciliationLogDirs, connection));

    ofNullable(reconciliationLogsBaseDirectory).ifPresent(this::deleteDirectoryWithVirtualThreads);
    
    // Shutdown the executor service after all tasks are complete
    virtualThreadExecutor.close();
  }

  /**
   * Deletes a directory using Virtual Threads for improved I/O performance.
   * 
   * @param reconciliationLogsBaseDirectory the directory to delete
   */
  private void deleteDirectoryWithVirtualThreads(final File reconciliationLogsBaseDirectory) {
    try {
      Future<?> task = virtualThreadExecutor.submit(() -> {
        try {
          log.debug("Deleting directory {} using Virtual Thread", reconciliationLogsBaseDirectory);
          FileUtils.deleteDirectory(reconciliationLogsBaseDirectory);
          log.debug("Successfully deleted directory {}", reconciliationLogsBaseDirectory);
        }
        catch (IOException e) {
          log.error("Error deleting directory {} with Virtual Thread", reconciliationLogsBaseDirectory, e);
          throw new UncheckedIOException(e);
        }
      });
      
      // Wait for the task to complete to ensure proper error propagation
      task.get();
    }
    catch (Exception e) {
      log.error("Failed to execute directory deletion task for {}", reconciliationLogsBaseDirectory, e);
      throw new RuntimeException("Failed to delete directory using Virtual Thread", e);
    }
  }

  /**
   * Legacy method kept for backward compatibility
   */
  private void deleteDirectory(final File reconciliationLogsBaseDirectory) {
    deleteDirectoryWithVirtualThreads(reconciliationLogsBaseDirectory);
  }

  /**
   * Copies files using Virtual Threads for improved I/O performance.
   * 
   * @param blobStoreReconciliationLogDirs array of directories to process
   * @param connection database connection
   */
  private void copyFilesWithVirtualThreads(final File[] blobStoreReconciliationLogDirs, final Connection connection) {
    log.info("Found reconciliation logs for {} blob stores to migrate", blobStoreReconciliationLogDirs.length);
    
    List<Future<?>> tasks = Arrays.stream(blobStoreReconciliationLogDirs)
        .map(reconciliationLogDirectory -> virtualThreadExecutor.submit(() -> {
          String blobStoreName = reconciliationLogDirectory.getName();
          try {
            log.debug("Processing blob store {} using Virtual Thread", blobStoreName);
            getBlobStorePath(blobStoreName, connection)
                .map(this::toAbsoluteBlobStorePath)
                .ifPresent(blobStorePath -> copyDirectoryContentsToBlobstoreWithVirtualThreads(reconciliationLogDirectory, blobStorePath));
          }
          catch (Exception e) {
            log.error("Error processing blob store {} with Virtual Thread", blobStoreName, e);
            throw new RuntimeException(STR."Failed to process blob store \{blobStoreName}", e);
          }
        }))
        .toList();
    
    // Wait for all tasks to complete to ensure proper error propagation
    tasks.forEach(task -> {
      try {
        task.get();
      }
      catch (Exception e) {
        log.error("Error waiting for copy task to complete", e);
        throw new RuntimeException("Failed to complete copy operation", e);
      }
    });
  }

  /**
   * Legacy method kept for backward compatibility
   */
  private void copyFiles(final File[] blobStoreReconciliationLogDirs, final Connection connection) {
    copyFilesWithVirtualThreads(blobStoreReconciliationLogDirs, connection);
  }

  /**
   * Copies directory contents to blobstore using Virtual Threads for improved I/O performance.
   * 
   * @param sourceDir source directory
   * @param blobStorePath destination blobstore path
   */
  private void copyDirectoryContentsToBlobstoreWithVirtualThreads(final File sourceDir, final Path blobStorePath) {
    try {
      Path destination = blobStorePath.resolve(RECONCILIATION_DIRECTORY_NAME);
      List<File> logFiles = ofNullable(sourceDir.listFiles()).map(Arrays::asList).orElse(emptyList());
      
      // Create destination directory if it doesn't exist
      if (!Files.exists(destination)) {
        Files.createDirectories(destination);
      }
      
      log.info("Copying reconciliation logs from {} to {}", sourceDir, destination);
      
      // Copy each file using a Virtual Thread
      List<Future<?>> copyTasks = logFiles.stream()
          .map(logFile -> virtualThreadExecutor.submit(() -> {
            try {
              log.debug("Copying file {} to {} using Virtual Thread", logFile, destination);
              Files.copy(logFile.toPath(), destination.resolve(logFile.getName()));
              log.debug("Successfully copied file {}", logFile);
            }
            catch (IOException e) {
              log.error("Error copying file {} with Virtual Thread", logFile, e);
              throw new UncheckedIOException(e);
            }
          }))
          .toList();
      
      // Wait for all copy tasks to complete
      for (Future<?> task : copyTasks) {
        task.get();
      }
      
      log.info("Copied reconciliation logs from {} to {}", sourceDir, destination);
      
      // Delete source directory after successful copy
      deleteDirectoryWithVirtualThreads(sourceDir);
    }
    catch (Exception e) {
      log.warn("Skipping copy of reconciliation logs contained in {} because of error {}", sourceDir,
          getFullStackTrace(e));
    }
  }

  /**
   * Legacy method kept for backward compatibility
   */
  private void copyDirectoryContentsToBlobstore(final File sourceDir, final Path blobStorePath) {
    copyDirectoryContentsToBlobstoreWithVirtualThreads(sourceDir, blobStorePath);
  }

  /**
   * Gets the blob store path from the database using a prepared statement.
   * Uses transaction context that is compatible with Virtual Threads.
   * 
   * @param blobStoreName name of the blob store
   * @param connection database connection
   * @return Optional containing the blob store path if found
   */
  private Optional<Path> getBlobStorePath(final String blobStoreName, final Connection connection) {
    try (PreparedStatement statement = connection.prepareStatement(QUERY)) {
      // Set auto-commit to false to maintain transaction context across Virtual Thread handoffs
      boolean originalAutoCommit = connection.getAutoCommit();
      try {
        if (originalAutoCommit) {
          connection.setAutoCommit(false);
        }
        
        statement.setString(1, blobStoreName);
        log.debug("Executing query to find blob store path for {}", blobStoreName);
        
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            String attributeString = resultSet.getString(ATTRIBUTES);
            log.debug("Retrieved attributes for blob store {}: {}", blobStoreName, attributeString);
            
            Map<String, Map<String, Object>> nestedAttributesMap = MAPPER.readValue(attributeString, ATTRIBUTES_TYPE_REF);
            String pathValue = nestedAttributesMap.get(CONFIG_KEY).get(PATH_KEY).toString();
            log.info("Found path {} for blob store {}", pathValue, blobStoreName);
            
            return Optional.of(Paths.get(pathValue));
          } else {
            log.warn("No configuration found for blob store {}", blobStoreName);
          }
        }
      } finally {
        // Restore original auto-commit setting
        if (originalAutoCommit) {
          connection.setAutoCommit(true);
        }
      }
    }
    catch (SQLException e) {
      log.error(STR."SQL error retrieving blob store path for \{blobStoreName}", e);
    }
    catch (Exception ex) {
      log.error(STR."Error retrieving blob store path for \{blobStoreName}", ex);
    }
    return empty();
  }

  /**
   * Converts a relative blob store path to an absolute path.
   * Uses NIO operations that are compatible with Virtual Threads.
   * 
   * @param configurationPath the configuration path to convert
   * @return the absolute blob store path
   */
  private Path toAbsoluteBlobStorePath(final Path configurationPath) {
    if (configurationPath.isAbsolute()) {
      log.debug("Configuration path {} is already absolute", configurationPath);
      return configurationPath;
    }

    Path baseDir = applicationDirectories.getWorkDirectory(BASEDIR).toPath();
    try {
      Path normalizedBase = baseDir.toRealPath().normalize();
      Path resolvedPath = normalizedBase.resolve(configurationPath.normalize());
      log.debug("Converted relative path {} to absolute path {}", configurationPath, resolvedPath);
      return resolvedPath;
    }
    catch (IOException e) {
      log.error(STR."Error converting \{baseDir} to absolute path", e);
      throw new UncheckedIOException(STR."Failed to resolve absolute path for \{configurationPath}", e);
    }
  }
  
  /**
   * Checks if a table exists in the database.
   * Uses JDBC metadata which is compatible with Virtual Threads.
   * 
   * @param connection database connection
   * @param tableName name of the table to check
   * @return true if the table exists, false otherwise
   */
  private boolean tableExists(final Connection connection, final String tableName) {
    try {
      // Use database metadata to check if table exists - compatible with Virtual Threads
      ResultSet tables = connection.getMetaData().getTables(
          null, null, tableName, new String[] {"TABLE"});
      boolean exists = tables.next();
      tables.close();
      
      log.debug("Table {} {} in the database", tableName, exists ? "exists" : "does not exist");
      return exists;
    }
    catch (SQLException e) {
      log.error(STR."Error checking if table \{tableName} exists", e);
      return false;
    }
  }
}