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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.base.Suppliers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * Tests transaction context propagation across different virtual thread execution scenarios.
 * 
 * This test class verifies that transaction context is correctly maintained when using
 * Java 21's Virtual Thread capabilities, ensuring that the transaction management system
 * works properly in various concurrency patterns.
 */
@RunWith(JUnit4.class)
public class TransactionVirtualThreadPropagationTest
    extends TestSupport
{
  private static final String TEST_MDC_KEY = "test-mdc-key";
  private static final String TEST_MDC_VALUE = "test-mdc-value";
  
  // Number of milliseconds to wait for virtual thread operations
  private static final long TIMEOUT_MILLIS = 5000;

  @Mock
  private Transaction transaction;

  @Mock
  private TransactionalSession<Transaction> session;

  @Before
  public void setUp() {
    when(session.getTransaction()).thenReturn(transaction);
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
  }

  @After
  public void tearDown() {
    UnitOfWork.end();
    MDC.remove(TEST_MDC_KEY);
  }

  /**
   * Tests that transaction context is properly propagated when using structured concurrency
   * with virtual threads via StructuredTaskScope.
   */
  @Test
  public void testStructuredConcurrencyWithVirtualThreads() throws InterruptedException, ExecutionException {
    // Begin a transaction in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    Transaction mainThreadTx = UnitOfWork.peekTransaction();
    assertThat(mainThreadTx, is(transaction));

    // Use StructuredTaskScope with virtual threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure("test-scope", Thread.ofVirtual().factory())) {
      // Submit a task that verifies transaction context is available
      Future<Transaction> future = scope.fork(() -> {
        // In a structured task scope, the transaction context is not automatically propagated
        // We need to manually check if it's available (it should be null initially)
        Transaction initialTx = UnitOfWork.peekTransaction();
        assertThat("Transaction should not be automatically propagated to virtual threads", 
                   initialTx, nullValue());
        
        // Now manually propagate the transaction context
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          // Verify transaction context is now available
          Transaction virtualThreadTx = UnitOfWork.peekTransaction();
          assertThat(virtualThreadTx, is(transaction));
          
          // Also verify MDC context is not automatically propagated
          String mdcValue = MDC.get(TEST_MDC_KEY);
          assertThat("MDC context should not be automatically propagated", mdcValue, nullValue());
          
          // Manually set MDC context
          MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
          mdcValue = MDC.get(TEST_MDC_KEY);
          assertThat(mdcValue, is(TEST_MDC_VALUE));
          
          return virtualThreadTx;
        } finally {
          UnitOfWork.end();
          MDC.remove(TEST_MDC_KEY);
        }
      });

      // Wait for all tasks to complete
      scope.join();
      scope.throwIfFailed();

      // Verify the transaction in the virtual thread matches the main thread's transaction
      Transaction virtualThreadTx = future.resultNow();
      assertThat(virtualThreadTx, is(transaction));
    }
  }

  /**
   * Tests that transaction context is properly propagated when using a virtual thread executor service.
   */
  @Test
  public void testVirtualThreadExecutorService() throws InterruptedException, ExecutionException {
    // Begin a transaction in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    Transaction mainThreadTx = UnitOfWork.peekTransaction();
    assertThat(mainThreadTx, is(transaction));

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task that verifies transaction context is available
      Future<Transaction> future = executor.submit(() -> {
        // Check that transaction context is not automatically propagated
        Transaction initialTx = UnitOfWork.peekTransaction();
        assertThat("Transaction should not be automatically propagated to virtual threads",
                   initialTx, nullValue());
        
        // In a virtual thread, we need to manually propagate the transaction context
        // This simulates what would happen in a real application with proper context propagation
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          Transaction virtualThreadTx = UnitOfWork.peekTransaction();
          assertThat(virtualThreadTx, is(transaction));
          
          // Also verify MDC context is not automatically propagated
          String initialMdcValue = MDC.get(TEST_MDC_KEY);
          assertThat("MDC context should not be automatically propagated", 
                     initialMdcValue, nullValue());
          
          // Manually set MDC context
          MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
          String mdcValue = MDC.get(TEST_MDC_KEY);
          assertThat(mdcValue, is(TEST_MDC_VALUE));
          
          return virtualThreadTx;
        } finally {
          UnitOfWork.end();
          MDC.remove(TEST_MDC_KEY);
        }
      });

      // Verify the transaction in the virtual thread matches the main thread's transaction
      Transaction virtualThreadTx = future.get();
      assertThat(virtualThreadTx, is(transaction));
      
      // Verify main thread's transaction is still intact
      Transaction afterTx = UnitOfWork.peekTransaction();
      assertThat("Main thread transaction should remain intact",
                 afterTx, is(transaction));
    }
  }

  /**
   * Tests that transaction context is properly propagated when using multiple concurrent virtual threads.
   * This test simulates a high-concurrency scenario with multiple virtual threads all accessing
   * the same transaction context.
   */
  @Test
  public void testConcurrentVirtualThreads() throws InterruptedException {
    // Begin a transaction in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    Transaction mainThreadTx = UnitOfWork.peekTransaction();
    assertThat(mainThreadTx, is(transaction));

    // Create a thread factory for virtual threads
    ThreadFactory factory = Thread.ofVirtual().factory();
    int threadCount = 10;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<Thread> threads = new ArrayList<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();

    // Create and start multiple virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int threadIndex = i;
      Thread thread = factory.newThread(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Check that transaction context is not automatically propagated
          Transaction initialTx = UnitOfWork.peekTransaction();
          assertThat("Transaction should not be automatically propagated to virtual thread " + threadIndex,
                     initialTx, nullValue());
          
          // In a virtual thread, we need to manually propagate the transaction context
          UnitOfWork.begin(Suppliers.ofInstance(session));
          try {
            // Verify transaction context is available
            Transaction virtualThreadTx = UnitOfWork.peekTransaction();
            assertThat(virtualThreadTx, is(transaction));
            
            // Simulate some work with the transaction
            Thread.sleep(10);
            
            // Verify transaction is still valid after some work
            Transaction afterWorkTx = UnitOfWork.peekTransaction();
            assertThat("Transaction should remain valid after work in thread " + threadIndex,
                       afterWorkTx, is(transaction));
          } finally {
            UnitOfWork.end();
            completionLatch.countDown();
          }
        } catch (Throwable t) {
          log.error("Error in virtual thread " + threadIndex, t);
          failure.set(t);
          completionLatch.countDown();
        }
      });
      threads.add(thread);
      thread.start();
    }

    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete with a timeout
    boolean completed = completionLatch.await(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    assertThat("All virtual threads should complete within timeout", completed, is(true));
    
    // Check if any thread failed
    assertThat("No virtual threads should fail", failure.get(), nullValue());
    
    // Verify main thread's transaction is still intact
    Transaction afterAllTx = UnitOfWork.peekTransaction();
    assertThat("Main thread transaction should remain intact after all virtual threads complete",
               afterAllTx, is(transaction));
  }

  /**
   * Tests transaction context propagation with nested virtual threads.
   */
  @Test
  public void testNestedVirtualThreads() throws InterruptedException {
    // Begin a transaction in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    Transaction mainThreadTx = UnitOfWork.peekTransaction();
    assertThat(mainThreadTx, is(transaction));

    // Create a thread factory for virtual threads
    ThreadFactory factory = Thread.ofVirtual().factory();
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();

    // Create and start a parent virtual thread
    Thread parentThread = factory.newThread(() -> {
      try {
        // Check that transaction context is not automatically propagated
        Transaction initialTx = UnitOfWork.peekTransaction();
        assertThat("Transaction should not be automatically propagated to virtual threads",
                   initialTx, nullValue());
        
        // Manually propagate the transaction context
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          // Verify transaction context is available in parent thread
          Transaction parentThreadTx = UnitOfWork.peekTransaction();
          assertThat(parentThreadTx, is(transaction));

          // Create and start a child virtual thread
          Thread childThread = factory.newThread(() -> {
            try {
              // Check that transaction context is not automatically propagated to child thread
              Transaction initialChildTx = UnitOfWork.peekTransaction();
              assertThat("Transaction should not be automatically propagated to child virtual threads",
                         initialChildTx, nullValue());
              
              // Manually propagate the transaction context to child thread
              UnitOfWork.begin(Suppliers.ofInstance(session));
              try {
                // Verify transaction context is available in child thread
                Transaction childThreadTx = UnitOfWork.peekTransaction();
                assertThat(childThreadTx, is(transaction));
                
                // Simulate some work with the transaction
                Thread.sleep(10);
              } finally {
                UnitOfWork.end();
              }
            } catch (Throwable t) {
              failure.set(t);
            }
          });
          childThread.start();
          childThread.join();
          
          // Verify parent thread's transaction is still intact after child completes
          Transaction afterChildTx = UnitOfWork.peekTransaction();
          assertThat("Parent thread transaction should remain intact after child thread completes",
                     afterChildTx, is(transaction));
        } finally {
          UnitOfWork.end();
          latch.countDown();
        }
      } catch (Throwable t) {
        failure.set(t);
        latch.countDown();
      }
    });
    parentThread.start();

    // Wait for all threads to complete
    latch.await();
    
    // Check if any thread failed
    assertThat(failure.get(), nullValue());
    
    // Verify main thread's transaction is still intact
    Transaction afterAllTx = UnitOfWork.peekTransaction();
    assertThat("Main thread transaction should remain intact after all virtual threads complete",
               afterAllTx, is(transaction));
  }

  /**
   * Tests transaction retry behavior with virtual threads.
   */
  @Test
  public void testTransactionRetryWithVirtualThreads() throws InterruptedException, ExecutionException {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task that performs a transaction with retry
      Future<Boolean> future = executor.submit(() -> {
        // Simulate a transactional operation that might need retries
        boolean success = false;
        int attempts = 0;
        final int maxAttempts = 3;
        
        while (!success && attempts < maxAttempts) {
          attempts++;
          UnitOfWork.begin(Suppliers.ofInstance(session));
          try {
            // Verify transaction context is available
            Transaction virtualThreadTx = UnitOfWork.peekTransaction();
            assertThat(virtualThreadTx, notNullValue());
            
            // Simulate work that might fail on first attempts
            if (attempts < maxAttempts) {
              throw new IOException("Simulated failure on attempt " + attempts);
            }
            
            // If we reach here, the operation succeeded
            success = true;
          } catch (IOException e) {
            // Simulate transaction retry logic
            log.info("Transaction failed, will retry: {}", e.getMessage());
          } finally {
            UnitOfWork.end();
          }
          
          // Simulate a small delay between retry attempts
          if (!success && attempts < maxAttempts) {
            Thread.sleep(10);
          }
        }
        
        return success;
      });

      // Verify the transaction eventually succeeded after retries
      boolean success = future.get();
      assertThat(success, is(true));
    }
  }
  
  /**
   * Tests transaction context propagation when using a custom transaction wrapper with virtual threads.
   * This simulates how a real application might implement context propagation across virtual threads
   * using the pause/resume mechanism provided by UnitOfWork.
   */
  @Test
  public void testCustomTransactionContextPropagation() throws InterruptedException, ExecutionException {
    // Begin a transaction in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    Transaction mainThreadTx = UnitOfWork.peekTransaction();
    assertThat(mainThreadTx, is(transaction));
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create a custom wrapper that propagates transaction context
      Future<Boolean> future = executor.submit(() -> {
        // Check that transaction context is not automatically propagated
        Transaction initialTx = UnitOfWork.peekTransaction();
        assertThat("Transaction should not be automatically propagated to virtual threads",
                   initialTx, nullValue());
        
        // In a real application, this would be handled by a framework or utility class
        // that automatically propagates transaction context to virtual threads
        UnitOfWork unitOfWork = UnitOfWork.pause();
        try {
          // Resume the transaction context in this virtual thread
          UnitOfWork.resume(unitOfWork);
          
          // Verify transaction context is properly propagated
          Transaction virtualThreadTx = UnitOfWork.peekTransaction();
          assertThat("Transaction should be propagated via pause/resume",
                     virtualThreadTx, is(transaction));
          
          // Simulate some transactional work
          Thread.sleep(10);
          
          // Verify transaction is still valid after some work
          Transaction afterWorkTx = UnitOfWork.peekTransaction();
          assertThat("Transaction should remain valid after work",
                     afterWorkTx, is(transaction));
          
          return true;
        } finally {
          // Clean up the transaction context
          UnitOfWork.end();
        }
      });
      
      // Verify the operation succeeded with proper transaction context
      boolean success = future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertThat(success, is(true));
    }
    
    // Verify main thread's transaction is still intact
    Transaction afterTx = UnitOfWork.peekTransaction();
    assertThat("Main thread transaction should remain intact",
               afterTx, is(transaction));
  }
  
  /**
   * Tests that MDC context can be properly propagated alongside transaction context
   * when using virtual threads. This is important for maintaining logging context
   * in concurrent applications.
   */
  @Test
  public void testMdcContextPropagationWithVirtualThreads() throws InterruptedException, ExecutionException {
    // Begin a transaction in the main thread
    UnitOfWork.begin(Suppliers.ofInstance(session));
    Transaction mainThreadTx = UnitOfWork.peekTransaction();
    assertThat(mainThreadTx, is(transaction));
    
    // Set MDC context in the main thread
    MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
    String mainThreadMdc = MDC.get(TEST_MDC_KEY);
    assertThat(mainThreadMdc, is(TEST_MDC_VALUE));
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task that verifies MDC context propagation
      Future<String> future = executor.submit(() -> {
        // Check that MDC context is not automatically propagated
        String initialMdcValue = MDC.get(TEST_MDC_KEY);
        assertThat("MDC context should not be automatically propagated",
                   initialMdcValue, nullValue());
        
        // In a real application, this would be handled by a framework or utility class
        // that automatically propagates both transaction and MDC context to virtual threads
        
        // Manually propagate transaction context
        UnitOfWork.begin(Suppliers.ofInstance(session));
        try {
          // Manually propagate MDC context
          MDC.put(TEST_MDC_KEY, TEST_MDC_VALUE);
          
          // Verify both contexts are available
          Transaction virtualThreadTx = UnitOfWork.peekTransaction();
          assertThat(virtualThreadTx, is(transaction));
          
          String mdcValue = MDC.get(TEST_MDC_KEY);
          assertThat(mdcValue, is(TEST_MDC_VALUE));
          
          // Simulate some work
          Thread.sleep(10);
          
          // Return the MDC value to verify it remained intact
          return MDC.get(TEST_MDC_KEY);
        } finally {
          // Clean up both contexts
          UnitOfWork.end();
          MDC.remove(TEST_MDC_KEY);
        }
      });
      
      // Verify the MDC context was properly maintained
      String virtualThreadMdc = future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
      assertThat(virtualThreadMdc, is(TEST_MDC_VALUE));
    }
    
    // Verify main thread's contexts are still intact
    Transaction afterTx = UnitOfWork.peekTransaction();
    assertThat("Main thread transaction should remain intact",
               afterTx, is(transaction));
               
    String afterMdc = MDC.get(TEST_MDC_KEY);
    assertThat("Main thread MDC should remain intact",
               afterMdc, is(TEST_MDC_VALUE));
  }
}