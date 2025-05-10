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
package org.sonatype.nexus.transaction;

import java.io.IOException;
import java.util.ConcurrentModificationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

// Import for Java 21 Virtual Threads

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.base.Suppliers;
import com.google.inject.Guice;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.transaction.Transactional.DEFAULT_REASON;

/**
 * Test transactional behaviour with Java 21 Virtual Threads.
 * 
 * This test class verifies that transaction boundaries, commit, rollback, and retry mechanisms
 * function correctly when executed on Java 21 Virtual Threads. It ensures that the core transaction
 * support functions properly with the new lightweight threading model introduced in Java 21.
 */
@SuppressWarnings("boxing")
public class VirtualThreadTransactionalTest
    extends TestSupport
{
  ExampleMethods methods = Guice.createInjector(new TransactionModule()).getInstance(ExampleMethods.class);

  @Mock
  TransactionalSession<Transaction> session;

  @Mock
  Transaction tx;

  boolean isActive;

  boolean throwExceptionOnCommit;

  @Before
  public void setUp() throws Exception {
    when(session.getTransaction()).thenReturn(tx);
    UnitOfWork.begin(Suppliers.ofInstance(session));

    when(tx.isActive()).thenAnswer(new Answer<Boolean>()
    {
      @Override
      public Boolean answer(final InvocationOnMock invocation) throws Throwable {
        return isActive;
      }
    });

    doAnswer(new Answer<Void>()
    {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = true;
        return null;
      }
    }).when(tx).begin();

    doAnswer(new Answer<Void>()
    {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = false;
        if (throwExceptionOnCommit) {
          throw new ConcurrentModificationException();
        }
        return null;
      }
    }).when(tx).commit();

    doAnswer(new Answer<Void>()
    {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = false;
        return null;
      }
    }).when(tx).rollback();
  }

  @After
  public void tearDown() {
    UnitOfWork.end();
  }

  /**
   * Test basic transactional behavior on a virtual thread.
   */
  @Test
  public void testTransactionalOnVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.transactional();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test multiple transactional operations on virtual threads.
   */
  @Test
  public void testMultipleTransactionsOnVirtualThreads() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    
    CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
      try {
        methods.transactional();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, executor);

    CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
      try {
        methods.transactional();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, executor);

    CompletableFuture<Void> future3 = CompletableFuture.runAsync(() -> {
      try {
        methods.transactional();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, executor);

    CompletableFuture.allOf(future1, future2, future3).get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test custom transaction reason on a virtual thread.
   */
  @Test
  public void testCustomReasonOnVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.customReason();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason("Testing!");
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test nested transactions on virtual threads.
   */
  @Test
  public void testNestedOnVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.outer();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(session).getTransaction();
    order.verify(tx).isActive();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test cross-thread nested transactions with virtual threads.
   * 
   * This test verifies that transactions can be properly managed across different virtual threads.
   * It creates an outer transaction in one virtual thread and an inner transaction in another,
   * ensuring that the transaction boundaries are respected and that the operations complete successfully.
   * This is particularly important for Java 21 Virtual Threads which have different thread-local
   * inheritance behavior compared to platform threads.
   */
  @Test
  public void testCrossThreadNestedTransactions() throws Exception {
    CountDownLatch outerStarted = new CountDownLatch(1);
    CountDownLatch innerCompleted = new CountDownLatch(1);
    AtomicReference<Exception> innerException = new AtomicReference<>();

    CompletableFuture<Void> outerFuture = CompletableFuture.runAsync(() -> {
      try {
        // Start outer transaction
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          tx.begin();
          isActive = true;
          outerStarted.countDown();
          
          // Wait for inner transaction to complete
          innerCompleted.await(5, TimeUnit.SECONDS);
          
          tx.commit();
        }
        finally {
          tx.end();
          UnitOfWork.end();
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    CompletableFuture<Void> innerFuture = CompletableFuture.runAsync(() -> {
      try {
        // Wait for outer transaction to start
        outerStarted.await(5, TimeUnit.SECONDS);
        
        // Run inner transaction
        methods.transactional();
        innerCompleted.countDown();
      }
      catch (Exception e) {
        innerException.set(e);
        innerCompleted.countDown();
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    CompletableFuture.allOf(outerFuture, innerFuture).get(10, TimeUnit.SECONDS);
    
    if (innerException.get() != null) {
      throw innerException.get();
    }

    InOrder order = inOrder(session, tx);
    // Verify outer transaction
    order.verify(session).getTransaction();
    // Verify inner transaction
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test thread-local propagation in virtual threads.
   * 
   * This test verifies that thread-locals are not automatically inherited by virtual threads in Java 21.
   * This is important to understand as it affects how UnitOfWork and transaction contexts are managed
   * across thread boundaries. Applications must explicitly handle thread-local propagation when using
   * virtual threads.
   */
  @Test
  public void testThreadLocalPropagationInVirtualThreads() throws Exception {
    // Set up a thread-local value in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    
    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Check if UnitOfWork is properly propagated to virtual thread
        Transaction currentTx = UnitOfWork.peekTransaction();
        return currentTx != null;
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    // Virtual threads should not inherit thread-locals by default in Java 21
    assertThat(future.get(5, TimeUnit.SECONDS), is(false));
    
    UnitOfWork.end();
  }

  /**
   * Test rollback on checked exception in a virtual thread.
   */
  @Test
  public void testRollbackOnCheckedExceptionInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.rollbackOnCheckedException();
      }
      catch (IOException expected) {
        // Expected exception
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test rollback on unchecked exception in a virtual thread.
   */
  @Test
  public void testRollbackOnUncheckedExceptionInVirtualThread() throws Exception {
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.rollbackOnUncheckedException();
      }
      catch (IllegalStateException expected) {
        // Expected exception
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test retry success on checked exception in a virtual thread.
   */
  @Test
  public void testRetrySuccessOnCheckedExceptionInVirtualThread() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true);

    methods.setCountdownToSuccess(3);
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.retryOnCheckedException();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test retry failure on checked exception in a virtual thread.
   */
  @Test
  public void testRetryFailureOnCheckedExceptionInVirtualThread() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    methods.setCountdownToSuccess(100);
    
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      try {
        methods.retryOnCheckedException();
      }
      catch (IOException expected) {
        // Expected exception
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, Executors.newVirtualThreadPerTaskExecutor());

    future.get(5, TimeUnit.SECONDS);

    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IOException.class));
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Test retry on commit failure in a virtual thread.
   * 
   * This test verifies that transaction retry mechanisms work correctly when a commit operation fails
   * in a virtual thread context. It simulates a ConcurrentModificationException during commit and
   * ensures that the retry logic is properly applied before ultimately failing after the retry limit
   * is reached. This is critical for ensuring data consistency in high-concurrency environments using
   * virtual threads.
   */
  @Test
  public void testRetryOnCommitFailureInVirtualThread() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    try {
      throwExceptionOnCommit = true;
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          methods.retryOnCommitFailure();
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());

      ExecutionException exception = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      assertThat(exception.getCause().getCause(), is(ConcurrentModificationException.class));
    }
    finally {
      throwExceptionOnCommit = false;
      InOrder order = inOrder(session, tx);
      order.verify(session).getTransaction();
      order.verify(tx).reason(DEFAULT_REASON);
      order.verify(tx).begin();
      order.verify(tx).commit();
      order.verify(tx).rollback();
      order.verify(tx).allowRetry(any(ConcurrentModificationException.class));
      order.verify(tx).begin();
      order.verify(tx).commit();
      order.verify(tx).rollback();
      order.verify(tx).allowRetry(any(ConcurrentModificationException.class));
      order.verify(tx).end();
      order.verify(session).close();
      verifyNoMoreInteractions(session, tx);
    }
  }

  /**
   * Test swallow commit failure in a virtual thread.
   */
  @Test
  public void testSwallowCommitFailureInVirtualThread() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);

    try {
      throwExceptionOnCommit = true;
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          methods.swallowCommitFailure();
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, Executors.newVirtualThreadPerTaskExecutor());

      future.get(5, TimeUnit.SECONDS);
    }
    finally {
      throwExceptionOnCommit = false;
      InOrder order = inOrder(session, tx);
      order.verify(session).getTransaction();
      order.verify(tx).reason(DEFAULT_REASON);
      order.verify(tx).begin();
      order.verify(tx).commit();
      order.verify(tx).rollback();
      order.verify(tx).end();
      order.verify(session).close();
      verifyNoMoreInteractions(session, tx);
    }
  }

  /**
   * Test concurrent virtual thread transactions with shared resources.
   * 
   * This test verifies that multiple virtual threads can concurrently execute transactional operations
   * without interfering with each other. It creates a pool of virtual threads that all execute
   * transactional methods simultaneously, then verifies that all transactions were properly managed.
   * This test is particularly important for Java 21 Virtual Threads which are designed to support
   * high concurrency scenarios with minimal overhead.
   */
  @Test
  public void testConcurrentVirtualThreadTransactions() throws Exception {
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    int numThreads = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(numThreads);
    
    CompletableFuture<?>[] futures = new CompletableFuture[numThreads];
    
    for (int i = 0; i < numThreads; i++) {
      final int threadNum = i;
      futures[i] = CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Run transactional method
          methods.transactional();
          
          completionLatch.countDown();
        }
        catch (Exception e) {
          throw new RuntimeException("Thread " + threadNum + " failed", e);
        }
      }, executor);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await(10, TimeUnit.SECONDS);
    CompletableFuture.allOf(futures).get(5, TimeUnit.SECONDS);
    
    // Verify that transaction methods were called the correct number of times
    // Note: We can't verify exact order due to concurrent execution
    InOrder order = inOrder(session, tx);
    for (int i = 0; i < numThreads; i++) {
      order.verify(session).getTransaction();
      order.verify(tx).reason(DEFAULT_REASON);
      order.verify(tx).begin();
      order.verify(tx).commit();
      order.verify(tx).end();
      order.verify(session).close();
    }
    verifyNoMoreInteractions(session, tx);
  }
}