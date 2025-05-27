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
package org.sonatype.nexus.datastore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.transaction.TransactionException;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Test class for validating transaction context preservation across Virtual Thread handoffs in the Nexus datastore.
 * <p>
 * This class ensures that database transactions maintain proper isolation, consistency, and atomicity
 * when operations span multiple Virtual Threads. It tests scenarios where transactions are started in one
 * thread and completed in another, verifying that commit and rollback operations work correctly.
 * <p>
 * Key aspects tested:
 * <ul>
 *   <li>Transaction context preservation across thread boundaries</li>
 *   <li>Rollback functionality during thread handoffs</li>
 *   <li>Transaction isolation levels with concurrent Virtual Threads</li>
 *   <li>Proper operation of transaction managers with Virtual Thread scheduling</li>
 * </ul>
 */
public class DataStoreTransactionVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_TABLE = "virtual_thread_tx_test";
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (id INTEGER PRIMARY KEY, value VARCHAR(255))";
  private static final String INSERT_SQL = "INSERT INTO " + TEST_TABLE + " VALUES (?, ?)";
  private static final String SELECT_SQL = "SELECT value FROM " + TEST_TABLE + " WHERE id = ?";
  private static final String UPDATE_SQL = "UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?";
  private static final String DELETE_SQL = "DELETE FROM " + TEST_TABLE + " WHERE id = ?";
  
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule();
  
  @Mock
  private EventManager eventManager;
  
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() throws Exception {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create test table
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      session.access(Connection.class).prepareStatement(CREATE_TABLE_SQL).execute();
      session.getTransaction().commit();
    }
  }
  
  @After
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.close();
    }
    
    // Clean up test table
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      session.access(Connection.class).prepareStatement("DROP TABLE IF EXISTS " + TEST_TABLE).execute();
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests that transaction context is preserved when a transaction is started in one Virtual Thread
   * and completed in another.
   * <p>
   * This test verifies that the transaction context is properly maintained across thread boundaries,
   * allowing operations to be performed in different threads while maintaining transactional integrity.
   */
  @Test
  public void testTransactionContextPreservationAcrossThreads() throws Exception {
    final int testId = 1;
    final String initialValue = "initial-value";
    final String updatedValue = "updated-value";
    final AtomicReference<DataSession<?>> sessionRef = new AtomicReference<>();
    final CountDownLatch threadHandoffLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(1);
    
    // First Virtual Thread: Start transaction and insert data
    CompletableFuture<Void> firstThreadFuture = CompletableFuture.runAsync(() -> {
      try {
        log.info("First Virtual Thread: Starting transaction and inserting data");
        DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
        sessionRef.set(session);
        
        // Begin transaction and insert initial data
        Connection conn = session.access(Connection.class);
        PreparedStatement stmt = conn.prepareStatement(INSERT_SQL);
        stmt.setInt(1, testId);
        stmt.setString(2, initialValue);
        stmt.executeUpdate();
        
        // Signal that the first part of the transaction is complete
        threadHandoffLatch.countDown();
        
        // Wait for the second thread to complete its work
        completionLatch.await(5, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        log.error("Error in first Virtual Thread", e);
        fail("Exception in first Virtual Thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the first thread to insert data
    assertTrue("First thread did not complete in time", 
        threadHandoffLatch.await(5, TimeUnit.SECONDS));
    
    // Second Virtual Thread: Continue the transaction and commit
    CompletableFuture<Void> secondThreadFuture = CompletableFuture.runAsync(() -> {
      try {
        log.info("Second Virtual Thread: Continuing transaction and updating data");
        DataSession<?> session = sessionRef.get();
        assertThat("Session should be available", session, notNullValue());
        
        // Verify the data was inserted by the first thread
        Connection conn = session.access(Connection.class);
        PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
        selectStmt.setInt(1, testId);
        ResultSet rs = selectStmt.executeQuery();
        assertTrue("Data should be available from first thread", rs.next());
        assertThat(rs.getString(1), is(initialValue));
        
        // Update the data
        PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL);
        updateStmt.setString(1, updatedValue);
        updateStmt.setInt(2, testId);
        updateStmt.executeUpdate();
        
        // Commit the transaction
        session.getTransaction().commit();
        
        // Signal completion
        completionLatch.countDown();
      }
      catch (Exception e) {
        log.error("Error in second Virtual Thread", e);
        fail("Exception in second Virtual Thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for both threads to complete
    CompletableFuture.allOf(firstThreadFuture, secondThreadFuture).get(10, TimeUnit.SECONDS);
    
    // Verify the final state of the data
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
      stmt.setInt(1, testId);
      ResultSet rs = stmt.executeQuery();
      
      assertTrue("Data should be available after cross-thread transaction", rs.next());
      assertThat("Data should have the updated value", rs.getString(1), is(updatedValue));
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests that transaction rollback works correctly when a transaction is started in one Virtual Thread
   * and rolled back in another.
   * <p>
   * This test verifies that when a transaction is rolled back in a different thread than where it was started,
   * the rollback is properly applied and no data is committed to the database.
   */
  @Test
  public void testTransactionRollbackAcrossThreads() throws Exception {
    final int testId = 2;
    final String testValue = "rollback-test-value";
    final AtomicReference<DataSession<?>> sessionRef = new AtomicReference<>();
    final CountDownLatch threadHandoffLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(1);
    
    // First Virtual Thread: Start transaction and insert data
    CompletableFuture<Void> firstThreadFuture = CompletableFuture.runAsync(() -> {
      try {
        log.info("First Virtual Thread: Starting transaction for rollback test");
        DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME);
        sessionRef.set(session);
        
        // Begin transaction and insert data
        Connection conn = session.access(Connection.class);
        PreparedStatement stmt = conn.prepareStatement(INSERT_SQL);
        stmt.setInt(1, testId);
        stmt.setString(2, testValue);
        stmt.executeUpdate();
        
        // Signal that the first part of the transaction is complete
        threadHandoffLatch.countDown();
        
        // Wait for the second thread to complete its work
        completionLatch.await(5, TimeUnit.SECONDS);
      }
      catch (Exception e) {
        log.error("Error in first Virtual Thread", e);
        fail("Exception in first Virtual Thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the first thread to insert data
    assertTrue("First thread did not complete in time", 
        threadHandoffLatch.await(5, TimeUnit.SECONDS));
    
    // Second Virtual Thread: Continue the transaction but roll it back
    CompletableFuture<Void> secondThreadFuture = CompletableFuture.runAsync(() -> {
      try {
        log.info("Second Virtual Thread: Continuing transaction and rolling back");
        DataSession<?> session = sessionRef.get();
        assertThat("Session should be available", session, notNullValue());
        
        // Verify the data is visible within the transaction
        Connection conn = session.access(Connection.class);
        PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
        selectStmt.setInt(1, testId);
        ResultSet rs = selectStmt.executeQuery();
        assertTrue("Data should be available within transaction", rs.next());
        assertThat(rs.getString(1), is(testValue));
        
        // Roll back the transaction
        session.getTransaction().rollback();
        
        // Signal completion
        completionLatch.countDown();
      }
      catch (Exception e) {
        log.error("Error in second Virtual Thread", e);
        fail("Exception in second Virtual Thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for both threads to complete
    CompletableFuture.allOf(firstThreadFuture, secondThreadFuture).get(10, TimeUnit.SECONDS);
    
    // Verify the data was not committed due to rollback
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
      stmt.setInt(1, testId);
      ResultSet rs = stmt.executeQuery();
      
      assertFalse("Data should not be available after rollback", rs.next());
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests transaction isolation levels with concurrent Virtual Threads.
   * <p>
   * This test verifies that transactions in different Virtual Threads are properly isolated from each other,
   * ensuring that changes made in one transaction are not visible to other transactions until committed.
   */
  @Test
  public void testTransactionIsolationWithConcurrentVirtualThreads() throws Exception {
    final int testId = 3;
    final String initialValue = "isolation-initial";
    final String updatedValue = "isolation-updated";
    final CountDownLatch setupLatch = new CountDownLatch(1);
    final CountDownLatch thread1StartedLatch = new CountDownLatch(1);
    final CountDownLatch thread2CompletedLatch = new CountDownLatch(1);
    final AtomicBoolean thread2SawUpdatedValue = new AtomicBoolean(false);
    
    // Setup: Insert initial data
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(INSERT_SQL);
      stmt.setInt(1, testId);
      stmt.setString(2, initialValue);
      stmt.executeUpdate();
      session.getTransaction().commit();
      setupLatch.countDown();
    }
    
    // Wait for setup to complete
    assertTrue("Setup did not complete in time", setupLatch.await(5, TimeUnit.SECONDS));
    
    // First Virtual Thread: Start a long-running transaction that updates the data
    CompletableFuture<Void> firstThreadFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        log.info("First Virtual Thread: Starting long-running transaction");
        
        // Begin transaction and update data
        Connection conn = session.access(Connection.class);
        PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL);
        updateStmt.setString(1, updatedValue);
        updateStmt.setInt(2, testId);
        updateStmt.executeUpdate();
        
        // Signal that the first thread has started its transaction
        thread1StartedLatch.countDown();
        
        // Wait for the second thread to complete its check
        thread2CompletedLatch.await(5, TimeUnit.SECONDS);
        
        // Now commit the transaction
        session.getTransaction().commit();
        log.info("First Virtual Thread: Committed transaction");
      }
      catch (Exception e) {
        log.error("Error in first Virtual Thread", e);
        fail("Exception in first Virtual Thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the first thread to start its transaction
    assertTrue("First thread did not start in time", 
        thread1StartedLatch.await(5, TimeUnit.SECONDS));
    
    // Second Virtual Thread: Start a separate transaction and check the value
    CompletableFuture<Void> secondThreadFuture = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        log.info("Second Virtual Thread: Starting separate transaction to check isolation");
        
        // Begin transaction and check data
        Connection conn = session.access(Connection.class);
        PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
        selectStmt.setInt(1, testId);
        ResultSet rs = selectStmt.executeQuery();
        
        assertTrue("Data should be available", rs.next());
        String value = rs.getString(1);
        log.info("Second Virtual Thread: Read value: {}", value);
        
        // If isolation is working correctly, we should still see the initial value
        // because the first transaction hasn't committed yet
        if (updatedValue.equals(value)) {
          thread2SawUpdatedValue.set(true);
        }
        
        session.getTransaction().commit();
        
        // Signal that the second thread has completed its check
        thread2CompletedLatch.countDown();
      }
      catch (Exception e) {
        log.error("Error in second Virtual Thread", e);
        fail("Exception in second Virtual Thread: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for both threads to complete
    CompletableFuture.allOf(firstThreadFuture, secondThreadFuture).get(10, TimeUnit.SECONDS);
    
    // Verify that the second thread did not see the updated value (isolation worked)
    assertFalse("Second thread should not have seen the updated value due to transaction isolation",
        thread2SawUpdatedValue.get());
    
    // Verify the final state of the data (should be updated)
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
      stmt.setInt(1, testId);
      ResultSet rs = stmt.executeQuery();
      
      assertTrue("Data should be available after transactions", rs.next());
      assertThat("Data should have the updated value", rs.getString(1), is(updatedValue));
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests transaction context preservation during Virtual Thread scheduling events.
   * <p>
   * This test verifies that transaction context is properly maintained when a Virtual Thread
   * is unmounted and remounted during blocking operations, which can happen during I/O or when
   * the thread yields.
   */
  @Test
  public void testTransactionContextDuringThreadScheduling() throws Exception {
    final int testId = 4;
    final String testValue = "scheduling-test-value";
    final int iterations = 5;
    final AtomicInteger successCount = new AtomicInteger(0);
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        log.info("Starting transaction with potential scheduling events");
        Connection conn = session.access(Connection.class);
        
        // Insert initial data
        PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL);
        insertStmt.setInt(1, testId);
        insertStmt.setString(2, testValue);
        insertStmt.executeUpdate();
        
        // Perform multiple operations with yields in between to force thread scheduling
        for (int i = 0; i < iterations; i++) {
          // Yield to potentially cause the Virtual Thread to be unmounted
          Thread.yield();
          
          // Sleep to force a blocking operation that will unmount the Virtual Thread
          Thread.sleep(10);
          
          // After potential remount, verify we can still access the transaction
          PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
          selectStmt.setInt(1, testId);
          ResultSet rs = selectStmt.executeQuery();
          
          if (rs.next() && testValue.equals(rs.getString(1))) {
            successCount.incrementAndGet();
          }
          
          // Update the data slightly to ensure we're making changes
          PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL);
          updateStmt.setString(1, testValue + "-" + i);
          updateStmt.setInt(2, testId);
          updateStmt.executeUpdate();
        }
        
        // Commit the transaction
        session.getTransaction().commit();
        log.info("Completed transaction with {} successful verifications", successCount.get());
      }
      catch (Exception e) {
        log.error("Error during transaction with scheduling events", e);
        fail("Exception during transaction with scheduling events: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the future to complete
    future.get(10, TimeUnit.SECONDS);
    
    // Verify that all iterations were successful
    assertThat("All iterations should have successfully verified the data",
        successCount.get(), is(iterations));
    
    // Verify the final state of the data
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
      stmt.setInt(1, testId);
      ResultSet rs = stmt.executeQuery();
      
      assertTrue("Data should be available after transaction", rs.next());
      assertThat("Data should have the final updated value",
          rs.getString(1), is(testValue + "-" + (iterations - 1)));
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests multiple concurrent transactions with Virtual Threads.
   * <p>
   * This test verifies that multiple concurrent transactions in different Virtual Threads
   * can operate independently without interfering with each other.
   */
  @Test
  public void testMultipleConcurrentTransactions() throws Exception {
    final int threadCount = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final List<Future<?>> futures = new ArrayList<>();
    
    // Start multiple virtual threads that will all perform their own transactions
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i + 10; // Use as record ID to avoid conflicts
      final String threadValue = "concurrent-thread-" + threadId;
      
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Perform transaction
          try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            Connection conn = session.access(Connection.class);
            
            // Insert data specific to this thread
            PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL);
            insertStmt.setInt(1, threadId);
            insertStmt.setString(2, threadValue);
            insertStmt.executeUpdate();
            
            // Simulate some work with potential thread scheduling
            Thread.sleep((long) (Math.random() * 50));
            
            // Verify our own data is visible in the transaction
            PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
            selectStmt.setInt(1, threadId);
            ResultSet rs = selectStmt.executeQuery();
            
            assertTrue("Thread should see its own data", rs.next());
            assertThat(rs.getString(1), is(threadValue));
            
            // Commit the transaction
            session.getTransaction().commit();
          }
        }
        catch (Exception e) {
          log.error("Error in concurrent transaction thread {}", threadId, e);
          fail("Exception in concurrent transaction thread " + threadId + ": " + e.getMessage());
        }
        finally {
          completionLatch.countDown();
        }
        return null;
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertTrue("Not all threads completed in time", 
        completionLatch.await(10, TimeUnit.SECONDS));
    
    // Verify that all threads' data was committed correctly
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i + 10;
        final String expectedValue = "concurrent-thread-" + threadId;
        
        PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
        stmt.setInt(1, threadId);
        ResultSet rs = stmt.executeQuery();
        
        assertTrue("Data from thread " + threadId + " should be available", rs.next());
        assertThat("Data from thread " + threadId + " should have the correct value",
            rs.getString(1), is(expectedValue));
      }
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests transaction context preservation with UnitOfWork across Virtual Thread handoffs.
   * <p>
   * This test verifies that the UnitOfWork transaction context is properly maintained when a Virtual Thread
   * is unmounted and remounted during blocking operations.
   */
  @Test
  public void testUnitOfWorkTransactionContextAcrossThreadHandoffs() throws Exception {
    final int testId = 5;
    final String initialValue = "unitofwork-initial";
    final String updatedValue = "unitofwork-updated";
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        // Set up UnitOfWork for this thread
        UnitOfWork.begin(sessionRule.openSession(DEFAULT_DATASTORE_NAME));
        
        try {
          log.info("Starting UnitOfWork transaction");
          
          // Insert initial data
          Connection conn = UnitOfWork.currentSession().access(Connection.class);
          PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL);
          insertStmt.setInt(1, testId);
          insertStmt.setString(2, initialValue);
          insertStmt.executeUpdate();
          
          // Simulate a blocking operation that would cause a Virtual Thread handoff
          Thread.sleep(100); // This will likely cause the Virtual Thread to be unmounted and remounted
          
          // After the handoff, verify we can still access the transaction context
          PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
          selectStmt.setInt(1, testId);
          ResultSet rs = selectStmt.executeQuery();
          assertTrue("Data should be available after thread handoff", rs.next());
          assertThat(rs.getString(1), is(initialValue));
          
          // Update the data after the handoff
          PreparedStatement updateStmt = conn.prepareStatement(UPDATE_SQL);
          updateStmt.setString(1, updatedValue);
          updateStmt.setInt(2, testId);
          updateStmt.executeUpdate();
          
          // Commit the transaction
          UnitOfWork.end();
          log.info("Completed UnitOfWork transaction");
        }
        catch (Exception e) {
          UnitOfWork.end(e);
          throw e;
        }
      }
      catch (Exception e) {
        log.error("Error in UnitOfWork transaction", e);
        fail("Exception in UnitOfWork transaction: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the future to complete
    future.get(10, TimeUnit.SECONDS);
    
    // Verify the final state of the data
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
      stmt.setInt(1, testId);
      ResultSet rs = stmt.executeQuery();
      
      assertTrue("Data should be available after UnitOfWork transaction", rs.next());
      assertThat("Data should have the updated value", rs.getString(1), is(updatedValue));
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Tests transaction rollback with exceptions across Virtual Thread handoffs.
   * <p>
   * This test verifies that when an exception occurs after a Virtual Thread handoff during a transaction,
   * the transaction is properly rolled back and no data is committed to the database.
   */
  @Test
  public void testTransactionRollbackWithExceptionAcrossThreadHandoffs() throws Exception {
    final int testId = 6;
    final String testValue = "exception-rollback-test";
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        // Set up UnitOfWork for this thread
        UnitOfWork.begin(sessionRule.openSession(DEFAULT_DATASTORE_NAME));
        
        try {
          log.info("Starting transaction that will roll back due to exception");
          
          // Insert data
          Connection conn = UnitOfWork.currentSession().access(Connection.class);
          PreparedStatement insertStmt = conn.prepareStatement(INSERT_SQL);
          insertStmt.setInt(1, testId);
          insertStmt.setString(2, testValue);
          insertStmt.executeUpdate();
          
          // Simulate a blocking operation that would cause a Virtual Thread handoff
          Thread.sleep(100); // This will likely cause the Virtual Thread to be unmounted and remounted
          
          // After the handoff, verify we can still access the transaction context
          PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL);
          selectStmt.setInt(1, testId);
          ResultSet rs = selectStmt.executeQuery();
          assertTrue("Data should be available within transaction", rs.next());
          assertThat(rs.getString(1), is(testValue));
          
          // Throw an exception to trigger rollback
          throw new RuntimeException("Intentional exception to trigger rollback");
        }
        catch (Exception e) {
          // End the UnitOfWork with the exception to trigger rollback
          UnitOfWork.end(e);
          
          // We expect a RuntimeException, so rethrow it
          if (e instanceof RuntimeException) {
            throw (RuntimeException) e;
          }
          throw new RuntimeException(e);
        }
      }
      catch (RuntimeException e) {
        // Expected exception, verify it's the one we threw
        if (!e.getMessage().contains("Intentional exception")) {
          log.error("Unexpected exception", e);
          fail("Unexpected exception: " + e.getMessage());
        }
      }
      catch (Exception e) {
        log.error("Error in transaction with exception", e);
        fail("Exception in transaction with exception: " + e.getMessage());
      }
    }, virtualThreadExecutor);
    
    // Wait for the future to complete
    future.get(10, TimeUnit.SECONDS);
    
    // Verify the data was not committed due to rollback
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      Connection conn = session.access(Connection.class);
      PreparedStatement stmt = conn.prepareStatement(SELECT_SQL);
      stmt.setInt(1, testId);
      ResultSet rs = stmt.executeQuery();
      
      assertFalse("Data should not be available after rollback", rs.next());
      
      session.getTransaction().commit();
    }
  }
}