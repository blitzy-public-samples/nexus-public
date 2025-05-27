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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.TransactionSupport;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Integration test for validating transaction integrity when database operations are executed across multiple
 * Virtual Threads in the Nexus datastore component.
 * 
 * This test ensures that ACID properties are maintained when transaction contexts span thread boundaries
 * or are handed off between Virtual Threads, confirming that transactional integrity is preserved with
 * Java 21's lightweight threading model.
 */
public class VirtualThreadTransactionTest
    extends TestSupport
{
  private static final String TEST_DATASTORE = "test";
  private static final String TEST_TABLE = "virtual_thread_test";
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " (id INTEGER PRIMARY KEY, value VARCHAR(255))";
  private static final String INSERT_SQL = "INSERT INTO " + TEST_TABLE + " (id, value) VALUES (?, ?)";
  private static final String UPDATE_SQL = "UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?";
  private static final String SELECT_SQL = "SELECT value FROM " + TEST_TABLE + " WHERE id = ?";
  private static final String DELETE_SQL = "DELETE FROM " + TEST_TABLE + " WHERE id = ?";
  private static final String COUNT_SQL = "SELECT COUNT(*) FROM " + TEST_TABLE;
  private static final String TRUNCATE_SQL = "TRUNCATE TABLE " + TEST_TABLE;

  @Inject
  private DataStoreManager dataStoreManager;

  private DataStore dataStore;
  private ExecutorService virtualThreadExecutor;

  @Before
  public void setUp() throws Exception {
    dataStore = dataStoreManager.get(TEST_DATASTORE);
    
    // Create test table
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection()) {
      try (PreparedStatement stmt = connection.prepareStatement(CREATE_TABLE_SQL)) {
        stmt.execute();
      }
      connection.commit();
    }
    
    // Initialize virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @After
  public void tearDown() throws Exception {
    // Clean up test data
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection()) {
      try (PreparedStatement stmt = connection.prepareStatement(TRUNCATE_SQL)) {
        stmt.execute();
      }
      connection.commit();
    }
    
    // Shutdown executor
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }

  /**
   * Tests that a transaction can be successfully committed when operations are performed across multiple virtual threads.
   * This verifies that transaction context is properly maintained when handed off between virtual threads.
   */
  @Test
  public void testTransactionCommitAcrossVirtualThreads() throws Exception {
    final int testId = 1;
    final String initialValue = "initial";
    final String updatedValue = "updated";
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(1);
    final AtomicReference<Exception> threadException = new AtomicReference<>();

    // Start a transaction in the main thread
    Transaction.begin();
    try {
      // Insert initial data in the main thread
      try (DataSession<?> session = dataStore.openSession();
           Connection connection = session.getConnection();
           PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
        stmt.setInt(1, testId);
        stmt.setString(2, initialValue);
        stmt.executeUpdate();
      }

      // Update the data in a virtual thread
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          // Wait for signal to start
          startLatch.await();
          
          // Verify transaction context is available in the virtual thread
          assertTrue("Transaction should be active in virtual thread", Transaction.isActive());
          
          // Update data in the same transaction
          try (DataSession<?> session = dataStore.openSession();
               Connection connection = session.getConnection();
               PreparedStatement stmt = connection.prepareStatement(UPDATE_SQL)) {
            stmt.setString(1, updatedValue);
            stmt.setInt(2, testId);
            stmt.executeUpdate();
          }
          
          completionLatch.countDown();
        }
        catch (Exception e) {
          threadException.set(e);
        }
      });

      // Signal the virtual thread to start
      startLatch.countDown();
      
      // Wait for the virtual thread to complete
      completionLatch.await();
      
      // Check for exceptions in the virtual thread
      if (threadException.get() != null) {
        throw threadException.get();
      }
      
      // Commit the transaction
      Transaction.commit();
    }
    catch (Exception e) {
      Transaction.rollback();
      throw e;
    }

    // Verify the data was updated correctly
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, testId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue("Result should exist", rs.next());
        assertThat(rs.getString(1), is(equalTo(updatedValue)));
      }
    }
  }

  /**
   * Tests that a transaction is properly rolled back when an exception occurs in a virtual thread,
   * ensuring that atomicity is maintained across thread boundaries.
   */
  @Test
  public void testTransactionRollbackOnExceptionInVirtualThread() throws Exception {
    final int testId = 2;
    final String initialValue = "initial";
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(1);
    final AtomicReference<RuntimeException> threadException = new AtomicReference<>(new RuntimeException("Simulated error"));

    // Start a transaction in the main thread
    Transaction.begin();
    try {
      // Insert initial data in the main thread
      try (DataSession<?> session = dataStore.openSession();
           Connection connection = session.getConnection();
           PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
        stmt.setInt(1, testId);
        stmt.setString(2, initialValue);
        stmt.executeUpdate();
      }

      // Throw an exception in a virtual thread
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          // Wait for signal to start
          startLatch.await();
          
          // Verify transaction context is available in the virtual thread
          assertTrue("Transaction should be active in virtual thread", Transaction.isActive());
          
          // Throw an exception to trigger rollback
          throw threadException.get();
        }
        catch (Exception e) {
          // Expected exception, propagate it
          if (e != threadException.get()) {
            threadException.set(new RuntimeException(e));
          }
        }
        finally {
          completionLatch.countDown();
        }
      });

      // Signal the virtual thread to start
      startLatch.countDown();
      
      // Wait for the virtual thread to complete
      completionLatch.await();
      
      // The exception should be propagated and cause the transaction to roll back
      throw threadException.get();
    }
    catch (Exception e) {
      // Expected exception, rollback the transaction
      Transaction.rollback();
    }

    // Verify the data was rolled back (should not exist)
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, testId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertFalse("Result should not exist after rollback", rs.next());
      }
    }
  }

  /**
   * Tests that transaction isolation levels are properly maintained when operations are performed
   * across multiple virtual threads, ensuring that concurrent transactions don't interfere with each other.
   */
  @Test
  public void testTransactionIsolationAcrossVirtualThreads() throws Exception {
    final int testId = 3;
    final String initialValue = "initial";
    final String updatedValue = "updated";
    final CountDownLatch transaction1Started = new CountDownLatch(1);
    final CountDownLatch transaction2Started = new CountDownLatch(1);
    final CountDownLatch transaction1Completed = new CountDownLatch(1);
    final CountDownLatch transaction2Completed = new CountDownLatch(1);
    final AtomicReference<Exception> thread1Exception = new AtomicReference<>();
    final AtomicReference<Exception> thread2Exception = new AtomicReference<>();
    final AtomicReference<String> valueSeenInTransaction2 = new AtomicReference<>();

    // Insert initial data outside of test transactions
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
      stmt.setInt(1, testId);
      stmt.setString(2, initialValue);
      stmt.executeUpdate();
      connection.commit();
    }

    // Start transaction 1 in a virtual thread - will update the data
    Future<?> future1 = virtualThreadExecutor.submit(() -> {
      try {
        Transaction.begin();
        try {
          // Signal that transaction 1 has started
          transaction1Started.countDown();
          
          // Wait for transaction 2 to start
          transaction2Started.await();
          
          // Update data in transaction 1
          try (DataSession<?> session = dataStore.openSession();
               Connection connection = session.getConnection();
               PreparedStatement stmt = connection.prepareStatement(UPDATE_SQL)) {
            stmt.setString(1, updatedValue);
            stmt.setInt(2, testId);
            stmt.executeUpdate();
          }
          
          // Signal that transaction 1 has completed its work but hasn't committed yet
          transaction1Completed.countDown();
          
          // Wait for transaction 2 to complete its read before committing
          transaction2Completed.await();
          
          // Commit transaction 1
          Transaction.commit();
        }
        catch (Exception e) {
          Transaction.rollback();
          throw e;
        }
      }
      catch (Exception e) {
        thread1Exception.set(e);
      }
    });

    // Start transaction 2 in another virtual thread - will read the data
    Future<?> future2 = virtualThreadExecutor.submit(() -> {
      try {
        // Wait for transaction 1 to start
        transaction1Started.await();
        
        Transaction.begin();
        try {
          // Signal that transaction 2 has started
          transaction2Started.countDown();
          
          // Wait for transaction 1 to update the data (but not commit)
          transaction1Completed.await();
          
          // Read data in transaction 2 - should still see the initial value due to isolation
          try (DataSession<?> session = dataStore.openSession();
               Connection connection = session.getConnection();
               PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
            stmt.setInt(1, testId);
            try (ResultSet rs = stmt.executeQuery()) {
              assertTrue("Result should exist", rs.next());
              valueSeenInTransaction2.set(rs.getString(1));
            }
          }
          
          // Signal that transaction 2 has completed its read
          transaction2Completed.countDown();
          
          // Commit transaction 2
          Transaction.commit();
        }
        catch (Exception e) {
          Transaction.rollback();
          throw e;
        }
      }
      catch (Exception e) {
        thread2Exception.set(e);
      }
    });

    // Wait for both futures to complete
    future1.get();
    future2.get();

    // Check for exceptions
    if (thread1Exception.get() != null) {
      throw thread1Exception.get();
    }
    if (thread2Exception.get() != null) {
      throw thread2Exception.get();
    }

    // Verify that transaction 2 saw the initial value due to transaction isolation
    assertThat(valueSeenInTransaction2.get(), is(equalTo(initialValue)));

    // Verify that after both transactions are committed, the data has the updated value
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, testId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue("Result should exist", rs.next());
        assertThat(rs.getString(1), is(equalTo(updatedValue)));
      }
    }
  }

  /**
   * Tests that nested transactions work correctly with virtual threads, ensuring that inner transactions
   * can be committed or rolled back independently of outer transactions.
   */
  @Test
  public void testNestedTransactionsWithVirtualThreads() throws Exception {
    final int outerRecordId = 4;
    final int innerRecordId = 5;
    final String outerValue = "outer";
    final String innerValue = "inner";
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(1);
    final AtomicReference<Exception> threadException = new AtomicReference<>();
    final AtomicBoolean innerTransactionRolledBack = new AtomicBoolean(false);

    // Start outer transaction in the main thread
    Transaction.begin();
    try {
      // Insert data for outer transaction
      try (DataSession<?> session = dataStore.openSession();
           Connection connection = session.getConnection();
           PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
        stmt.setInt(1, outerRecordId);
        stmt.setString(2, outerValue);
        stmt.executeUpdate();
      }

      // Start inner transaction in a virtual thread
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          // Wait for signal to start
          startLatch.await();
          
          // Verify outer transaction context is available in the virtual thread
          assertTrue("Outer transaction should be active in virtual thread", Transaction.isActive());
          
          // Start inner transaction
          Transaction.begin();
          try {
            // Insert data for inner transaction
            try (DataSession<?> session = dataStore.openSession();
                 Connection connection = session.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
              stmt.setInt(1, innerRecordId);
              stmt.setString(2, innerValue);
              stmt.executeUpdate();
            }
            
            // Rollback inner transaction
            Transaction.rollback();
            innerTransactionRolledBack.set(true);
          }
          catch (Exception e) {
            Transaction.rollback();
            throw e;
          }
          
          completionLatch.countDown();
        }
        catch (Exception e) {
          threadException.set(e);
          completionLatch.countDown();
        }
      });

      // Signal the virtual thread to start
      startLatch.countDown();
      
      // Wait for the virtual thread to complete
      completionLatch.await();
      
      // Check for exceptions in the virtual thread
      if (threadException.get() != null) {
        throw threadException.get();
      }
      
      // Commit the outer transaction
      Transaction.commit();
    }
    catch (Exception e) {
      Transaction.rollback();
      throw e;
    }

    // Verify that inner transaction was rolled back
    assertTrue("Inner transaction should have been rolled back", innerTransactionRolledBack.get());

    // Verify the outer transaction data was committed
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, outerRecordId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue("Outer record should exist", rs.next());
        assertThat(rs.getString(1), is(equalTo(outerValue)));
      }
    }

    // Verify the inner transaction data was rolled back
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, innerRecordId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertFalse("Inner record should not exist after rollback", rs.next());
      }
    }
  }

  /**
   * Tests that multiple concurrent transactions with virtual threads can operate independently
   * without interfering with each other, ensuring proper isolation and concurrency.
   */
  @Test
  public void testConcurrentTransactionsWithVirtualThreads() throws Exception {
    final int numThreads = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final CountDownLatch completionLatch = new CountDownLatch(numThreads);
    final AtomicInteger successCount = new AtomicInteger(0);
    final List<Exception> exceptions = new ArrayList<>();

    // Start multiple concurrent transactions in virtual threads
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      final int threadId = 100 + i;
      final String value = "value-" + threadId;
      
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          // Wait for signal to start
          startLatch.await();
          
          // Start a transaction
          Transaction.begin();
          try {
            // Insert data specific to this thread
            try (DataSession<?> session = dataStore.openSession();
                 Connection connection = session.getConnection();
                 PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
              stmt.setInt(1, threadId);
              stmt.setString(2, value);
              stmt.executeUpdate();
            }
            
            // Commit the transaction
            Transaction.commit();
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            Transaction.rollback();
            throw e;
          }
        }
        catch (Exception e) {
          synchronized (exceptions) {
            exceptions.add(e);
          }
        }
        finally {
          completionLatch.countDown();
        }
      });
      
      futures.add(future);
    }

    // Signal all threads to start
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // Wait for all futures to complete
    for (Future<?> future : futures) {
      future.get();
    }

    // Check for exceptions
    if (!exceptions.isEmpty()) {
      fail("Encountered " + exceptions.size() + " exceptions: " + exceptions.get(0));
    }

    // Verify all transactions succeeded
    assertThat(successCount.get(), is(equalTo(numThreads)));

    // Verify all records were inserted correctly
    for (int i = 0; i < numThreads; i++) {
      final int threadId = 100 + i;
      final String expectedValue = "value-" + threadId;
      
      try (DataSession<?> session = dataStore.openSession();
           Connection connection = session.getConnection();
           PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
        stmt.setInt(1, threadId);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue("Record for thread " + threadId + " should exist", rs.next());
          assertThat(rs.getString(1), is(equalTo(expectedValue)));
        }
      }
    }

    // Verify the total count of records
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(COUNT_SQL);
         ResultSet rs = stmt.executeQuery()) {
      assertTrue(rs.next());
      assertThat(rs.getInt(1), is(equalTo(numThreads)));
    }
  }

  /**
   * Tests that a long-running transaction in a virtual thread can be properly managed and committed,
   * ensuring that virtual threads can handle transactions with varying durations.
   */
  @Test
  public void testLongRunningTransactionInVirtualThread() throws Exception {
    final int testId = 6;
    final String value = "long-running";
    final AtomicReference<Exception> threadException = new AtomicReference<>();
    final AtomicBoolean transactionCompleted = new AtomicBoolean(false);

    // Start a long-running transaction in a virtual thread
    Future<?> future = virtualThreadExecutor.submit(() -> {
      try {
        Transaction.begin();
        try {
          // Insert data
          try (DataSession<?> session = dataStore.openSession();
               Connection connection = session.getConnection();
               PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
            stmt.setInt(1, testId);
            stmt.setString(2, value);
            stmt.executeUpdate();
          }
          
          // Simulate a long-running operation
          Thread.sleep(2000);
          
          // Verify transaction is still active
          assertTrue("Transaction should still be active", Transaction.isActive());
          
          // Commit the transaction
          Transaction.commit();
          transactionCompleted.set(true);
        }
        catch (Exception e) {
          Transaction.rollback();
          throw e;
        }
      }
      catch (Exception e) {
        threadException.set(e);
      }
    });

    // Wait for the future to complete
    future.get();

    // Check for exceptions
    if (threadException.get() != null) {
      throw threadException.get();
    }

    // Verify the transaction completed successfully
    assertTrue("Transaction should have completed", transactionCompleted.get());

    // Verify the data was inserted correctly
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, testId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue("Result should exist", rs.next());
        assertThat(rs.getString(1), is(equalTo(value)));
      }
    }
  }

  /**
   * Tests that a transaction can be properly managed when it spans multiple virtual threads with handoffs,
   * ensuring that transaction context is preserved across complex thread interactions.
   */
  @Test
  public void testTransactionWithMultipleVirtualThreadHandoffs() throws Exception {
    final int testId = 7;
    final String initialValue = "initial";
    final String middleValue = "middle";
    final String finalValue = "final";
    final CountDownLatch thread1Started = new CountDownLatch(1);
    final CountDownLatch thread1Completed = new CountDownLatch(1);
    final CountDownLatch thread2Started = new CountDownLatch(1);
    final CountDownLatch thread2Completed = new CountDownLatch(1);
    final AtomicReference<Exception> threadException = new AtomicReference<>();

    // Start a transaction in the main thread
    Transaction.begin();
    try {
      // Insert initial data in the main thread
      try (DataSession<?> session = dataStore.openSession();
           Connection connection = session.getConnection();
           PreparedStatement stmt = connection.prepareStatement(INSERT_SQL)) {
        stmt.setInt(1, testId);
        stmt.setString(2, initialValue);
        stmt.executeUpdate();
      }

      // Hand off to first virtual thread
      Future<?> future1 = virtualThreadExecutor.submit(() -> {
        try {
          // Signal that thread 1 has started
          thread1Started.countDown();
          
          // Verify transaction context is available
          assertTrue("Transaction should be active in first virtual thread", Transaction.isActive());
          
          // Update data in the same transaction
          try (DataSession<?> session = dataStore.openSession();
               Connection connection = session.getConnection();
               PreparedStatement stmt = connection.prepareStatement(UPDATE_SQL)) {
            stmt.setString(1, middleValue);
            stmt.setInt(2, testId);
            stmt.executeUpdate();
          }
          
          // Signal that thread 1 has completed
          thread1Completed.countDown();
        }
        catch (Exception e) {
          threadException.set(e);
        }
      });

      // Wait for first virtual thread to complete
      thread1Started.await();
      thread1Completed.await();
      
      // Check for exceptions
      if (threadException.get() != null) {
        throw threadException.get();
      }

      // Hand off to second virtual thread
      Future<?> future2 = virtualThreadExecutor.submit(() -> {
        try {
          // Signal that thread 2 has started
          thread2Started.countDown();
          
          // Verify transaction context is available
          assertTrue("Transaction should be active in second virtual thread", Transaction.isActive());
          
          // Update data in the same transaction
          try (DataSession<?> session = dataStore.openSession();
               Connection connection = session.getConnection();
               PreparedStatement stmt = connection.prepareStatement(UPDATE_SQL)) {
            stmt.setString(1, finalValue);
            stmt.setInt(2, testId);
            stmt.executeUpdate();
          }
          
          // Signal that thread 2 has completed
          thread2Completed.countDown();
        }
        catch (Exception e) {
          threadException.set(e);
        }
      });

      // Wait for second virtual thread to complete
      thread2Started.await();
      thread2Completed.await();
      
      // Check for exceptions
      if (threadException.get() != null) {
        throw threadException.get();
      }

      // Commit the transaction in the main thread
      Transaction.commit();
    }
    catch (Exception e) {
      Transaction.rollback();
      throw e;
    }

    // Verify the data was updated correctly through all handoffs
    try (DataSession<?> session = dataStore.openSession();
         Connection connection = session.getConnection();
         PreparedStatement stmt = connection.prepareStatement(SELECT_SQL)) {
      stmt.setInt(1, testId);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue("Result should exist", rs.next());
        assertThat(rs.getString(1), is(equalTo(finalValue)));
      }
    }
  }
}