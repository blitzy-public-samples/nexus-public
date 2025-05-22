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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.upgrade.events.UpgradeCompletedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeStartedEvent;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.UpgradeException;
import org.sonatype.nexus.upgrade.datastore.internal.PostStartupUpgradeAuditor;
import org.sonatype.nexus.upgrade.datastore.internal.TestMigrationStep;
import org.sonatype.nexus.upgrade.datastore.internal.UpgradeManagerImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests that validate the Nexus upgrade framework's compatibility with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class VirtualThreadUpgradeTest
    extends TestSupport
{
  private static final String SELECT_FROM_FLYWAY_SCHEMA_HISTORY = "SELECT * FROM \"flyway_schema_history\"";

  private static final String SELECT_FROM_EXAMPLE = "SELECT * FROM example";

  private static final int CONCURRENT_THREADS = 10;
  
  private static final int TIMEOUT_SECONDS = 30;

  private DataSessionRule dataSessionRule = new DataSessionRule();

  @Mock
  private DataStoreManager dataStoreManager;

  @Mock
  private PostStartupUpgradeAuditor auditor;

  private TestMigrationStep migrationStep = new TestMigrationStep();

  @BeforeEach
  public void setUp() {
    when(dataStoreManager.get(DEFAULT_DATASTORE_NAME)).thenReturn(getDataStore());
  }

  /**
   * Tests that a basic upgrade operation works with virtual threads.
   */
  @Test
  public void testBasicUpgradeWithVirtualThread() throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
    
    // Create a virtual thread to run the upgrade
    Thread virtualThread = Thread.ofVirtual().name("upgrade-virtual-thread").start(() -> {
      try {
        upgradeManager.migrate();
      }
      catch (Exception e) {
        fail("Upgrade failed with exception: " + e.getMessage());
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    
    // Verify the upgrade was successful
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement()) {
      try (ResultSet results = stmt.executeQuery(SELECT_FROM_FLYWAY_SCHEMA_HISTORY)) {
        if (migrationStep.isH2(conn)) {
          // for H2 flyway inserts an initial null version
          assertTrue(results.next());
          assertNull(results.getString("version"));
        }
        // assert there is history of one schema upgrade
        assertForExampleTable(results, "version", "1.0");
      }
      catch (Exception exception) {
        fail(exception.getMessage());
      }

      // check for the result of the upgrade step
      try (ResultSet results = stmt.executeQuery(SELECT_FROM_EXAMPLE)) {
        assertForExampleTable(results, "name", "fawkes");
      }
      catch (Exception exception) {
        fail(exception.getMessage());
      }
    }

    // Migrations should trigger events
    verify(auditor).post(any(UpgradeStartedEvent.class));
    verify(auditor).post(any(UpgradeCompletedEvent.class));
    verifyNoMoreInteractions(auditor);
  }

  /**
   * Tests that multiple concurrent upgrade operations can be performed using virtual threads.
   * This validates that the upgrade framework correctly handles thread scheduling and resource
   * management when running with Java 21's lightweight thread implementation.
   */
  @Test
  public void testConcurrentUpgradesWithVirtualThreads() throws Exception {
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create multiple migration steps for concurrent execution
      List<DatabaseMigrationStep> migrationSteps = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        migrationSteps.add(new ConcurrentTestMigrationStep("concurrent_" + i));
      }
      
      // Create a countdown latch to wait for all threads to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      
      // Track any errors that occur during concurrent execution
      AtomicBoolean hasErrors = new AtomicBoolean(false);
      
      // Submit tasks to the executor
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a new upgrade manager for each thread with a single migration step
            UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
                dataStoreManager, 
                auditor, 
                singletonList(migrationSteps.get(index)));
            
            // Perform the migration
            upgradeManager.migrate();
          }
          catch (Exception e) {
            log.error("Error in virtual thread {}: {}", index, e.getMessage(), e);
            hasErrors.set(true);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      
      // Verify all threads completed successfully
      assertTrue(completed, "Not all virtual threads completed within the timeout period");
      assertFalse(hasErrors.get(), "One or more virtual threads encountered errors");
      
      // Verify that all tables were created
      try (Connection conn = getConnection();
           Statement stmt = conn.createStatement()) {
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
          String tableName = "concurrent_" + i;
          String query = "SELECT * FROM " + tableName;
          
          try (ResultSet results = stmt.executeQuery(query)) {
            assertTrue(results.next(), "No data found in table " + tableName);
            assertThat(results.getString("name"), equalTo("virtual_thread_" + i));
            assertFalse(results.next(), "More than one row found in table " + tableName);
          }
        }
      }
    }
    finally {
      executor.shutdown();
      if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests that database connections are properly managed when used with virtual threads during upgrades.
   * This verifies that thread pinning is minimized and resources are properly released.
   */
  @Test
  public void testDatabaseConnectionsWithVirtualThreads() throws Exception {
    // Create a thread factory for virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service that uses virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Create a large number of CompletableFuture tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Submit multiple tasks that perform database operations
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int index = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Create a unique migration step for this thread
            DatabaseMigrationStep step = new ConnectionTestMigrationStep("conn_test_" + index);
            
            // Create an upgrade manager with this step
            UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
                dataStoreManager, 
                auditor, 
                singletonList(step));
            
            // Perform the migration
            upgradeManager.migrate();
            
            // Increment success counter
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread {}: {}", index, e.getMessage(), e);
            throw new RuntimeException(e);
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all futures to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      allFutures.join();
      
      // Verify all operations completed successfully
      assertThat(successCount.get(), equalTo(CONCURRENT_THREADS));
      
      // Verify that all tables were created
      try (Connection conn = getConnection();
           Statement stmt = conn.createStatement()) {
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
          String tableName = "conn_test_" + i;
          String query = "SELECT * FROM " + tableName;
          
          try (ResultSet results = stmt.executeQuery(query)) {
            assertTrue(results.next(), "No data found in table " + tableName);
            assertThat(results.getString("name"), equalTo("connection_test_" + i));
            assertFalse(results.next(), "More than one row found in table " + tableName);
          }
        }
      }
    }
    finally {
      executor.shutdown();
      if (!executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  private static void assertForExampleTable(ResultSet results, String name, String fawkes) throws SQLException {
    assertTrue(results.next());
    assertThat(results.getString(name), equalTo(fawkes));
    assertFalse(results.next());
  }

  private Optional<DataStore<?>> getDataStore() {
    return dataSessionRule.getDataStore(DEFAULT_DATASTORE_NAME);
  }

  private Connection getConnection() throws SQLException {
    return getDataStore()
        .orElseThrow(() -> new IllegalStateException("No DataStore found"))
        .getDataSource()
        .getConnection();
  }

  /**
   * A test migration step for concurrent execution testing.
   */
  private static class ConcurrentTestMigrationStep implements DatabaseMigrationStep {
    private final String tableName;
    
    public ConcurrentTestMigrationStep(String tableName) {
      this.tableName = tableName;
    }
    
    @Override
    public Optional<String> version() {
      return Optional.of("1.0-" + tableName);
    }

    @Override
    public void migrate(Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        // Create a unique table for this migration step
        stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (name VARCHAR(50))");
        
        // Insert a record with a unique name
        int threadId = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
        stmt.execute("INSERT INTO " + tableName + " (name) VALUES('virtual_thread_" + threadId + "')");
        
        // Simulate some work to increase the chance of thread scheduling
        Thread.sleep(50);
      }
    }
  }

  /**
   * A test migration step for connection management testing.
   */
  private static class ConnectionTestMigrationStep implements DatabaseMigrationStep {
    private final String tableName;
    
    public ConnectionTestMigrationStep(String tableName) {
      this.tableName = tableName;
    }
    
    @Override
    public Optional<String> version() {
      return Optional.of("1.0-" + tableName);
    }

    @Override
    public void migrate(Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        // Create a unique table for this migration step
        stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (name VARCHAR(50))");
        
        // Insert a record with a unique name
        int threadId = Integer.parseInt(tableName.substring(tableName.lastIndexOf('_') + 1));
        stmt.execute("INSERT INTO " + tableName + " (name) VALUES('connection_test_" + threadId + "')");
        
        // Perform multiple database operations to test connection management
        for (int i = 0; i < 5; i++) {
          try (ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName)) {
            // Just iterate through the results
            while (rs.next()) {
              rs.getString("name");
            }
          }
          
          // Small delay to allow thread scheduling
          Thread.sleep(10);
        }
      }
    }
  }
}