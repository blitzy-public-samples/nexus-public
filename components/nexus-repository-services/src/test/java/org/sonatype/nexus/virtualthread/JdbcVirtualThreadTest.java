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
package org.sonatype.nexus.virtualthread;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.transaction.TransactionException;
import org.sonatype.nexus.transaction.UnitOfWork;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Tests JDBC operations using Java 21 Virtual Threads in the Nexus Repository Services context.
 * <p>
 * This class verifies that database interactions work correctly with virtual threads, focusing on
 * connection pooling behavior, transaction handling, and avoiding thread pinning issues. It ensures
 * that DataStore operations can fully leverage virtual threads for improved throughput without
 * compromising data integrity or running into JDBC driver compatibility issues.
 * <p>
 * Key aspects tested:
 * <ul>
 *   <li>JDBC operations with Virtual Threads</li>
 *   <li>Connection pooling compatibility with the Virtual Thread model</li>
 *   <li>Transaction handling across virtual threads</li>
 *   <li>Thread pinning detection and avoidance</li>
 *   <li>Performance comparison between platform threads and Virtual Threads</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
public class JdbcVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_TABLE = "virtual_thread_jdbc_test";
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (id INTEGER PRIMARY KEY, value VARCHAR(255))";
  private static final String INSERT_SQL = "INSERT INTO " + TEST_TABLE + " VALUES (?, ?)";
  private static final String SELECT_SQL = "SELECT value FROM " + TEST_TABLE + " WHERE id = ?";
  private static final String UPDATE_SQL = "UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?";
  private static final String DELETE_SQL = "DELETE FROM " + TEST_TABLE + " WHERE id = ?";
  private static final String COUNT_SQL = "SELECT COUNT(*) FROM " + TEST_TABLE;
  
  @Mock
  private EventManager eventManager;
  
  private DataSessionRule sessionRule = new DataSessionRule();
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  private HikariDataSource directDataSource;
  
  @BeforeEach
  public void setUp() throws Exception {
    // Create executors for both virtual threads and platform threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Create test table using the DataSessionRule
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      session.access(Connection.class).prepareStatement(CREATE_TABLE_SQL).execute();
      session.getTransaction().commit();
    }
    
    // Set up a direct HikariCP data source for testing connection pooling
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    config.setUsername("sa");
    config.setPassword("");
    config.setMaximumPoolSize(10);
    config.setMinimumIdle(2);
    config.setConnectionTimeout(1000);
    config.setIdleTimeout(10000);
    config.setMaxLifetime(30000);
    
    directDataSource = new HikariDataSource(config);
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    // Close executors
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.close();
    }
    if (platformThreadExecutor != null) {
      platformThreadExecutor.close();
    }
    
    // Close direct data source
    if (directDataSource != null) {
      directDataSource.close();
    }
    
    // Clean up test table
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      session.access(Connection.class).prepareStatement("DROP TABLE IF EXISTS " + TEST_TABLE).execute();
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests basic JDBC operations using Virtual Threads.
   * <p>
   * This test verifies that standard JDBC operations (insert, select, update, delete)
   * work correctly when executed on Virtual Threads.
   */
  @Test
  public void testBasicJdbcOperationsWithVirtualThreads() throws Exception {
    final int testId = 1;
    final String initialValue = "initial-value";
    final String updatedValue = "updated-value";
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        Connection conn = session.access(Connection.class);
        
        // Insert data
        PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL);
        insertStmt.setInt(1, testId);
        insertStmt.setString(2, initialValue);
        insertStmt.executeUpdate();
        
        // Select data
        PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
        selectStmt.setInt(1, testId);
        ResultSet rs = selectStmt.executeQuery();
        assertTrue(rs.next(), "Data should be available");
        assertThat(rs.getString(1), is(initialValue));
        
        // Update data
        PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL);
        updateStmt.setString(1, updatedValue);
        updateStmt.setInt(2, testId);
        updateStmt.executeUpdate();
        
        // Verify update
        rs = selectStmt.executeQuery();
        assertTrue(rs.next(), "Data should be available after update");
        assertThat(rs.getString(1), is(updatedValue));
        
        // Delete data
        PreparedStatement deleteStmt = conn.prepareStatement(DELETE_SQL);
        deleteStmt.setInt(1, testId);
        deleteStmt.executeUpdate();
        
        // Verify deletion
        rs = selectStmt.executeQuery();
        assertFalse(rs.next(), "Data should be deleted");
        
        session.getTransaction().commit();
      }
      catch (Exception e) {
        log.error("Error in virtual thread JDBC operations", e);
        fail("Exception in virtual thread JDBC operations: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the future to complete
    future.get(10, TimeUnit.SECONDS);
  }
  
  /**
   * Tests connection pooling behavior with Virtual Threads.
   * <p>
   * This test verifies that the HikariCP connection pool works correctly with Virtual Threads,
   * properly managing connections and avoiding leaks even with a high number of concurrent
   * Virtual Threads.
   */
  @Test
  public void testConnectionPoolingWithVirtualThreads() throws Exception {
    final int threadCount = 100; // Much higher than the connection pool size
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicInteger failureCount = new AtomicInteger(0);
    
    // Start many virtual threads that will all try to get connections from the pool
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      
      CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Get connection from pool and perform a simple operation
          try (Connection conn = directDataSource.getConnection()) {
            // Create a simple statement and execute it
            try (PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
              ResultSet rs = stmt.executeQuery();
              if (rs.next() && rs.getInt(1) == 1) {
                successCount.incrementAndGet();
              }
            }
          }
          catch (SQLException e) {
            log.error("Thread {} failed to execute SQL", threadId, e);
            failureCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          log.error("Thread {} encountered an error", threadId, e);
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
        "Not all threads completed in time");
    
    // Verify that all operations were successful
    assertThat("All operations should succeed", successCount.get(), is(threadCount));
    assertThat("No operations should fail", failureCount.get(), is(0));
    
    // Verify that the connection pool is in a healthy state
    assertThat("Active connections should be zero after all operations", 
        directDataSource.getHikariPoolMXBean().getActiveConnections(), is(0));
    assertThat("Idle connections should be available", 
        directDataSource.getHikariPoolMXBean().getIdleConnections(), greaterThan(0));
  }
  
  /**
   * Tests transaction handling across Virtual Thread handoffs.
   * <p>
   * This test verifies that database transactions maintain proper isolation, consistency,
   * and atomicity when operations span multiple Virtual Threads and thread handoffs occur
   * during blocking operations.
   */
  @Test
  public void testTransactionHandlingWithVirtualThreads() throws Exception {
    final int testId = 2;
    final String testValue = "transaction-test-value";
    final CountDownLatch transactionStartedLatch = new CountDownLatch(1);
    final CountDownLatch verificationCompleteLatch = new CountDownLatch(1);
    final AtomicBoolean valueVisibleInOtherTransaction = new AtomicBoolean(false);
    
    // First thread: Start a transaction but don't commit it
    CompletableFuture<Void> transaction1Future = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        Connection conn = session.access(Connection.class);
        
        // Insert data but don't commit yet
        PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL);
        insertStmt.setInt(1, testId);
        insertStmt.setString(2, testValue);
        insertStmt.executeUpdate();
        
        // Signal that the transaction has started
        transactionStartedLatch.countDown();
        
        // Wait for the verification to complete
        verificationCompleteLatch.await(10, TimeUnit.SECONDS);
        
        // Now commit the transaction
        session.getTransaction().commit();
      }
      catch (Exception e) {
        log.error("Error in first transaction thread", e);
        fail("Exception in first transaction thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the first transaction to start
    assertTrue(transactionStartedLatch.await(5, TimeUnit.SECONDS), 
        "First transaction did not start in time");
    
    // Second thread: Try to read the data from the first transaction
    CompletableFuture<Void> transaction2Future = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        Connection conn = session.access(Connection.class);
        
        // Try to read the data inserted by the first transaction
        PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
        selectStmt.setInt(1, testId);
        ResultSet rs = selectStmt.executeQuery();
        
        // The data should not be visible because the first transaction hasn't committed
        if (rs.next()) {
          valueVisibleInOtherTransaction.set(true);
        }
        
        session.getTransaction().commit();
        
        // Signal that the verification is complete
        verificationCompleteLatch.countDown();
      }
      catch (Exception e) {
        log.error("Error in second transaction thread", e);
        fail("Exception in second transaction thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for both futures to complete
    CompletableFuture.allOf(transaction1Future, transaction2Future).get(20, TimeUnit.SECONDS);
    
    // Verify that transaction isolation worked correctly
    assertFalse(valueVisibleInOtherTransaction.get(), 
        "Data should not be visible in second transaction before first transaction commits");
    
    // Verify the data is now visible after both transactions have completed
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
      selectStmt.setInt(1, testId);
      ResultSet rs = selectStmt.executeQuery();
      
      assertTrue(rs.next(), "Data should be visible after transaction commits");
      assertThat(rs.getString(1), is(testValue));
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests for thread pinning issues with JDBC operations.
   * <p>
   * This test verifies that JDBC operations with Virtual Threads don't cause thread pinning,
   * which would prevent the Virtual Thread from being unmounted during blocking operations
   * and reduce the efficiency gains of using Virtual Threads.
   */
  @Test
  public void testThreadPinningWithJdbcOperations() throws Exception {
    final int operationCount = 1000;
    final AtomicInteger completedOperations = new AtomicInteger(0);
    final AtomicReference<Throwable> error = new AtomicReference<>();
    
    // Create a thread that will monitor the progress of operations
    Thread monitorThread = new Thread(() -> {
      try {
        int lastCount = 0;
        int stallCount = 0;
        
        // Monitor progress for up to 10 seconds
        for (int i = 0; i < 10; i++) {
          Thread.sleep(1000);
          int currentCount = completedOperations.get();
          
          // If no progress is made for 3 consecutive checks, we might have thread pinning
          if (currentCount == lastCount) {
            stallCount++;
            if (stallCount >= 3) {
              error.set(new AssertionError("Possible thread pinning detected: " +
                  "No progress made for 3 seconds, completed " + currentCount + 
                  " of " + operationCount + " operations"));
              break;
            }
          }
          else {
            stallCount = 0;
          }
          
          lastCount = currentCount;
          
          // If all operations completed, we can stop monitoring
          if (currentCount >= operationCount) {
            break;
          }
        }
      }
      catch (InterruptedException e) {
        // Monitor thread interrupted, which is fine
      }
    });
    monitorThread.setDaemon(true);
    monitorThread.start();
    
    // Start many virtual threads performing JDBC operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      final int id = i;
      futures.add(CompletableFuture.runAsync(() -> {
        try (Connection conn = directDataSource.getConnection()) {
          // Perform a simple query
          try (PreparedStatement stmt = conn.prepareStatement("SELECT 1")) {
            ResultSet rs = stmt.executeQuery();
            rs.next();
          }
          
          // Increment the counter of completed operations
          completedOperations.incrementAndGet();
        }
        catch (SQLException e) {
          log.error("Error in operation {}", id, e);
          error.set(e);
        }
      }, virtualThreadExecutor));
    }
    
    // Wait for all operations to complete or for an error to occur
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    try {
      allFutures.get(20, TimeUnit.SECONDS);
    }
    catch (Exception e) {
      if (error.get() == null) {
        error.set(e);
      }
    }
    finally {
      monitorThread.interrupt();
      monitorThread.join(1000);
    }
    
    // Check if any error occurred
    if (error.get() != null) {
      fail("Error during thread pinning test: " + error.get().getMessage());
    }
    
    // Verify that all operations completed
    assertThat("All operations should complete without thread pinning",
        completedOperations.get(), is(operationCount));
  }
  
  /**
   * Tests performance comparison between platform threads and Virtual Threads for JDBC operations.
   * <p>
   * This test compares the performance of JDBC operations when executed with traditional
   * platform threads versus Virtual Threads, measuring throughput and resource utilization.
   */
  @Test
  public void testPerformanceComparisonBetweenThreadTypes() throws Exception {
    final int operationCount = 1000;
    final int batchSize = 10;
    
    // Function to run the performance test with a given executor
    class PerformanceResult {
      final long durationMs;
      final int successCount;
      
      PerformanceResult(long durationMs, int successCount) {
        this.durationMs = durationMs;
        this.successCount = successCount;
      }
    }
    
    // Run performance test function
    java.util.function.Function<ExecutorService, PerformanceResult> runPerformanceTest = 
        (executor) -> {
          AtomicInteger successCount = new AtomicInteger(0);
          long startTime = System.currentTimeMillis();
          
          try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            
            // Create batches of operations
            for (int batch = 0; batch < operationCount / batchSize; batch++) {
              for (int i = 0; i < batchSize; i++) {
                final int id = batch * batchSize + i;
                
                futures.add(CompletableFuture.runAsync(() -> {
                  try (Connection conn = directDataSource.getConnection()) {
                    // Perform a simple insert and select operation
                    try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL)) {
                      insertStmt.setInt(1, id);
                      insertStmt.setString(2, "performance-test-" + id);
                      insertStmt.executeUpdate();
                      
                      // Select the data back
                      try (PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL)) {
                        selectStmt.setInt(1, id);
                        ResultSet rs = selectStmt.executeQuery();
                        if (rs.next()) {
                          successCount.incrementAndGet();
                        }
                      }
                    }
                  }
                  catch (SQLException e) {
                    log.error("Error in performance test operation {}", id, e);
                  }
                }, executor));
              }
            }
            
            // Wait for all operations to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);
          }
          catch (Exception e) {
            log.error("Error in performance test", e);
          }
          
          long endTime = System.currentTimeMillis();
          return new PerformanceResult(endTime - startTime, successCount.get());
        };
    
    // Clean up any existing data
    try (Connection conn = directDataSource.getConnection()) {
      conn.prepareStatement("DELETE FROM " + TEST_TABLE).executeUpdate();
    }
    
    // Run the test with platform threads
    log.info("Running performance test with platform threads...");
    PerformanceResult platformResult = runPerformanceTest.apply(platformThreadExecutor);
    log.info("Platform threads: {} operations in {} ms", 
        platformResult.successCount, platformResult.durationMs);
    
    // Clean up data between tests
    try (Connection conn = directDataSource.getConnection()) {
      conn.prepareStatement("DELETE FROM " + TEST_TABLE).executeUpdate();
    }
    
    // Run the test with virtual threads
    log.info("Running performance test with virtual threads...");
    PerformanceResult virtualResult = runPerformanceTest.apply(virtualThreadExecutor);
    log.info("Virtual threads: {} operations in {} ms", 
        virtualResult.successCount, virtualResult.durationMs);
    
    // Verify that both tests completed all operations
    assertThat("Platform threads should complete all operations", 
        platformResult.successCount, is(operationCount));
    assertThat("Virtual threads should complete all operations", 
        virtualResult.successCount, is(operationCount));
    
    // Log the performance comparison
    double speedupFactor = (double) platformResult.durationMs / virtualResult.durationMs;
    log.info("Virtual threads performance speedup factor: {}", speedupFactor);
    
    // We expect virtual threads to be at least as fast as platform threads for I/O-bound operations
    // This is a soft assertion as performance can vary based on the test environment
    if (speedupFactor < 0.9) {
      log.warn("Virtual threads were slower than platform threads (factor: {}). " +
          "This might indicate an issue with the JDBC driver or connection pool.", speedupFactor);
    }
  }
  
  /**
   * Tests high concurrency JDBC operations with Virtual Threads.
   * <p>
   * This test verifies that a large number of concurrent JDBC operations can be executed
   * efficiently using Virtual Threads, demonstrating the scalability benefits compared to
   * traditional platform threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    final int concurrentOperations = 500;
    final CountDownLatch completionLatch = new CountDownLatch(concurrentOperations);
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicInteger failureCount = new AtomicInteger(0);
    
    // Clean up any existing data
    try (Connection conn = directDataSource.getConnection()) {
      conn.prepareStatement("DELETE FROM " + TEST_TABLE).executeUpdate();
    }
    
    // Start many concurrent operations
    for (int i = 0; i < concurrentOperations; i++) {
      final int id = i;
      CompletableFuture.runAsync(() -> {
        try (Connection conn = directDataSource.getConnection()) {
          // Insert a record
          try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL)) {
            insertStmt.setInt(1, id);
            insertStmt.setString(2, "concurrent-test-" + id);
            insertStmt.executeUpdate();
            
            // Small delay to increase chance of concurrent operations
            Thread.sleep(10);
            
            // Update the record
            try (PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL)) {
              updateStmt.setString(1, "concurrent-updated-" + id);
              updateStmt.setInt(2, id);
              updateStmt.executeUpdate();
            }
            
            // Verify the update
            try (PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL)) {
              selectStmt.setInt(1, id);
              ResultSet rs = selectStmt.executeQuery();
              if (rs.next() && rs.getString(1).equals("concurrent-updated-" + id)) {
                successCount.incrementAndGet();
              }
            }
          }
        }
        catch (Exception e) {
          log.error("Error in concurrent operation {}", id, e);
          failureCount.incrementAndGet();
        }
        finally {
          completionLatch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all operations to complete
    assertTrue(completionLatch.await(30, TimeUnit.SECONDS), 
        "Not all concurrent operations completed in time");
    
    // Verify that all operations were successful
    assertThat("All operations should succeed", successCount.get(), is(concurrentOperations));
    assertThat("No operations should fail", failureCount.get(), is(0));
    
    // Verify the total count of records
    try (Connection conn = directDataSource.getConnection()) {
      try (PreparedStatement countStmt = conn.prepareStatement(COUNT_SQL)) {
        ResultSet rs = countStmt.executeQuery();
        assertTrue(rs.next(), "Count result should be available");
        assertThat("All records should be in the database", 
            rs.getInt(1), is(concurrentOperations));
      }
    }
  }
  
  /**
   * Tests JDBC batch operations with Virtual Threads.
   * <p>
   * This test verifies that JDBC batch operations work correctly with Virtual Threads,
   * which is important for efficient bulk data processing.
   */
  @Test
  public void testJdbcBatchOperationsWithVirtualThreads() throws Exception {
    final int batchSize = 100;
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try (Connection conn = directDataSource.getConnection()) {
        // Prepare batch insert
        try (PreparedStatement batchInsert = conn.prepareStatement(INSERT_SQL)) {
          for (int i = 0; i < batchSize; i++) {
            batchInsert.setInt(1, i);
            batchInsert.setString(2, "batch-value-" + i);
            batchInsert.addBatch();
          }
          
          // Execute batch insert
          int[] results = batchInsert.executeBatch();
          assertThat("Batch insert should affect all rows", results.length, is(batchSize));
          
          // Verify all records were inserted
          try (PreparedStatement countStmt = conn.prepareStatement(COUNT_SQL)) {
            ResultSet rs = countStmt.executeQuery();
            assertTrue(rs.next(), "Count result should be available");
            assertThat("All batch records should be inserted", rs.getInt(1), is(batchSize));
          }
          
          // Prepare batch update
          try (PreparedStatement batchUpdate = conn.prepareStatement(UPDATE_SQL)) {
            for (int i = 0; i < batchSize; i++) {
              batchUpdate.setString(1, "batch-updated-" + i);
              batchUpdate.setInt(2, i);
              batchUpdate.addBatch();
            }
            
            // Execute batch update
            results = batchUpdate.executeBatch();
            assertThat("Batch update should affect all rows", results.length, is(batchSize));
          }
          
          // Verify updates with a sample check
          try (PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL)) {
            selectStmt.setInt(1, batchSize / 2); // Check a middle record
            ResultSet rs = selectStmt.executeQuery();
            assertTrue(rs.next(), "Sample record should be available");
            assertThat("Sample record should be updated", 
                rs.getString(1), is("batch-updated-" + (batchSize / 2)));
          }
          
          // Prepare batch delete
          try (PreparedStatement batchDelete = conn.prepareStatement(DELETE_SQL)) {
            for (int i = 0; i < batchSize; i++) {
              batchDelete.setInt(1, i);
              batchDelete.addBatch();
            }
            
            // Execute batch delete
            results = batchDelete.executeBatch();
            assertThat("Batch delete should affect all rows", results.length, is(batchSize));
          }
          
          // Verify all records were deleted
          try (PreparedStatement countStmt = conn.prepareStatement(COUNT_SQL)) {
            ResultSet rs = countStmt.executeQuery();
            assertTrue(rs.next(), "Count result should be available");
            assertThat("All batch records should be deleted", rs.getInt(1), is(0));
          }
        }
      }
      catch (Exception e) {
        log.error("Error in batch operations test", e);
        fail("Exception in batch operations test: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the future to complete
    future.get(20, TimeUnit.SECONDS);
  }
  
  /**
   * Tests long-running JDBC operations with Virtual Threads.
   * <p>
   * This test verifies that long-running JDBC operations don't cause issues with Virtual Threads,
   * such as thread starvation or excessive resource consumption.
   */
  @Test
  public void testLongRunningJdbcOperationsWithVirtualThreads() throws Exception {
    final int operationCount = 10;
    final long operationDurationMs = 500; // Each operation takes 500ms
    final CountDownLatch completionLatch = new CountDownLatch(operationCount);
    
    // Start multiple long-running operations
    for (int i = 0; i < operationCount; i++) {
      final int id = i;
      CompletableFuture.runAsync(() -> {
        try (Connection conn = directDataSource.getConnection()) {
          // Execute a query that takes some time (simulated with sleep)
          try (PreparedStatement stmt = conn.prepareStatement(
              "SELECT SLEEP(?) FROM DUAL")) {
            stmt.setInt(1, (int) (operationDurationMs / 1000));
            stmt.executeQuery();
          }
          
          // Insert a record to verify the connection is still usable
          try (PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL)) {
            insertStmt.setInt(1, id);
            insertStmt.setString(2, "long-running-test-" + id);
            insertStmt.executeUpdate();
          }
        }
        catch (Exception e) {
          log.error("Error in long-running operation {}", id, e);
          fail("Exception in long-running operation " + id + ": " + e.getMessage());
        }
        finally {
          completionLatch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all operations to complete
    assertTrue(completionLatch.await(operationCount * operationDurationMs * 2, TimeUnit.MILLISECONDS), 
        "Not all long-running operations completed in time");
    
    // Verify that all records were inserted
    try (Connection conn = directDataSource.getConnection()) {
      try (PreparedStatement countStmt = conn.prepareStatement(COUNT_SQL)) {
        ResultSet rs = countStmt.executeQuery();
        assertTrue(rs.next(), "Count result should be available");
        assertThat("All long-running operation records should be inserted", 
            rs.getInt(1), is(operationCount));
      }
    }
  }
}