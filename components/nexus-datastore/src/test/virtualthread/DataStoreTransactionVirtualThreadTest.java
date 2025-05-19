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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.datastore.api.DataSession;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.transaction.Transaction;
import org.sonatype.nexus.transaction.TransactionIsolation;
import org.sonatype.nexus.transaction.UnitOfWork;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for transaction context preservation across Virtual Thread handoffs in the Nexus datastore.
 * 
 * This class ensures that database transactions maintain proper isolation, consistency, and atomicity
 * when operations span multiple Virtual Threads. It tests scenarios where transactions are started in
 * one thread and completed in another, verifying that commit and rollback operations work correctly.
 */
public class DataStoreTransactionVirtualThreadTest
    extends TestSupport
{
  @Mock
  private DataStore<?> dataStore;

  @Mock
  private DataSession<?> dataSession;

  @Mock
  private Transaction transaction;

  @Before
  public void setup() throws Exception {
    when(dataStore.openSession()).thenReturn(dataSession);
    when(dataSession.getTransaction()).thenReturn(transaction);
  }

  @After
  public void tearDown() throws Exception {
    UnitOfWork.end();
  }

  /**
   * Tests that a transaction started in one Virtual Thread can be successfully committed in another Virtual Thread.
   * 
   * This verifies that the transaction context is properly preserved across thread handoffs, which is essential
   * for maintaining data consistency when using Virtual Threads for I/O operations.
   */
  @Test
  public void testTransactionContextPreservationAcrossVirtualThreads() throws Exception {
    // Create a latch to coordinate between threads
    CountDownLatch threadHandoffLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);
    
    // Reference to hold any exceptions that occur in the threads
    AtomicReference<Throwable> threadException = new AtomicReference<>();
    
    // Start a virtual thread that begins a transaction
    Thread firstThread = Thread.ofVirtual().name("first-thread").start(() -> {
      try {
        // Begin a unit of work and open a session
        UnitOfWork.begin(dataStore);
        
        // Verify the transaction is active
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        assertThat(UnitOfWork.peekTransaction(), is(sameInstance(transaction)));
        
        // Signal the second thread to continue
        threadHandoffLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        threadHandoffLatch.countDown();
      }
    });
    
    // Start a second virtual thread that continues the transaction
    Thread secondThread = Thread.ofVirtual().name("second-thread").start(() -> {
      try {
        // Wait for the first thread to begin the transaction
        threadHandoffLatch.await(5, TimeUnit.SECONDS);
        
        // If an exception occurred in the first thread, don't proceed
        if (threadException.get() != null) {
          completionLatch.countDown();
          return;
        }
        
        // Verify we can access the same transaction
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        assertThat(UnitOfWork.peekTransaction(), is(sameInstance(transaction)));
        
        // Commit the transaction
        UnitOfWork.end();
        
        completionLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        completionLatch.countDown();
      }
    });
    
    // Wait for both threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Check if any exceptions occurred
    if (threadException.get() != null) {
      fail("Exception in test threads: " + threadException.get().getMessage());
    }
    
    // Verify the transaction was committed
    verify(transaction).commit();
    verify(dataSession).close();
  }

  /**
   * Tests that a transaction started in one Virtual Thread can be rolled back in another Virtual Thread.
   * 
   * This verifies that rollback functionality works correctly during thread handoffs, which is important
   * for maintaining data consistency when errors occur in asynchronous operations.
   */
  @Test
  public void testRollbackDuringVirtualThreadHandoff() throws Exception {
    // Create a latch to coordinate between threads
    CountDownLatch threadHandoffLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);
    
    // Reference to hold any exceptions that occur in the threads
    AtomicReference<Throwable> threadException = new AtomicReference<>();
    
    // Start a virtual thread that begins a transaction
    Thread firstThread = Thread.ofVirtual().name("first-thread").start(() -> {
      try {
        // Begin a unit of work and open a session
        UnitOfWork.begin(dataStore);
        
        // Verify the transaction is active
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        
        // Signal the second thread to continue
        threadHandoffLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        threadHandoffLatch.countDown();
      }
    });
    
    // Start a second virtual thread that rolls back the transaction
    Thread secondThread = Thread.ofVirtual().name("second-thread").start(() -> {
      try {
        // Wait for the first thread to begin the transaction
        threadHandoffLatch.await(5, TimeUnit.SECONDS);
        
        // If an exception occurred in the first thread, don't proceed
        if (threadException.get() != null) {
          completionLatch.countDown();
          return;
        }
        
        // Verify we can access the same transaction
        Transaction tx = UnitOfWork.peekTransaction();
        assertThat(tx, is(notNullValue()));
        
        // Roll back the transaction
        tx.rollback();
        UnitOfWork.end();
        
        completionLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        completionLatch.countDown();
      }
    });
    
    // Wait for both threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Check if any exceptions occurred
    if (threadException.get() != null) {
      fail("Exception in test threads: " + threadException.get().getMessage());
    }
    
    // Verify the transaction was rolled back and not committed
    verify(transaction).rollback();
    verify(transaction, never()).commit();
    verify(dataSession).close();
  }

  /**
   * Tests transaction isolation levels with concurrent Virtual Threads.
   * 
   * This verifies that transactions with SERIALIZABLE isolation level properly handle
   * concurrent access from multiple Virtual Threads.
   */
  @Test
  public void testTransactionIsolationWithConcurrentVirtualThreads() throws Exception {
    // Mock a data store that supports SERIALIZABLE isolation
    DataStore<?> serializableStore = Mockito.mock(DataStore.class);
    DataSession<?> serializableSession = Mockito.mock(DataSession.class);
    Transaction serializableTx = Mockito.mock(Transaction.class);
    
    when(serializableStore.openSession(TransactionIsolation.SERIALIZABLE)).thenReturn(serializableSession);
    when(serializableSession.getTransaction()).thenReturn(serializableTx);
    
    // Create a barrier to synchronize the start of both threads
    CyclicBarrier barrier = new CyclicBarrier(2);
    CountDownLatch completionLatch = new CountDownLatch(2);
    
    // Reference to hold any exceptions that occur in the threads
    AtomicReference<Throwable> threadException = new AtomicReference<>();
    
    // Flag to track if both transactions were active simultaneously
    AtomicBoolean concurrentTransactions = new AtomicBoolean(false);
    
    // Start two virtual threads that begin transactions with SERIALIZABLE isolation
    Thread thread1 = Thread.ofVirtual().name("isolation-thread-1").start(() -> {
      try {
        // Wait for both threads to start simultaneously
        barrier.await(5, TimeUnit.SECONDS);
        
        // Begin a unit of work with SERIALIZABLE isolation
        UnitOfWork.begin(() -> serializableStore.openSession(TransactionIsolation.SERIALIZABLE));
        
        // Simulate some work
        Thread.sleep(100);
        
        // Check if the other transaction is also active
        if (UnitOfWork.isActiveInOtherThread()) {
          concurrentTransactions.set(true);
        }
        
        // Commit and end the transaction
        UnitOfWork.end();
        
        completionLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        completionLatch.countDown();
      }
    });
    
    Thread thread2 = Thread.ofVirtual().name("isolation-thread-2").start(() -> {
      try {
        // Wait for both threads to start simultaneously
        barrier.await(5, TimeUnit.SECONDS);
        
        // Begin a unit of work with SERIALIZABLE isolation
        UnitOfWork.begin(() -> serializableStore.openSession(TransactionIsolation.SERIALIZABLE));
        
        // Simulate some work
        Thread.sleep(100);
        
        // Check if the other transaction is also active
        if (UnitOfWork.isActiveInOtherThread()) {
          concurrentTransactions.set(true);
        }
        
        // Commit and end the transaction
        UnitOfWork.end();
        
        completionLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        completionLatch.countDown();
      }
    });
    
    // Wait for both threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Check if any exceptions occurred
    if (threadException.get() != null) {
      fail("Exception in test threads: " + threadException.get().getMessage());
    }
    
    // Verify both transactions were committed
    verify(serializableTx, times(2)).commit();
    verify(serializableSession, times(2)).close();
    
    // With SERIALIZABLE isolation, we expect concurrent transactions to be possible with Virtual Threads
    // since they don't block each other at the thread level (database will handle isolation)
    assertThat("Concurrent transactions should be possible with Virtual Threads", concurrentTransactions.get(), is(true));
  }

  /**
   * Tests that transaction managers operate properly with Virtual Thread scheduling.
   * 
   * This verifies that transactions can be properly managed even when Virtual Threads
   * are unmounted and remounted by the scheduler during I/O operations.
   */
  @Test
  public void testTransactionManagerWithVirtualThreadScheduling() throws Exception {
    // Create a latch to coordinate between threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch midpointLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);
    
    // Reference to hold any exceptions that occur in the thread
    AtomicReference<Throwable> threadException = new AtomicReference<>();
    
    // Start a virtual thread that performs a transaction with I/O simulation
    Thread virtualThread = Thread.ofVirtual().name("scheduling-test-thread").start(() -> {
      try {
        // Wait for the test to signal start
        startLatch.await(5, TimeUnit.SECONDS);
        
        // Begin a unit of work
        UnitOfWork.begin(dataStore);
        
        // Verify the transaction is active
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        
        // Signal that we've reached the midpoint
        midpointLatch.countDown();
        
        // Simulate I/O operation that would cause the virtual thread to be unmounted
        Thread.sleep(500);
        
        // After "I/O", verify the transaction is still active
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        assertThat(UnitOfWork.peekTransaction(), is(sameInstance(transaction)));
        
        // Commit and end the transaction
        UnitOfWork.end();
        
        completionLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        midpointLatch.countDown();
        completionLatch.countDown();
      }
    });
    
    // Signal the thread to start
    startLatch.countDown();
    
    // Wait for the thread to reach the midpoint
    midpointLatch.await(5, TimeUnit.SECONDS);
    
    // Verify the transaction is active at this point
    verify(transaction, never()).commit();
    verify(transaction, never()).rollback();
    
    // Wait for the thread to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Check if any exceptions occurred
    if (threadException.get() != null) {
      fail("Exception in test thread: " + threadException.get().getMessage());
    }
    
    // Verify the transaction was committed
    verify(transaction).commit();
    verify(dataSession).close();
  }

  /**
   * Tests that exceptions during transaction processing are properly propagated across Virtual Thread boundaries.
   * 
   * This verifies that when an exception occurs in one Virtual Thread, it can be properly caught and handled
   * in another Virtual Thread, ensuring robust error handling in asynchronous operations.
   */
  @Test
  public void testExceptionPropagationAcrossVirtualThreads() throws Exception {
    // Create a latch to coordinate between threads
    CountDownLatch threadHandoffLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(1);
    
    // Reference to hold any exceptions that occur in the threads
    AtomicReference<Throwable> threadException = new AtomicReference<>();
    
    // Configure the transaction to throw an exception on commit
    doThrow(new RuntimeException("Simulated commit failure")).when(transaction).commit();
    
    // Start a virtual thread that begins a transaction
    Thread firstThread = Thread.ofVirtual().name("first-thread").start(() -> {
      try {
        // Begin a unit of work and open a session
        UnitOfWork.begin(dataStore);
        
        // Verify the transaction is active
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        
        // Signal the second thread to continue
        threadHandoffLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        threadHandoffLatch.countDown();
      }
    });
    
    // Start a second virtual thread that tries to commit the transaction
    Thread secondThread = Thread.ofVirtual().name("second-thread").start(() -> {
      try {
        // Wait for the first thread to begin the transaction
        threadHandoffLatch.await(5, TimeUnit.SECONDS);
        
        // If an exception occurred in the first thread, don't proceed
        if (threadException.get() != null) {
          completionLatch.countDown();
          return;
        }
        
        // Verify we can access the same transaction
        assertThat(UnitOfWork.peekTransaction(), is(notNullValue()));
        
        // Try to commit the transaction - this should throw an exception
        assertThrows(RuntimeException.class, () -> UnitOfWork.end());
        
        completionLatch.countDown();
      }
      catch (Throwable t) {
        threadException.set(t);
        completionLatch.countDown();
      }
    });
    
    // Wait for both threads to complete
    completionLatch.await(5, TimeUnit.SECONDS);
    
    // Check if any unexpected exceptions occurred
    if (threadException.get() != null) {
      fail("Unexpected exception in test threads: " + threadException.get().getMessage());
    }
    
    // Verify the transaction commit was attempted but failed
    verify(transaction).commit();
    verify(dataSession).close();
  }
}