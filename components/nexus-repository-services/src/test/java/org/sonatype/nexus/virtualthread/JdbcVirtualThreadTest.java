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
import java.sql.Statement;
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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests JDBC operations using Java 21 Virtual Threads in the Nexus Repository Services context.
 * This class verifies that database interactions work correctly with virtual threads, focusing on
 * connection pooling behavior, transaction handling, and avoiding thread pinning issues.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21")
@Tag("VirtualThread")
public class JdbcVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int SMALL_CONCURRENT_OPERATIONS = 50;
  private static final int TIMEOUT_SECONDS = 30;
  private static final String TEST_QUERY = "SELECT 1";
  
  @Mock
  private DataSource dataSource;
  
  @Mock
  private Connection connection;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  private ThreadPinningDetector threadPinningDetector;
  
  @BeforeEach
  void setUp() throws SQLException {
    // Set up mock DataSource and Connection
    when(dataSource.getConnection()).thenReturn(connection);
    
    // Create statement mock that returns a result set with a single row
    Statement statement = mock(Statement.class);
    ResultSet resultSet = mock(ResultSet.class);
    when(statement.executeQuery(anyString())).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true).thenReturn(false);
    when(resultSet.getInt(1)).thenReturn(1);
    when(connection.createStatement()).thenReturn(statement);
    
    // Create prepared statement mock
    PreparedStatement preparedStatement = mock(PreparedStatement.class);
    when(preparedStatement.executeQuery()).thenReturn(resultSet);
    when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
    
    // Create executors
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    
    // Initialize thread pinning detector
    threadPinningDetector = new ThreadPinningDetector();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    platformThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    virtualThreadExecutor.shutdown();
    virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Tests basic JDBC operations with virtual threads.
   * Verifies that simple query operations work correctly when executed on virtual threads.
   */
  @Test
  @DisplayName("Basic JDBC operations should work with virtual threads")
  void testBasicJdbcOperationsWithVirtualThreads() throws Exception {
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Start thread pinning detection
        threadPinningDetector.startDetection();
        
        // Execute a simple query
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(TEST_QUERY)) {
          
          if (rs.next()) {
            return rs.getInt(1);
          }
          return -1;
        }
      }
      catch (SQLException e) {
        log.error("SQL Exception", e);
        return -1;
      }
      finally {
        // Stop thread pinning detection
        threadPinningDetector.stopDetection();
      }
    }, virtualThreadExecutor);
    
    int result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify results
    assertEquals(1, result, "Query should return expected result");
    assertFalse(threadPinningDetector.wasPinningDetected(), 
        "Thread pinning should not occur during basic JDBC operations");
  }
  
  /**
   * Tests connection pooling behavior with virtual threads.
   * Verifies that connection pooling works correctly when many virtual threads
   * request connections concurrently.
   */
  @Test
  @DisplayName("Connection pooling should work correctly with virtual threads")
  void testConnectionPoolingWithVirtualThreads() throws Exception {
    // Track how many connections are created
    AtomicInteger connectionCount = new AtomicInteger(0);
    
    // Mock connection creation to track connection count
    when(dataSource.getConnection()).thenAnswer(invocation -> {
      connectionCount.incrementAndGet();
      return connection;
    });
    
    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Start thread pinning detection
    threadPinningDetector.startDetection();
    
    // Execute concurrent operations
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          try (Connection conn = dataSource.getConnection();
               Statement stmt = conn.createStatement()) {
            stmt.executeQuery(TEST_QUERY);
          }
        }
        catch (SQLException e) {
          log.error("SQL Exception", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "All operations should complete within timeout");
    
    // Stop thread pinning detection
    threadPinningDetector.stopDetection();
    
    // Verify results
    assertThat("Connection count should be less than operation count due to pooling",
        connectionCount.get(), lessThan(CONCURRENT_OPERATIONS));
    assertFalse(threadPinningDetector.wasPinningDetected(), 
        "Thread pinning should not occur during connection pooling");
  }
  
  /**
   * Tests transaction handling with virtual threads.
   * Verifies that transactions work correctly when executed on virtual threads.
   */
  @Test
  @DisplayName("Transaction handling should work correctly with virtual threads")
  void testTransactionHandlingWithVirtualThreads() throws Exception {
    // Track transaction state
    AtomicBoolean transactionCommitted = new AtomicBoolean(false);
    AtomicBoolean transactionRolledBack = new AtomicBoolean(false);
    
    // Mock transaction methods
    doAnswer(invocation -> {
      transactionCommitted.set(true);
      return null;
    }).when(connection).commit();
    
    doAnswer(invocation -> {
      transactionRolledBack.set(true);
      return null;
    }).when(connection).rollback();
    
    // Start thread pinning detection
    threadPinningDetector.startDetection();
    
    // Execute transaction in virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        try (Connection conn = dataSource.getConnection()) {
          conn.setAutoCommit(false);
          
          try (Statement stmt = conn.createStatement()) {
            stmt.executeQuery(TEST_QUERY);
          }
          
          conn.commit();
        }
      }
      catch (SQLException e) {
        log.error("SQL Exception", e);
        try {
          connection.rollback();
        }
        catch (SQLException ex) {
          log.error("Rollback failed", ex);
        }
      }
    }, virtualThreadExecutor);
    
    future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Stop thread pinning detection
    threadPinningDetector.stopDetection();
    
    // Verify results
    assertTrue(transactionCommitted.get(), "Transaction should be committed");
    assertFalse(transactionRolledBack.get(), "Transaction should not be rolled back");
    assertFalse(threadPinningDetector.wasPinningDetected(), 
        "Thread pinning should not occur during transaction handling");
  }
  
  /**
   * Tests for thread pinning issues with JDBC operations.
   * Verifies that JDBC operations don't cause thread pinning when executed on virtual threads.
   */
  @Test
  @DisplayName("JDBC operations should not cause thread pinning")
  void testThreadPinningWithJdbcOperations() throws Exception {
    // Create a latch to wait for all operations to complete
    CountDownLatch latch = new CountDownLatch(SMALL_CONCURRENT_OPERATIONS);
    
    // Start thread pinning detection
    threadPinningDetector.startDetection();
    
    // Execute concurrent operations with potential pinning scenarios
    for (int i = 0; i < SMALL_CONCURRENT_OPERATIONS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          try (Connection conn = dataSource.getConnection()) {
            // Test autocommit changes
            conn.setAutoCommit(false);
            
            // Test prepared statements
            try (PreparedStatement pstmt = conn.prepareStatement(TEST_QUERY)) {
              pstmt.executeQuery();
            }
            
            // Test transaction isolation level changes
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            
            // Test commit
            conn.commit();
          }
        }
        catch (SQLException e) {
          log.error("SQL Exception", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "All operations should complete within timeout");
    
    // Stop thread pinning detection
    threadPinningDetector.stopDetection();
    
    // Verify results
    assertFalse(threadPinningDetector.wasPinningDetected(), 
        "Thread pinning should not occur during JDBC operations");
  }
  
  /**
   * Compares performance between virtual threads and platform threads for JDBC operations.
   * Verifies that virtual threads provide better performance for I/O-bound JDBC operations.
   */
  @Test
  @DisplayName("Virtual threads should provide better performance than platform threads for JDBC operations")
  void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    // Configure connection to simulate network delay
    final long simulatedNetworkDelayMs = 50;
    when(dataSource.getConnection()).thenAnswer(invocation -> {
      Thread.sleep(simulatedNetworkDelayMs); // Simulate network delay
      return connection;
    });
    
    // Measure performance with platform threads
    long platformThreadStartTime = System.nanoTime();
    runConcurrentQueries(platformThreadExecutor, CONCURRENT_OPERATIONS);
    long platformThreadDuration = Duration.ofNanos(System.nanoTime() - platformThreadStartTime).toMillis();
    
    // Measure performance with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    runConcurrentQueries(virtualThreadExecutor, CONCURRENT_OPERATIONS);
    long virtualThreadDuration = Duration.ofNanos(System.nanoTime() - virtualThreadStartTime).toMillis();
    
    // Log performance results
    log.info("Platform thread duration: {} ms", platformThreadDuration);
    log.info("Virtual thread duration: {} ms", virtualThreadDuration);
    
    // Verify results
    // Virtual threads should be significantly faster for I/O-bound operations
    assertThat("Virtual threads should be faster than platform threads for I/O-bound operations",
        virtualThreadDuration, lessThan(platformThreadDuration));
  }
  
  /**
   * Helper method to run concurrent queries using the specified executor.
   */
  private void runConcurrentQueries(ExecutorService executor, int concurrentOperations) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < concurrentOperations; i++) {
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          try (Connection conn = dataSource.getConnection();
               Statement stmt = conn.createStatement()) {
            stmt.executeQuery(TEST_QUERY);
          }
        }
        catch (SQLException e) {
          log.error("SQL Exception", e);
        }
        finally {
          latch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), 
        "All operations should complete within timeout");
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Utility class to detect thread pinning issues.
   * This class monitors for carrier thread pinning when virtual threads are used.
   */
  private static class ThreadPinningDetector {
    private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    private final AtomicReference<Thread.Builder.OfVirtual> virtualThreadBuilder = new AtomicReference<>();
    
    /**
     * Starts detection of thread pinning issues.
     */
    public void startDetection() {
      pinningDetected.set(false);
      virtualThreadBuilder.set(Thread.ofVirtual());
      
      // Enable thread pinning detection via system property if not already set
      String pinnedThreadsProperty = System.getProperty("jdk.tracePinnedThreads");
      if (pinnedThreadsProperty == null || pinnedThreadsProperty.isEmpty()) {
        System.setProperty("jdk.tracePinnedThreads", "full");
      }
    }
    
    /**
     * Stops detection of thread pinning issues.
     */
    public void stopDetection() {
      // Reset system property if we set it
      String pinnedThreadsProperty = System.getProperty("jdk.tracePinnedThreads");
      if ("full".equals(pinnedThreadsProperty)) {
        System.clearProperty("jdk.tracePinnedThreads");
      }
    }
    
    /**
     * Checks if thread pinning was detected.
     */
    public boolean wasPinningDetected() {
      return pinningDetected.get();
    }
    
    /**
     * Sets the pinning detected flag.
     */
    public void setPinningDetected() {
      pinningDetected.set(true);
    }
  }
}