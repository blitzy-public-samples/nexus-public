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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.base.Suppliers;
import com.google.inject.Guice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assertions;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.transaction.Transactional.DEFAULT_REASON;

/**
 * Test transactional behavior with Java 21 Virtual Threads.
 * 
 * This test class verifies that transaction boundaries, commit, rollback, and retry mechanisms
 * function correctly in a virtual thread context, ensuring that the core transaction support
 * functions properly with the new lightweight threading model introduced in Java 21.
 */
@SuppressWarnings("boxing")
@ExtendWith(MockitoExtension.class)
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
  
  // Virtual thread factory for test cases
  ThreadFactory virtualThreadFactory;

  @BeforeEach
  public void setUp() throws Exception {
    when(session.getTransaction()).thenReturn(tx);
    UnitOfWork.begin(Suppliers.ofInstance(session));

    when(tx.isActive()).thenAnswer(new Answer<Boolean>() {
      @Override
      public Boolean answer(final InvocationOnMock invocation) throws Throwable {
        return isActive;
      }
    });

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = true;
        return null;
      }
    }).when(tx).begin();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = false;
        if (throwExceptionOnCommit) {
          throw new ConcurrentModificationException();
        }
        return null;
      }
    }).when(tx).commit();

    doAnswer(new Answer<Void>() {
      @Override
      public Void answer(final InvocationOnMock invocation) throws Throwable {
        isActive = false;
        return null;
      }
    }).when(tx).rollback();
    
    // Initialize virtual thread factory
    virtualThreadFactory = Thread.ofVirtual().name("vt-test-", 0).factory();
  }

  @AfterEach
  public void tearDown() {
    UnitOfWork.end();
  }

  /**
   * Tests basic transaction execution in a virtual thread.
   * Verifies that a simple transactional method can be executed successfully in a virtual thread context.
   */
  @Test
  public void testBasicTransactionInVirtualThread() throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          result.set(methods.transactional());
        }
        finally {
          UnitOfWork.end();
        }
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertEquals("success", result.get(), "Transaction should execute successfully in virtual thread");
    
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
   * Tests nested transactions in virtual threads.
   * Verifies that nested transactional methods work correctly when executed in a virtual thread.
   */
  @Test
  public void testNestedTransactionsInVirtualThread() throws Exception {
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          result.set(methods.outer());
        }
        finally {
          UnitOfWork.end();
        }
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertEquals("success", result.get(), "Nested transactions should execute successfully in virtual thread");
    
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
   * Tests transaction rollback on exception in a virtual thread.
   * Verifies that transactions are properly rolled back when an exception occurs in a virtual thread.
   */
  @Test
  public void testRollbackOnExceptionInVirtualThread() throws Exception {
    AtomicBoolean exceptionThrown = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          methods.rollbackOnUncheckedException();
        }
        catch (IllegalStateException e) {
          exceptionThrown.set(true);
        }
        finally {
          UnitOfWork.end();
        }
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertTrue(exceptionThrown.get(), "Exception should be thrown in virtual thread");
    
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
   * Tests transaction retry mechanism in a virtual thread.
   * Verifies that transaction retry works correctly when executed in a virtual thread.
   */
  @Test
  public void testRetryMechanismInVirtualThread() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true);
    
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          methods.setCountdownToSuccess(3);
          result.set(methods.retryOnUncheckedException());
        }
        finally {
          UnitOfWork.end();
        }
      }
      catch (Exception e) {
        result.set("failed: " + e.getMessage());
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertEquals("success", result.get(), "Retry mechanism should work in virtual thread");
    
    InOrder order = inOrder(session, tx);
    order.verify(session).getTransaction();
    order.verify(tx).reason(DEFAULT_REASON);
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).rollback();
    order.verify(tx).allowRetry(any(IllegalStateException.class));
    order.verify(tx).begin();
    order.verify(tx).commit();
    order.verify(tx).end();
    order.verify(session).close();
    verifyNoMoreInteractions(session, tx);
  }

  /**
   * Tests thread-local variable propagation in virtual threads.
   * Verifies that thread-local variables are properly isolated between virtual threads.
   */
  @Test
  public void testThreadLocalIsolationInVirtualThreads() throws Exception {
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("main-thread");
    
    AtomicReference<String> virtualThreadValue = new AtomicReference<>();
    AtomicReference<String> mainThreadValueAfter = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Get initial value in virtual thread
        virtualThreadValue.set(threadLocal.get());
        
        // Set a new value in the virtual thread
        threadLocal.set("virtual-thread");
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    
    // Check value in main thread after virtual thread execution
    mainThreadValueAfter.set(threadLocal.get());
    
    // Virtual thread should start with null (not inheriting from carrier thread)
    Assertions.assertNull(virtualThreadValue.get(), "Virtual thread should not inherit ThreadLocal values");
    
    // Main thread value should remain unchanged
    Assertions.assertEquals("main-thread", mainThreadValueAfter.get(), 
        "ThreadLocal in main thread should not be affected by virtual thread");
  }

  /**
   * Tests transaction context propagation across virtual thread boundaries.
   * Verifies that transaction context is properly maintained when crossing virtual thread boundaries.
   */
  @Test
  public void testTransactionContextPropagationAcrossVirtualThreads() throws Exception {
    AtomicBoolean success = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start a transaction in the main thread
    methods.transactional(); // This will set up a transaction
    
    // Now try to access the transaction from a virtual thread
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // This should fail because the transaction context is not propagated to the virtual thread
        UnitOfWork.currentTx();
        success.set(false);
      }
      catch (IllegalStateException e) {
        // Expected - transaction context should not be available
        success.set(true);
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertTrue(success.get(), "Transaction context should not propagate to virtual thread");
  }

  /**
   * Tests concurrent transactions in multiple virtual threads.
   * Verifies that multiple virtual threads can execute transactions concurrently without interference.
   */
  @Test
  public void testConcurrentTransactionsInVirtualThreads() throws Exception {
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    AtomicBoolean success = new AtomicBoolean(true);
    
    // Create multiple virtual threads, each with its own transaction session
    for (int i = 0; i < threadCount; i++) {
      final int threadId = i;
      final TransactionalSession<Transaction> threadSession = mock(TransactionalSession.class);
      final Transaction threadTx = mock(Transaction.class);
      when(threadSession.getTransaction()).thenReturn(threadTx);
      
      // Set up the mock behavior for this thread's transaction
      when(threadTx.isActive()).thenReturn(false).thenReturn(true).thenReturn(false);
      
      Thread virtualThread = virtualThreadFactory.newThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Begin a unit of work with this thread's session
          UnitOfWork.begin(Suppliers.ofInstance(threadSession));
          try {
            // Execute a transactional operation
            String result = "success-" + threadId;
            
            // Verify transaction was started
            if (!threadTx.isActive()) {
              success.set(false);
            }
          }
          finally {
            UnitOfWork.end();
          }
        }
        catch (Exception e) {
          success.set(false);
        }
        finally {
          completionLatch.countDown();
        }
      });
      
      virtualThread.start();
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    Assertions.assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertTrue(success.get(), "All virtual threads should execute transactions successfully");
  }

  /**
   * Tests transaction commit failure and retry in a virtual thread.
   * Verifies that transaction commit failures are properly handled in virtual threads.
   */
  @Test
  public void testCommitFailureAndRetryInVirtualThread() throws Exception {
    when(tx.allowRetry(any(Exception.class))).thenReturn(true).thenReturn(false);
    
    AtomicBoolean exceptionThrown = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          throwExceptionOnCommit = true;
          methods.retryOnCommitFailure();
        }
        catch (ConcurrentModificationException e) {
          exceptionThrown.set(true);
        }
        finally {
          throwExceptionOnCommit = false;
          UnitOfWork.end();
        }
      }
      finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
    Assertions.assertTrue(exceptionThrown.get(), "Exception should be thrown after retry failure");
    
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

  /**
   * Tests transaction execution with a virtual thread executor service.
   * Verifies that transactions work correctly when executed through a virtual thread executor.
   */
  @Test
  public void testTransactionWithVirtualThreadExecutor() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    try {
      CompletableFuture.runAsync(() -> {
        try {
          UnitOfWork.begin(Suppliers.ofInstance(session));
          try {
            result.set(methods.transactional());
          }
          finally {
            UnitOfWork.end();
          }
        }
        finally {
          latch.countDown();
        }
      }, executor);
      
      Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS), "Virtual thread execution timed out");
      Assertions.assertEquals("success", result.get(), "Transaction should execute successfully in virtual thread executor");
      
      InOrder order = inOrder(session, tx);
      order.verify(session).getTransaction();
      order.verify(tx).reason(DEFAULT_REASON);
      order.verify(tx).begin();
      order.verify(tx).commit();
      order.verify(tx).end();
      order.verify(session).close();
      verifyNoMoreInteractions(session, tx);
    }
    finally {
      executor.shutdown();
    }
  }
}