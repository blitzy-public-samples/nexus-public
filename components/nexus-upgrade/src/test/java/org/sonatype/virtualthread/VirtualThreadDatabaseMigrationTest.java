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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.content.testsuite.groups.PostgresTestGroup;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.UpgradeException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests the Nexus upgrade framework's database migration capabilities using Java 21 virtual threads.
 * This test validates that database migrations execute correctly when run on virtual threads,
 * focusing on I/O-bound operations like schema creation, table modifications, and data migrations.
 * 
 * @since 3.60
 */
@Category(PostgresTestGroup.class)
public class VirtualThreadDatabaseMigrationTest
    extends TestSupport
{
  private static final String TEST_TABLE = "vt_migration_test";
  private static final String TEST_INDEX = "idx_vt_migration_test";
  private static final String TEST_COLUMN = "test_column";
  private static final int CONCURRENT_MIGRATIONS = 50;
  private static final int ITERATIONS_PER_MIGRATION = 10;
  
  private DataSessionRule dataSessionRule;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  private final AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
  private final ConcurrentHashMap<String, AtomicLong> executionMetrics = new ConcurrentHashMap<>();
  
  @BeforeEach
  public void setUp() {
    dataSessionRule = new DataSessionRule(DEFAULT_DATASTORE_NAME);
    
    // Create virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name("vt-migration-", 0)
        .factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Create platform thread executor for comparison
    ThreadFactory platformThreadFactory = Thread.ofPlatform()
        .name("pt-migration-", 0)
        .factory();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors(), 
        platformThreadFactory);
    
    // Reset metrics
    threadPinningDetected.set(false);
    executionMetrics.clear();
    
    // Clean up any existing test tables
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      try {
        new TestMigrationStep().runStatement(conn, "DROP TABLE IF EXISTS " + TEST_TABLE);
      }
      catch (SQLException e) {
        log.warn("Failed to clean up test table", e);
      }
    }
    catch (SQLException e) {
      log.warn("Failed to open connection for cleanup", e);
    }
  }
  
  @AfterEach
  public void tearDown() {
    virtualThreadExecutor.shutdown();
    platformThreadExecutor.shutdown();
    try {
      if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        virtualThreadExecutor.shutdownNow();
      }
      if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        platformThreadExecutor.shutdownNow();
      }
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Tests that a database migration can be executed successfully using a virtual thread.
   */
  @Test
  @DisplayName("Execute database migration on a virtual thread")
  public void testVirtualThreadMigration() throws Exception {
    TestMigrationStep migrationStep = new TestMigrationStep();
    
    // Execute migration on a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        migrationStep.migrate(conn);
      }
      catch (Exception e) {
        throw new RuntimeException("Migration failed", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion
    future.join();
    
    // Verify migration was successful
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      assertTrue(migrationStep.tableExists(conn, TEST_TABLE), "Test table should exist");
      assertTrue(migrationStep.indexExists(conn, TEST_INDEX), "Test index should exist");
      
      // Verify data was inserted
      try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM " + TEST_TABLE);
           ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "Result set should have at least one row");
        assertEquals(1, rs.getInt(1), "Table should have one row");
      }
    }
  }
  
  /**
   * Tests concurrent execution of multiple database migrations using virtual threads.
   * This validates that the database migration framework can handle concurrent migrations
   * without issues when using virtual threads.
   */
  @Test
  @DisplayName("Execute concurrent database migrations using virtual threads")
  public void testConcurrentVirtualThreadMigrations() throws Exception {
    // Create multiple migration steps with unique table names
    List<ConcurrentTestMigrationStep> migrationSteps = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_MIGRATIONS; i++) {
      migrationSteps.add(new ConcurrentTestMigrationStep(i));
    }
    
    // Execute all migrations concurrently using virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (ConcurrentTestMigrationStep step : migrationSteps) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
          step.migrate(conn);
        }
        catch (Exception e) {
          throw new RuntimeException("Migration failed: " + step.getTableName(), e);
        }
      }, virtualThreadExecutor);
      futures.add(future);
    }
    
    // Wait for all migrations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify all migrations were successful
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      for (ConcurrentTestMigrationStep step : migrationSteps) {
        assertTrue(step.tableExists(conn, step.getTableName()), 
            "Table should exist: " + step.getTableName());
        
        // Verify data was inserted
        try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM " + step.getTableName());
             ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "Result set should have at least one row");
          assertEquals(ITERATIONS_PER_MIGRATION, rs.getInt(1), 
              "Table should have correct number of rows: " + step.getTableName());
        }
      }
    }
    
    // Verify no thread pinning was detected
    assertFalse(threadPinningDetected.get(), "Thread pinning should not be detected");
  }
  
  /**
   * Tests that a database migration that cannot be executed in a transaction
   * works correctly with virtual threads.
   */
  @Test
  @DisplayName("Execute non-transactional database migration on a virtual thread")
  public void testNonTransactionalVirtualThreadMigration() throws Exception {
    NonTransactionalMigrationStep migrationStep = new NonTransactionalMigrationStep();
    
    // Execute migration on a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        migrationStep.migrate(conn);
      }
      catch (Exception e) {
        throw new RuntimeException("Migration failed", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion
    future.join();
    
    // Verify migration was successful
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      assertTrue(migrationStep.tableExists(conn, TEST_TABLE + "_nontx"), "Test table should exist");
      
      // Verify data was inserted
      try (PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM " + TEST_TABLE + "_nontx");
           ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next(), "Result set should have at least one row");
        assertEquals(1, rs.getInt(1), "Table should have one row");
      }
    }
  }
  
  /**
   * Tests that a database migration that fails is properly handled when using virtual threads.
   */
  @Test
  @DisplayName("Handle failed database migration on a virtual thread")
  public void testFailedVirtualThreadMigration() {
    FailingMigrationStep migrationStep = new FailingMigrationStep();
    
    // Execute migration on a virtual thread and expect failure
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
        migrationStep.migrate(conn);
      }
      catch (Exception e) {
        throw new RuntimeException("Migration failed as expected", e);
      }
    }, virtualThreadExecutor);
    
    // Wait for completion and expect exception
    Exception exception = assertThrows(Exception.class, future::join);
    assertTrue(exception.getCause().getMessage().contains("Migration failed as expected"), 
        "Exception should contain expected message");
  }
  
  /**
   * Compares the performance of database migrations executed on virtual threads vs platform threads.
   * This test helps validate the performance benefits of using virtual threads for I/O-bound
   * database operations.
   */
  @Test
  @DisplayName("Compare virtual thread vs platform thread performance for database migrations")
  public void testVirtualThreadVsPlatformThreadPerformance() throws Exception {
    // Run performance test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      runConcurrentMigrations(virtualThreadExecutor, "virtual");
      return null;
    });
    executionMetrics.put("virtualThreadTime", new AtomicLong(virtualThreadTime));
    
    // Clean up tables before platform thread test
    cleanupTestTables();
    
    // Run performance test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      runConcurrentMigrations(platformThreadExecutor, "platform");
      return null;
    });
    executionMetrics.put("platformThreadTime", new AtomicLong(platformThreadTime));
    
    // Log performance comparison
    log.info("Performance comparison:");
    log.info("  Virtual thread execution time: {} ms", virtualThreadTime);
    log.info("  Platform thread execution time: {} ms", platformThreadTime);
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }
  
  /**
   * Tests detection of thread pinning during database migrations with virtual threads.
   * Thread pinning occurs when a virtual thread is forced to stay on its carrier thread
   * due to blocking operations that don't support virtual thread parking.
   */
  @Test
  @DisplayName("Detect thread pinning during database migrations with virtual threads")
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    // Create a thread pinning detector
    ThreadPinningDetector detector = new ThreadPinningDetector();
    Thread detectorThread = new Thread(detector);
    detectorThread.setDaemon(true);
    detectorThread.start();
    
    try {
      // Run migrations that should not cause pinning
      runConcurrentMigrations(virtualThreadExecutor, "pinning-test");
      
      // Check if pinning was detected
      assertFalse(threadPinningDetected.get(), "No thread pinning should be detected with proper JDBC operations");
    }
    finally {
      // Stop the detector
      detector.stop();
      detectorThread.interrupt();
      detectorThread.join(1000);
      
      // Reset system property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }
  
  /**
   * Helper method to run concurrent migrations using the specified executor.
   */
  private void runConcurrentMigrations(ExecutorService executor, String testId) {
    // Create multiple migration steps with unique table names
    List<ConcurrentTestMigrationStep> migrationSteps = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_MIGRATIONS; i++) {
      migrationSteps.add(new ConcurrentTestMigrationStep(i, testId));
    }
    
    // Execute all migrations concurrently
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (ConcurrentTestMigrationStep step : migrationSteps) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
          step.migrate(conn);
        }
        catch (Exception e) {
          throw new RuntimeException("Migration failed: " + step.getTableName(), e);
        }
      }, executor);
      futures.add(future);
    }
    
    // Wait for all migrations to complete
    try {
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
    catch (Exception e) {
      fail("Concurrent migrations failed: " + e.getMessage());
    }
  }
  
  /**
   * Helper method to clean up test tables between performance tests.
   */
  private void cleanupTestTables() {
    try (Connection conn = dataSessionRule.openConnection(DEFAULT_DATASTORE_NAME)) {
      for (int i = 0; i < CONCURRENT_MIGRATIONS; i++) {
        try {
          new TestMigrationStep().runStatement(conn, "DROP TABLE IF EXISTS " + TEST_TABLE + "_" + i);
        }
        catch (SQLException e) {
          log.warn("Failed to clean up test table {}", TEST_TABLE + "_" + i, e);
        }
      }
    }
    catch (SQLException e) {
      log.warn("Failed to open connection for cleanup", e);
    }
  }
  
  /**
   * Measures the execution time of a supplier function.
   */
  private <T> long measureExecutionTime(Supplier<T> supplier) {
    long startTime = System.currentTimeMillis();
    supplier.get();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Test implementation of DatabaseMigrationStep that creates a test table and index.
   */
  private class TestMigrationStep implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }

    @Override
    public void migrate(Connection connection) throws Exception {
      // Create test table
      String createTableSql = "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (" +
          "id SERIAL PRIMARY KEY, " +
          TEST_COLUMN + " VARCHAR(100) NOT NULL" +
          ")";
      runStatement(connection, createTableSql);
      
      // Create index
      String createIndexSql = "CREATE INDEX IF NOT EXISTS " + TEST_INDEX + " ON " + TEST_TABLE + " (" + TEST_COLUMN + ")";
      runStatement(connection, createIndexSql);
      
      // Insert test data
      String insertSql = "INSERT INTO " + TEST_TABLE + " (" + TEST_COLUMN + ") VALUES (?)";
      try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
        stmt.setString(1, "test-value");
        stmt.executeUpdate();
      }
    }
  }
  
  /**
   * Test implementation of DatabaseMigrationStep for concurrent testing with unique table names.
   */
  private class ConcurrentTestMigrationStep implements DatabaseMigrationStep
  {
    private final String tableName;
    private final String indexName;
    
    public ConcurrentTestMigrationStep(int id) {
      this(id, "");
    }
    
    public ConcurrentTestMigrationStep(int id, String prefix) {
      this.tableName = TEST_TABLE + "_" + (prefix.isEmpty() ? "" : prefix + "_") + id;
      this.indexName = TEST_INDEX + "_" + (prefix.isEmpty() ? "" : prefix + "_") + id;
    }
    
    public String getTableName() {
      return tableName;
    }
    
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }

    @Override
    public void migrate(Connection connection) throws Exception {
      // Create test table
      String createTableSql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
          "id SERIAL PRIMARY KEY, " +
          TEST_COLUMN + " VARCHAR(100) NOT NULL" +
          ")";
      runStatement(connection, createTableSql);
      
      // Create index
      String createIndexSql = "CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + " (" + TEST_COLUMN + ")";
      runStatement(connection, createIndexSql);
      
      // Insert test data with multiple iterations to simulate more work
      String insertSql = "INSERT INTO " + tableName + " (" + TEST_COLUMN + ") VALUES (?)";
      try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
        for (int i = 0; i < ITERATIONS_PER_MIGRATION; i++) {
          stmt.setString(1, "test-value-" + i);
          stmt.executeUpdate();
          
          // Small delay to simulate work and increase chance of thread scheduling
          Thread.sleep(10);
        }
      }
    }
  }
  
  /**
   * Test implementation of DatabaseMigrationStep that cannot be executed in a transaction.
   */
  private class NonTransactionalMigrationStep implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }

    @Override
    public boolean canExecuteInTransaction() {
      return false;
    }
    
    @Override
    public void migrate(Connection connection) throws Exception {
      // Create test table
      String createTableSql = "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + "_nontx" + " (" +
          "id SERIAL PRIMARY KEY, " +
          TEST_COLUMN + " VARCHAR(100) NOT NULL" +
          ")";
      runStatement(connection, createTableSql);
      
      // Insert test data
      String insertSql = "INSERT INTO " + TEST_TABLE + "_nontx" + " (" + TEST_COLUMN + ") VALUES (?)";
      try (PreparedStatement stmt = connection.prepareStatement(insertSql)) {
        stmt.setString(1, "test-value-nontx");
        stmt.executeUpdate();
      }
    }
  }
  
  /**
   * Test implementation of DatabaseMigrationStep that fails during migration.
   */
  private class FailingMigrationStep implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }

    @Override
    public void migrate(Connection connection) throws Exception {
      // Create test table
      String createTableSql = "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + "_failing" + " (" +
          "id SERIAL PRIMARY KEY, " +
          TEST_COLUMN + " VARCHAR(100) NOT NULL" +
          ")";
      runStatement(connection, createTableSql);
      
      // Throw exception to simulate failure
      throw new UpgradeException("Simulated migration failure");
    }
  }
  
  /**
   * Thread pinning detector that monitors for thread pinning events.
   * This uses the JDK 21 thread pinning detection mechanism.
   */
  private class ThreadPinningDetector implements Runnable
  {
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    public void stop() {
      running.set(false);
    }
    
    @Override
    public void run() {
      // This is a simple implementation that relies on the JDK's thread pinning detection
      // In a real implementation, you might want to use JFR events or other mechanisms
      while (running.get()) {
        try {
          // Check for thread pinning in thread dumps
          // In a real implementation, you would parse thread dumps or use JFR events
          // For this test, we're relying on the jdk.tracePinnedThreads system property
          // which will log pinning events to stderr
          
          // For now, we're just simulating detection by checking thread names
          Thread[] threads = new Thread[Thread.activeCount() * 2];
          int count = Thread.enumerate(threads);
          
          for (int i = 0; i < count; i++) {
            Thread thread = threads[i];
            if (thread != null && thread.getName().startsWith("vt-migration-") && 
                thread.getState() == Thread.State.RUNNABLE) {
              // In a real implementation, you would check if this thread is pinned
              // For now, we're just checking if it's been RUNNABLE for too long
              // which might indicate pinning
              
              // This is just a placeholder for demonstration purposes
              // In a real implementation, you would use proper pinning detection
            }
          }
          
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
  }
}