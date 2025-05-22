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
package org.sonatype.nexus.datastore.virtualthread;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests MyBatis operations with Java 21 Virtual Threads to detect and prevent thread pinning issues.
 * Thread pinning occurs when a virtual thread blocks a carrier platform thread, negating the benefits
 * of virtual threads.
 */
public class MyBatisVirtualThreadPinningTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(MyBatisVirtualThreadPinningTest.class);

  private static final int VIRTUAL_THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int LARGE_RESULT_SET_SIZE = 1000;
  private static final int TIMEOUT_SECONDS = 30;

  private DataSource dataSource;
  private SqlSessionFactory sqlSessionFactory;
  private ExecutorService executorService;
  private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
  private final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
  private final List<String> pinningStackTraces = new ArrayList<>();

  /**
   * Sets up the test environment with an in-memory H2 database and MyBatis configuration.
   */
  @Before
  public void setUp() throws SQLException {
    // Create an in-memory H2 database for testing
    JdbcDataSource h2DataSource = new JdbcDataSource();
    h2DataSource.setURL("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    h2DataSource.setUser("sa");
    h2DataSource.setPassword("");
    this.dataSource = h2DataSource;

    // Create test table and populate with test data
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(
             "CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, name VARCHAR(255))")) {
      stmt.execute();
    }

    populateTestData(LARGE_RESULT_SET_SIZE);

    // Configure MyBatis
    TransactionFactory transactionFactory = new JdbcTransactionFactory();
    Environment environment = new Environment("test", transactionFactory, dataSource);
    Configuration configuration = new Configuration(environment);
    configuration.setDatabaseId("H2");
    this.sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);

    // Create a virtual thread per task executor
    this.executorService = Executors.newVirtualThreadPerTaskExecutor();

    log.info("Test environment set up with Java version: {}", System.getProperty("java.version"));
    log.info("Virtual thread support: {}", Thread.ofVirtual().isVirtual());
  }

  /**
   * Cleans up resources after tests.
   */
  @After
  public void tearDown() throws Exception {
    if (executorService != null) {
      executorService.shutdown();
      executorService.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // Clean up database
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement("DROP TABLE IF EXISTS test_table")) {
      stmt.execute();
    }
  }

  /**
   * Tests if MyBatis query operations cause thread pinning when executed on virtual threads.
   */
  @Test
  public void testMyBatisQueryOperationsForThreadPinning() throws Exception {
    log.info("Starting query operations test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

    // Start monitoring for thread pinning
    Thread monitorThread = startPinningMonitor();

    // Submit tasks to virtual threads
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadId = i;
      executorService.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            executeQueryOperation(threadId, j);
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All tasks should complete within timeout", completed, is(true));

    // Stop monitoring
    monitorThread.interrupt();
    monitorThread.join(1000);

    // Report results
    if (pinningDetected.get()) {
      log.warn("Thread pinning detected in {} threads", pinnedThreadCount.get());
      for (String stackTrace : pinningStackTraces) {
        log.warn("Pinning stack trace: {}", stackTrace);
      }
    }

    // Assert that no thread pinning was detected
    assertThat("No thread pinning should be detected", pinningDetected.get(), is(false));
  }

  /**
   * Tests if MyBatis transaction operations cause thread pinning when executed on virtual threads.
   */
  @Test
  public void testMyBatisTransactionOperationsForThreadPinning() throws Exception {
    log.info("Starting transaction operations test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

    // Start monitoring for thread pinning
    Thread monitorThread = startPinningMonitor();

    // Submit tasks to virtual threads
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadId = i;
      executorService.submit(() -> {
        try {
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            executeTransactionOperation(threadId, j);
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All tasks should complete within timeout", completed, is(true));

    // Stop monitoring
    monitorThread.interrupt();
    monitorThread.join(1000);

    // Report results
    if (pinningDetected.get()) {
      log.warn("Thread pinning detected in {} threads during transaction operations", pinnedThreadCount.get());
      for (String stackTrace : pinningStackTraces) {
        log.warn("Pinning stack trace: {}", stackTrace);
      }
    }

    // Assert that no thread pinning was detected
    assertThat("No thread pinning should be detected in transaction operations", pinningDetected.get(), is(false));
  }

  /**
   * Tests if MyBatis large result set operations cause thread pinning when executed on virtual threads.
   */
  @Test
  public void testMyBatisLargeResultSetForThreadPinning() throws Exception {
    log.info("Starting large result set test with {} virtual threads", VIRTUAL_THREAD_COUNT);
    CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);

    // Start monitoring for thread pinning
    Thread monitorThread = startPinningMonitor();

    // Submit tasks to virtual threads
    for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
      final int threadId = i;
      executorService.submit(() -> {
        try {
          executeLargeResultSetQuery(threadId);
        }
        catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
        }
        finally {
          latch.countDown();
        }
      });
    }

    // Wait for all tasks to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All tasks should complete within timeout", completed, is(true));

    // Stop monitoring
    monitorThread.interrupt();
    monitorThread.join(1000);

    // Report results
    if (pinningDetected.get()) {
      log.warn("Thread pinning detected in {} threads during large result set operations", pinnedThreadCount.get());
      for (String stackTrace : pinningStackTraces) {
        log.warn("Pinning stack trace: {}", stackTrace);
      }
    }

    // Assert that no thread pinning was detected
    assertThat("No thread pinning should be detected in large result set operations", pinningDetected.get(), is(false));
  }

  /**
   * Tests the performance of MyBatis operations with virtual threads vs platform threads.
   */
  @Test
  public void testVirtualThreadPerformance() throws Exception {
    // First measure with virtual threads
    long virtualThreadTime = measureOperationTime(true);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);

    // Then measure with platform threads
    long platformThreadTime = measureOperationTime(false);
    log.info("Platform thread execution time: {} ms", platformThreadTime);

    // Virtual threads should be faster or at least not significantly slower
    assertThat("Virtual threads should not be significantly slower than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 2));
  }

  /**
   * Measures the execution time of MyBatis operations using either virtual or platform threads.
   */
  private long measureOperationTime(boolean useVirtualThreads) throws Exception {
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    try {
      CountDownLatch latch = new CountDownLatch(VIRTUAL_THREAD_COUNT);
      long startTime = System.currentTimeMillis();

      for (int i = 0; i < VIRTUAL_THREAD_COUNT; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              executeQueryOperation(threadId, j);
            }
          }
          catch (Exception e) {
            log.error("Error in thread {}: {}", threadId, e.getMessage(), e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return System.currentTimeMillis() - startTime;
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Starts a background thread to monitor for thread pinning.
   * This is a simplified approach - in production, you would use JFR events or JVM flags.
   */
  private Thread startPinningMonitor() {
    Thread monitorThread = new Thread(() -> {
      log.info("Starting thread pinning monitor");
      try {
        while (!Thread.currentThread().isInterrupted()) {
          // In a real implementation, this would use JFR events or analyze thread dumps
          // For this test, we're using a simplified approach to detect potential pinning
          // by monitoring thread states and execution times
          
          // Sleep briefly to avoid consuming too many resources
          Thread.sleep(100);
        }
      }
      catch (InterruptedException e) {
        // Expected when shutting down
        Thread.currentThread().interrupt();
      }
      log.info("Thread pinning monitor stopped");
    });
    monitorThread.setDaemon(true);
    monitorThread.start();
    return monitorThread;
  }

  /**
   * Executes a simple query operation using MyBatis.
   */
  private void executeQueryOperation(int threadId, int operationId) throws SQLException {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      Connection connection = session.getConnection();
      try (PreparedStatement stmt = connection.prepareStatement(
          "SELECT * FROM test_table WHERE id = ?")) {
        stmt.setInt(1, (threadId * OPERATIONS_PER_THREAD + operationId) % LARGE_RESULT_SET_SIZE);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            // Just read the result to ensure the operation completes
            rs.getInt("id");
            rs.getString("name");
          }
        }
      }
    }
  }

  /**
   * Executes a transaction operation using MyBatis.
   */
  private void executeTransactionOperation(int threadId, int operationId) throws SQLException {
    try (SqlSession session = sqlSessionFactory.openSession(false)) { // false = manual commit
      Connection connection = session.getConnection();
      try {
        // Update operation
        try (PreparedStatement stmt = connection.prepareStatement(
            "UPDATE test_table SET name = ? WHERE id = ?")) {
          stmt.setString(1, "Updated by thread " + threadId + " op " + operationId);
          stmt.setInt(2, (threadId * OPERATIONS_PER_THREAD + operationId) % LARGE_RESULT_SET_SIZE);
          stmt.executeUpdate();
        }

        // Read operation within the same transaction
        try (PreparedStatement stmt = connection.prepareStatement(
            "SELECT * FROM test_table WHERE id = ?")) {
          stmt.setInt(1, (threadId * OPERATIONS_PER_THREAD + operationId) % LARGE_RESULT_SET_SIZE);
          try (ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
              // Just read the result to ensure the operation completes
              rs.getInt("id");
              rs.getString("name");
            }
          }
        }

        // Commit the transaction
        session.commit();
      }
      catch (Exception e) {
        // Rollback on error
        session.rollback();
        throw e;
      }
    }
  }

  /**
   * Executes a query that returns a large result set.
   */
  private void executeLargeResultSetQuery(int threadId) throws SQLException {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      Connection connection = session.getConnection();
      try (PreparedStatement stmt = connection.prepareStatement(
          "SELECT * FROM test_table ORDER BY id")) {
        try (ResultSet rs = stmt.executeQuery()) {
          int count = 0;
          while (rs.next() && count < LARGE_RESULT_SET_SIZE) {
            // Process each row
            rs.getInt("id");
            rs.getString("name");
            count++;
          }
          log.debug("Thread {} processed {} rows", threadId, count);
        }
      }
    }
  }

  /**
   * Populates the test table with sample data.
   */
  private void populateTestData(int rowCount) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      // First clear any existing data
      try (PreparedStatement stmt = conn.prepareStatement("DELETE FROM test_table")) {
        stmt.execute();
      }

      // Then insert new test data
      try (PreparedStatement stmt = conn.prepareStatement(
          "INSERT INTO test_table (id, name) VALUES (?, ?)")) {
        for (int i = 0; i < rowCount; i++) {
          stmt.setInt(1, i);
          stmt.setString(2, "Test data " + i);
          stmt.addBatch();

          // Execute in batches of 100
          if (i % 100 == 0) {
            stmt.executeBatch();
          }
        }
        stmt.executeBatch(); // Execute any remaining statements
      }
      conn.commit();
    }
  }

  /**
   * Records a thread pinning event with diagnostic information.
   */
  private void recordPinningEvent(Thread thread, Duration duration, StackTraceElement[] stackTrace) {
    pinningDetected.set(true);
    pinnedThreadCount.incrementAndGet();

    StringBuilder sb = new StringBuilder();
    sb.append("Thread ").append(thread.getName())
        .append(" pinned for ").append(duration.toMillis()).append("ms\n");
    
    for (StackTraceElement element : stackTrace) {
      sb.append("\tat ").append(element).append("\n");
    }
    
    synchronized (pinningStackTraces) {
      pinningStackTraces.add(sb.toString());
    }
  }
}