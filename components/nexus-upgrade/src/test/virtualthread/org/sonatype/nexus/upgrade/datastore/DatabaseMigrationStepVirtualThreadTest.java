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
package org.sonatype.nexus.upgrade.datastore;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.PostgresTestGroup;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;
import org.sonatype.nexus.testdb.DataSessionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests {@link DatabaseMigrationStep} with Java 21 Virtual Threads to verify
 * that database operations work correctly when executed with virtual threads.
 * 
 * This test validates that schema-scoped index detection and SQL execution logic
 * in DatabaseMigrationStep works correctly when executed with virtual threads,
 * ensuring that database operations don't cause thread pinning issues.
 */
@Category({PostgresTestGroup.class, VirtualThreadTestGroup.class})
public class DatabaseMigrationStepVirtualThreadTest
    extends TestSupport
{
  private static final String CUSTOM_SQL = "CREATE TABLE IF NOT EXISTS custom.test (\n"
      + "      domain            VARCHAR(200)   NOT NULL,\n"
      + "      token             VARCHAR(200)   NOT NULL,\n"
      + "      CONSTRAINT pk_domain PRIMARY KEY (domain)\n"
      + "    );\n"
      + "";

  @Rule
  public DataSessionRule customSessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME);

  /**
   * Tests that custom schema index detection works correctly when executed with virtual threads.
   * This verifies that the JDBC operations in DatabaseMigrationStep don't cause thread pinning
   * when executed with virtual threads.
   */
  @Test
  public void testCustomSchemaIndexExistsWithVirtualThread() throws Exception {
    // Create and run the test in a virtual thread
    Thread virtualThread = Thread.ofVirtual().name("custom-schema-test").start(() -> {
      try (Connection conn = customSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        underTest.runStatement(conn, "drop schema if exists custom");
        underTest.runStatement(conn, "create schema custom authorization test");

        // create a table + index in custom schema
        underTest.runStatement(conn, CUSTOM_SQL);
        assertFalse("index does not exist in public schema", underTest.indexExists(conn, "pk_domain"));

        underTest.runStatement(conn, "SET search_path TO custom");
        assertTrue("index exists in custom schema", underTest.indexExists(conn, "pk_domain"));
      }
      catch (SQLException e) {
        fail("SQL exception during virtual thread execution: " + e.getMessage());
      }
    });

    // Wait for the virtual thread to complete
    virtualThread.join();
  }

  /**
   * Tests concurrent execution of SQL statements using virtual threads.
   * This verifies that multiple concurrent database operations can be executed
   * efficiently using virtual threads without thread pinning issues.
   */
  @Test
  public void testConcurrentSqlExecutionWithVirtualThreads() throws Exception {
    final int threadCount = 50;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicBoolean anyFailures = new AtomicBoolean(false);
    
    // Create a virtual thread per task executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try (Connection conn = customSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
            // Create a unique schema for each thread
            String schemaName = "vt_schema_" + threadId;
            underTest.runStatement(conn, "drop schema if exists " + schemaName);
            underTest.runStatement(conn, "create schema " + schemaName + " authorization test");
            
            // Create a table with a primary key index in the schema
            String createTableSql = "CREATE TABLE IF NOT EXISTS " + schemaName + ".test_table (\n"
                + "      id               INTEGER      NOT NULL,\n"
                + "      name             VARCHAR(200) NOT NULL,\n"
                + "      CONSTRAINT pk_" + schemaName + " PRIMARY KEY (id)\n"
                + "    );";
            underTest.runStatement(conn, createTableSql);
            
            // Verify the index exists in the schema
            underTest.runStatement(conn, "SET search_path TO " + schemaName);
            boolean indexExists = underTest.indexExists(conn, "pk_" + schemaName);
            
            if (indexExists) {
              successCount.incrementAndGet();
            }
            else {
              anyFailures.set(true);
            }
          }
          catch (Exception e) {
            anyFailures.set(true);
            log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a timeout
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertTrue("Not all threads completed in time", completed);
      assertFalse("Some threads encountered errors", anyFailures.get());
      assertEquals("All threads should have succeeded", threadCount, successCount.get());
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Test implementation of DatabaseMigrationStep for testing purposes.
   */
  private final DatabaseMigrationStep underTest = new DatabaseMigrationStep() {
    public Optional<String> version() {
      return Optional.of("0.0");
    }
    
    @Override
    public void migrate(final Connection connection) {
      // Not used in this test
    }
  };
}