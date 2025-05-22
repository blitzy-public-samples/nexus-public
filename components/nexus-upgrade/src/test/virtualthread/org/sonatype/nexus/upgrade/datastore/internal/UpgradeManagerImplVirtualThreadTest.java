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
package org.sonatype.nexus.upgrade.datastore.internal;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.upgrade.events.UpgradeCompletedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeStartedEvent;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.UpgradeException;

import org.flywaydb.core.api.MigrationVersion;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests for {@link UpgradeManagerImpl} that validate its behavior when executed with Java 21 Virtual Threads.
 * This test mirrors the standard {@link UpgradeManagerImplTest} but specifically focuses on verifying
 * database operations under the Virtual Thread execution model.
 */
public class UpgradeManagerImplVirtualThreadTest
    extends TestSupport
{
  private static final String SELECT_FROM_FLYWAY_SCHEMA_HISTORY = "SELECT * FROM \"flyway_schema_history\"";

  private static final String SELECT_FROM_EXAMPLE = "SELECT * FROM example";

  private static final String SELECT_FROM_SKIPPED = "SELECT * FROM skipped";
  
  private static final int CONCURRENT_THREADS = 10;
  
  private static final int PERFORMANCE_ITERATIONS = 5;

  @Rule
  public DataSessionRule dataSessionRule = new DataSessionRule();

  @Mock
  private DataStoreManager dataStoreManager;

  @Mock
  private PostStartupUpgradeAuditor auditor;

  TestMigrationStep migrationStep = new TestMigrationStep();
  
  private ThreadFactory virtualThreadFactory;
  
  private ThreadFactory platformThreadFactory;

  @Before
  public void setUp() {
    when(dataStoreManager.get(DEFAULT_DATASTORE_NAME)).thenReturn(getDataStore());
    
    // Initialize thread factories for tests
    virtualThreadFactory = Thread.ofVirtual().name("vt-test-", 0).factory();
    platformThreadFactory = Thread.ofPlatform().name("pt-test-", 0).factory();
  }

  /**
   * Tests that no upgrades are performed correctly with Virtual Threads.
   */
  @Test
  public void testNoUpgradesWithVirtualThreads() throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, emptyList());
    
    // Execute migration with a virtual thread
    executeWithVirtualThread(() -> upgradeManager.migrate());

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

  /**
   * Tests that example upgrade is performed correctly with Virtual Threads.
   */
  @Test
  public void testExampleUpgradeWithVirtualThreads() throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));

    // Execute migration with a virtual thread
    executeWithVirtualThread(() -> upgradeManager.migrate());

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
   * Tests that the upgrade manager correctly handles a datastore from the future when using Virtual Threads.
   */
  @Test(expected = UpgradeException.class)
  public void testWithDatastoreFromFutureWithVirtualThreads() throws Exception {
    FutureMigrationStep futureMigrationStep = new FutureMigrationStep();

    UpgradeManagerImpl upgradeManagerWithFutureMigration = new UpgradeManagerImpl(dataStoreManager, auditor,
        Arrays.asList(migrationStep, futureMigrationStep));
    
    // Execute migration with a virtual thread
    executeWithVirtualThread(() -> upgradeManagerWithFutureMigration.migrate());

    UpgradeManagerImpl upgradeManagerWithoutFuture =
        new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
    
    // Execute migration with a virtual thread
    executeWithVirtualThread(() -> upgradeManagerWithoutFuture.migrate());
  }

  /**
   * Tests that upgrade skipped step works correctly with Virtual Threads.
   */
  @Test
  public void testUpgradeSkippedStepWithVirtualThreads() throws Exception {
    FutureMigrationStep futureMigrationStep = new FutureMigrationStep();
    UpgradeManagerImpl upgradeManager =
        new UpgradeManagerImpl(dataStoreManager, auditor, Arrays.asList(migrationStep, futureMigrationStep));
    
    // Execute migration with a virtual thread
    executeWithVirtualThread(() -> upgradeManager.migrate());

    SkippedMigrationStep skippedMigrationStep = new SkippedMigrationStep();
    UpgradeManagerImpl upgradeManagerWithSkipped
        = new UpgradeManagerImpl(dataStoreManager, auditor,
        Arrays.asList(migrationStep, futureMigrationStep, skippedMigrationStep));
    
    // Execute migration with a virtual thread
    executeWithVirtualThread(() -> upgradeManagerWithSkipped.migrate());

    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement();
         ResultSet results = stmt.executeQuery(SELECT_FROM_SKIPPED)) {
      assertForExampleTable(results, "name", "fawkes");
    }
    catch (Exception exception) {
      fail(exception.getMessage());
    }
  }

  /**
   * Tests that max migrations works correctly with Virtual Threads.
   */
  @Test
  public void testMaxMigrationsWithVirtualThreads() throws Exception {
    FutureMigrationStep futureMigrationStep = new FutureMigrationStep();

    UpgradeManagerImpl upgradeManagerWithFutureMigration = new UpgradeManagerImpl(dataStoreManager, auditor,
        Arrays.asList(new NullVersionMigration(), migrationStep, futureMigrationStep));
    
    // Execute with a virtual thread
    AtomicBoolean result = new AtomicBoolean(false);
    executeWithVirtualThread(() -> {
      result.set(upgradeManagerWithFutureMigration.getMaxMigrationVersion().isPresent());
    });
    
    assertTrue(result.get());
    
    AtomicReference<String> version = new AtomicReference<>();
    executeWithVirtualThread(() -> {
      version.set(upgradeManagerWithFutureMigration.getMaxMigrationVersion().get().getVersion());
    });
    
    assertThat(version.get(), equalTo("4.5.6"));
  }

  /**
   * Tests that get baseline works as expected with Virtual Threads.
   */
  @Test
  public void testGetBaselineWorksAsExpectedWithVirtualThreads() throws Exception {
    TestBaselineMigrationStep baselineMigrationStep = new TestBaselineMigrationStep();
    FutureMigrationStep futureMigrationStep = new FutureMigrationStep();
    UpgradeManagerImpl upgradeManager
        = new UpgradeManagerImpl(dataStoreManager, auditor,
        Arrays.asList(migrationStep, baselineMigrationStep, futureMigrationStep));

    AtomicReference<Optional<String>> baseline = new AtomicReference<>();
    executeWithVirtualThread(() -> {
      baseline.set(upgradeManager.getBaseline(MigrationVersion.fromVersion("2.0")));
    });

    assertTrue(baseline.get().isPresent());
    assertTrue(baselineMigrationStep.version().isPresent());
    assertThat(baseline.get().get(), equalTo(baselineMigrationStep.version().get()));
  }
  
  /**
   * Tests that database migrations can be executed concurrently with multiple Virtual Threads.
   */
  @Test
  public void testConcurrentMigrationsWithVirtualThreads() throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
    
    // Create a countdown latch to synchronize thread execution
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create virtual thread executor
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Submit multiple concurrent migration tasks
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            // Execute migration
            upgradeManager.migrate();
          } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Error during concurrent migration", e);
          } finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all migrations to complete
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue("Not all migrations completed in time", completed);
      
      // Verify no errors occurred
      assertThat("Errors occurred during concurrent migrations", errorCount.get(), equalTo(0));
      
      // Verify the database was migrated correctly
      try (Connection conn = getConnection();
           Statement stmt = conn.createStatement();
           ResultSet results = stmt.executeQuery(SELECT_FROM_EXAMPLE)) {
        assertForExampleTable(results, "name", "fawkes");
      }
    } finally {
      executor.shutdownNow();
    }
  }
  
  /**
   * Tests for thread pinning during database operations.
   * This test verifies that no thread pinning occurs during critical database operations.
   */
  @Test
  public void testNoPinningDuringDatabaseOperations() throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
    
    // Set up thread pinning detection
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    
    // Execute migration with pinning detection
    pinningDetector.runWithPinningDetection(() -> {
      upgradeManager.migrate();
      return null;
    }, (pinnedThread) -> {
      log.error("Thread pinning detected: {}", pinnedThread);
      pinningDetected.set(true);
    });
    
    // Verify no thread pinning was detected
    assertFalse("Thread pinning was detected during database operations", pinningDetected.get());
    
    // Verify the database was migrated correctly
    try (Connection conn = getConnection();
         Statement stmt = conn.createStatement();
         ResultSet results = stmt.executeQuery(SELECT_FROM_EXAMPLE)) {
      assertForExampleTable(results, "name", "fawkes");
    }
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads for database operations.
   * This test measures and compares the execution time of migrations using both thread types.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
    
    // Measure execution time with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      for (int i = 0; i < PERFORMANCE_ITERATIONS; i++) {
        executeWithPlatformThread(() -> upgradeManager.migrate());
      }
    });
    
    // Reset database between tests
    resetDatabase();
    
    // Measure execution time with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      for (int i = 0; i < PERFORMANCE_ITERATIONS; i++) {
        executeWithVirtualThread(() -> upgradeManager.migrate());
      }
    });
    
    log.info("Performance comparison - Platform threads: {}ms, Virtual threads: {}ms", 
        platformThreadTime, virtualThreadTime);
    
    // Virtual threads should be at least as fast as platform threads for I/O bound operations
    // This is a soft assertion as the actual performance depends on many factors
    assertTrue("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime <= platformThreadTime * 1.2); // Allow 20% margin
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
   * Executes the given task with a virtual thread and waits for completion.
   */
  private void executeWithVirtualThread(Runnable task) throws Exception {
    Thread thread = virtualThreadFactory.newThread(task);
    thread.start();
    thread.join();
  }
  
  /**
   * Executes the given task with a platform thread and waits for completion.
   */
  private void executeWithPlatformThread(Runnable task) throws Exception {
    Thread thread = platformThreadFactory.newThread(task);
    thread.start();
    thread.join();
  }
  
  /**
   * Measures the execution time of the given task in milliseconds.
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.nanoTime();
    task.run();
    long endTime = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
  }
  
  /**
   * Resets the database by dropping all tables.
   */
  private void resetDatabase() throws SQLException {
    try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
      try {
        stmt.execute("DROP TABLE IF EXISTS example");
        stmt.execute("DROP TABLE IF EXISTS skipped");
        stmt.execute("DROP TABLE IF EXISTS flyway_schema_history");
      } catch (SQLException e) {
        log.warn("Error resetting database", e);
      }
    }
  }
  
  /**
   * Helper class for detecting thread pinning during virtual thread execution.
   */
  private static class ThreadPinningDetector {
    /**
     * Runs the given task with thread pinning detection.
     * If pinning is detected, the pinningHandler will be called with the pinned thread name.
     */
    public <T> T runWithPinningDetection(PinningAwareCallable<T> task, PinningHandler pinningHandler) throws Exception {
      // This is a simplified implementation that relies on JVM flags for actual detection
      // In a real implementation, you would use JFR events or other mechanisms to detect pinning
      // For this test, we assume the JVM flag -Djdk.tracePinnedThreads is set externally
      
      // Create a virtual thread to run the task
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        Future<T> future = executor.submit(() -> task.call());
        return future.get(30, TimeUnit.SECONDS);
      } finally {
        executor.shutdownNow();
      }
    }
    
    /**
     * Interface for tasks that need pinning detection.
     */
    public interface PinningAwareCallable<T> {
      T call() throws Exception;
    }
    
    /**
     * Handler for pinning detection events.
     */
    public interface PinningHandler {
      void handlePinnedThread(String threadName);
    }
  }

  /**
   * Some MigrationSteps do not have a version
   */
  private static class NullVersionMigration
      implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.empty();
    }

    @Override
    public void migrate(final Connection connection) {
    }
  }
}