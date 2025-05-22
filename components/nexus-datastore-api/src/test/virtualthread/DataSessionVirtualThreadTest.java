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
package org.sonatype.nexus.datastore.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.transaction.Transaction;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DataSession} behavior when used with Java 21 Virtual Threads.
 * 
 * @since 3.31
 */
public class DataSessionVirtualThreadTest
    extends TestSupport
{
  private ExecutorService virtualThreadExecutor;
  
  @Mock
  private Transaction transaction;
  
  @Mock
  private DataAccess dataAccess;
  
  private TestDataSession dataSession;
  
  private static final ThreadLocal<String> TRANSACTION_CONTEXT = new ThreadLocal<>();
  
  @Before
  public void setUp() {
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    dataSession = new TestDataSession(transaction);
  }
  
  @After
  public void tearDown() {
    virtualThreadExecutor.shutdown();
    TRANSACTION_CONTEXT.remove();
  }
  
  @Test
  public void testVirtualThreadTransactionContextPreservation() throws Exception {
    // Set up transaction context in the main thread
    String mainThreadContext = "main-thread-context";
    TRANSACTION_CONTEXT.set(mainThreadContext);
    
    // Execute a task in a virtual thread that verifies the context is preserved
    Future<String> future = virtualThreadExecutor.submit(() -> {
      return dataSession.executeWithContext(() -> TRANSACTION_CONTEXT.get());
    });
    
    // Verify the context was preserved in the virtual thread
    assertThat(future.get(5, TimeUnit.SECONDS), is(mainThreadContext));
  }
  
  @Test
  public void testDataAccessAcquisitionInVirtualThread() throws Exception {
    // Set up mock for data access
    Class<TestDataAccess> dataAccessClass = TestDataAccess.class;
    when(dataSession.access(dataAccessClass)).thenReturn((TestDataAccess) dataAccess);
    
    // Execute a task in a virtual thread that acquires data access
    Future<DataAccess> future = virtualThreadExecutor.submit(() -> {
      return dataSession.executeWithContext(() -> dataSession.access(dataAccessClass));
    });
    
    // Verify data access was acquired correctly
    assertThat(future.get(5, TimeUnit.SECONDS), is(dataAccess));
    verify(dataSession, times(1)).access(dataAccessClass);
  }
  
  @Test
  public void testTransactionLifecycleInVirtualThread() throws Exception {
    // Set up transaction mock behavior
    AtomicBoolean transactionBegan = new AtomicBoolean(false);
    AtomicBoolean transactionCommitted = new AtomicBoolean(false);
    
    doAnswer(invocation -> {
      transactionBegan.set(true);
      return null;
    }).when(transaction).begin();
    
    doAnswer(invocation -> {
      transactionCommitted.set(true);
      return null;
    }).when(transaction).commit();
    
    when(transaction.isActive()).thenReturn(true);
    
    // Execute transaction operations in a virtual thread
    Future<Boolean> future = virtualThreadExecutor.submit(() -> {
      dataSession.getTransaction().begin();
      boolean result = dataSession.executeWithContext(() -> {
        // Simulate some work in the transaction
        return dataSession.getTransaction().isActive();
      });
      dataSession.getTransaction().commit();
      return result;
    });
    
    // Verify transaction operations worked correctly
    assertThat(future.get(5, TimeUnit.SECONDS), is(true));
    assertThat(transactionBegan.get(), is(true));
    assertThat(transactionCommitted.get(), is(true));
  }
  
  @Test
  public void testTransactionHooksInVirtualThread() throws Exception {
    // Set up lists to track hook execution
    List<String> executedHooks = new ArrayList<>();
    
    // Execute transaction with hooks in a virtual thread
    Future<Void> future = virtualThreadExecutor.submit(() -> {
      // Register hooks
      dataSession.preCommit(() -> {
        TRANSACTION_CONTEXT.set("pre-commit-context");
        executedHooks.add("preCommit");
      });
      
      dataSession.postCommit(() -> {
        String context = TRANSACTION_CONTEXT.get();
        executedHooks.add("postCommit:" + context);
      });
      
      dataSession.onRollback(() -> {
        executedHooks.add("rollback");
      });
      
      // Simulate transaction lifecycle
      dataSession.simulateCommit();
      
      return null;
    });
    
    // Wait for completion
    future.get(5, TimeUnit.SECONDS);
    
    // Verify hooks executed in correct order with context preservation
    assertThat(executedHooks, hasSize(2));
    assertThat(executedHooks, contains("preCommit", "postCommit:pre-commit-context"));
  }
  
  @Test
  public void testRollbackHookInVirtualThread() throws Exception {
    // Set up lists to track hook execution
    List<String> executedHooks = new ArrayList<>();
    
    // Execute transaction with rollback in a virtual thread
    Future<Void> future = virtualThreadExecutor.submit(() -> {
      // Register hooks
      dataSession.preCommit(() -> {
        executedHooks.add("preCommit");
      });
      
      dataSession.postCommit(() -> {
        executedHooks.add("postCommit");
      });
      
      dataSession.onRollback(() -> {
        executedHooks.add("rollback");
      });
      
      // Simulate transaction rollback
      dataSession.simulateRollback();
      
      return null;
    });
    
    // Wait for completion
    future.get(5, TimeUnit.SECONDS);
    
    // Verify only rollback hook executed
    assertThat(executedHooks, hasSize(1));
    assertThat(executedHooks, contains("rollback"));
  }
  
  @Test
  public void testConcurrentExecutionInVirtualThread() throws Exception {
    // Set up a context value to verify it's preserved across all tasks
    TRANSACTION_CONTEXT.set("concurrent-context");
    
    // Create tasks that will run concurrently
    Callable<String> task1 = () -> {
      Thread.sleep(100); // Simulate some work
      return "Task1:" + TRANSACTION_CONTEXT.get();
    };
    
    Callable<String> task2 = () -> {
      Thread.sleep(50); // Simulate some work
      return "Task2:" + TRANSACTION_CONTEXT.get();
    };
    
    Callable<String> task3 = () -> {
      Thread.sleep(25); // Simulate some work
      return "Task3:" + TRANSACTION_CONTEXT.get();
    };
    
    // Execute tasks concurrently in a virtual thread
    Future<Supplier<String[]>> future = virtualThreadExecutor.submit(() -> {
      return dataSession.executeConcurrently(task1, task2, task3);
    });
    
    // Get the results
    Supplier<String[]> resultsSupplier = future.get(5, TimeUnit.SECONDS);
    String[] results = resultsSupplier.get();
    
    // Verify all tasks completed with the correct context
    assertThat(results.length, is(3));
    assertThat(results[0], is("Task1:concurrent-context"));
    assertThat(results[1], is("Task2:concurrent-context"));
    assertThat(results[2], is("Task3:concurrent-context"));
  }
  
  @Test
  public void testResourceCleanupAfterVirtualThreadCompletion() throws Exception {
    // Use a latch to control when the virtual thread completes
    CountDownLatch threadCompletedLatch = new CountDownLatch(1);
    AtomicReference<Exception> threadException = new AtomicReference<>();
    
    // Start a virtual thread that uses the data session
    Thread virtualThread = Thread.startVirtualThread(() -> {
      try {
        // Simulate using the session
        dataSession.getTransaction().begin();
        dataSession.executeWithContext(() -> "some work");
        dataSession.getTransaction().commit();
        
        // Close the session
        dataSession.close();
      }
      catch (Exception e) {
        threadException.set(e);
      }
      finally {
        threadCompletedLatch.countDown();
      }
    });
    
    // Wait for the virtual thread to complete
    boolean completed = threadCompletedLatch.await(5, TimeUnit.SECONDS);
    assertThat("Virtual thread did not complete in time", completed, is(true));
    
    // Check for exceptions
    Exception exception = threadException.get();
    if (exception != null) {
      throw exception;
    }
    
    // Verify session was closed
    verify(dataSession, times(1)).close();
  }
  
  /**
   * Test implementation of DataSession for testing purposes.
   */
  private static class TestDataSession implements DataSession<Transaction> {
    private final Transaction transaction;
    private final List<Runnable> preCommitHooks = new ArrayList<>();
    private final List<Runnable> postCommitHooks = new ArrayList<>();
    private final List<Runnable> rollbackHooks = new ArrayList<>();
    
    TestDataSession(Transaction transaction) {
      this.transaction = transaction;
    }
    
    @Override
    public Transaction getTransaction() {
      return transaction;
    }
    
    @Override
    public void close() {
      // No-op for testing
    }
    
    @Override
    @SuppressWarnings("unchecked")
    public <D extends DataAccess> D access(Class<D> type) {
      // This method is mocked in the tests
      return null;
    }
    
    @Override
    public void preCommit(Runnable hook) {
      preCommitHooks.add(hook);
    }
    
    @Override
    public void postCommit(Runnable hook) {
      postCommitHooks.add(hook);
    }
    
    @Override
    public void onRollback(Runnable hook) {
      rollbackHooks.add(hook);
    }
    
    @Override
    public <R> R executeWithContext(Callable<R> task) throws Exception {
      // Preserve the transaction context
      String context = TRANSACTION_CONTEXT.get();
      try {
        return task.call();
      }
      finally {
        // Restore the context
        if (context != null) {
          TRANSACTION_CONTEXT.set(context);
        }
      }
    }
    
    @Override
    public String sqlDialect() {
      return "H2";
    }
    
    // Test helper methods
    void simulateCommit() {
      // Run pre-commit hooks
      preCommitHooks.forEach(Runnable::run);
      
      // Run post-commit hooks
      postCommitHooks.forEach(Runnable::run);
    }
    
    void simulateRollback() {
      // Run rollback hooks
      rollbackHooks.forEach(Runnable::run);
    }
  }
  
  /**
   * Test DataAccess implementation for testing purposes.
   */
  private interface TestDataAccess extends DataAccess {
    // No additional methods needed for testing
  }
}