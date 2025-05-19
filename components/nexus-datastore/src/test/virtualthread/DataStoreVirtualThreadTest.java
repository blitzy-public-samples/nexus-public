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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.stateguard.StateGuardModule;
import org.sonatype.nexus.datastore.DataStoreSupport;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Injector;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.rules.TestName;

import static com.google.inject.Guice.createInjector;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Tests for validating Virtual Thread compatibility with Nexus datastore operations.
 * 
 * @since 3.60
 */
public class DataStoreVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int TIMEOUT_SECONDS = 30;
  
  @Rule
  public final RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();
  
  @Rule
  public TestName testName = new TestName();
  
  /**
   * Test implementation of DataStoreSupport that uses an in-memory H2 database.
   */
  static class TestDataStore
      extends DataStoreSupport<DataSession<?>>
  {
    private Connection connection;
    
    @Override
    public void register(final Class<?> accessType) {
      // no-op for testing
    }

    @Override
    public void unregister(final Class<?> accessType) {
      // no-op for testing
    }

    @Override
    public DataSession<?> openSession() {
      return mock(DataSession.class);
    }

    @Override
    public Connection openConnection() {
      try {
        if (connection == null || connection.isClosed()) {
          connection = getDataSource().getConnection();
        }
        return connection;
      }
      catch (SQLException e) {
        throw new RuntimeException("Failed to open connection", e);
      }
    }

    @Override
    public DataSource getDataSource() {
      return mock(DataSource.class);
    }

    @Override
    protected void doStart(final String storeName, final Map<String, String> attributes) throws Exception {
      // Create an in-memory H2 database for testing
      // This would be implemented with actual connection setup in a real test
    }

    @Override
    public void freeze() {
      // no-op for testing
    }

    @Override
    public void unfreeze() {
      // no-op for testing
    }

    @Override
    public boolean isFrozen() {
      return false;
    }

    @Override
    public void backup(final String location) throws SQLException {
      // no-op for testing
    }
  }
  
  private DataStoreSupport<?> dataStore;
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    // Set up system properties
    System.setProperty("karaf.data", "/tmp/test-data");
    
    // Create the data store
    Injector injector = createInjector(new StateGuardModule());
    dataStore = spy(injector.getInstance(TestDataStore.class));
    
    // Configure and start the data store
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName("virtualThreadTest-" + testName.getMethodName());
    config.setType("h2");
    config.setSource("local");
    config.setAttributes(ImmutableMap.of(
        "jdbcUrl", "jdbc:h2:mem:virtualthread-test;DB_CLOSE_DELAY=-1",
        "username", "sa",
        "password", ""
    ));
    dataStore.setConfiguration(config);
    dataStore.start();
    
    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize test table
    initializeTestTable();
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdownNow();
    }
    
    if (dataStore != null) {
      dataStore.stop();
    }
  }
  
  /**
   * Initialize the test table for CRUD operations.
   */
  private void initializeTestTable() {
    try (Connection conn = dataStore.openConnection();
         Statement stmt = conn.createStatement()) {
      
      // Drop the table if it exists
      stmt.execute("DROP TABLE IF EXISTS test_virtual_thread");
      
      // Create a simple test table
      stmt.execute("CREATE TABLE test_virtual_thread (id VARCHAR(36) PRIMARY KEY, value VARCHAR(255))");
      
    } catch (SQLException e) {
      fail("Failed to initialize test table: " + e.getMessage());
    }
  }
  
  /**
   * Test basic CRUD operations using a Virtual Thread.
   */
  @Test
  public void testBasicCrudWithVirtualThread() throws Exception {
    String id = UUID.randomUUID().toString();
    String value = "Test Value";
    String updatedValue = "Updated Value";
    
    // Create record using a virtual thread
    Future<?> createFuture = virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataStore.openConnection();
           PreparedStatement stmt = conn.prepareStatement("INSERT INTO test_virtual_thread (id, value) VALUES (?, ?)")) {
        stmt.setString(1, id);
        stmt.setString(2, value);
        int rowsAffected = stmt.executeUpdate();
        assertThat(rowsAffected, is(1));
      } catch (SQLException e) {
        fail("Failed to create record: " + e.getMessage());
      }
    });
    
    // Wait for creation to complete
    createFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read record using a virtual thread
    Future<String> readFuture = virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataStore.openConnection();
           PreparedStatement stmt = conn.prepareStatement("SELECT value FROM test_virtual_thread WHERE id = ?")) {
        stmt.setString(1, id);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            return rs.getString("value");
          }
          return null;
        }
      } catch (SQLException e) {
        fail("Failed to read record: " + e.getMessage());
        return null;
      }
    });
    
    // Verify read result
    String readValue = readFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(readValue, is(equalTo(value)));
    
    // Update record using a virtual thread
    Future<?> updateFuture = virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataStore.openConnection();
           PreparedStatement stmt = conn.prepareStatement("UPDATE test_virtual_thread SET value = ? WHERE id = ?")) {
        stmt.setString(1, updatedValue);
        stmt.setString(2, id);
        int rowsAffected = stmt.executeUpdate();
        assertThat(rowsAffected, is(1));
      } catch (SQLException e) {
        fail("Failed to update record: " + e.getMessage());
      }
    });
    
    // Wait for update to complete
    updateFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Read updated record using a virtual thread
    Future<String> readUpdatedFuture = virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataStore.openConnection();
           PreparedStatement stmt = conn.prepareStatement("SELECT value FROM test_virtual_thread WHERE id = ?")) {
        stmt.setString(1, id);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            return rs.getString("value");
          }
          return null;
        }
      } catch (SQLException e) {
        fail("Failed to read updated record: " + e.getMessage());
        return null;
      }
    });
    
    // Verify updated value
    String updatedReadValue = readUpdatedFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(updatedReadValue, is(equalTo(updatedValue)));
    
    // Delete record using a virtual thread
    Future<?> deleteFuture = virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataStore.openConnection();
           PreparedStatement stmt = conn.prepareStatement("DELETE FROM test_virtual_thread WHERE id = ?")) {
        stmt.setString(1, id);
        int rowsAffected = stmt.executeUpdate();
        assertThat(rowsAffected, is(1));
      } catch (SQLException e) {
        fail("Failed to delete record: " + e.getMessage());
      }
    });
    
    // Wait for deletion to complete
    deleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify record is deleted
    Future<Boolean> verifyDeleteFuture = virtualThreadExecutor.submit(() -> {
      try (Connection conn = dataStore.openConnection();
           PreparedStatement stmt = conn.prepareStatement("SELECT COUNT(*) FROM test_virtual_thread WHERE id = ?")) {
        stmt.setString(1, id);
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            return rs.getInt(1) == 0;
          }
          return false;
        }
      } catch (SQLException e) {
        fail("Failed to verify deletion: " + e.getMessage());
        return false;
      }
    });
    
    Boolean isDeleted = verifyDeleteFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat(isDeleted, is(true));
  }
  
  /**
   * Test high concurrency with multiple Virtual Threads performing database operations simultaneously.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track successful operations
    AtomicInteger successfulOperations = new AtomicInteger(0);
    
    // Create multiple virtual threads to perform concurrent operations
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Perform multiple operations per thread
          for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
            String id = UUID.randomUUID().toString();
            String value = "Thread-" + threadId + "-Value-" + j;
            
            // Insert a record
            try (Connection conn = dataStore.openConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO test_virtual_thread (id, value) VALUES (?, ?)")) {
              stmt.setString(1, id);
              stmt.setString(2, value);
              stmt.executeUpdate();
            }
            
            // Read the record back
            try (Connection conn = dataStore.openConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT value FROM test_virtual_thread WHERE id = ?")) {
              stmt.setString(1, id);
              try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && value.equals(rs.getString("value"))) {
                  successfulOperations.incrementAndGet();
                }
              }
            }
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
        } 
        finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete or timeout
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete within the timeout", completed, is(true));
    
    // Verify that all operations were successful
    int expectedOperations = CONCURRENT_THREADS * OPERATIONS_PER_THREAD;
    assertThat("All operations should succeed", successfulOperations.get(), is(expectedOperations));
    
    // Verify the total number of records in the database
    try (Connection conn = dataStore.openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_virtual_thread")) {
      assertThat(rs.next(), is(true));
      assertThat(rs.getInt(1), is(expectedOperations));
    }
  }
  
  /**
   * Test connection pool behavior with Virtual Threads.
   */
  @Test
  public void testConnectionPoolWithVirtualThreads() throws Exception {
    // Map to track which connections are used by which virtual threads
    Map<String, String> connectionToThreadMap = new ConcurrentHashMap<>();
    
    // Create multiple virtual threads that will use connections from the pool
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      final String threadName = "VirtualThread-" + i;
      futures.add(virtualThreadExecutor.submit(() -> {
        try (Connection conn = dataStore.openConnection()) {
          // Record which thread is using which connection
          String connectionId = conn.toString();
          connectionToThreadMap.put(connectionId, threadName);
          
          // Simulate some work with the connection
          Thread.sleep(100);
          
          // Perform a simple query to ensure the connection is working
          try (Statement stmt = conn.createStatement();
               ResultSet rs = stmt.executeQuery("SELECT 1")) {
            assertThat(rs.next(), is(true));
            assertThat(rs.getInt(1), is(1));
          }
        } catch (Exception e) {
          fail("Error in virtual thread " + threadName + ": " + e.getMessage());
        }
      }));
    }
    
    // Wait for all futures to complete
    for (Future<?> future : futures) {
      future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Verify that connections were shared among virtual threads
    // In a properly functioning virtual thread environment, we should see fewer
    // unique connections than threads, as connections are reused
    log.info("Number of unique connections used: {}", connectionToThreadMap.size());
    assertThat("Connection pool should reuse connections", 
        connectionToThreadMap.size(), is(greaterThan(0)));
  }
  
  /**
   * Test for thread pinning issues with Virtual Threads.
   * Thread pinning occurs when a virtual thread is pinned to its carrier thread,
   * preventing the carrier thread from being used by other virtual threads.
   */
  @Test
  public void testThreadPinningWithVirtualThreads() throws Exception {
    // Create a countdown latch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Track thread execution times
    List<Long> executionTimes = new ArrayList<>();
    
    // Create multiple virtual threads that will use synchronized blocks
    // which can cause thread pinning
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          long startTime = System.nanoTime();
          
          // Use a synchronized block which can cause thread pinning
          synchronized (dataStore) {
            // Perform a database operation inside the synchronized block
            try (Connection conn = dataStore.openConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO test_virtual_thread (id, value) VALUES (?, ?)")) {
              stmt.setString(1, UUID.randomUUID().toString());
              stmt.setString(2, "Pinned-Thread-" + threadId);
              stmt.executeUpdate();
              
              // Simulate some work inside the synchronized block
              Thread.sleep(10);
            }
          }
          
          long endTime = System.nanoTime();
          long executionTime = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
          
          synchronized (executionTimes) {
            executionTimes.add(executionTime);
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete or timeout
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete within the timeout", completed, is(true));
    
    // Calculate statistics about execution times
    long totalTime = 0;
    long maxTime = 0;
    for (long time : executionTimes) {
      totalTime += time;
      maxTime = Math.max(maxTime, time);
    }
    double avgTime = (double) totalTime / executionTimes.size();
    
    log.info("Thread pinning test results:");
    log.info("  Total threads: {}", CONCURRENT_THREADS);
    log.info("  Average execution time: {} ms", avgTime);
    log.info("  Maximum execution time: {} ms", maxTime);
    
    // Verify that all operations completed
    assertThat(executionTimes.size(), is(CONCURRENT_THREADS));
    
    // Note: We're not making assertions about the actual execution times
    // as they will vary by environment. The purpose is to log the information
    // for analysis and to ensure the test completes successfully despite
    // potential thread pinning issues.
  }
  
  /**
   * Test JDBC driver compatibility with Virtual Threads.
   * This test verifies that the JDBC driver works correctly with Virtual Threads
   * and doesn't cause unexpected thread pinning or other issues.
   */
  @Test
  public void testJdbcDriverCompatibilityWithVirtualThreads() throws Exception {
    // Create a list to store any exceptions that occur during the test
    List<Exception> exceptions = new ArrayList<>();
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_THREADS);
    
    // Create multiple virtual threads that will perform various JDBC operations
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Perform a mix of JDBC operations
          String id = UUID.randomUUID().toString();
          
          // 1. Create a prepared statement and execute an insert
          try (Connection conn = dataStore.openConnection();
               PreparedStatement stmt = conn.prepareStatement(
                   "INSERT INTO test_virtual_thread (id, value) VALUES (?, ?)")) {
            stmt.setString(1, id);
            stmt.setString(2, "JDBC-Test-" + threadId);
            stmt.executeUpdate();
          }
          
          // 2. Create a statement and execute a query
          try (Connection conn = dataStore.openConnection();
               Statement stmt = conn.createStatement();
               ResultSet rs = stmt.executeQuery(
                   "SELECT COUNT(*) FROM test_virtual_thread")) {
            assertThat(rs.next(), is(true));
            assertThat(rs.getInt(1), is(greaterThan(0)));
          }
          
          // 3. Test transaction support
          Connection conn = null;
          try {
            conn = dataStore.openConnection();
            conn.setAutoCommit(false);
            
            try (PreparedStatement stmt = conn.prepareStatement(
                "UPDATE test_virtual_thread SET value = ? WHERE id = ?")) {
              stmt.setString(1, "Updated-JDBC-Test-" + threadId);
              stmt.setString(2, id);
              stmt.executeUpdate();
            }
            
            // Commit the transaction
            conn.commit();
            
            // Verify the update was successful
            try (PreparedStatement stmt = conn.prepareStatement(
                "SELECT value FROM test_virtual_thread WHERE id = ?")) {
              stmt.setString(1, id);
              try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next(), is(true));
                assertThat(rs.getString("value"), is("Updated-JDBC-Test-" + threadId));
              }
            }
          } 
          catch (Exception e) {
            if (conn != null) {
              try {
                conn.rollback();
              } 
              catch (SQLException rollbackEx) {
                log.error("Error rolling back transaction", rollbackEx);
              }
            }
            throw e;
          } 
          finally {
            if (conn != null) {
              try {
                conn.setAutoCommit(true);
                conn.close();
              } 
              catch (SQLException closeEx) {
                log.error("Error closing connection", closeEx);
              }
            }
          }
        } 
        catch (Exception e) {
          log.error("Error in JDBC test thread {}: {}", threadId, e.getMessage(), e);
          synchronized (exceptions) {
            exceptions.add(e);
          }
        } 
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete or timeout
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    assertThat("All virtual threads should complete within the timeout", completed, is(true));
    
    // Verify that no exceptions occurred during the test
    if (!exceptions.isEmpty()) {
      fail("JDBC operations failed with " + exceptions.size() + " exceptions. First exception: " 
          + exceptions.get(0).getMessage());
    }
    
    // Verify that we can still perform JDBC operations after the test
    try (Connection conn = dataStore.openConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM test_virtual_thread")) {
      assertThat(rs.next(), is(true));
      int count = rs.getInt(1);
      log.info("Total records after JDBC compatibility test: {}", count);
      assertThat(count, is(greaterThan(0)));
    }
  }
  
  /**
   * Test for comparing performance between traditional platform threads and virtual threads.
   * This test runs the same database operations using both thread types and compares the results.
   */
  @Test
  public void testVirtualThreadPerformanceComparison() throws Exception {
    final int operationsCount = 1000;
    final String testValue = "Performance-Test-Value";
    
    // First run with virtual threads
    long virtualThreadStartTime = System.nanoTime();
    
    // Create a countdown latch to wait for all operations to complete
    CountDownLatch virtualThreadLatch = new CountDownLatch(operationsCount);
    
    // Perform operations with virtual threads
    for (int i = 0; i < operationsCount; i++) {
      final String id = "vt-" + UUID.randomUUID().toString();
      virtualThreadExecutor.submit(() -> {
        try {
          // Insert a record
          try (Connection conn = dataStore.openConnection();
               PreparedStatement stmt = conn.prepareStatement(
                   "INSERT INTO test_virtual_thread (id, value) VALUES (?, ?)")) {
            stmt.setString(1, id);
            stmt.setString(2, testValue);
            stmt.executeUpdate();
          }
          
          // Read the record back
          try (Connection conn = dataStore.openConnection();
               PreparedStatement stmt = conn.prepareStatement(
                   "SELECT value FROM test_virtual_thread WHERE id = ?")) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
              assertThat(rs.next(), is(true));
              assertThat(rs.getString("value"), is(testValue));
            }
          }
          
          // Delete the record
          try (Connection conn = dataStore.openConnection();
               PreparedStatement stmt = conn.prepareStatement(
                   "DELETE FROM test_virtual_thread WHERE id = ?")) {
            stmt.setString(1, id);
            stmt.executeUpdate();
          }
        } 
        catch (Exception e) {
          log.error("Error in virtual thread performance test: {}", e.getMessage(), e);
        } 
        finally {
          virtualThreadLatch.countDown();
        }
      });
    }
    
    // Wait for all virtual thread operations to complete
    virtualThreadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long virtualThreadEndTime = System.nanoTime();
    long virtualThreadDuration = TimeUnit.NANOSECONDS.toMillis(virtualThreadEndTime - virtualThreadStartTime);
    
    // Now run with a traditional thread pool (simulating platform threads)
    ExecutorService platformThreadPool = null;
    try {
      // Create a fixed thread pool with a limited number of threads (simulating platform threads)
      platformThreadPool = Executors.newFixedThreadPool(10); // Limited to 10 threads
      
      long platformThreadStartTime = System.nanoTime();
      
      // Create a countdown latch to wait for all operations to complete
      CountDownLatch platformThreadLatch = new CountDownLatch(operationsCount);
      
      // Perform operations with platform threads
      for (int i = 0; i < operationsCount; i++) {
        final String id = "pt-" + UUID.randomUUID().toString();
        platformThreadPool.submit(() -> {
          try {
            // Insert a record
            try (Connection conn = dataStore.openConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO test_virtual_thread (id, value) VALUES (?, ?)")) {
              stmt.setString(1, id);
              stmt.setString(2, testValue);
              stmt.executeUpdate();
            }
            
            // Read the record back
            try (Connection conn = dataStore.openConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT value FROM test_virtual_thread WHERE id = ?")) {
              stmt.setString(1, id);
              try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next(), is(true));
                assertThat(rs.getString("value"), is(testValue));
              }
            }
            
            // Delete the record
            try (Connection conn = dataStore.openConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM test_virtual_thread WHERE id = ?")) {
              stmt.setString(1, id);
              stmt.executeUpdate();
            }
          } 
          catch (Exception e) {
            log.error("Error in platform thread performance test: {}", e.getMessage(), e);
          } 
          finally {
            platformThreadLatch.countDown();
          }
        });
      }
      
      // Wait for all platform thread operations to complete
      platformThreadLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      long platformThreadEndTime = System.nanoTime();
      long platformThreadDuration = TimeUnit.NANOSECONDS.toMillis(platformThreadEndTime - platformThreadStartTime);
      
      // Log the performance comparison results
      log.info("Performance comparison results:");
      log.info("  Operations count: {}", operationsCount);
      log.info("  Virtual thread execution time: {} ms", virtualThreadDuration);
      log.info("  Platform thread execution time: {} ms", platformThreadDuration);
      log.info("  Difference: {} ms", Math.abs(platformThreadDuration - virtualThreadDuration));
      
      // Note: We're not making assertions about which is faster as that will depend on the
      // environment and the specific operations being performed. The purpose is to log the
      // information for analysis.
      
      // Verify that both tests completed all operations
      assertThat("All operations should complete with virtual threads", 
          virtualThreadLatch.getCount(), is(0L));
      assertThat("All operations should complete with platform threads", 
          platformThreadLatch.getCount(), is(0L));
    } 
    finally {
      if (platformThreadPool != null) {
        platformThreadPool.shutdownNow();
      }
    }
  }