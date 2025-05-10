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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests transaction context propagation across different virtual thread execution scenarios.
 * 
 * @since 3.60
 */
public class TransactionVirtualThreadPropagationTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(TransactionVirtualThreadPropagationTest.class);
  
  private static final String MDC_TEST_KEY = "test-key";
  private static final String MDC_TEST_VALUE = "test-value";
  
  private MockTransactionalStore store;
  
  @Before
  public void setUp() {
    store = new MockTransactionalStore();
  }
  
  @After
  public void tearDown() {
    // Ensure MDC is cleared after each test
    MDC.clear();
    
    // Ensure UnitOfWork is cleared after each test
    try {
      UnitOfWork.end();
    }
    catch (IllegalStateException e) {
      // Ignore if no work was set
    }
  }
  
  /**
   * Tests that transaction context is properly propagated to a virtual thread.
   */
  @Test
  public void testVirtualThreadTransactionPropagation() throws Exception {
    // Start a unit of work in the main thread
    UnitOfWork.begin(store);
    try {
      // Set MDC context in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Create a virtual thread and verify transaction context is accessible
      Thread virtualThread = Thread.ofVirtual().name("test-virtual-thread").start(() -> {
        // Verify we're running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Verify UnitOfWork context is propagated
        Transaction tx = UnitOfWork.peekTransaction();
        assertThat(tx, notNullValue());
        assertThat(tx.isVirtualThread(), is(true));
        
        // Verify MDC context is propagated
        assertThat(MDC.get(MDC_TEST_KEY), equalTo(MDC_TEST_VALUE));
      });
      
      // Wait for the virtual thread to complete
      virtualThread.join();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests transaction context propagation using structured concurrency with virtual threads.
   */
  @Test
  public void testStructuredConcurrencyWithVirtualThreads() throws Exception {
    // Start a unit of work in the main thread
    UnitOfWork.begin(store);
    try {
      // Set MDC context in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Use StructuredTaskScope to manage virtual threads
      try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
        // Fork multiple subtasks as virtual threads
        List<StructuredTaskScope.Subtask<Boolean>> subtasks = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
          final int taskId = i;
          subtasks.add(scope.fork(() -> {
            // Verify we're running in a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            log.info("Task {} running in {}", taskId, UnitOfWork.getThreadTypeDescription());
            
            // Verify UnitOfWork context is propagated
            Transaction tx = UnitOfWork.peekTransaction();
            assertThat(tx, notNullValue());
            assertThat(tx.isVirtualThread(), is(true));
            
            // Verify MDC context is propagated
            assertThat(MDC.get(MDC_TEST_KEY), equalTo(MDC_TEST_VALUE));
            
            return true;
          }));
        }
        
        // Wait for all subtasks to complete
        scope.join();
        scope.throwIfFailed();
        
        // Verify all subtasks completed successfully
        for (var subtask : subtasks) {
          assertThat(subtask.get(), is(true));
        }
      }
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests transaction context propagation using a virtual thread executor service.
   */
  @Test
  public void testVirtualThreadExecutorService() throws Exception {
    // Start a unit of work in the main thread
    UnitOfWork.begin(store);
    try {
      // Set MDC context in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Create a virtual thread executor
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Submit multiple tasks
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < 5; i++) {
          final int taskId = i;
          futures.add(executor.submit(() -> {
            // Verify we're running in a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            log.info("Executor task {} running in {}", taskId, UnitOfWork.getThreadTypeDescription());
            
            // Verify UnitOfWork context is propagated
            Transaction tx = UnitOfWork.peekTransaction();
            assertThat(tx, notNullValue());
            assertThat(tx.isVirtualThread(), is(true));
            
            // Verify MDC context is propagated
            assertThat(MDC.get(MDC_TEST_KEY), equalTo(MDC_TEST_VALUE));
            
            return true;
          }));
        }
        
        // Wait for all tasks to complete and verify results
        for (Future<Boolean> future : futures) {
          assertThat(future.get(), is(true));
        }
      }
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests transaction context propagation with nested virtual threads.
   */
  @Test
  public void testNestedVirtualThreads() throws Exception {
    // Start a unit of work in the main thread
    UnitOfWork.begin(store);
    try {
      // Set MDC context in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Create a virtual thread
      Thread outerThread = Thread.ofVirtual().name("outer-virtual-thread").start(() -> {
        // Verify we're running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        log.info("Outer thread running in {}", UnitOfWork.getThreadTypeDescription());
        
        // Verify UnitOfWork context is propagated to outer thread
        Transaction outerTx = UnitOfWork.peekTransaction();
        assertThat(outerTx, notNullValue());
        assertThat(outerTx.isVirtualThread(), is(true));
        
        // Verify MDC context is propagated to outer thread
        assertThat(MDC.get(MDC_TEST_KEY), equalTo(MDC_TEST_VALUE));
        
        try {
          // Create a nested virtual thread
          Thread innerThread = Thread.ofVirtual().name("inner-virtual-thread").start(() -> {
            // Verify we're running in a virtual thread
            assertThat(Thread.currentThread().isVirtual(), is(true));
            
            log.info("Inner thread running in {}", UnitOfWork.getThreadTypeDescription());
            
            // Verify UnitOfWork context is propagated to inner thread
            Transaction innerTx = UnitOfWork.peekTransaction();
            assertThat(innerTx, notNullValue());
            assertThat(innerTx.isVirtualThread(), is(true));
            
            // Verify MDC context is propagated to inner thread
            assertThat(MDC.get(MDC_TEST_KEY), equalTo(MDC_TEST_VALUE));
          });
          
          // Wait for the inner thread to complete
          innerThread.join();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException("Interrupted while waiting for inner thread", e);
        }
      });
      
      // Wait for the outer thread to complete
      outerThread.join();
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Tests transaction context isolation between different virtual threads.
   */
  @Test
  public void testVirtualThreadContextIsolation() throws Exception {
    // Create a map to store transaction references from each thread
    Map<String, Transaction> threadTransactions = new ConcurrentHashMap<>();
    
    // Create and start the first virtual thread with its own transaction
    Thread thread1 = Thread.ofVirtual().name("thread-1").start(() -> {
      // Start a unit of work in this thread
      UnitOfWork.begin(store);
      try {
        // Set thread-specific MDC context
        MDC.put(MDC_TEST_KEY, "thread-1-value");
        
        // Store the transaction reference
        threadTransactions.put("thread-1", UnitOfWork.peekTransaction());
        
        // Sleep to allow the other thread to run
        try {
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        
        // Verify MDC context is still correct
        assertThat(MDC.get(MDC_TEST_KEY), equalTo("thread-1-value"));
      }
      finally {
        UnitOfWork.end();
      }
    });
    
    // Create and start the second virtual thread with its own transaction
    Thread thread2 = Thread.ofVirtual().name("thread-2").start(() -> {
      // Start a unit of work in this thread
      UnitOfWork.begin(store);
      try {
        // Set thread-specific MDC context
        MDC.put(MDC_TEST_KEY, "thread-2-value");
        
        // Store the transaction reference
        threadTransactions.put("thread-2", UnitOfWork.peekTransaction());
        
        // Sleep to allow the other thread to run
        try {
          Thread.sleep(100);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        
        // Verify MDC context is still correct
        assertThat(MDC.get(MDC_TEST_KEY), equalTo("thread-2-value"));
      }
      finally {
        UnitOfWork.end();
      }
    });
    
    // Wait for both threads to complete
    thread1.join();
    thread2.join();
    
    // Verify that each thread had its own transaction
    assertThat(threadTransactions.get("thread-1"), notNullValue());
    assertThat(threadTransactions.get("thread-2"), notNullValue());
    assertThat(threadTransactions.get("thread-1") != threadTransactions.get("thread-2"), is(true));
  }
  
  /**
   * Tests transaction context propagation when pausing and resuming work across virtual threads.
   */
  @Test
  public void testPauseResumeAcrossVirtualThreads() throws Exception {
    // Start a unit of work in the main thread
    UnitOfWork.begin(store);
    try {
      // Set MDC context in the main thread
      MDC.put(MDC_TEST_KEY, MDC_TEST_VALUE);
      
      // Get the current transaction
      Transaction mainTx = UnitOfWork.peekTransaction();
      assertThat(mainTx, notNullValue());
      
      // Pause the unit of work
      UnitOfWork pausedWork = UnitOfWork.pause();
      assertThat(pausedWork, notNullValue());
      assertThat(UnitOfWork.peekTransaction(), nullValue());
      
      // Create an atomic reference to hold the transaction from the virtual thread
      AtomicReference<Transaction> virtualThreadTx = new AtomicReference<>();
      
      // Create a virtual thread and resume the work there
      Thread virtualThread = Thread.ofVirtual().name("resume-virtual-thread").start(() -> {
        // Verify we're running in a virtual thread
        assertThat(Thread.currentThread().isVirtual(), is(true));
        
        // Verify no transaction is active before resuming
        assertThat(UnitOfWork.peekTransaction(), nullValue());
        
        // Resume the paused work
        UnitOfWork.resume(pausedWork);
        
        try {
          // Get the transaction in the virtual thread
          Transaction tx = UnitOfWork.peekTransaction();
          virtualThreadTx.set(tx);
          
          // Verify transaction is available
          assertThat(tx, notNullValue());
          assertThat(tx.isVirtualThread(), is(true));
          
          // Verify MDC context is propagated
          assertThat(MDC.get(MDC_TEST_KEY), equalTo(MDC_TEST_VALUE));
        }
        finally {
          // Pause the work again so it can be resumed in the main thread
          UnitOfWork pausedAgain = UnitOfWork.pause();
          assertThat(pausedAgain, notNullValue());
          assertThat(UnitOfWork.peekTransaction(), nullValue());
        }
      });
      
      // Wait for the virtual thread to complete
      virtualThread.join();
      
      // Resume the work in the main thread
      UnitOfWork.resume(pausedWork);
      
      // Verify the transaction is available again in the main thread
      Transaction resumedTx = UnitOfWork.peekTransaction();
      assertThat(resumedTx, notNullValue());
      
      // Verify it's the same transaction that was in the virtual thread
      assertThat(resumedTx, equalTo(virtualThreadTx.get()));
    }
    finally {
      UnitOfWork.end();
    }
  }
  
  /**
   * Mock implementation of TransactionalStore for testing.
   */
  private static class MockTransactionalStore
      implements TransactionalStore<Transaction>
  {
    @Override
    public TransactionalSession<Transaction> openSession(TransactionIsolation isolation) {
      return new MockTransactionalSession();
    }
  }
  
  /**
   * Mock implementation of TransactionalSession for testing.
   */
  private static class MockTransactionalSession
      implements TransactionalSession<Transaction>
  {
    private final MockTransaction transaction = new MockTransaction();
    
    @Override
    public Transaction getTransaction() {
      return transaction;
    }
    
    @Override
    public void close() {
      // No-op for testing
    }
  }
  
  /**
   * Mock implementation of Transaction for testing.
   */
  private static class MockTransaction
      implements Transaction
  {
    private boolean active = false;
    private String reason = "test";
    
    @Override
    public void begin() {
      active = true;
    }
    
    @Override
    public void commit() {
      active = false;
    }
    
    @Override
    public void rollback() {
      active = false;
    }
    
    @Override
    public boolean isActive() {
      return active;
    }
    
    @Override
    public boolean allowRetry(Exception cause) {
      return false;
    }
    
    @Override
    public void reason(String reason) {
      this.reason = reason;
    }
    
    @Override
    public String reason() {
      return reason;
    }
    
    @Override
    public boolean isVirtualThread() {
      return Thread.currentThread().isVirtual();
    }
  }
}