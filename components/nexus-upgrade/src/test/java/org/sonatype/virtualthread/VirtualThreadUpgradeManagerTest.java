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
package org.sonatype.virtualthread;

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
import org.sonatype.nexus.upgrade.datastore.internal.UpgradeManagerImpl;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.emptyList;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the Nexus upgrade manager's ability to handle concurrent upgrade operations using Java 21 virtual threads.
 * This test validates that the upgrade manager can correctly sequence, execute, and monitor multiple upgrade tasks
 * simultaneously using virtual threads, ensuring proper event publication, transaction handling, and error recovery
 * under high concurrency scenarios.
 */
@ExtendWith(MockitoExtension.class)
class VirtualThreadUpgradeManagerTest
    extends TestSupport
{
  private static final String SELECT_FROM_FLYWAY_SCHEMA_HISTORY = "SELECT * FROM \"flyway_schema_history\"";
  private static final String SELECT_FROM_EXAMPLE = "SELECT * FROM example";
  private static final String SELECT_FROM_CONCURRENT = "SELECT * FROM concurrent";
  private static final String SELECT_FROM_ERROR = "SELECT * FROM error_table";

  private static final int CONCURRENT_TASKS = 50;
  private static final int TIMEOUT_SECONDS = 30;

  @org.junit.jupiter.api.extension.RegisterExtension
  public DataSessionRule dataSessionRule = new DataSessionRule();

  @Mock
  private DataStoreManager dataStoreManager;

  @Mock
  private PostStartupUpgradeAuditor auditor;

  private TestMigrationStep migrationStep;
  private ConcurrentMigrationStep concurrentMigrationStep;
  private ErrorMigrationStep errorMigrationStep;

  @BeforeEach
  void setUp() {
    migrationStep = new TestMigrationStep();
    concurrentMigrationStep = new ConcurrentMigrationStep();
    errorMigrationStep = new ErrorMigrationStep();
    when(dataStoreManager.get(DEFAULT_DATASTORE_NAME)).thenReturn(getDataStore());
  }

  /**
   * Tests that the upgrade manager works correctly with no upgrades when using virtual threads.
   */
  @Test
  void testNoUpgradesWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("upgrade-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, emptyList());
      CompletableFuture.runAsync(upgradeManager::migrate, executor).join();

      try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
        Exception exception = assertThrows(Exception.class,
            () -> stmt.executeQuery(SELECT_FROM_FLYWAY_SCHEMA_HISTORY));

        if ("PostgreSQL".equals(conn.getMetaData().getDatabaseProductName())) {
          assertThat(exception.getMessage(), containsString("relation \"flyway_schema_history\" does not exist"));
        }
        else {
          assertThat(exception.getMessage(), containsString("Table \"flyway_schema_history\" not found"));
        }
      }
      // No changes should fire no events
      verifyNoInteractions(auditor);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the upgrade manager can execute a basic upgrade using virtual threads.
   */
  @Test
  void testBasicUpgradeWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("upgrade-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
      CompletableFuture.runAsync(upgradeManager::migrate, executor).join();

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
          fail("Failed to query flyway_schema_history: " + exception.getMessage());
        }

        // check for the result of the upgrade step
        try (ResultSet results = stmt.executeQuery(SELECT_FROM_EXAMPLE)) {
          assertForExampleTable(results, "name", "fawkes");
        }
        catch (Exception exception) {
          fail("Failed to query example table: " + exception.getMessage());
        }
      }

      // Migrations should trigger events
      verify(auditor).post(any(UpgradeStartedEvent.class));
      verify(auditor).post(any(UpgradeCompletedEvent.class));
      verifyNoMoreInteractions(auditor);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the upgrade manager can handle concurrent upgrades using virtual threads.
   * This test creates multiple concurrent migration steps and verifies they all complete successfully.
   */
  @Test
  void testConcurrentUpgradesWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("upgrade-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
          dataStoreManager, auditor, List.of(migrationStep, concurrentMigrationStep));

      CompletableFuture.runAsync(upgradeManager::migrate, executor).join();

      try (Connection conn = getConnection();
           Statement stmt = conn.createStatement()) {
        // Verify the example table was created
        try (ResultSet results = stmt.executeQuery(SELECT_FROM_EXAMPLE)) {
          assertForExampleTable(results, "name", "fawkes");
        }
        catch (Exception exception) {
          fail("Failed to query example table: " + exception.getMessage());
        }

        // Verify the concurrent table was created with all expected rows
        try (ResultSet results = stmt.executeQuery(SELECT_FROM_CONCURRENT)) {
          int count = 0;
          while (results.next()) {
            count++;
          }
          assertThat(count, equalTo(CONCURRENT_TASKS));
        }
        catch (Exception exception) {
          fail("Failed to query concurrent table: " + exception.getMessage());
        }
      }

      // Verify events were posted correctly
      verify(auditor).post(any(UpgradeStartedEvent.class));
      verify(auditor).post(any(UpgradeCompletedEvent.class));
      verifyNoMoreInteractions(auditor);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the upgrade manager can handle errors during concurrent upgrades using virtual threads.
   * This test creates a migration step that intentionally throws errors and verifies error handling.
   */
  @Test
  void testErrorHandlingWithVirtualThreads() {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("upgrade-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
          dataStoreManager, auditor, List.of(migrationStep, errorMigrationStep));

      Exception exception = assertThrows(UpgradeException.class, () -> {
        CompletableFuture.runAsync(upgradeManager::migrate, executor).join();
      });

      assertThat(exception.getMessage(), containsString("Simulated error in migration"));

      // Verify the started event was posted, but not the completed event
      verify(auditor).post(any(UpgradeStartedEvent.class));
      verifyNoMoreInteractions(auditor);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the upgrade manager can handle cancellation during concurrent upgrades using virtual threads.
   */
  @Test
  void testCancellationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("upgrade-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Create a migration step that can be cancelled
      CancellableMigrationStep cancellableMigrationStep = new CancellableMigrationStep();

      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
          dataStoreManager, auditor, List.of(migrationStep, cancellableMigrationStep));

      // Start the migration in a separate thread
      CompletableFuture<Void> migrationFuture = CompletableFuture.runAsync(upgradeManager::migrate, executor);

      // Wait for the migration to start
      assertTrue(cancellableMigrationStep.awaitStarted(TIMEOUT_SECONDS, TimeUnit.SECONDS));

      // Cancel the migration
      cancellableMigrationStep.cancel();

      // Wait for the migration to complete or timeout
      try {
        migrationFuture.join();
        fail("Migration should have been cancelled");
      }
      catch (Exception e) {
        // Expected exception due to cancellation
        assertThat(e.getMessage(), containsString("cancelled"));
      }

      // Verify the started event was posted, but not the completed event
      verify(auditor).post(any(UpgradeStartedEvent.class));
      verifyNoMoreInteractions(auditor);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Tests that the upgrade manager can handle a mix of platform and virtual threads.
   * This test creates a scenario where some migrations run on platform threads and others on virtual threads.
   */
  @Test
  void testMixedThreadTypesUpgrade() throws Exception {
    // Create a mixed thread executor that uses both platform and virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("v-upgrade-", 0).factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().name("p-upgrade-", 0).factory();

    ExecutorService virtualExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    ExecutorService platformExecutor = Executors.newFixedThreadPool(2, platformThreadFactory);

    try {
      // Create migration steps that will run on different thread types
      MixedThreadMigrationStep mixedThreadStep = new MixedThreadMigrationStep(platformExecutor, virtualExecutor);

      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
          dataStoreManager, auditor, List.of(migrationStep, mixedThreadStep));

      // Run the migration on a virtual thread
      CompletableFuture.runAsync(upgradeManager::migrate, virtualExecutor).join();

      // Verify both platform and virtual thread tasks completed
      assertThat(mixedThreadStep.getPlatformTasksCompleted(), equalTo(5));
      assertThat(mixedThreadStep.getVirtualTasksCompleted(), equalTo(5));

      // Verify events were posted correctly
      verify(auditor).post(any(UpgradeStartedEvent.class));
      verify(auditor).post(any(UpgradeCompletedEvent.class));
      verifyNoMoreInteractions(auditor);
    }
    finally {
      virtualExecutor.shutdown();
      platformExecutor.shutdown();
    }
  }

  /**
   * Tests that the upgrade manager correctly handles record patterns in Java 21.
   * This test uses record patterns to process upgrade results in a more concise way.
   */
  @Test
  void testRecordPatternsWithUpgrades() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("upgrade-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

    try {
      // Create a migration step that returns results as records
      RecordPatternMigrationStep recordPatternStep = new RecordPatternMigrationStep();

      UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(
          dataStoreManager, auditor, List.of(migrationStep, recordPatternStep));

      // Run the migration
      CompletableFuture.runAsync(upgradeManager::migrate, executor).join();

      // Verify the migration results using record patterns
      List<UpgradeResult> results = recordPatternStep.getResults();
      assertThat(results.size(), equalTo(3));

      // Use record patterns to process the results
      int successCount = 0;
      int errorCount = 0;

      for (UpgradeResult result : results) {
        // Using Java 21 record pattern matching
        if (result instanceof UpgradeResult.Success(String message, long timestamp)) {
          successCount++;
          assertTrue(timestamp > 0);
          assertThat(message, containsString("success"));
        } else if (result instanceof UpgradeResult.Error(String message, Exception error)) {
          errorCount++;
          assertThat(message, containsString("error"));
          assertThat(error.getMessage(), containsString("test error"));
        }
      }

      assertThat(successCount, equalTo(2));
      assertThat(errorCount, equalTo(1));

      // Verify events were posted correctly
      verify(auditor).post(any(UpgradeStartedEvent.class));
      verify(auditor).post(any(UpgradeCompletedEvent.class));
      verifyNoMoreInteractions(auditor);
    }
    finally {
      executor.shutdown();
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
   * Basic test migration step that creates a simple example table.
   */
  private static class TestMigrationStep
      implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }

    @Override
    public void migrate(final Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE example (name VARCHAR(100))");
        stmt.execute("INSERT INTO example VALUES ('fawkes')");
      }
    }

    boolean isH2(final Connection connection) throws SQLException {
      return connection.getMetaData().getDatabaseProductName().contains("H2");
    }
  }

  /**
   * Migration step that creates multiple concurrent operations using virtual threads.
   */
  private static class ConcurrentMigrationStep
      implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("2.0");
    }

    @Override
    public void migrate(final Connection connection) throws Exception {
      // Create the table
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE concurrent (id INT, value VARCHAR(100))");
      }

      // Create a virtual thread executor
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("concurrent-migration-", 0).factory();
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);

      try {
        // Create a latch to wait for all tasks to complete
        CountDownLatch latch = new CountDownLatch(CONCURRENT_TASKS);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Submit concurrent tasks
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < CONCURRENT_TASKS; i++) {
          final int id = i;
          futures.add(CompletableFuture.runAsync(() -> {
            try (Connection conn = connection.getMetaData().getConnection();
                 Statement stmt = conn.createStatement()) {
              // Insert a row with this task's ID
              stmt.execute(String.format("INSERT INTO concurrent VALUES (%d, 'value-%d')", id, id));
            }
            catch (Exception e) {
              errorCount.incrementAndGet();
            }
            finally {
              latch.countDown();
            }
          }, executor));
        }

        // Wait for all tasks to complete
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          throw new Exception("Timed out waiting for concurrent tasks to complete");
        }

        // Check for errors
        if (errorCount.get() > 0) {
          throw new Exception("Encountered " + errorCount.get() + " errors during concurrent migration");
        }

        // Wait for all futures to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      }
      finally {
        executor.shutdown();
      }
    }
  }

  /**
   * Migration step that intentionally throws an error to test error handling.
   */
  private static class ErrorMigrationStep
      implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("3.0");
    }

    @Override
    public void migrate(final Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE error_table (id INT, message VARCHAR(100))");
        stmt.execute("INSERT INTO error_table VALUES (1, 'before error')");

        // Throw an exception to simulate an error during migration
        throw new SQLException("Simulated error in migration");
      }
    }
  }

  /**
   * Migration step that can be cancelled to test cancellation handling.
   */
  private static class CancellableMigrationStep
      implements DatabaseMigrationStep
  {
    private final CountDownLatch startedLatch = new CountDownLatch(1);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    @Override
    public Optional<String> version() {
      return Optional.of("4.0");
    }

    @Override
    public void migrate(final Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE cancellable (id INT, status VARCHAR(100))");
        stmt.execute("INSERT INTO cancellable VALUES (1, 'started')");

        // Signal that the migration has started
        startedLatch.countDown();

        // Simulate long-running work that checks for cancellation
        for (int i = 0; i < 100; i++) {
          if (cancelled.get()) {
            throw new InterruptedException("Migration was cancelled");
          }
          Thread.sleep(100);
        }

        // This should not execute if cancelled
        stmt.execute("INSERT INTO cancellable VALUES (2, 'completed')");
      }
    }

    public void cancel() {
      cancelled.set(true);
    }

    public boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException {
      return startedLatch.await(timeout, unit);
    }
  }

  /**
   * Migration step that uses a mix of platform and virtual threads.
   */
  private static class MixedThreadMigrationStep
      implements DatabaseMigrationStep
  {
    private final ExecutorService platformExecutor;
    private final ExecutorService virtualExecutor;
    private final AtomicInteger platformTasksCompleted = new AtomicInteger(0);
    private final AtomicInteger virtualTasksCompleted = new AtomicInteger(0);

    public MixedThreadMigrationStep(ExecutorService platformExecutor, ExecutorService virtualExecutor) {
      this.platformExecutor = platformExecutor;
      this.virtualExecutor = virtualExecutor;
    }

    @Override
    public Optional<String> version() {
      return Optional.of("5.0");
    }

    @Override
    public void migrate(final Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE mixed_threads (id INT, thread_type VARCHAR(100))");
      }

      // Create tasks for both thread types
      List<CompletableFuture<Void>> futures = new ArrayList<>();

      // Platform thread tasks
      for (int i = 0; i < 5; i++) {
        final int id = i;
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = connection.getMetaData().getConnection();
               Statement stmt = conn.createStatement()) {
            stmt.execute(String.format(
                "INSERT INTO mixed_threads VALUES (%d, 'platform-%d')", id, id));
            platformTasksCompleted.incrementAndGet();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, platformExecutor));
      }

      // Virtual thread tasks
      for (int i = 5; i < 10; i++) {
        final int id = i;
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = connection.getMetaData().getConnection();
               Statement stmt = conn.createStatement()) {
            stmt.execute(String.format(
                "INSERT INTO mixed_threads VALUES (%d, 'virtual-%d')", id, id));
            virtualTasksCompleted.incrementAndGet();
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, virtualExecutor));
      }

      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    public int getPlatformTasksCompleted() {
      return platformTasksCompleted.get();
    }

    public int getVirtualTasksCompleted() {
      return virtualTasksCompleted.get();
    }
  }

  /**
   * Sealed interface for upgrade results to demonstrate record pattern matching.
   */
  private sealed interface UpgradeResult {
    record Success(String message, long timestamp) implements UpgradeResult {}
    record Error(String message, Exception error) implements UpgradeResult {}
  }

  /**
   * Migration step that uses record patterns to process results.
   */
  private static class RecordPatternMigrationStep
      implements DatabaseMigrationStep
  {
    private final List<UpgradeResult> results = new ArrayList<>();

    @Override
    public Optional<String> version() {
      return Optional.of("6.0");
    }

    @Override
    public void migrate(final Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE record_patterns (id INT, status VARCHAR(100))");

        // Add a success result
        results.add(new UpgradeResult.Success("First operation success", System.currentTimeMillis()));

        // Add an error result
        results.add(new UpgradeResult.Error("Second operation error", 
            new SQLException("test error")));

        // Add another success result
        results.add(new UpgradeResult.Success("Third operation success", System.currentTimeMillis()));
      }
    }

    public List<UpgradeResult> getResults() {
      return results;
    }
  }
}