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
package virtualthread;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

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
import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests to identify and validate JDBC operations that might cause thread pinning issues
 * when using Java 21 Virtual Threads with MyBatis.
 * 
 * This test class detects synchronized blocks, native methods, and other operations
 * that could impact Virtual Thread performance in the MyBatis datastore integration.
 */
public class MyBatisThreadPinningTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(MyBatisThreadPinningTest.class);
  
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS test_table (id INT PRIMARY KEY, name VARCHAR(255))";
  
  private static final String INSERT_SQL = 
      "INSERT INTO test_table (id, name) VALUES (?, ?)";
  
  private static final String SELECT_SQL = 
      "SELECT * FROM test_table WHERE id = ?";
  
  private static final String SLOW_QUERY_SQL = 
      "SELECT * FROM test_table WHERE id = ? AND SLEEP(?) = 0";
  
  private static final int NUM_THREADS = 20;
  private static final int QUERY_DELAY_MS = 500;
  
  private DataSource dataSource;
  private SqlSessionFactory sqlSessionFactory;
  private ThreadPinningMonitor threadPinningMonitor;
  
  /**
   * Monitors virtual thread pinning by tracking carrier thread utilization.
   */
  private static class ThreadPinningMonitor {
    private final ConcurrentHashMap<Thread, Long> carrierThreads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Thread, StackTraceElement[]> pinnedThreadStacks = new ConcurrentHashMap<>();
    private final AtomicInteger pinnedThreadCount = new AtomicInteger(0);
    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private Thread monitorThread;
    
    public void start() {
      monitoring.set(true);
      monitorThread = new Thread(() -> {
        while (monitoring.get()) {
          try {
            // Check for pinned virtual threads by examining thread states
            Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
              if (thread.isVirtual() && thread.getState() == Thread.State.RUNNABLE) {
                // If this virtual thread has been RUNNABLE for too long, it might be pinned
                Long startTime = carrierThreads.putIfAbsent(thread, System.currentTimeMillis());
                if (startTime != null) {
                  long pinnedDuration = System.currentTimeMillis() - startTime;
                  if (pinnedDuration > 100) { // Consider pinned if RUNNABLE for >100ms
                    if (!pinnedThreadStacks.containsKey(thread)) {
                      pinnedThreadStacks.put(thread, stackTrace);
                      pinnedThreadCount.incrementAndGet();
                    }
                  }
                }
              } else {
                // Thread is not RUNNABLE, remove from tracking
                carrierThreads.remove(thread);
              }
            });
            Thread.sleep(50); // Check every 50ms
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      });
      monitorThread.setDaemon(true);
      monitorThread.start();
    }
    
    public void stop() {
      monitoring.set(false);
      if (monitorThread != null) {
        monitorThread.interrupt();
      }
    }
    
    public int getPinnedThreadCount() {
      return pinnedThreadCount.get();
    }
    
    public ConcurrentHashMap<Thread, StackTraceElement[]> getPinnedThreadStacks() {
      return pinnedThreadStacks;
    }
    
    public void reset() {
      pinnedThreadCount.set(0);
      pinnedThreadStacks.clear();
      carrierThreads.clear();
    }
  }
  
  @Before
  public void setUp() throws SQLException {
    // Set up H2 in-memory database
    JdbcDataSource h2DataSource = new JdbcDataSource();
    h2DataSource.setURL("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1");
    h2DataSource.setUser("sa");
    h2DataSource.setPassword("");
    this.dataSource = h2DataSource;
    
    // Initialize database schema
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(CREATE_TABLE_SQL)) {
      stmt.execute();
    }
    
    // Insert test data
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
      for (int i = 1; i <= 100; i++) {
        stmt.setInt(1, i);
        stmt.setString(2, "Test Name " + i);
        stmt.executeUpdate();
      }
    }
    
    // Set up MyBatis
    TransactionFactory transactionFactory = new JdbcTransactionFactory();
    Environment environment = new Environment("test", transactionFactory, dataSource);
    Configuration configuration = new Configuration(environment);
    this.sqlSessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    
    // Initialize thread pinning monitor
    this.threadPinningMonitor = new ThreadPinningMonitor();
    this.threadPinningMonitor.start();
  }
  
  @After
  public void tearDown() {
    threadPinningMonitor.stop();
  }
  
  /**
   * Tests basic database operations with virtual threads to verify functionality.
   */
  @Test
  public void testBasicOperationsWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 10;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int id = i + 1;
        executor.submit(() -> {
          try (SqlSession session = sqlSessionFactory.openSession()) {
            try (PreparedStatement stmt = session.getConnection().prepareStatement(SELECT_SQL)) {
              stmt.setInt(1, id);
              try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                  assertEquals(id, rs.getInt("id"));
                  assertEquals("Test Name " + id, rs.getString("name"));
                } else {
                  errorCount.incrementAndGet();
                }
              }
            }
          } catch (Exception e) {
            log.error("Error in virtual thread operation", e);
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(5, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get());
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests for thread pinning with long-running queries.
   * This test executes slow queries that use SLEEP() function to simulate
   * long-running database operations that might cause thread pinning.
   */
  @Test
  public void testLongRunningQueriesForPinning() throws Exception {
    threadPinningMonitor.reset();
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    CountDownLatch latch = new CountDownLatch(NUM_THREADS);
    List<Long> executionTimes = new ArrayList<>();
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    long startTime = System.currentTimeMillis();
    
    try {
      // Submit multiple concurrent slow queries using virtual threads
      for (int i = 0; i < NUM_THREADS; i++) {
        final int id = i + 1;
        executor.submit(() -> {
          long threadStart = System.currentTimeMillis();
          try (SqlSession session = sqlSessionFactory.openSession()) {
            try (PreparedStatement stmt = session.getConnection().prepareStatement(SLOW_QUERY_SQL)) {
              stmt.setInt(1, id);
              stmt.setDouble(2, QUERY_DELAY_MS / 1000.0); // Convert ms to seconds for SLEEP function
              
              try (ResultSet rs = stmt.executeQuery()) {
                // Just iterate through results
                while (rs.next()) {
                  // Do nothing with the results
                }
              }
            }
          } catch (Exception e) {
            log.error("Error in long-running query", e);
            firstException.compareAndSet(null, e);
          } finally {
            executionTimes.add(System.currentTimeMillis() - threadStart);
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(NUM_THREADS * QUERY_DELAY_MS * 2, TimeUnit.MILLISECONDS);
      assertTrue("Not all tasks completed in time", completed);
      
      long totalDuration = System.currentTimeMillis() - startTime;
      
      // If virtual threads are working correctly without pinning, the total duration should be close to QUERY_DELAY_MS
      // If pinning occurs, it will be closer to NUM_THREADS * QUERY_DELAY_MS
      log.info("Total execution time: {} ms for {} threads with {} ms delay each", 
          totalDuration, NUM_THREADS, QUERY_DELAY_MS);
      log.info("Detected {} pinned threads", threadPinningMonitor.getPinnedThreadCount());
      
      // Log pinned thread stack traces if any were detected
      threadPinningMonitor.getPinnedThreadStacks().forEach((thread, stackTrace) -> {
        log.info("Pinned thread detected: {}", thread.getName());
        for (StackTraceElement element : stackTrace) {
          log.info("  at {}", element);
        }
      });
      
      // Calculate statistics on execution times
      double avgExecutionTime = executionTimes.stream().mapToLong(Long::longValue).average().orElse(0);
      log.info("Average execution time per thread: {} ms", avgExecutionTime);
      
      // Check if we have evidence of pinning
      if (threadPinningMonitor.getPinnedThreadCount() > 0) {
        log.warn("Thread pinning detected! This may indicate issues with MyBatis and JDBC operations on virtual threads.");
      }
      
      // If an exception occurred, fail the test
      if (firstException.get() != null) {
        throw new AssertionError("Test failed with exception", firstException.get());
      }
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests concurrent transaction handling with virtual threads.
   * This test verifies that transaction management works correctly with virtual threads
   * and checks for any thread pinning issues during transaction operations.
   */
  @Test
  public void testConcurrentTransactionsWithVirtualThreads() throws Exception {
    threadPinningMonitor.reset();
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = NUM_THREADS;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent transaction tasks using virtual threads
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < taskCount; i++) {
        final int id = 1000 + i; // Use IDs that don't conflict with existing data
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try (SqlSession session = sqlSessionFactory.openSession(false)) { // false = manual commit
            try {
              // Insert a new record in a transaction
              try (PreparedStatement insertStmt = session.getConnection().prepareStatement(INSERT_SQL)) {
                insertStmt.setInt(1, id);
                insertStmt.setString(2, "Transaction Test " + id);
                insertStmt.executeUpdate();
              }
              
              // Simulate some processing time that might cause pinning
              Thread.sleep(QUERY_DELAY_MS / 2);
              
              // Verify the record exists before commit
              try (PreparedStatement selectStmt = session.getConnection().prepareStatement(SELECT_SQL)) {
                selectStmt.setInt(1, id);
                try (ResultSet rs = selectStmt.executeQuery()) {
                  assertTrue("Inserted record not found in transaction", rs.next());
                  assertEquals(id, rs.getInt("id"));
                  assertEquals("Transaction Test " + id, rs.getString("name"));
                }
              }
              
              // Commit the transaction
              session.commit();
              successCount.incrementAndGet();
            } catch (Exception e) {
              session.rollback();
              throw e;
            }
          } catch (Exception e) {
            log.error("Error in transaction", e);
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(10, TimeUnit.SECONDS);
      
      // Check for pinned threads
      int pinnedCount = threadPinningMonitor.getPinnedThreadCount();
      log.info("Detected {} pinned threads during transaction operations", pinnedCount);
      
      // Log pinned thread stack traces if any were detected
      threadPinningMonitor.getPinnedThreadStacks().forEach((thread, stackTrace) -> {
        log.info("Pinned thread detected during transaction: {}", thread.getName());
        for (StackTraceElement element : stackTrace) {
          log.info("  at {}", element);
        }
      });
      
      // Verify all transactions succeeded
      assertEquals(taskCount, successCount.get());
      
      // Verify the data was actually committed
      try (Connection conn = dataSource.getConnection();
           PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM test_table WHERE id >= 1000")) {
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(taskCount, rs.getInt(1));
        }
      }
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for database operations.
   * This test helps identify if there are significant performance differences that might
   * indicate thread pinning or other issues with virtual threads.
   */
  @Test
  public void testCompareVirtualAndPlatformThreadPerformance() throws Exception {
    int iterations = 5;
    int threadsPerIteration = 50;
    
    // Run with platform threads
    long platformThreadTime = runPerformanceTest(false, threadsPerIteration, iterations);
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    
    // Run with virtual threads
    long virtualThreadTime = runPerformanceTest(true, threadsPerIteration, iterations);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    
    // Calculate performance ratio
    double ratio = (double) platformThreadTime / virtualThreadTime;
    log.info("Performance ratio (platform/virtual): {}", ratio);
    
    // Virtual threads should generally be faster for I/O bound operations
    // unless there's significant pinning
    assertThat("Virtual threads should be at least as fast as platform threads", 
        ratio, greaterThan(0.8));
    
    // If virtual threads are significantly slower, it might indicate pinning
    if (ratio < 1.0) {
      log.warn("Virtual threads were slower than platform threads. This may indicate thread pinning issues.");
    }
  }
  
  /**
   * Helper method to run performance tests with either platform or virtual threads.
   */
  private long runPerformanceTest(boolean useVirtualThreads, int threadCount, int iterations) throws Exception {
    threadPinningMonitor.reset();
    
    ExecutorService executor;
    if (useVirtualThreads) {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    } else {
      executor = Executors.newFixedThreadPool(Math.min(threadCount, 20)); // Limit platform threads
    }
    
    try {
      long totalTime = 0;
      
      for (int iter = 0; iter < iterations; iter++) {
        CountDownLatch latch = new CountDownLatch(threadCount);
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
          final int id = (i % 100) + 1; // Cycle through existing IDs
          
          executor.submit(() -> {
            try (SqlSession session = sqlSessionFactory.openSession()) {
              // Perform a mix of operations
              try (PreparedStatement stmt = session.getConnection().prepareStatement(SELECT_SQL)) {
                stmt.setInt(1, id);
                try (ResultSet rs = stmt.executeQuery()) {
                  while (rs.next()) {
                    // Just read the data
                    rs.getInt("id");
                    rs.getString("name");
                  }
                }
              }
              
              // Add a small delay to simulate processing
              Thread.sleep(10);
            } catch (Exception e) {
              log.error("Error in performance test", e);
            } finally {
              latch.countDown();
            }
          });
        }
        
        latch.await(30, TimeUnit.SECONDS);
        long iterationTime = System.currentTimeMillis() - startTime;
        totalTime += iterationTime;
        
        log.info("Iteration {} with {} threads ({}): {} ms", 
            iter + 1, 
            threadCount, 
            useVirtualThreads ? "virtual" : "platform", 
            iterationTime);
      }
      
      // Check for pinned threads if using virtual threads
      if (useVirtualThreads) {
        int pinnedCount = threadPinningMonitor.getPinnedThreadCount();
        log.info("Detected {} pinned threads during performance test", pinnedCount);
        
        if (pinnedCount > 0) {
          log.warn("Thread pinning detected during performance test!");
          threadPinningMonitor.getPinnedThreadStacks().forEach((thread, stackTrace) -> {
            log.info("Pinned thread: {}", thread.getName());
            for (StackTraceElement element : stackTrace) {
              log.info("  at {}", element);
            }
          });
        }
      }
      
      return totalTime / iterations; // Return average time per iteration
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests for thread pinning during connection pool operations.
   * This test verifies that connection acquisition and release operations
   * don't cause thread pinning with virtual threads.
   */
  @Test
  public void testConnectionPoolOperationsForPinning() throws Exception {
    threadPinningMonitor.reset();
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    int taskCount = 100; // Large number to stress the connection pool
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger activeConnections = new AtomicInteger(0);
    AtomicInteger maxActiveConnections = new AtomicInteger(0);
    
    try {
      // Submit many concurrent tasks to stress the connection pool
      for (int i = 0; i < taskCount; i++) {
        executor.submit(() -> {
          try {
            // Get a connection from the pool via SqlSession
            try (SqlSession session = sqlSessionFactory.openSession()) {
              // Track active connections
              int active = activeConnections.incrementAndGet();
              maxActiveConnections.updateAndGet(max -> Math.max(max, active));
              
              // Perform a simple query
              try (PreparedStatement stmt = session.getConnection().prepareStatement(
                  "SELECT 1")) {
                try (ResultSet rs = stmt.executeQuery()) {
                  rs.next(); // Just consume the result
                }
              }
              
              // Hold the connection briefly
              Thread.sleep(50);
            } finally {
              // Connection returned to pool
              activeConnections.decrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in connection pool test", e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      assertTrue("Not all connection operations completed in time", completed);
      
      // Check for pinned threads
      int pinnedCount = threadPinningMonitor.getPinnedThreadCount();
      log.info("Detected {} pinned threads during connection pool operations", pinnedCount);
      log.info("Maximum concurrent connections: {}", maxActiveConnections.get());
      
      // Log pinned thread stack traces if any were detected
      threadPinningMonitor.getPinnedThreadStacks().forEach((thread, stackTrace) -> {
        log.info("Pinned thread detected during connection pool operations: {}", thread.getName());
        for (StackTraceElement element : stackTrace) {
          log.info("  at {}", element);
        }
      });
      
      // Verify all connections were properly returned to the pool
      assertEquals(0, activeConnections.get());
    } finally {
      executor.shutdown();
    }
  }
}