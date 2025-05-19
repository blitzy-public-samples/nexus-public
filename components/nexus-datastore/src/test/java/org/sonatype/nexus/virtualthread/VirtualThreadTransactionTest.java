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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Provider;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.datastore.api.SchemaTemplate;
import org.sonatype.nexus.transaction.TransactionSupport;
import org.sonatype.nexus.transaction.UnitOfWork;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test for validating transaction integrity when database operations are executed
 * across multiple Virtual Threads in the Nexus datastore component.
 * 
 * @since 3.60
 */
public class VirtualThreadTransactionTest
    extends TestSupport
{
  private static final String TEST_STORE = "test-store";
  
  @Mock
  private DataStore dataStore;
  
  @Mock
  private DataSession<?> dataSession;
  
  @Mock
  private Connection connection;
  
  @Mock
  private DataSource dataSource;
  
  @Mock
  private EventManager eventManager;
  
  @Mock
  private Provider<DataSession<?>> sessionProvider;
  
  private TransactionSupport transactionSupport;
  
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() throws SQLException {
    // Setup mock DataStore and DataSession
    when(dataStore.openSession()).thenReturn(dataSession);
    when(dataStore.getConfiguration()).thenReturn(new DataStoreConfiguration());
    when(dataSession.getConnection()).thenReturn(connection);
    when(dataSession.getDataStore()).thenReturn(dataStore);
    when(sessionProvider.get()).thenReturn(dataSession);
    
    // Configure connection behavior
    when(connection.getAutoCommit()).thenReturn(false);
    
    // Initialize transaction support
    transactionSupport = new TransactionSupport();
    
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  void tearDown() {
    virtualThreadExecutor.shutdownNow();
  }
  
  /**
   * Tests that a transaction can be successfully committed when executed across multiple Virtual Threads.
   */
  @Test
  void testTransactionCommitAcrossVirtualThreads() throws Exception {
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start transaction
      transactionSupport.begin();
      
      // Capture the transaction context
      AtomicReference<Object> transactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      assertThat(transactionContext.get(), is(notNullValue()));
      
      // Execute work in a virtual thread
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean threadTransactionValid = new AtomicBoolean(false);
      
      CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in virtual thread
          UnitOfWork.begin(transactionContext.get());
          
          try {
            // Verify we have the same transaction context
            threadTransactionValid.set(UnitOfWork.currentUnit() == transactionContext.get());
            
            // Simulate database operation
            connection.prepareStatement("INSERT INTO test_table VALUES (1, 'test')").execute();
          }
          finally {
            UnitOfWork.end();
            latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      // Wait for virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      
      // Verify transaction context was preserved
      assertTrue(threadTransactionValid.get(), "Transaction context should be preserved across virtual threads");
      
      // Commit the transaction
      transactionSupport.commit();
      
      // Verify connection was committed
      verify(connection, times(1)).commit();
      verify(connection, never()).rollback();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests that a transaction is properly rolled back when an exception occurs in a Virtual Thread.
   */
  @Test
  void testTransactionRollbackOnExceptionInVirtualThread() throws Exception {
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start transaction
      transactionSupport.begin();
      
      // Capture the transaction context
      AtomicReference<Object> transactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      
      // Execute work in a virtual thread that throws an exception
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Exception> threadException = new AtomicReference<>();
      
      CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in virtual thread
          UnitOfWork.begin(transactionContext.get());
          
          try {
            // Simulate database operation that fails
            throw new SQLException("Simulated database error");
          }
          catch (Exception e) {
            threadException.set(e);
            throw new RuntimeException(e);
          }
          finally {
            UnitOfWork.end();
            latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Expected error in virtual thread", e);
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      // Wait for virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      
      // Verify exception was thrown in virtual thread
      assertThat(threadException.get(), is(notNullValue()));
      
      // Attempt to commit, should rollback due to exception
      assertThrows(RuntimeException.class, () -> transactionSupport.commit());
      
      // Verify connection was rolled back
      verify(connection, never()).commit();
      verify(connection, times(1)).rollback();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests that nested transactions work correctly with Virtual Threads.
   */
  @Test
  void testNestedTransactionsWithVirtualThreads() throws Exception {
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start outer transaction
      transactionSupport.begin();
      
      // Capture the transaction context
      AtomicReference<Object> outerTransactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      
      // Execute nested transaction in a virtual thread
      CountDownLatch latch = new CountDownLatch(1);
      AtomicBoolean nestedTransactionExecuted = new AtomicBoolean(false);
      
      CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in virtual thread
          UnitOfWork.begin(outerTransactionContext.get());
          
          try {
            // Start nested transaction
            transactionSupport.begin();
            
            try {
              // Simulate database operation in nested transaction
              connection.prepareStatement("INSERT INTO test_table VALUES (2, 'nested')").execute();
              nestedTransactionExecuted.set(true);
            }
            finally {
              // Commit nested transaction
              transactionSupport.commit();
            }
          }
          finally {
            UnitOfWork.end();
            latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      // Wait for virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      
      // Verify nested transaction was executed
      assertTrue(nestedTransactionExecuted.get(), "Nested transaction should be executed");
      
      // Commit the outer transaction
      transactionSupport.commit();
      
      // Verify connection was committed once (for the outer transaction)
      // Nested transactions don't actually commit the connection
      verify(connection, times(1)).commit();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests that transaction isolation levels are properly maintained with Virtual Threads.
   */
  @Test
  void testTransactionIsolationWithVirtualThreads() throws Exception {
    // Setup isolation level
    final int expectedIsolation = Connection.TRANSACTION_SERIALIZABLE;
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start transaction with specific isolation level
      transactionSupport.begin();
      
      // Set isolation level
      connection.setTransactionIsolation(expectedIsolation);
      
      // Capture the transaction context
      AtomicReference<Object> transactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      
      // Execute work in a virtual thread
      CountDownLatch latch = new CountDownLatch(1);
      AtomicInteger threadIsolationLevel = new AtomicInteger();
      
      CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in virtual thread
          UnitOfWork.begin(transactionContext.get());
          
          try {
            // Check isolation level in virtual thread
            threadIsolationLevel.set(connection.getTransactionIsolation());
          }
          finally {
            UnitOfWork.end();
            latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Error in virtual thread", e);
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      // Wait for virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      
      // Verify isolation level was preserved in virtual thread
      assertThat(threadIsolationLevel.get(), is(equalTo(expectedIsolation)));
      
      // Commit the transaction
      transactionSupport.commit();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests that multiple Virtual Threads can work within the same transaction concurrently.
   */
  @Test
  void testConcurrentVirtualThreadsInSameTransaction() throws Exception {
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start transaction
      transactionSupport.begin();
      
      // Capture the transaction context
      AtomicReference<Object> transactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      
      // Number of concurrent threads
      final int threadCount = 10;
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      
      // Track thread execution
      ConcurrentHashMap<Integer, Boolean> threadExecuted = new ConcurrentHashMap<>();
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      // Launch multiple virtual threads
      for (int i = 0; i < threadCount; i++) {
        final int threadId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Restore transaction context in virtual thread
            UnitOfWork.begin(transactionContext.get());
            
            try {
              // Simulate database operation with thread ID
              connection.prepareStatement("INSERT INTO test_table VALUES (" + threadId + ", 'thread-" + threadId + "')").execute();
              threadExecuted.put(threadId, true);
            }
            finally {
              UnitOfWork.end();
              completionLatch.countDown();
            }
          }
          catch (Exception e) {
            log.error("Error in virtual thread " + threadId, e);
            completionLatch.countDown();
          }
        }, virtualThreadExecutor);
        
        futures.add(future);
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS));
      
      // Verify all threads executed successfully
      assertThat(threadExecuted.size(), is(equalTo(threadCount)));
      
      // Commit the transaction
      transactionSupport.commit();
      
      // Verify connection was committed once
      verify(connection, times(1)).commit();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests that ACID properties are maintained when transaction contexts are handed off between Virtual Threads.
   */
  @Test
  void testTransactionHandoffBetweenVirtualThreads() throws Exception {
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start transaction
      transactionSupport.begin();
      
      // Capture the transaction context
      AtomicReference<Object> transactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      
      // Setup for thread handoff
      CountDownLatch thread1Latch = new CountDownLatch(1);
      CountDownLatch thread2Latch = new CountDownLatch(1);
      AtomicBoolean thread1Success = new AtomicBoolean(false);
      AtomicBoolean thread2Success = new AtomicBoolean(false);
      
      // First virtual thread
      CompletableFuture<Void> thread1 = CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in first virtual thread
          UnitOfWork.begin(transactionContext.get());
          
          try {
            // Simulate first database operation
            connection.prepareStatement("INSERT INTO test_table VALUES (1, 'thread1')").execute();
            thread1Success.set(true);
          }
          finally {
            // Don't end UnitOfWork yet, just signal completion
            thread1Latch.countDown();
            
            // Wait for thread2 to complete before ending UnitOfWork
            thread2Latch.await(5, TimeUnit.SECONDS);
            UnitOfWork.end();
          }
        }
        catch (Exception e) {
          log.error("Error in first virtual thread", e);
          thread1Latch.countDown();
          UnitOfWork.end();
        }
      }, virtualThreadExecutor);
      
      // Wait for first thread to execute its operation
      assertTrue(thread1Latch.await(5, TimeUnit.SECONDS));
      
      // Second virtual thread - will use same transaction context
      CompletableFuture<Void> thread2 = CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in second virtual thread
          UnitOfWork.begin(transactionContext.get());
          
          try {
            // Simulate second database operation
            connection.prepareStatement("INSERT INTO test_table VALUES (2, 'thread2')").execute();
            thread2Success.set(true);
          }
          finally {
            UnitOfWork.end();
            thread2Latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Error in second virtual thread", e);
          thread2Latch.countDown();
        }
      }, virtualThreadExecutor);
      
      // Wait for both threads to complete
      CompletableFuture.allOf(thread1, thread2).join();
      
      // Verify both threads executed successfully
      assertTrue(thread1Success.get(), "First thread should execute successfully");
      assertTrue(thread2Success.get(), "Second thread should execute successfully");
      
      // Commit the transaction
      transactionSupport.commit();
      
      // Verify connection was committed once
      verify(connection, times(1)).commit();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests that a transaction is properly rolled back when a deadlock is detected in a Virtual Thread.
   */
  @Test
  void testDeadlockDetectionWithVirtualThreads() throws Exception {
    // Setup UnitOfWork with our session provider
    UnitOfWork.begin(sessionProvider);
    
    try {
      // Start transaction
      transactionSupport.begin();
      
      // Capture the transaction context
      AtomicReference<Object> transactionContext = new AtomicReference<>(UnitOfWork.currentUnit());
      
      // Configure connection to throw deadlock exception
      SQLException deadlockException = new SQLException("Deadlock detected", "40001");
      doAnswer(invocation -> {
        throw deadlockException;
      }).when(connection).prepareStatement("UPDATE test_table SET value = 'updated' WHERE id = 1");
      
      // Execute work in a virtual thread
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Exception> threadException = new AtomicReference<>();
      
      CompletableFuture.runAsync(() -> {
        try {
          // Restore transaction context in virtual thread
          UnitOfWork.begin(transactionContext.get());
          
          try {
            // Simulate database operation that causes deadlock
            connection.prepareStatement("UPDATE test_table SET value = 'updated' WHERE id = 1").execute();
          }
          catch (Exception e) {
            threadException.set(e);
            throw new RuntimeException(e);
          }
          finally {
            UnitOfWork.end();
            latch.countDown();
          }
        }
        catch (Exception e) {
          log.error("Expected deadlock error in virtual thread", e);
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      // Wait for virtual thread to complete
      assertTrue(latch.await(5, TimeUnit.SECONDS));
      
      // Verify deadlock exception was thrown in virtual thread
      assertThat(threadException.get(), is(notNullValue()));
      assertThat(threadException.get(), is(deadlockException));
      
      // Attempt to commit, should rollback due to deadlock exception
      assertThrows(RuntimeException.class, () -> transactionSupport.commit());
      
      // Verify connection was rolled back
      verify(connection, never()).commit();
      verify(connection, times(1)).rollback();
    }
    finally {
      UnitOfWork.end();
    }
  }
}