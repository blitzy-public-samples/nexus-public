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
package org.sonatype.nexus.internal.support;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;

import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.stream.Collectors.toMap;

/**
 * Adds Db diagnostic info to support zip
 *
 */
public class DbDiagnostics
{
  private static final Logger log = LoggerFactory.getLogger(DbDiagnostics.class);

  private final ApplicationDirectories directories;

  private final DataStoreManager dataStoreManager;

  private static final String DATABASE_NAME = "Database Name: ";

  private static final String DATABASE_VERSION = "Database Version: ";

  private static final String DATABASE_SIZE = "Database Size (Bytes): ";

  private static final String MICROSECONDS = " microseconds";

  @Inject
  public DbDiagnostics(ApplicationDirectories directories, final DataStoreManager dataStoreManager) {
    this.directories = checkNotNull(directories);
    this.dataStoreManager = checkNotNull(dataStoreManager);
  }

  /**
   * Collects database information using Virtual Threads for improved I/O performance.
   * Uses Java 21 String Templates for more readable logging.
   */
  private Stream<Entry<String, Object>> dataStoreHelper(final DataStore<?> dataStore) {
    String databaseProductName = "";
    String databaseProductVersion = "";
    String h2DBPath = "";

    long databaseSize = 0;
    StringBuilder latencySB = new StringBuilder();
    StringBuilder dbSettingsSB = new StringBuilder();

    // Use try-with-resources for proper JDBC resource management
    try (Connection connection = dataStore.getDataSource().getConnection()) {
      // Retrieve database metadata using non-blocking pattern
      CompletableFuture<DatabaseMetaData> metadataFuture = CompletableFuture.supplyAsync(() -> {
        try {
          return connection.getMetaData();
        } catch (SQLException e) {
          throw new RuntimeException(STR."Failed to retrieve database metadata: \{e.getMessage()}", e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      DatabaseMetaData metaData = metadataFuture.join();
      databaseProductName = metaData.getDatabaseProductName();
      databaseProductVersion = STR."\{metaData.getDatabaseMajorVersion()}.\{metaData.getDatabaseMinorVersion()}";

      if (databaseProductName.equalsIgnoreCase("H2")) {
        final File h2Db = new File(getH2DB().toString());
        h2DBPath = h2Db.getPath();
        databaseSize = h2Db.length();

        // Use Virtual Thread to retrieve H2 settings
        CompletableFuture<SortedMap<String, String>> h2SettingsFuture = CompletableFuture.supplyAsync(() -> {
          try {
            return getH2Settings(connection);
          } catch (SQLException e) {
            throw new RuntimeException(STR."Failed to retrieve H2 settings: \{e.getMessage()}", e);
          }
        }, Executors.newVirtualThreadPerTaskExecutor());
        
        h2SettingsFuture.join().forEach((name, value) -> 
            dbSettingsSB.append(STR."\{name}: \{value}\n"));
      }
      else {
        // Use Virtual Thread to retrieve PostgreSQL settings
        CompletableFuture<SortedMap<String, String>> pgSettingsFuture = CompletableFuture.supplyAsync(() -> {
          try {
            return getPostgresSettings(connection);
          } catch (SQLException e) {
            throw new RuntimeException(STR."Failed to retrieve PostgreSQL settings: \{e.getMessage()}", e);
          }
        }, Executors.newVirtualThreadPerTaskExecutor());
        
        pgSettingsFuture.join().forEach((name, value) -> 
            dbSettingsSB.append(STR."\{name}: \{value}\n"));
      }
      
      // Collect latency information using Virtual Threads
      CompletableFuture<StringBuilder> latencyFuture = CompletableFuture.supplyAsync(() -> {
        try {
          return getLatencyInformation(dataStore);
        } catch (SQLException e) {
          throw new RuntimeException(STR."Failed to collect latency information: \{e.getMessage()}", e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
      
      latencySB = latencyFuture.join();
    }
    catch (SQLException e) {
      log.error(STR."Failed to connect to the database: \{e.getMessage()}", e);
      throw new RuntimeException(STR."Failed to connect to the database: \{e.getMessage()}", e);
    }

    return Stream.of(
        new SimpleEntry<>(DATABASE_NAME, databaseProductName),
        new SimpleEntry<>(DATABASE_VERSION, databaseProductVersion),
        new SimpleEntry<>(DATABASE_SIZE, databaseSize),
        new SimpleEntry<>("Latency", latencySB),
        new SimpleEntry<>("H2DB PATH: ", h2DBPath),
        new SimpleEntry<>("DB SETTINGS: ", dbSettingsSB));
  }

  /**
   * Collects metrics for all database types using Virtual Threads for parallel processing.
   * 
   * @return Map of database metrics
   */
  public Map<String, Object> metricsByDbType() {
    // Use parallel stream with Virtual Threads for improved performance
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Process each datastore in parallel using Virtual Threads
      return StreamSupport.stream(dataStoreManager.browse().spliterator(), true)
          .flatMap(dataStore -> {
            try {
              return dataStoreHelper(dataStore);
            } catch (Exception e) {
              log.error(STR."Error collecting metrics for datastore: \{e.getMessage()}", e);
              return Stream.empty();
            }
          })
          .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (v1, v2) -> v2));
    }
  }

  /**
   * Gets database file information using Java 21 String Templates for improved readability.
   * 
   * @return Formatted database diagnostic information
   */
  public String getDbFileInfo() {
    log.trace("Getting DB file info");
    Map<String, Object> metricsByDbType = metricsByDbType();
    final StringBuilder dbInfo = new StringBuilder();

    // Use String Templates for more readable output formatting
    dbInfo.append("-- Database Diagnostics --\n");
    dbInfo.append(STR."\{DATABASE_NAME}\{metricsByDbType.get(DATABASE_NAME)}\n");
    dbInfo.append(STR."\{DATABASE_VERSION}\{metricsByDbType.get(DATABASE_VERSION)}\n");

    if (metricsByDbType.get(DATABASE_NAME).equals("H2")) {
      dbInfo.append(STR."\{DATABASE_SIZE}\{metricsByDbType.get(DATABASE_SIZE)}\n");
      dbInfo.append(STR."H2DB Path: \{metricsByDbType.get("H2DB PATH: ")}\n");
    }

    dbInfo.append(metricsByDbType.get("Latency"));
    dbInfo.append("-- Database Settings --\n");
    dbInfo.append(metricsByDbType.get("DB SETTINGS: "));

    return dbInfo.toString();
  }

  /**
   * Gets the path to the H2 database file.
   * 
   * @return Path to the H2 database file, or null if it doesn't exist
   */
  public Path getH2DB() {
    Path dbPath = directories.getWorkDirectory("db").toPath();
    Path h2Db = dbPath.resolve("nexus.mv.db");

    // Use Java 21 String Templates for improved logging
    if (Files.exists(h2Db)) {
      log.trace(STR."Found H2 database at: \{h2Db}");
      return h2Db;
    }
    else {
      log.trace(STR."H2 database not found at expected location: \{h2Db}");
      return null;
    }
  }

  /**
   * Collects database latency information using Virtual Threads for improved performance.
   * Implements Virtual Thread-aware metrics collection for database connection latency.
   * 
   * @param dataStore The datastore to measure latency for
   * @return Formatted latency information
   * @throws SQLException if database operations fail
   */
  public StringBuilder getLatencyInformation(final DataStore<?> dataStore) throws SQLException {
    StringBuilder sb = new StringBuilder();
    
    // Use atomic variables for thread-safe updates from multiple Virtual Threads
    AtomicLong latencyMinimum = new AtomicLong(Long.MAX_VALUE);
    AtomicLong latencyMaximum = new AtomicLong(Long.MIN_VALUE);
    AtomicLong latencyCumulative = new AtomicLong(0);

    try {
      // Create a Virtual Thread executor for parallel latency measurements
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Ping database 5 times, find out the minimum, maximum and average latency
        int tryCount = 5;
        List<Future<?>> futures = new ArrayList<>(tryCount);
        
        // Launch multiple parallel latency tests using Virtual Threads
        for (int i = 0; i < tryCount; i++) {
          futures.add(executor.submit(() -> {
            try (Connection connection = dataStore.getDataSource().getConnection()) {
              long start = System.nanoTime();
              connection.isValid(/* timeout in seconds */ 3);
              long latency = (System.nanoTime() - start) / 1000;
              
              // Update metrics atomically
              updateLatencyMetrics(latencyMinimum, latencyMaximum, latencyCumulative, latency);
              
              log.trace(STR."Database connection latency measurement: \{latency}\{MICROSECONDS}");
            } catch (SQLException e) {
              log.error(STR."Failed to measure database latency: \{e.getMessage()}", e);
            }
          }));
        }
        
        // Wait for all latency measurements to complete
        for (Future<?> future : futures) {
          try {
            future.get(10, TimeUnit.SECONDS);
          } catch (Exception e) {
            log.error(STR."Error during latency measurement: \{e.getMessage()}", e);
          }
        }
      }
      
      // Calculate average latency
      long averageLatency = latencyCumulative.get() / 5; // tryCount is 5

      // Format the information using String Templates
      sb.append("-- Latency Information --\n");
      sb.append(STR."Minimum: \{latencyMinimum.get()}\{MICROSECONDS}\n");
      sb.append(STR."Average: \{averageLatency}\{MICROSECONDS}\n");
      sb.append(STR."Maximum: \{latencyMaximum.get()}\{MICROSECONDS}\n");
    }
    catch (Exception e) {
      throw new SQLException(STR."Failed to get database latency info: \{e.getMessage()}", e);
    }

    return sb;
  }
  
  /**
   * Updates latency metrics in a thread-safe manner.
   * 
   * @param min Minimum latency atomic reference
   * @param max Maximum latency atomic reference
   * @param sum Cumulative latency atomic reference
   * @param latency Current latency measurement
   */
  private void updateLatencyMetrics(AtomicLong min, AtomicLong max, AtomicLong sum, long latency) {
    // Update minimum latency (if smaller)
    min.getAndUpdate(current -> Math.min(current, latency));
    
    // Update maximum latency (if larger)
    max.getAndUpdate(current -> Math.max(current, latency));
    
    // Add to cumulative latency
    sum.addAndGet(latency);
  }

  /**
   * Retrieves PostgreSQL database settings using Java 21 compatible JDBC operations.
   * Uses non-blocking patterns for improved performance with Virtual Threads.
   * 
   * @param connection Database connection
   * @return Map of PostgreSQL settings
   * @throws SQLException if database operations fail
   */
  private static SortedMap<String, String> getPostgresSettings(Connection connection) throws SQLException {
    SortedMap<String, String> postgresSettingsMap = new TreeMap<>();

    String query = "SHOW ALL";
    try (Statement stmt = connection.createStatement()) {
      // Execute query with proper resource management
      try (ResultSet rs = stmt.executeQuery(query)) {
        while (rs.next()) {
          String name = rs.getString(1);
          String value = rs.getString(2);
          postgresSettingsMap.put(name, value);
        }
      }
    }
    catch (SQLException e) {
      throw new SQLException(STR."Failed to execute SHOW ALL query: \{e.getMessage()}", e);
    }

    return postgresSettingsMap;
  }

  /**
   * Retrieves H2 database settings using Java 21 compatible JDBC operations.
   * Uses non-blocking patterns for improved performance with Virtual Threads.
   * 
   * @param connection Database connection
   * @return Map of H2 database settings
   * @throws SQLException if database operations fail
   */
  private static SortedMap<String, String> getH2Settings(Connection connection) throws SQLException {
    SortedMap<String, String> h2SettingsMap = new TreeMap<>();
    String query = "SELECT SETTING_NAME, SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS";

    try (PreparedStatement stmt = connection.prepareStatement(query)) {
      // Execute query with proper resource management
      try (ResultSet rs = stmt.executeQuery()) {
        while (rs.next()) {
          String name = rs.getString(1);
          String value = rs.getString(2);
          h2SettingsMap.put(name, value);
        }
      }
    }
    catch (SQLException e) {
      throw new SQLException(
          STR."Failed to execute query: \{query}: \{e.getMessage()}", e);
    }

    return h2SettingsMap;
  }

}