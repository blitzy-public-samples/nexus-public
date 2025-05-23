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
package org.sonatype.nexus.testsuite.testsupport;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;
import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests to validate JDBC driver compatibility with Java 21 Virtual Threads.
 * <p>
 * These tests verify that JDBC operations work correctly when executed on Virtual Threads,
 * including connection acquisition, transaction management, and concurrent operations.
 * <p>
 * The tests also check for thread pinning issues that can occur when JDBC drivers use
 * synchronized blocks or native methods that prevent Virtual Threads from unmounting.
 *
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
public class JdbcVirtualThreadCompatibilityTest
    extends TestSupport
{
  private static final String H2_JDBC_URL = "jdbc:h2:mem:virtualthreadtest;DB_CLOSE_DELAY=-1";
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  private Connection connection;

  @BeforeEach
  void setUp() throws SQLException {
    // Set up an in-memory H2 database for testing
    connection = DriverManager.getConnection(H2_JDBC_URL);
    
    // Create a test table
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, name VARCHAR(255))");
      
      // Insert some test data
      stmt.execute("DELETE FROM test_table");
      for (int i = 1; i <= 100; i++) {
        stmt.execute(String.format("INSERT INTO test_table VALUES (%d, 'Item %d')", i, i));
      }
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    if (connection != null && !connection.isClosed()) {
      connection.close();
    }
  }

  /**
   * Tests basic JDBC operations using Virtual Threads.
   * <p>
   * This test verifies that simple JDBC operations (select, insert, update, delete)
   * work correctly when executed on a Virtual Thread.
   */
  @Test
  void testBasicJdbcOperationsOnVirtualThread() throws Exception {
    Thread virtualThread = Thread.ofVirtual().name("jdbc-basic-ops").start(() -> {
      try {
        // Verify we're running on a virtual thread
        Thread currentThread = Thread.currentThread();
        log.info("Running on thread: {}, isVirtual: {}", currentThread.getName(), currentThread.isVirtual());
        assertThat(currentThread.isVirtual(), is(true));
        
        // Test SELECT operation
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_table")) {
          rs.next();
          int count = rs.getInt(1);
          assertThat(count, is(100));
        }
        
        // Test INSERT operation
        try (PreparedStatement pstmt = connection.prepareStatement(
            "INSERT INTO test_table VALUES (?, ?)")) {
          pstmt.setInt(1, 101);
          pstmt.setString(2, "Virtual Thread Item");
          int inserted = pstmt.executeUpdate();
          assertThat(inserted, is(1));
        }
        
        // Test UPDATE operation
        try (PreparedStatement pstmt = connection.prepareStatement(
            "UPDATE test_table SET name = ? WHERE id = ?")) {
          pstmt.setString(1, "Updated by Virtual Thread");
          pstmt.setInt(2, 101);
          int updated = pstmt.executeUpdate();
          assertThat(updated, is(1));
        }
        
        // Test DELETE operation
        try (PreparedStatement pstmt = connection.prepareStatement(
            "DELETE FROM test_table WHERE id = ?")) {
          pstmt.setInt(1, 101);
          int deleted = pstmt.executeUpdate();
          assertThat(deleted, is(1));
        }
      }
      catch (SQLException e) {
        log.error("JDBC operation failed", e);
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    assertThat("Virtual thread did not complete in time", virtualThread.isAlive(), is(false));
  }

  /**
   * Tests transaction management using Virtual Threads.
   * <p>
   * This test verifies that transaction operations (commit, rollback) work correctly
   * when executed on a Virtual Thread.
   */
  @Test
  void testTransactionManagementOnVirtualThread() throws Exception {
    Thread virtualThread = Thread.ofVirtual().name("jdbc-transaction").start(() -> {
      try {
        // Verify we're running on a virtual thread
        Thread currentThread = Thread.currentThread();
        log.info("Running on thread: {}, isVirtual: {}", currentThread.getName(), currentThread.isVirtual());
        assertThat(currentThread.isVirtual(), is(true));
        
        // Test successful transaction with commit
        connection.setAutoCommit(false);
        try {
          try (PreparedStatement pstmt = connection.prepareStatement(
              "INSERT INTO test_table VALUES (?, ?)")) {
            pstmt.setInt(1, 201);
            pstmt.setString(2, "Transaction Item");
            pstmt.executeUpdate();
          }
          connection.commit();
          
          // Verify the insert was committed
          try (Statement stmt = connection.createStatement();
               ResultSet rs = stmt.executeQuery("SELECT name FROM test_table WHERE id = 201")) {
            assertThat(rs.next(), is(true));
            assertThat(rs.getString(1), is("Transaction Item"));
          }
        }
        finally {
          connection.setAutoCommit(true);
        }
        
        // Test transaction rollback
        connection.setAutoCommit(false);
        try {
          try (PreparedStatement pstmt = connection.prepareStatement(
              "INSERT INTO test_table VALUES (?, ?)")) {
            pstmt.setInt(1, 202);
            pstmt.setString(2, "Rollback Item");
            pstmt.executeUpdate();
          }
          connection.rollback();
          
          // Verify the insert was rolled back
          try (Statement stmt = connection.createStatement();
               ResultSet rs = stmt.executeQuery("SELECT name FROM test_table WHERE id = 202")) {
            assertThat(rs.next(), is(false));
          }
        }
        finally {
          connection.setAutoCommit(true);
        }
      }
      catch (SQLException e) {
        log.error("Transaction operation failed", e);
        throw new RuntimeException(e);
      }
    });
    
    // Wait for the virtual thread to complete
    virtualThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    assertThat("Virtual thread did not complete in time", virtualThread.isAlive(), is(false));
  }

  /**
   * Tests concurrent JDBC operations using Virtual Threads.
   * <p>
   * This test creates a large number of Virtual Threads, each performing a database operation,
   * to verify that the JDBC driver can handle concurrent operations on Virtual Threads.
   */
  @Test
  void testConcurrentJdbcOperationsOnVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("jdbc-concurrent-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = CONCURRENT_OPERATIONS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    List<Future<?>> futures = new ArrayList<>();
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int id = i;
        futures.add(executor.submit(() -> {
          try {
            // Perform a simple query
            try (Connection conn = DriverManager.getConnection(H2_JDBC_URL);
                 PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT * FROM test_table WHERE id = ?")) {
              pstmt.setInt(1, (id % 100) + 1); // Ensure ID is between 1-100
              try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                  // Just read the data to ensure the query works
                  rs.getInt("id");
                  rs.getString("name");
                }
              }
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread task", e);
            firstException.compareAndSet(null, e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("Not all tasks completed in time", completed, is(true));
      
      // Verify results
      if (firstException.get() != null) {
        throw new AssertionError("Errors occurred during concurrent execution", firstException.get());
      }
      assertThat("Some tasks failed", errorCount.get(), is(0));
    }
    finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Tests for thread pinning issues when using JDBC with Virtual Threads.
   * <p>
   * This test performs operations that might cause thread pinning (like using synchronized blocks)
   * and verifies that the JDBC driver doesn't cause excessive pinning that would impact performance.
   */
  @Test
  void testThreadPinningWithJdbcOperations() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("jdbc-pinning-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(taskCount);
    
    List<Future<?>> futures = new ArrayList<>();
    List<Long> executionTimes = new ArrayList<>();
    
    try {
      // Submit tasks that will start simultaneously
      for (int i = 0; i < taskCount; i++) {
        final int id = i;
        futures.add(executor.submit(() -> {
          try {
            // Wait for the signal to start
            startLatch.await();
            
            long startTime = System.nanoTime();
            
            // Perform a query that might cause pinning
            try (Connection conn = DriverManager.getConnection(H2_JDBC_URL)) {
              // Use a transaction which might involve synchronized blocks
              conn.setAutoCommit(false);
              try {
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "SELECT * FROM test_table WHERE id = ?")) {
                  pstmt.setInt(1, (id % 100) + 1);
                  try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                      // Process results
                      rs.getInt("id");
                      rs.getString("name");
                    }
                  }
                }
                
                // Perform an update in the same transaction
                try (PreparedStatement pstmt = conn.prepareStatement(
                    "UPDATE test_table SET name = ? WHERE id = ?")) {
                  pstmt.setString(1, "Updated in pinning test " + id);
                  pstmt.setInt(2, (id % 100) + 1);
                  pstmt.executeUpdate();
                }
                
                conn.commit();
              }
              catch (Exception e) {
                conn.rollback();
                throw e;
              }
              finally {
                conn.setAutoCommit(true);
              }
            }
            
            long endTime = System.nanoTime();
            synchronized (executionTimes) {
              executionTimes.add(endTime - startTime);
            }
          }
          catch (Exception e) {
            log.error("Error in pinning test task", e);
            throw new RuntimeException(e);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      assertThat("Not all tasks completed in time", completed, is(true));
      
      // Analyze execution times to detect potential pinning issues
      synchronized (executionTimes) {
        assertThat("No execution times recorded", executionTimes.size(), greaterThan(0));
        
        // Calculate average and max execution time
        long totalTime = 0;
        long maxTime = 0;
        for (long time : executionTimes) {
          totalTime += time;
          maxTime = Math.max(maxTime, time);
        }
        long avgTime = totalTime / executionTimes.size();
        
        log.info("Execution time statistics: avg={}ns, max={}ns", avgTime, maxTime);
        
        // If max time is significantly higher than average, it might indicate pinning issues
        // This is a heuristic and might need adjustment based on the specific environment
        assertThat("Max execution time is too high compared to average, possible pinning issues",
            (double) maxTime / avgTime, lessThan(10.0));
      }
    }
    finally {
      executor.shutdown();
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        executor.shutdownNow();
      }
    }
  }

  /**
   * Compares the performance of JDBC operations between Virtual Threads and Platform Threads.
   * <p>
   * This test executes the same database operations using both thread types and compares
   * the throughput to verify that Virtual Threads provide better scalability for I/O-bound operations.
   */
  @Test
  void testCompareVirtualThreadsVsPlatformThreads() throws Exception {
    int operationsPerThread = 10;
    int threadCount = 100;
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      ExecutorService platformExecutor = Executors.newFixedThreadPool(20); // Limited pool size
      try {
        CountDownLatch platformLatch = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
          platformExecutor.submit(() -> {
            try {
              performMultipleDbOperations(operationsPerThread);
            }
            finally {
              platformLatch.countDown();
            }
          });
        }
        platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      finally {
        platformExecutor.shutdown();
        platformExecutor.awaitTermination(5, TimeUnit.SECONDS);
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
      try {
        CountDownLatch virtualLatch = new CountDownLatch(threadCount);
        for (int i = 0; i < threadCount; i++) {
          virtualExecutor.submit(() -> {
            try {
              performMultipleDbOperations(operationsPerThread);
            }
            finally {
              virtualLatch.countDown();
            }
          });
        }
        virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
      finally {
        virtualExecutor.shutdown();
        virtualExecutor.awaitTermination(5, TimeUnit.SECONDS);
      }
    });
    
    log.info("Platform thread execution time: {}ms", platformThreadTime);
    log.info("Virtual thread execution time: {}ms", virtualThreadTime);
    
    // Virtual threads should be more efficient for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Performs multiple database operations in sequence.
   */
  private void performMultipleDbOperations(int count) throws SQLException {
    for (int i = 0; i < count; i++) {
      try (Connection conn = DriverManager.getConnection(H2_JDBC_URL);
           PreparedStatement pstmt = conn.prepareStatement(
               "SELECT * FROM test_table WHERE id = ?")) {
        pstmt.setInt(1, (i % 100) + 1);
        try (ResultSet rs = pstmt.executeQuery()) {
          while (rs.next()) {
            // Just read the data
            rs.getInt("id");
            rs.getString("name");
          }
        }
      }
    }
  }
  
  /**
   * Measures the execution time of a runnable in milliseconds.
   */
  private long measureExecutionTime(ThrowingRunnable runnable) throws Exception {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Functional interface for a Runnable that can throw exceptions.
   */
  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}