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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocal;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.base.Suppliers;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Miscellaneous methods to exercise transactional aspects.
 */
@SuppressWarnings("unused")
@Singleton
public class ExampleMethods
{
  @Singleton
  static class ExampleNestedStore
      implements TransactionalStore<TransactionalSession<?>>
  {
    @Override
    public TransactionalSession<?> openSession() {
      throw new UnsupportedOperationException("Should never be called as this store is only used inside existing TX");
    }

    @Transactional
    public String storeSomething(final String something) {
      return "stored " + something;
    }
  }

  final ExampleNestedStore nestedStore;

  @Inject
  public ExampleMethods(final ExampleNestedStore nestedStore) {
    this.nestedStore = checkNotNull(nestedStore);
  }

  public String nonTransactional() {
    return "success";
  }

  @Transactional
  public String transactional() {
    return "success";
  }

  @Transactional(reason = "Testing!")
  public String customReason() {
    return "success";
  }

  @Transactional
  public String outer() {
    return inner();
  }

  @Transactional
  public String inner() {
    return transactional();
  }

  @Transactional
  public String captureNestedStore() {
    return nestedStore.storeSomething("example");
  }

  @Transactional
  public void canSeeTransactionInsideTransactional() {
    checkState(UnitOfWork.currentTx() != null);
  }

  // should throw IllegalStateException
  public void cannotSeeTransactionOutsideTransactional() {
    UnitOfWork.currentTx();
  }

  @Transactional
  public String rollbackOnCheckedException() throws IOException {
    throw new IOException();
  }

  @Transactional
  public String rollbackOnUncheckedException() throws IOException {
    throw new IllegalStateException();
  }

  private int countdown;

  public void setCountdownToSuccess(int countdown) {
    this.countdown = countdown;
  }

  @Transactional(commitOn = IOException.class)
  public String commitOnCheckedException() throws IOException {
    throw new IOException();
  }

  @Transactional(commitOn = RuntimeException.class)
  public String commitOnUncheckedException() throws IOException {
    throw new IllegalStateException();
  }

  @Transactional(retryOn = IOException.class)
  public String retryOnCheckedException() throws IOException {
    if (countdown-- > 0) {
      throw new IOException();
    }
    return "success";
  }

  @Transactional(retryOn = RuntimeException.class)
  public String retryOnUncheckedException() throws IOException {
    if (countdown-- > 0) {
      throw new IllegalStateException();
    }
    return "success";
  }

  @Transactional(retryOn = IOException.class)
  public String retryOnExceptionCause() {
    if (countdown-- > 0) {
      throw new IllegalStateException(new IOException());
    }
    return "success";
  }

  @Transactional(retryOn = RuntimeException.class)
  public String retryOnCommitFailure() {
    return "success";
  }

  @Transactional(swallow = RuntimeException.class)
  public String swallowCommitFailure() {
    return "success";
  }

  @Transactional(commitOn = RuntimeException.class, swallow = RuntimeException.class)
  public String commitOnUncheckedSwallowCommitFailure() {
    throw new IllegalStateException();
  }

  // should throw IllegalStateException
  @Transactional
  public void beginWorkInTransaction() {
    UnitOfWork.begin(Suppliers.ofInstance((TransactionalSession<?>) null));
  }

  // should throw IllegalStateException
  @Transactional
  public void endWorkInTransaction() {
    UnitOfWork.end();
  }

  @RetryOnIOException
  public String canUseStereotypeAnnotation() throws IOException {
    if (countdown-- > 0) {
      throw new IOException();
    }
    return "success";
  }
  
  /**
   * Tests transaction execution in a virtual thread context.
   * Virtual threads in Java 21 are lightweight threads that can be created in large numbers.
   * This method verifies that transactions work properly when executed in a virtual thread.
   */
  @Transactional
  public String executeInVirtualThread() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-transaction-", 0).factory();
    AtomicReference<String> result = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Execute transactional operation in virtual thread
        result.set(transactional());
      } finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    return result.get();
  }
  
  /**
   * Tests that thread-local variables propagate correctly in virtual threads.
   * This is important for transaction context propagation in Java 21 virtual threads.
   */
  @Transactional
  public boolean testThreadLocalPropagationInVirtualThreads() throws Exception {
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    threadLocal.set("transaction-context");
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("vt-threadlocal-", 0).factory();
    AtomicBoolean success = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);
    
    Thread virtualThread = virtualThreadFactory.newThread(() -> {
      try {
        // Thread-local should not be inherited by default in virtual threads
        success.set(threadLocal.get() == null);
        
        // Set a new value in the virtual thread
        threadLocal.set("virtual-thread-context");
      } finally {
        latch.countDown();
      }
    });
    
    virtualThread.start();
    latch.await(5, TimeUnit.SECONDS);
    
    // Original thread should still have its own thread-local value
    return success.get() && "transaction-context".equals(threadLocal.get());
  }
  
  /**
   * Tests concurrent execution of transactions using virtual threads.
   * This demonstrates how to leverage Java 21 virtual threads for high concurrency scenarios.
   */
  @Transactional
  public boolean testConcurrentTransactionsWithVirtualThreads() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicBoolean success = new AtomicBoolean(true);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int taskId = i;
        CompletableFuture.runAsync(() -> {
          try {
            // Each virtual thread should have its own transaction context
            canSeeTransactionInsideTransactional();
            
            // Verify nested transactions work in virtual threads
            String result = captureNestedStore();
            if (!"stored example".equals(result)) {
              success.set(false);
            }
          } catch (Exception e) {
            success.set(false);
          } finally {
            latch.countDown();
          }
        }, executor);
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      return success.get();
    } finally {
      executor.shutdown();
    }
  }