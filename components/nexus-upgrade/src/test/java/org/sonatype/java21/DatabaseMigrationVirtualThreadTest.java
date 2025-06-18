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
package org.sonatype.java21;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.PostgresTestGroup;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.java21.Java21TestGroup;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestGroup;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests to validate Java 21 Virtual Thread compatibility with the Nexus database migration framework.
 * 
 * This test class verifies that database migrations can be executed concurrently using virtual threads,
 * ensuring that JDBC operations work correctly with Java 21's lightweight thread implementation.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
@org.junit.jupiter.api.Tag("Java21")
@org.junit.jupiter.api.Tag("VirtualThread")
public class DatabaseMigrationVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_SCHEMA = "vt_test";
  
  private static final String CREATE_SCHEMA_SQL = "CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA + " AUTHORIZATION test";
  
  private static final String DROP_SCHEMA_SQL = "DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE";
  
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS " + TEST_SCHEMA + ".migration_test (\n" +
      "      id                INTEGER       NOT NULL,\n" +
      "      name              VARCHAR(200)  NOT NULL,\n" +
      "      status            VARCHAR(50)   NOT NULL,\n" +
      "      CONSTRAINT pk_migration_test PRIMARY KEY (id)\n" +
      "    );";
  
  private static final String INSERT_DATA_SQL = 
      "INSERT INTO " + TEST_SCHEMA + ".migration_test (id, name, status) VALUES (?, ?, ?)";
  
  private static final String SELECT_COUNT_SQL = 
      "SELECT COUNT(*) FROM " + TEST_SCHEMA + ".migration_test";
  
  private static final int CONCURRENT_MIGRATIONS = 50;
  private static final int TIMEOUT_SECONDS = 30;
  
  @org.junit.jupiter.api.extension.RegisterExtension
  public DataSessionRule dataSessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME);
  
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  public void setUp() throws Exception {
    // Create a virtual thread per task executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Initialize test schema
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      conn.createStatement().execute(DROP_SCHEMA_SQL);
      conn.createStatement().execute(CREATE_SCHEMA_SQL);
      conn.createStatement().execute(CREATE_TABLE_SQL);
      conn.commit();
    }
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    // Clean up executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      if (!virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
    }
    
    // Clean up schema
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      conn.createStatement().execute(DROP_SCHEMA_SQL);
      conn.commit();
    }
  }
  
  /**
   * Tests that a single database migration can be executed in a virtual thread.
   */
  @Test
  public void testSingleMigrationInVirtualThread() throws Exception {
    AtomicBoolean migrationCompleted = new AtomicBoolean(false);
    
    // Create a test migration step
    DatabaseMigrationStep migrationStep = new DatabaseMigrationStep() {
      @Override
      public Optional<String> version() {
        return Optional.of("1.0");
      }
      
      @Override
      public void migrate(Connection connection) throws Exception {
        // Execute a simple migration that creates a record
        try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
          stmt.setInt(1, 1);
          stmt.setString(2, "Test Migration");
          stmt.setString(3, "Completed");
          stmt.executeUpdate();
        }
        migrationCompleted.set(true);
      }
    };
    
    // Execute the migration in a virtual thread
    virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        migrationStep.migrate(conn);
        conn.commit();
      } catch (Exception e) {
        fail("Migration failed with exception: " + e.getMessage());
      }
    }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify migration was completed
    assertTrue(migrationCompleted.get(), "Migration should have completed successfully");
    
    // Verify data was inserted
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(1));
    }
  }
  
  /**
   * Tests that multiple database migrations can be executed concurrently using virtual threads.
   */
  @Test
  public void testConcurrentMigrationsWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_MIGRATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<DatabaseMigrationStep> migrations = new ArrayList<>();
    
    // Create multiple migration steps
    for (int i = 0; i < CONCURRENT_MIGRATIONS; i++) {
      final int migrationId = i;
      migrations.add(new DatabaseMigrationStep() {
        @Override
        public Optional<String> version() {
          return Optional.of("1." + migrationId);
        }
        
        @Override
        public void migrate(Connection connection) throws Exception {
          // Wait for all migrations to start at the same time
          startLatch.await();
          
          // Execute migration
          try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
            stmt.setInt(1, migrationId);
            stmt.setString(2, "Migration " + migrationId);
            stmt.setString(3, "Completed");
            stmt.executeUpdate();
            successCount.incrementAndGet();
          } catch (SQLException e) {
            errorCount.incrementAndGet();
            throw e;
          } finally {
            completionLatch.countDown();
          }
        }
      });
    }
    
    // Submit all migrations to be executed concurrently with virtual threads
    for (DatabaseMigrationStep migration : migrations) {
      virtualThreadExecutor.submit(() -> {
        try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
          migration.migrate(conn);
          conn.commit();
        } catch (Exception e) {
          logger.error("Migration failed", e);
        }
      });
    }
    
    // Start all migrations simultaneously
    startLatch.countDown();
    
    // Wait for all migrations to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "All migrations should complete within the timeout period");
    
    // Verify all migrations completed successfully
    assertThat(errorCount.get(), is(0));
    assertThat(successCount.get(), is(CONCURRENT_MIGRATIONS));
    
    // Verify all data was inserted
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(CONCURRENT_MIGRATIONS));
    }
  }
  
  /**
   * Tests that transaction boundaries are properly maintained when using virtual threads.
   */
  @Test
  public void testTransactionIntegrityWithVirtualThreads() throws Exception {
    AtomicBoolean transactionRolledBack = new AtomicBoolean(false);
    
    // Create a test migration step that will fail
    DatabaseMigrationStep failingMigration = new DatabaseMigrationStep() {
      @Override
      public Optional<String> version() {
        return Optional.of("2.0");
      }
      
      @Override
      public void migrate(Connection connection) throws Exception {
        // Insert a valid record
        try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
          stmt.setInt(1, 100);
          stmt.setString(2, "Before Failure");
          stmt.setString(3, "Completed");
          stmt.executeUpdate();
        }
        
        // Throw an exception to cause rollback
        throw new SQLException("Simulated failure to test transaction rollback");
      }
    };
    
    // Execute the migration in a virtual thread
    virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        try {
          failingMigration.migrate(conn);
          conn.commit();
        } catch (Exception e) {
          // Expected exception, rollback the transaction
          conn.rollback();
          transactionRolledBack.set(true);
        }
      } catch (Exception e) {
        fail("Unexpected exception: " + e.getMessage());
      }
    }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify transaction was rolled back
    assertTrue(transactionRolledBack.get(), "Transaction should have been rolled back");
    
    // Verify no data was inserted (transaction was rolled back)
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(0));
    }
  }
  
  /**
   * Tests that database schema operations work correctly with virtual threads.
   */
  @Test
  public void testSchemaOperationsWithVirtualThreads() throws Exception {
    final String testIndexName = "idx_vt_test";
    final String createIndexSql = "CREATE INDEX " + testIndexName + " ON " + TEST_SCHEMA + ".migration_test(name)";
    
    // Create a test migration step that performs schema operations
    DatabaseMigrationStep schemaMigration = new DatabaseMigrationStep() {
      @Override
      public Optional<String> version() {
        return Optional.of("3.0");
      }
      
      @Override
      public void migrate(Connection connection) throws Exception {
        // Create an index
        connection.createStatement().execute(createIndexSql);
      }
      
      /**
       * Helper method to check if an index exists
       */
      public boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (var rs = connection.getMetaData().getIndexInfo(null, TEST_SCHEMA, "migration_test", false, false)) {
          while (rs.next()) {
            if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
              return true;
            }
          }
          return false;
        }
      }
    };
    
    // Execute the migration in a virtual thread
    virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        schemaMigration.migrate(conn);
        conn.commit();
      } catch (Exception e) {
        fail("Schema migration failed with exception: " + e.getMessage());
      }
    }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify index was created
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      assertTrue(schemaMigration.indexExists(conn, testIndexName), 
          "Index should have been created successfully");
    }
  }
}