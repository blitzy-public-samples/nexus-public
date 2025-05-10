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
import java.lang.Thread;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.google.common.base.Suppliers;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Miscellaneous methods to exercise transactional aspects.
 * 
 * Includes methods for testing with Virtual Thread execution context and
 * thread-local variable propagation with Virtual Threads.
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
   * Tests transaction execution in a Virtual Thread context.
   * 
   * @return result of the transaction
   * @since 3.60
   */
  @Transactional
  public String transactionalInVirtualThread() {
    // Check if we're running in a virtual thread
    boolean isVirtual = UnitOfWork.isVirtualThread();
    return "success in " + (isVirtual ? "virtual" : "platform") + " thread";
  }
  
  /**
   * Tests asynchronous transaction execution with Virtual Threads.
   * 
   * @return result of the transaction
   * @throws Exception if an error occurs
   * @since 3.60
   */
  public String asyncTransactionalWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Execute transaction in a virtual thread
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        UnitOfWork.begin(Suppliers.ofInstance((TransactionalSession<?>) null));
        try {
          return transactional();
        } finally {
          UnitOfWork.end();
        }
      }, executor);
      
      // Wait for the result
      return future.get();
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests thread-local variable propagation with Virtual Threads.
   * 
   * @return result indicating if thread-local was properly propagated
   * @throws Exception if an error occurs
   * @since 3.60
   */
  public String testThreadLocalPropagation() throws Exception {
    // Create a thread-local variable
    ThreadLocal<String> threadLocal = new ThreadLocal<>();
    // Use InheritableThreadLocal for proper propagation to virtual threads
    InheritableThreadLocal<String> inheritableThreadLocal = new InheritableThreadLocal<>();
    
    // Set values in the current thread
    threadLocal.set("regular-thread-local");
    inheritableThreadLocal.set("inheritable-thread-local");
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Capture values in virtual thread
      AtomicReference<String> regularValue = new AtomicReference<>();
      AtomicReference<String> inheritableValue = new AtomicReference<>();
      
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        regularValue.set(threadLocal.get());
        inheritableValue.set(inheritableThreadLocal.get());
        return "completed";
      }, executor);
      
      future.get();
      
      // Regular ThreadLocal won't propagate to virtual threads
      // InheritableThreadLocal will propagate to virtual threads
      return "Regular: " + (regularValue.get() == null ? "not propagated" : "propagated") + 
             ", Inheritable: " + (inheritableValue.get() == null ? "not propagated" : "propagated");
    } finally {
      executor.shutdown();
      threadLocal.remove();
      inheritableThreadLocal.remove();
    }
  }
  
  /**
   * Tests UnitOfWork context propagation with Virtual Threads.
   * 
   * @return result indicating if UnitOfWork context was properly propagated
   * @throws Exception if an error occurs
   * @since 3.60
   */
  public String testUnitOfWorkPropagation() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Begin a unit of work in the current thread
      UnitOfWork.begin(Suppliers.ofInstance((TransactionalSession<?>) null));
      
      try {
        // Execute in a virtual thread and check if UnitOfWork context is available
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
          try {
            // This should succeed if UnitOfWork context is propagated
            UnitOfWork.currentTx();
            return "UnitOfWork propagated to virtual thread";
          } catch (IllegalStateException e) {
            return "UnitOfWork not propagated to virtual thread";
          }
        }, executor);
        
        return future.get();
      } finally {
        UnitOfWork.end();
      }
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests pausing and resuming UnitOfWork in Virtual Threads.
   * 
   * @return result of the operation
   * @throws Exception if an error occurs
   * @since 3.60
   */
  public String testPauseResumeUnitOfWork() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Begin a unit of work in the current thread
      UnitOfWork.begin(Suppliers.ofInstance((TransactionalSession<?>) null));
      
      try {
        // Pause the current unit of work
        UnitOfWork pausedWork = UnitOfWork.pause();
        
        try {
          // Execute in a virtual thread
          CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            // Resume the paused unit of work in the virtual thread
            UnitOfWork.resume(pausedWork);
            
            try {
              // This should succeed if UnitOfWork was properly resumed
              UnitOfWork.currentTx();
              return "UnitOfWork successfully paused and resumed in virtual thread";
            } catch (IllegalStateException e) {
              return "Failed to resume UnitOfWork in virtual thread";
            } finally {
              // Pause again so we can resume in the original thread
              UnitOfWork.pause();
            }
          }, executor);
          
          String result = future.get();
          
          // Resume in the original thread
          UnitOfWork.resume(pausedWork);
          return result;
        } catch (Exception e) {
          // Make sure we resume in case of exception
          UnitOfWork.resume(pausedWork);
          throw e;
        }
      } finally {
        UnitOfWork.end();
      }
    } finally {
      executor.shutdown();
    }
  }
}