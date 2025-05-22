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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.PostgresTestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;

import org.junit.Rule;
import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests for validating Java 21 Virtual Thread compatibility with the Nexus database migration framework.
 */
@Category({PostgresTestGroup.class, Java21TestGroup.class, VirtualThreadTestGroup.class})
public class DatabaseMigrationVirtualThreadTest
    extends VirtualThreadTestSupport
{
  private static final String CUSTOM_SQL = "CREATE TABLE IF NOT EXISTS custom.test (\n"
      + "      domain            VARCHAR(200)   NOT NULL,\n"
      + "      token             VARCHAR(200)   NOT NULL,\n"
      + "      CONSTRAINT pk_domain PRIMARY KEY (domain)\n"
      + "    );\n"
      + "";

  // Using both JUnit 4 @Rule and JUnit 5 @RegisterExtension for compatibility during migration
  @Rule
  @RegisterExtension
  public DataSessionRule customSessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME);

  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    ThreadFactory factory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(factory);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  @Test
  @DisplayName("Test schema creation with virtual threads")
  void testSchemaCreationWithVirtualThreads() throws Exception {
    AtomicBoolean success = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try (Connection conn = customSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        underTest.runStatement(conn, "drop schema if exists custom");
        underTest.runStatement(conn, "create schema custom authorization test");
        underTest.runStatement(conn, CUSTOM_SQL);
        
        // Verify index doesn't exist in public schema
        assertFalse(underTest.indexExists(conn, "pk_domain"), "index should not exist in public schema");
        
        // Switch to custom schema and verify index exists
        underTest.runStatement(conn, "SET search_path TO custom");
        assertTrue(underTest.indexExists(conn, "pk_domain"), "index should exist in custom schema");
        
        success.set(true);
      } catch (SQLException e) {
        fail("Failed to execute database operations with virtual thread: " + e.getMessage());
      }
    }).get(); // Wait for completion
    
    assertTrue(success.get(), "Virtual thread database operations should complete successfully");
  }

  @Test
  @DisplayName("Test concurrent migrations with virtual threads")
  void testConcurrentMigrationsWithVirtualThreads() throws Exception {
    final int threadCount = 5;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicBoolean[] successes = new AtomicBoolean[threadCount];
    
    for (int i = 0; i < threadCount; i++) {
      final int threadIndex = i;
      successes[threadIndex] = new AtomicBoolean(false);
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Each thread creates its own schema
          String schemaName = "custom_" + threadIndex;
          
          try (Connection conn = customSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
            underTest.runStatement(conn, "drop schema if exists " + schemaName);
            underTest.runStatement(conn, "create schema " + schemaName + " authorization test");
            
            // Create table in the schema
            String sql = "CREATE TABLE IF NOT EXISTS " + schemaName + ".test (\n"
                + "      domain            VARCHAR(200)   NOT NULL,\n"
                + "      token             VARCHAR(200)   NOT NULL,\n"
                + "      CONSTRAINT pk_domain_" + threadIndex + " PRIMARY KEY (domain)\n"
                + "    );\n";
            
            underTest.runStatement(conn, sql);
            
            // Switch to schema and verify index exists
            underTest.runStatement(conn, "SET search_path TO " + schemaName);
            assertTrue(underTest.indexExists(conn, "pk_domain_" + threadIndex), 
                "index should exist in " + schemaName + " schema");
            
            successes[threadIndex].set(true);
          }
        } catch (Exception e) {
          fail("Thread " + threadIndex + " failed: " + e.getMessage());
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), "All virtual threads should complete in time");
    
    // Verify all threads succeeded
    for (int i = 0; i < threadCount; i++) {
      assertTrue(successes[i].get(), "Virtual thread " + i + " should complete successfully");
    }
  }

  @Test
  @DisplayName("Test transaction boundaries with virtual threads")
  void testTransactionBoundariesWithVirtualThreads() throws Exception {
    AtomicBoolean success = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      Connection conn = null;
      try {
        conn = customSessionRule.openConnection(DEFAULT_DATASTORE_NAME);
        conn.setAutoCommit(false); // Start transaction
        
        underTest.runStatement(conn, "drop schema if exists transaction_test");
        underTest.runStatement(conn, "create schema transaction_test authorization test");
        
        // Create table in the schema
        String sql = "CREATE TABLE IF NOT EXISTS transaction_test.data (\n"
            + "      id               VARCHAR(200)   NOT NULL,\n"
            + "      value            VARCHAR(200)   NOT NULL,\n"
            + "      CONSTRAINT pk_id PRIMARY KEY (id)\n"
            + "    );\n";
        
        underTest.runStatement(conn, sql);
        
        // Insert data
        underTest.runStatement(conn, "INSERT INTO transaction_test.data VALUES ('1', 'test')");
        
        // Commit transaction
        conn.commit();
        
        // Verify data exists
        underTest.runStatement(conn, "SET search_path TO transaction_test");
        assertTrue(underTest.tableExists(conn, "data"), "table should exist");
        
        success.set(true);
      } catch (SQLException e) {
        if (conn != null) {
          try {
            conn.rollback();
          } catch (SQLException ex) {
            // Ignore
          }
        }
        fail("Failed to execute transaction with virtual thread: " + e.getMessage());
      } finally {
        if (conn != null) {
          try {
            conn.close();
          } catch (SQLException e) {
            // Ignore
          }
        }
      }
    }).get(); // Wait for completion
    
    assertTrue(success.get(), "Virtual thread transaction should complete successfully");
  }

  private final DatabaseMigrationStep underTest = new DatabaseMigrationStep() {
    public Optional<String> version() {
      return Optional.of("0.0");
    }
    
    @Override
    public void migrate(final Connection connection) {
      // Not used in these tests
    }
    
    // Expose protected methods for testing
    @Override
    public boolean indexExists(Connection connection, String indexName) throws SQLException {
      return super.indexExists(connection, indexName);
    }
    
    @Override
    public boolean tableExists(Connection connection, String tableName) throws SQLException {
      return super.tableExists(connection, tableName);
    }
  };
}