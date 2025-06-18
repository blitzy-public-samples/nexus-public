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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.upgrade.Checkpoint;
import org.sonatype.nexus.common.upgrade.Upgrade;
import org.sonatype.nexus.common.upgrade.events.UpgradeCompletedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeStartedEvent;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestGroup;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.content.testsuite.groups.VirtualThreadTestSupport;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.internal.PostStartupUpgradeAuditor;
import org.sonatype.nexus.upgrade.datastore.internal.UpgradeManagerImpl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests to validate Java 21 Virtual Thread compatibility with the Nexus upgrade framework.
 *
 * This test class verifies that upgrade operations can be executed concurrently using virtual threads,
 * ensuring that the upgrade framework correctly handles thread scheduling, resource management,
 * and transaction isolation when running with Java 21's lightweight thread implementation.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
@org.junit.jupiter.api.Tag("Java21")
@org.junit.jupiter.api.Tag("VirtualThread")
public class VirtualThreadUpgradeTest
    extends VirtualThreadTestSupport
{
  private static final String TEST_SCHEMA = "vt_upgrade_test";

  private static final String CREATE_SCHEMA_SQL = "CREATE SCHEMA IF NOT EXISTS " + TEST_SCHEMA + " AUTHORIZATION test";

  private static final String DROP_SCHEMA_SQL = "DROP SCHEMA IF EXISTS " + TEST_SCHEMA + " CASCADE";

  private static final String CREATE_TABLE_SQL =
      "CREATE TABLE IF NOT EXISTS " + TEST_SCHEMA + ".upgrade_test (\n" +
      "      id                INTEGER       NOT NULL,\n" +
      "      version           VARCHAR(50)   NOT NULL,\n" +
      "      status            VARCHAR(50)   NOT NULL,\n" +
      "      CONSTRAINT pk_upgrade_test PRIMARY KEY (id)\n" +
      "    );";

  private static final String INSERT_DATA_SQL =
      "INSERT INTO " + TEST_SCHEMA + ".upgrade_test (id, version, status) VALUES (?, ?, ?)";

  private static final String SELECT_COUNT_SQL =
      "SELECT COUNT(*) FROM " + TEST_SCHEMA + ".upgrade_test";

  private static final int CONCURRENT_UPGRADES = 20;
  private static final int TIMEOUT_SECONDS = 30;

  @org.junit.jupiter.api.extension.RegisterExtension
  public DataSessionRule dataSessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME);

  @Mock
  private DataStoreManager dataStoreManager;

  @Mock
  private PostStartupUpgradeAuditor auditor;

  private ExecutorService virtualThreadExecutor;
  private AutoCloseable mocks;
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadUpgradeTest.class);

  @BeforeEach
  public void setUp() throws Exception {
    // Initialize mocks
    mocks = MockitoAnnotations.openMocks(this);

    // Create a virtual thread per task executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    // Setup DataStoreManager mock
    when(dataStoreManager.get(DEFAULT_DATASTORE_NAME)).thenReturn(getDataStore());

    // Initialize test schema
    try (Connection conn = getConnection()) {
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
    try (Connection conn = getConnection()) {
      conn.createStatement().execute(DROP_SCHEMA_SQL);
      conn.commit();
    }

    // Close mocks
    if (mocks != null) {
      mocks.close();
    }
  }

  /**
   * Tests that a single upgrade can be executed in a virtual thread.
   */
  @Test
  public void testSingleUpgradeInVirtualThread() throws Exception {
    // Create a test migration step
    TestMigrationStep migrationStep = new TestMigrationStep();

    // Create upgrade manager with the migration step
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));

    // Execute the upgrade in a virtual thread
    supplyFromVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertCurrentThreadIsVirtual();

        // Execute the upgrade
        upgradeManager.migrate();
        return true;
      } catch (Exception e) {
        log.error("Upgrade failed", e);
        fail("Upgrade failed with exception: " + e.getMessage());
        return false;
      }
    });

    // Verify upgrade events were fired
    verify(auditor).post(any(UpgradeStartedEvent.class));
    verify(auditor).post(any(UpgradeCompletedEvent.class));

    // Verify data was inserted
    try (Connection conn = getConnection()) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(1));
    }
  }
  private static <T> T supplyFromVirtualThread(Callable<T> task) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<T> future = executor.submit(task);
      return future.get(); // waits for completion
    } catch (Exception e) {
      throw new RuntimeException("Virtual thread execution failed", e);
    }
  }
  private static void assertCurrentThreadIsVirtual() {
    assertTrue(Thread.currentThread().isVirtual(), "Thread should be virtual");
  }

  /**
   * Tests that multiple upgrades can be executed concurrently using virtual threads.
   */
  @Test
  public void testConcurrentUpgradesWithVirtualThreads() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_UPGRADES);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    List<DatabaseMigrationStep> migrations = new ArrayList<>();

    // Create multiple migration steps
    for (int i = 0; i < CONCURRENT_UPGRADES; i++) {
      final int upgradeId = i;
      migrations.add(new DatabaseMigrationStep() {
        @Override
        public Optional<String> version() {
          return Optional.of("1." + upgradeId);
        }

        @Override
        public void migrate(Connection connection) throws Exception {
          // Wait for all upgrades to start at the same time
          startLatch.await();

          // Verify we're running in a virtual thread
          assertTrue(Thread.currentThread().isVirtual(),
              "Migration should be running in a virtual thread");

          // Execute upgrade
          try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
            stmt.setInt(1, upgradeId);
            stmt.setString(2, "1." + upgradeId);
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

    // Create upgrade manager with all migration steps
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, migrations);

    // Submit the upgrade to be executed with virtual threads
    virtualThreadExecutor.submit(() -> {
      try {
        // Start all upgrades simultaneously
        startLatch.countDown();

        // Execute the upgrades
        upgradeManager.migrate();
      } catch (Exception e) {
        log.error("Concurrent upgrades failed", e);
      }
    });

    // Wait for all upgrades to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "All upgrades should complete within the timeout period");

    // Verify all upgrades completed successfully
    assertThat(errorCount.get(), is(0));
    assertThat(successCount.get(), is(CONCURRENT_UPGRADES));

    // Verify all data was inserted
    try (Connection conn = getConnection()) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(CONCURRENT_UPGRADES));
    }
  }

  /**
   * Tests that transaction boundaries are properly maintained when using virtual threads for upgrades.
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
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(),
            "Migration should be running in a virtual thread");

        // Insert a valid record
        try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
          stmt.setInt(1, 100);
          stmt.setString(2, "2.0");
          stmt.setString(3, "Before Failure");
          stmt.executeUpdate();
        }

        // Throw an exception to cause rollback
        throw new SQLException("Simulated failure to test transaction rollback");
      }
    };

    // Create upgrade manager with the failing migration step
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor,
        singletonList(failingMigration));

    // Execute the upgrade in a virtual thread and expect failure
    try {
      supplyFromVirtualThread(() -> {
        try {
          upgradeManager.migrate();
          return true;
        } catch (Exception e) {
          // Expected exception
          transactionRolledBack.set(true);
          return false;
        }
      });
    } catch (Exception e) {
      // Expected exception might be propagated
      transactionRolledBack.set(true);
    }

    // Verify transaction was rolled back
    assertTrue(transactionRolledBack.get(), "Transaction should have been rolled back");

    // Verify no data was inserted (transaction was rolled back)
    try (Connection conn = getConnection()) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(0));
    }
  }

  /**
   * Tests that checkpoint operations work correctly with virtual threads.
   */
  @Test
  public void testCheckpointWithVirtualThreads() throws Exception {
    AtomicBoolean checkpointExecuted = new AtomicBoolean(false);

    // Create a test checkpoint
    TestCheckpoint checkpoint = new TestCheckpoint(checkpointExecuted);

    // Execute the checkpoint in a virtual thread
    supplyFromVirtualThread(() -> {
      try {
        // Verify we're running in a virtual thread
        assertCurrentThreadIsVirtual();

        // Execute the checkpoint
        checkpoint.begin(getConnection());
        checkpoint.end(getConnection());
        return true;
      } catch (Exception e) {
        log.error("Checkpoint failed", e);
        fail("Checkpoint failed with exception: " + e.getMessage());
        return false;
      }
    });

    // Verify checkpoint was executed
    assertTrue(checkpointExecuted.get(), "Checkpoint should have been executed");
  }

  /**
   * Tests that upgrade operations with long-running I/O don't cause thread pinning issues.
   */
  @Test
  public void testUpgradeWithLongRunningIO() throws Exception {
    // Create a test migration step with simulated I/O
    DatabaseMigrationStep ioMigration = new DatabaseMigrationStep() {
      @Override
      public Optional<String> version() {
        return Optional.of("3.0");
      }

      @Override
      public void migrate(Connection connection) throws Exception {
        // Verify we're running in a virtual thread
        assertTrue(Thread.currentThread().isVirtual(),
            "Migration should be running in a virtual thread");

        // Simulate I/O operation by sleeping
        // This should not pin the virtual thread if properly implemented
        Thread.sleep(500);

        // Execute database operation after I/O
        try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
          stmt.setInt(1, 200);
          stmt.setString(2, "3.0");
          stmt.setString(3, "After I/O");
          stmt.executeUpdate();
        }
      }
    };

    // Create upgrade manager with the I/O migration step
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor,
        singletonList(ioMigration));

    // Execute the upgrade in a virtual thread
    supplyFromVirtualThread(() -> {
      try {
        upgradeManager.migrate();
        return true;
      } catch (Exception e) {
        log.error("I/O upgrade failed", e);
        fail("I/O upgrade failed with exception: " + e.getMessage());
        return false;
      }
    });

    // Verify data was inserted after I/O
    try (Connection conn = getConnection()) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(1));
    }
  }

  /**
   * Tests that multiple concurrent upgrades with mixed I/O and CPU operations work correctly.
   */
  @Test
  public void testMixedWorkloadUpgrades() throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_UPGRADES);
    List<DatabaseMigrationStep> migrations = new ArrayList<>();

    // Create multiple migration steps with mixed workloads
    for (int i = 0; i < CONCURRENT_UPGRADES; i++) {
      final int upgradeId = i;
      final boolean isIOBound = i % 2 == 0; // Alternate between I/O and CPU bound

      migrations.add(new DatabaseMigrationStep() {
        @Override
        public Optional<String> version() {
          return Optional.of("4." + upgradeId);
        }

        @Override
        public void migrate(Connection connection) throws Exception {
          // Wait for all upgrades to start at the same time
          startLatch.await();

          // Verify we're running in a virtual thread
          assertTrue(Thread.currentThread().isVirtual(),
              "Migration should be running in a virtual thread");

          if (isIOBound) {
            // Simulate I/O bound operation
            Thread.sleep(200 + (upgradeId % 5) * 50); // Varied sleep times
          } else {
            // Simulate CPU bound operation
            // This is a simple computation that shouldn't cause pinning
            long result = 0;
            for (int j = 0; j < 100000; j++) {
              result += j;
            }
          }

          // Execute database operation
          try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
            stmt.setInt(1, upgradeId);
            stmt.setString(2, "4." + upgradeId);
            stmt.setString(3, isIOBound ? "I/O Bound" : "CPU Bound");
            stmt.executeUpdate();
          } finally {
            completionLatch.countDown();
          }
        }
      });
    }

    // Create upgrade manager with all migration steps
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, migrations);

    // Submit the upgrade to be executed with virtual threads
    virtualThreadExecutor.submit(() -> {
      try {
        // Start all upgrades simultaneously
        startLatch.countDown();

        // Execute the upgrades
        upgradeManager.migrate();
      } catch (Exception e) {
        log.error("Mixed workload upgrades failed", e);
      }
    });

    // Wait for all upgrades to complete
    assertTrue(completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "All mixed workload upgrades should complete within the timeout period");

    // Verify all data was inserted
    try (Connection conn = getConnection()) {
      var stmt = conn.createStatement();
      var rs = stmt.executeQuery(SELECT_COUNT_SQL);
      assertTrue(rs.next(), "Result set should have at least one row");
      assertThat(rs.getInt(1), is(CONCURRENT_UPGRADES));
    }
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
   * Test implementation of DatabaseMigrationStep for upgrade testing.
   */
  private class TestMigrationStep implements DatabaseMigrationStep {
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }

    @Override
    public void migrate(Connection connection) throws Exception {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(),
          "Migration should be running in a virtual thread");

      // Execute a simple migration that creates a record
      try (var stmt = connection.prepareStatement(INSERT_DATA_SQL)) {
        stmt.setInt(1, 1);
        stmt.setString(2, "1.0");
        stmt.setString(3, "Completed");
        stmt.executeUpdate();
      }
    }

    public boolean isH2(Connection connection) throws SQLException {
      return connection.getMetaData().getDatabaseProductName().contains("H2");
    }
  }

  /**
   * Test implementation of Checkpoint for checkpoint testing.
   */
  private static class TestCheckpoint implements Checkpoint
  {
    private final AtomicBoolean executed;

    public TestCheckpoint(AtomicBoolean executed) {
      this.executed = executed;
    }

    //@Override
    public void begin(Connection connection) throws Exception {
      // Verify we're running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(),
          "Checkpoint should be running in a virtual thread");

      executed.set(true);
    }

    //@Override
    public void end(Connection connection) throws Exception {
      // Verify we're still running in a virtual thread
      assertTrue(Thread.currentThread().isVirtual(),
          "Checkpoint should be running in a virtual thread");
    }

    @Override
    public void begin(String version) throws Exception {

    }

    @Override
    public void commit() throws Exception {

    }

    @Override
    public void rollback() throws Exception {

    }

    @Override
    public void end() {

    }
  }

  /**
   * Marker interface for Java 21 tests.
   */
  public interface Java21TestGroup {
    // Marker interface
  }

  /**
   * Marker interface for Virtual Thread tests.
   */
  public interface VirtualThreadTestGroup {
    // Marker interface
  }
}