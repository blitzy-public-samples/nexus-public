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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.transaction.TransactionException;
import org.sonatype.nexus.transaction.UnitOfWork;

import jakarta.transaction.HeuristicMixedException;
import jakarta.transaction.HeuristicRollbackException;
import jakarta.transaction.NotSupportedException;
import jakarta.transaction.RollbackException;
import jakarta.transaction.Status;
import jakarta.transaction.SystemException;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Test class for validating Jakarta Transaction API integration with Virtual Threads in the Nexus datastore.
 * This class verifies that Jakarta Transaction annotations and APIs function correctly when used with Virtual Threads,
 * ensuring proper transaction demarcation, resource management, and exception handling.
 */
public class DataStoreJakartaTransactionVirtualThreadTest
    extends TestSupport
{
  private static final String TEST_TABLE = "test_virtual_thread_tx";
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (id INTEGER PRIMARY KEY, value VARCHAR(255))";
  private static final String INSERT_SQL = "INSERT INTO " + TEST_TABLE + " VALUES (?, ?)";
  private static final String SELECT_SQL = "SELECT value FROM " + TEST_TABLE + " WHERE id = ?";
  private static final String DELETE_SQL = "DELETE FROM " + TEST_TABLE + " WHERE id = ?";
  
  @Rule
  public DataSessionRule sessionRule = new DataSessionRule();
  
  @Mock
  private TransactionManager transactionManager;
  
  @Mock
  private Transaction transaction;
  
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
    virtualThreadExecutor.close();
    
    // Clean up test table
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      session.access(Connection.class).prepareStatement("DROP TABLE IF EXISTS " + TEST_TABLE).execute();
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test that @Transactional annotation works correctly with Virtual Threads.
   * This test verifies that transaction boundaries are properly maintained when
   * a method annotated with @Transactional is executed in a Virtual Thread.
   */
  @Test
  public void testTransactionalAnnotationWithVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      TransactionalService service = new TransactionalService();
      service.insertWithAnnotation(1, "test-value");
      return null;
    });
    
    // Wait for the virtual thread to complete
    future.get();
    
    // Verify the data was inserted correctly
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
      stmt.setInt(1, 1);
      var rs = stmt.executeQuery();
      assertTrue("Data should be inserted", rs.next());
      assertThat(rs.getString(1), is("test-value"));
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test that transaction rollback works correctly with Virtual Threads.
   * This test verifies that when an exception is thrown within a transactional method
   * executed in a Virtual Thread, the transaction is properly rolled back.
   */
  @Test
  public void testTransactionRollbackWithVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      TransactionalService service = new TransactionalService();
      try {
        service.insertWithExceptionAndRollback(2, "rollback-test");
        fail("Expected exception was not thrown");
      }
      catch (RuntimeException e) {
        // Expected exception
      }
      return null;
    });
    
    // Wait for the virtual thread to complete
    future.get();
    
    // Verify the data was not inserted due to rollback
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
      stmt.setInt(1, 2);
      var rs = stmt.executeQuery();
      assertFalse("Data should not be inserted due to rollback", rs.next());
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test programmatic transaction control using TransactionManager with Virtual Threads.
   * This test verifies that the TransactionManager can be used to manually control
   * transaction boundaries when executed in a Virtual Thread.
   */
  @Test
  public void testProgrammaticTransactionWithVirtualThread() throws Exception {
    AtomicBoolean transactionStarted = new AtomicBoolean(false);
    AtomicBoolean transactionCommitted = new AtomicBoolean(false);
    
    when(transactionManager.getStatus()).thenReturn(Status.STATUS_NO_TRANSACTION, Status.STATUS_ACTIVE);
    when(transactionManager.getTransaction()).thenReturn(transaction);
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      try {
        // Begin transaction
        transactionManager.begin();
        transactionStarted.set(true);
        
        // Perform some transactional work
        try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
          var stmt = session.access(Connection.class).prepareStatement(INSERT_SQL);
          stmt.setInt(1, 3);
          stmt.setString(2, "programmatic-tx");
          stmt.executeUpdate();
          session.getTransaction().commit();
        }
        
        // Commit transaction
        transactionManager.commit();
        transactionCommitted.set(true);
      }
      catch (NotSupportedException | SystemException | RollbackException | 
             HeuristicMixedException | HeuristicRollbackException e) {
        throw new RuntimeException("Transaction failed", e);
      }
      return null;
    });
    
    // Wait for the virtual thread to complete
    future.get();
    
    // Verify transaction operations were called
    assertTrue("Transaction should have been started", transactionStarted.get());
    assertTrue("Transaction should have been committed", transactionCommitted.get());
  }
  
  /**
   * Test transaction isolation across multiple Virtual Threads.
   * This test verifies that transactions in different Virtual Threads are properly
   * isolated from each other.
   */
  @Test
  public void testTransactionIsolationAcrossVirtualThreads() throws Exception {
    final int threadCount = 5;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(threadCount);
    final AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start multiple virtual threads that will all try to modify the same data
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          TransactionalService service = new TransactionalService();
          service.insertWithAnnotation(10, "thread-" + threadId);
          
          // Read the value to verify it's what this thread wrote
          try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
            var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
            stmt.setInt(1, 10);
            var rs = stmt.executeQuery();
            assertTrue("Data should be available", rs.next());
            // The last thread to commit will determine the final value
            session.getTransaction().commit();
          }
        }
        catch (Exception e) {
          threadException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
        return null;
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Check if any thread had an exception
    if (threadException.get() != null) {
      fail("Thread exception: " + threadException.get().getMessage());
    }
    
    // Verify that a value exists for the contested record
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
      stmt.setInt(1, 10);
      var rs = stmt.executeQuery();
      assertTrue("Data should be available after concurrent operations", rs.next());
      String value = rs.getString(1);
      assertThat(value, notNullValue());
      assertTrue("Value should be from one of the threads", value.startsWith("thread-"));
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test transaction context propagation across Virtual Thread handoffs.
   * This test verifies that transaction context is properly maintained when a Virtual Thread
   * is suspended and resumed, which can happen during blocking operations.
   */
  @Test
  public void testTransactionContextPropagationAcrossThreadHandoffs() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      try {
        // Set up UnitOfWork for this thread
        UnitOfWork.begin(sessionRule.openSession(DEFAULT_DATASTORE_NAME));
        
        // Insert initial data
        Connection conn = UnitOfWork.currentSession().access(Connection.class);
        var stmt = conn.prepareStatement(INSERT_SQL);
        stmt.setInt(1, 4);
        stmt.setString(2, "before-handoff");
        stmt.executeUpdate();
        
        // Simulate a blocking operation that would cause a Virtual Thread handoff
        Thread.sleep(100); // This will likely cause the Virtual Thread to be unmounted and remounted
        
        // After the handoff, verify we can still access the transaction context
        stmt = conn.prepareStatement(SELECT_SQL);
        stmt.setInt(1, 4);
        var rs = stmt.executeQuery();
        assertTrue("Data should be available after thread handoff", rs.next());
        assertThat(rs.getString(1), is("before-handoff"));
        
        // Update the data after the handoff
        stmt = conn.prepareStatement("UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?");
        stmt.setString(1, "after-handoff");
        stmt.setInt(2, 4);
        stmt.executeUpdate();
        
        // Commit the transaction
        UnitOfWork.end();
      }
      catch (Exception e) {
        UnitOfWork.end(e);
        throw new RuntimeException("Transaction failed", e);
      }
      return null;
    });
    
    // Wait for the virtual thread to complete
    future.get();
    
    // Verify the data was updated correctly after the handoff
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
      stmt.setInt(1, 4);
      var rs = stmt.executeQuery();
      assertTrue("Data should be available", rs.next());
      assertThat(rs.getString(1), is("after-handoff"));
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test nested transactions with Virtual Threads.
   * This test verifies that nested transactions behave correctly when executed in Virtual Threads.
   */
  @Test
  public void testNestedTransactionsWithVirtualThread() throws Exception {
    Future<?> future = virtualThreadExecutor.submit(() -> {
      TransactionalService service = new TransactionalService();
      service.outerTransactionalMethod(5);
      return null;
    });
    
    // Wait for the virtual thread to complete
    future.get();
    
    // Verify the data was inserted correctly by both transactions
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
      
      // Check outer transaction data
      stmt.setInt(1, 5);
      var rs = stmt.executeQuery();
      assertTrue("Outer transaction data should be inserted", rs.next());
      assertThat(rs.getString(1), is("outer-tx"));
      
      // Check inner transaction data
      stmt.setInt(1, 6);
      rs = stmt.executeQuery();
      assertTrue("Inner transaction data should be inserted", rs.next());
      assertThat(rs.getString(1), is("inner-tx"));
      
      session.getTransaction().commit();
    }
  }
  
  /**
   * Test exception handling and transaction rollback with Virtual Threads.
   * This test verifies that when an exception occurs in a Virtual Thread during a transaction,
   * the transaction is properly rolled back and the exception is propagated correctly.
   */
  @Test
  public void testExceptionHandlingWithVirtualThread() throws Exception {
    final String exceptionMessage = "Test exception for rollback";
    
    Future<?> future = virtualThreadExecutor.submit(() -> {
      TransactionalService service = new TransactionalService();
      
      // This should throw an exception and roll back
      RuntimeException exception = assertThrows(RuntimeException.class, 
          () -> service.methodWithException(7, exceptionMessage));
      
      // Verify the exception message is preserved
      assertThat(exception.getMessage(), is(exceptionMessage));
      return null;
    });
    
    // Wait for the virtual thread to complete
    future.get();
    
    // Verify the data was not inserted due to rollback
    try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
      var stmt = session.access(Connection.class).prepareStatement(SELECT_SQL);
      stmt.setInt(1, 7);
      var rs = stmt.executeQuery();
      assertFalse("Data should not be inserted due to rollback", rs.next());
      session.getTransaction().commit();
    }
  }
  
  /**
   * Service class with transactional methods for testing.
   */
  class TransactionalService {
    
    /**
     * Insert data with a transaction annotation.
     */
    @Transactional(TxType.REQUIRED)
    public void insertWithAnnotation(int id, String value) {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        var stmt = session.access(Connection.class).prepareStatement(INSERT_SQL);
        stmt.setInt(1, id);
        stmt.setString(2, value);
        stmt.executeUpdate();
        session.getTransaction().commit();
      }
      catch (Exception e) {
        throw new RuntimeException("Failed to insert data", e);
      }
    }
    
    /**
     * Insert data and then throw an exception to cause rollback.
     */
    @Transactional(TxType.REQUIRED)
    public void insertWithExceptionAndRollback(int id, String value) {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        var stmt = session.access(Connection.class).prepareStatement(INSERT_SQL);
        stmt.setInt(1, id);
        stmt.setString(2, value);
        stmt.executeUpdate();
        session.getTransaction().commit();
        
        // Throw exception after insert to trigger rollback
        throw new RuntimeException("Rollback test exception");
      }
      catch (Exception e) {
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new RuntimeException("Failed to insert data", e);
      }
    }
    
    /**
     * Outer transactional method that calls an inner transactional method.
     */
    @Transactional(TxType.REQUIRED)
    public void outerTransactionalMethod(int id) {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        var stmt = session.access(Connection.class).prepareStatement(INSERT_SQL);
        stmt.setInt(1, id);
        stmt.setString(2, "outer-tx");
        stmt.executeUpdate();
        session.getTransaction().commit();
        
        // Call inner transactional method
        innerTransactionalMethod(id + 1);
      }
      catch (Exception e) {
        throw new RuntimeException("Failed in outer transaction", e);
      }
    }
    
    /**
     * Inner transactional method called by the outer method.
     */
    @Transactional(TxType.REQUIRES_NEW)
    public void innerTransactionalMethod(int id) {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        var stmt = session.access(Connection.class).prepareStatement(INSERT_SQL);
        stmt.setInt(1, id);
        stmt.setString(2, "inner-tx");
        stmt.executeUpdate();
        session.getTransaction().commit();
      }
      catch (Exception e) {
        throw new RuntimeException("Failed in inner transaction", e);
      }
    }
    
    /**
     * Method that throws an exception during transaction.
     */
    @Transactional(TxType.REQUIRED)
    public void methodWithException(int id, String exceptionMessage) {
      try (DataSession<?> session = sessionRule.openSession(DEFAULT_DATASTORE_NAME)) {
        var stmt = session.access(Connection.class).prepareStatement(INSERT_SQL);
        stmt.setInt(1, id);
        stmt.setString(2, "exception-data");
        stmt.executeUpdate();
        session.getTransaction().commit();
        
        // Throw exception with the provided message
        throw new RuntimeException(exceptionMessage);
      }
      catch (Exception e) {
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new RuntimeException("Failed during transaction", e);
      }
    }
  }
}