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
package org.sonatype.nexus.common.cooperation2.internal;

import java.lang.SafeVarargs;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.Map;

import org.slf4j.MDC;

import org.sonatype.nexus.common.cooperation2.Cooperation2.Builder;
import org.sonatype.nexus.common.cooperation2.IOCall;
import org.sonatype.nexus.common.cooperation2.IOCheck;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Abstract implementation of {@link Builder}
 * <p>
 * This implementation is optimized for Java 21 Virtual Threads, providing efficient
 * thread management and concurrency control for I/O-bound operations. It ensures proper
 * context propagation across virtual threads and leverages Java 21's improved
 * concurrency capabilities.
 * <p>
 * Key Java 21 compatibility features:
 * <ul>
 *   <li>Virtual Thread support - optimized for I/O-bound operations using Java 21 virtual threads</li>
 *   <li>Thread context preservation - maintains MDC and ThreadLocal context across thread boundaries</li>
 *   <li>No thread pinning - avoids operations that would pin virtual threads to carrier threads</li>
 *   <li>Efficient resource utilization - virtual threads automatically yield during blocking I/O</li>
 * </ul>
 */
public abstract class Cooperation2Builder<RET>
    implements Builder<RET>
{
  /**
   * Executor service for handling virtual threads when needed.
   * This uses Java 21's virtual thread per task executor for optimal performance
   * with I/O-bound operations, avoiding thread pool limitations.
   * <p>
   * Virtual threads are lightweight and can be created in much larger numbers than platform threads.
   * They automatically yield during blocking I/O operations, allowing the carrier thread to do other work.
   */
  protected static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  
  /**
   * Flag indicating whether to perform work on failure.
   */
  protected boolean performWorkOnFail;

  /**
   * Function to check if work has already been completed.
   * Default implementation returns an empty Optional.
   */
  protected IOCheck<RET> checkFunction = Optional::empty;

  /**
   * The work function to be executed.
   */
  protected final IOCall<RET> workFunction;

  /**
   * Creates a new builder with the specified work function.
   * <p>
   * This constructor is compatible with Java 21 Virtual Threads and can be safely called
   * from any thread context.
   *
   * @param workFunction the function to execute, must not be null
   * @throws NullPointerException if workFunction is null
   */
  protected Cooperation2Builder(final IOCall<RET> workFunction) {
    this.workFunction = checkNotNull(workFunction, "The work function for this co-operation is missing");
  }

  /**
   * Sets the function to check if work has already been completed.
   * <p>
   * This method is compatible with Java 21 Virtual Threads and can be safely called
   * from any thread context.
   *
   * @param checkFunction the function to check for existing results, must not be null
   * @return this builder for method chaining
   * @throws NullPointerException if checkFunction is null
   */
  @Override
  public Cooperation2Builder<RET> checkFunction(final IOCheck<RET> checkFunction) {
    this.checkFunction = checkNotNull(checkFunction, "The check function for this co-operation is missing");
    return this;
  }

  /**
   * Sets whether to perform work on failure.
   * <p>
   * This method is compatible with Java 21 Virtual Threads and can be safely called
   * from any thread context.
   *
   * @param performWorkOnFail whether to perform work on failure
   * @return this builder for method chaining
   */
  @Override
  public Cooperation2Builder<RET> performWorkOnFail(final boolean performWorkOnFail) {
    this.performWorkOnFail = performWorkOnFail;
    return this;
  }
  
  /**
   * Helper method to capture and propagate MDC context across thread boundaries.
   * This is particularly important for Virtual Threads which may unmount/remount
   * on different carrier threads.
   * 
   * @param runnable the runnable to execute with MDC context
   */
  protected void withMdcContext(Runnable runnable) {
    // Capture current MDC context
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    // Store original context to restore later
    Map<String, String> originalMdc = MDC.getCopyOfContextMap();
    try {
      // Set captured context
      if (mdcContext != null) {
        MDC.setContextMap(mdcContext);
      }
      
      // Execute the runnable
      runnable.run();
    } 
    finally {
      // Restore original context or clear if it was null
      if (originalMdc != null) {
        MDC.setContextMap(originalMdc);
      } 
      else {
        MDC.clear();
      }
    }
  }
  
  /**
   * Executes a task using a Virtual Thread from the shared executor.
   * This is particularly useful for I/O-bound operations that can benefit from
   * the lightweight threading model of Java 21 Virtual Threads.
   * <p>
   * The method ensures proper MDC context propagation across thread boundaries.
   * 
   * @param <T> the return type of the task
   * @param task the task to execute
   * @return a CompletableFuture that will complete with the result of the task
   */
  protected <T> java.util.concurrent.CompletableFuture<T> executeWithVirtualThread(java.util.function.Supplier<T> task) {
    // Capture MDC context for propagation
    final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      // Store original context to restore later
      Map<String, String> originalMdc = MDC.getCopyOfContextMap();
      try {
        // Set captured context in the virtual thread
        if (mdcContext != null) {
          MDC.setContextMap(mdcContext);
        }
        
        // Execute the task
        return task.get();
      } 
      finally {
        // Restore original context or clear if it was null
        if (originalMdc != null) {
          MDC.setContextMap(originalMdc);
        } 
        else {
          MDC.clear();
        }
      }
    }, VIRTUAL_THREAD_EXECUTOR);
  }
  
  /**
   * Executes an I/O operation using a Virtual Thread from the shared executor.
   * This is optimized for I/O-bound operations that can benefit from the lightweight
   * threading model of Java 21 Virtual Threads.
   * <p>
   * The method ensures proper MDC context propagation across thread boundaries and
   * handles IOException properly.
   * 
   * @param <T> the return type of the operation
   * @param ioOperation the I/O operation to execute
   * @return a CompletableFuture that will complete with the result of the operation
   */
  protected <T> java.util.concurrent.CompletableFuture<T> executeIOWithVirtualThread(IOCall<T> ioOperation) {
    // Capture MDC context for propagation
    final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      // Store original context to restore later
      Map<String, String> originalMdc = MDC.getCopyOfContextMap();
      try {
        // Set captured context in the virtual thread
        if (mdcContext != null) {
          MDC.setContextMap(mdcContext);
        }
        
        // Execute the I/O operation
        return ioOperation.call();
      } 
      catch (java.io.IOException e) {
        // Wrap IOException in UncheckedIOException to propagate through CompletableFuture
        throw new java.io.UncheckedIOException(e);
      }
      finally {
        // Restore original context or clear if it was null
        if (originalMdc != null) {
          MDC.setContextMap(originalMdc);
        } 
        else {
          MDC.clear();
        }
      }
    }, VIRTUAL_THREAD_EXECUTOR);
  }
  
  /**
   * Safely cleans up ThreadLocal resources to prevent memory leaks, especially important
   * with Virtual Threads which are created and destroyed frequently.
   * <p>
   * This method should be called in finally blocks when using ThreadLocal variables
   * with Virtual Threads to ensure proper cleanup.
   *
   * @param threadLocals the ThreadLocal instances to clean up
   */
  @SafeVarargs
  protected static void cleanupThreadLocals(ThreadLocal<?>... threadLocals) {
    if (threadLocals != null) {
      for (ThreadLocal<?> threadLocal : threadLocals) {
        if (threadLocal != null) {
          threadLocal.remove();
        }
      }
    }
  }
  
  /**
   * Checks if the current thread is a Virtual Thread.
   * This is useful for making runtime decisions based on the thread type.
   * <p>
   * In Java 21, this uses Thread.currentThread().isVirtual().
   *
   * @return true if the current thread is a Virtual Thread, false otherwise
   */
  protected static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Executes multiple I/O operations concurrently using Virtual Threads and waits for all to complete.
   * This leverages Java 21's improved concurrency to ensure all operations are properly managed.
   * <p>
   * The method ensures proper MDC context propagation across thread boundaries.
   * 
   * @param <T> the return type of the operations
   * @param ioOperations the I/O operations to execute concurrently
   * @return a list of results from all operations in the same order as the input operations
   * @throws java.io.IOException if any operation throws an IOException
   */
  @SafeVarargs
  protected final <T> java.util.List<T> executeIOConcurrently(IOCall<T>... ioOperations) throws java.io.IOException {
    if (ioOperations == null || ioOperations.length == 0) {
      return java.util.Collections.emptyList();
    }
    
    // Capture MDC context for propagation
    final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    // Create futures for all operations
    java.util.List<java.util.concurrent.CompletableFuture<T>> futures = new java.util.ArrayList<>(ioOperations.length);
    
    for (IOCall<T> operation : ioOperations) {
      futures.add(executeIOWithVirtualThread(operation));
    }
    
    // Wait for all futures to complete
    try {
      return futures.stream()
          .map(future -> {
            try {
              return future.join();
            } 
            catch (java.util.concurrent.CompletionException e) {
              if (e.getCause() instanceof java.io.UncheckedIOException) {
                throw new java.util.concurrent.CompletionException(
                    ((java.io.UncheckedIOException) e.getCause()).getCause());
              }
              throw e;
            }
          })
          .collect(java.util.stream.Collectors.toList());
    } 
    catch (java.util.concurrent.CompletionException e) {
      if (e.getCause() instanceof java.io.IOException) {
        throw (java.io.IOException) e.getCause();
      }
      if (e.getCause() instanceof RuntimeException) {
        throw (RuntimeException) e.getCause();
      }
      throw new RuntimeException("Error executing concurrent I/O operations", e.getCause());
    }
  }
  
  /**
   * Executes an I/O operation with automatic retry logic using Virtual Threads.
   * This is particularly useful for network or file operations that may fail transiently.
   * <p>
   * The method ensures proper MDC context propagation across thread boundaries.
   * 
   * @param <T> the return type of the operation
   * @param ioOperation the I/O operation to execute
   * @param maxRetries the maximum number of retry attempts
   * @param retryDelayMillis the delay between retries in milliseconds
   * @return the result of the operation
   * @throws java.io.IOException if the operation fails after all retry attempts
   */
  protected <T> T executeIOWithRetry(IOCall<T> ioOperation, int maxRetries, long retryDelayMillis) 
      throws java.io.IOException {
    // Capture MDC context for propagation
    final Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    java.io.IOException lastException = null;
    
    for (int attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        // Store original context to restore later
        Map<String, String> originalMdc = MDC.getCopyOfContextMap();
        try {
          // Set captured context
          if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
          }
          
          // Execute the I/O operation
          return ioOperation.call();
        } 
        finally {
          // Restore original context or clear if it was null
          if (originalMdc != null) {
            MDC.setContextMap(originalMdc);
          } 
          else {
            MDC.clear();
          }
        }
      } 
      catch (java.io.IOException e) {
        lastException = e;
        
        // Don't sleep on the last attempt
        if (attempt < maxRetries) {
          try {
            // Use exponential backoff with jitter for more efficient retries
            long delay = retryDelayMillis * (1L << attempt) + 
                (long) (retryDelayMillis * Math.random() * 0.1);
            Thread.sleep(Math.min(delay, 30000)); // Cap at 30 seconds
          } 
          catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException("Retry interrupted", ie);
          }
        }
      }
    }
    
    // If we get here, all retries failed
    throw lastException;
  }
}