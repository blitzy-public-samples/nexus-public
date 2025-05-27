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
package org.sonatype.nexus.email.internal;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.security.subject.FakeAlmightySubject;
import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import com.google.common.base.Preconditions;

/**
 * Utility class for managing Java 21 Virtual Threads specifically optimized for email operations.
 * <p>
 * This class provides methods for creating, executing, and managing Virtual Threads for email-related
 * tasks, ensuring proper context propagation, error handling, and monitoring. It leverages Java 21's
 * Virtual Threads feature to improve performance and resource utilization for concurrent email operations.
 * <p>
 * Key features include:
 * <ul>
 *   <li>Thread creation and naming with proper Nexus context</li>
 *   <li>Security subject propagation across thread boundaries</li>
 *   <li>MDC context propagation for consistent logging</li>
 *   <li>Timeout handling for long-running operations</li>
 *   <li>Monitoring and metrics for active threads</li>
 *   <li>Graceful shutdown and cancellation support</li>
 * </ul>
 *
 * @since 3.60
 */
public final class EmailVirtualThreads
{
  private static final Logger log = LoggerFactory.getLogger(EmailVirtualThreads.class);
  
  /**
   * Default thread name prefix for email-related virtual threads.
   */
  private static final String DEFAULT_THREAD_NAME_PREFIX = "nexus-email-vthread";
  
  /**
   * Default timeout for email operations in seconds.
   */
  private static final long DEFAULT_TIMEOUT_SECONDS = 60;
  
  /**
   * Counter for generating unique thread names.
   */
  private static final AtomicInteger threadCounter = new AtomicInteger(0);
  
  /**
   * Metrics counter for active email virtual threads.
   */
  private static final AtomicInteger activeThreadsCounter = new AtomicInteger(0);
  
  /**
   * Default executor for email-related virtual threads.
   */
  private static final Executor DEFAULT_EXECUTOR = createEmailVirtualThreadExecutor(DEFAULT_THREAD_NAME_PREFIX);
  
  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private EmailVirtualThreads() {
    // Utility class, no instances
  }
  
  /**
   * Creates a virtual thread executor specifically configured for email operations.
   * <p>
   * The executor uses a custom thread factory that applies the specified name prefix
   * to all created virtual threads.
   *
   * @param threadNamePrefix the prefix to use for thread names
   * @return an executor that creates virtual threads for each task
   */
  public static Executor createEmailVirtualThreadExecutor(String threadNamePrefix) {
    Preconditions.checkNotNull(threadNamePrefix, "Thread name prefix cannot be null");
    
    ThreadFactory threadFactory = Thread.ofVirtual()
        .name(threadNamePrefix, threadCounter.incrementAndGet())
        .factory();
    
    return Executors.newThreadPerTaskExecutor(threadFactory);
  }
  
  /**
   * Executes a runnable task in a virtual thread with the default email thread executor.
   * <p>
   * This method wraps the provided runnable with MDC context propagation to ensure
   * logging context is maintained across thread boundaries.
   *
   * @param task the task to execute
   */
  public static void execute(Runnable task) {
    execute(task, DEFAULT_EXECUTOR, FakeAlmightySubject.TASK_SUBJECT);
  }
  
  /**
   * Executes a runnable task in a virtual thread with the specified subject.
   * <p>
   * This method wraps the provided runnable with MDC context propagation and
   * associates the specified security subject with the virtual thread.
   *
   * @param task the task to execute
   * @param subject the security subject to associate with the thread
   */
  public static void execute(Runnable task, Subject subject) {
    execute(task, DEFAULT_EXECUTOR, subject);
  }
  
  /**
   * Executes a runnable task in a virtual thread with the specified executor and subject.
   * <p>
   * This method provides full control over the execution environment, allowing
   * custom executors and security subjects.
   *
   * @param task the task to execute
   * @param executor the executor to use
   * @param subject the security subject to associate with the thread
   */
  public static void execute(Runnable task, Executor executor, Subject subject) {
    Preconditions.checkNotNull(task, "Task cannot be null");
    Preconditions.checkNotNull(executor, "Executor cannot be null");
    Preconditions.checkNotNull(subject, "Subject cannot be null");
    
    // Wrap the task to propagate MDC context
    Runnable mdcAwareTask = new MDCAwareRunnable(task);
    
    // Track active threads for monitoring
    activeThreadsCounter.incrementAndGet();
    
    // Execute the task with the subject bound to the thread
    executor.execute(() -> {
      try {
        subject.execute(mdcAwareTask);
      }
      catch (Exception e) {
        log.error("Error executing email task in virtual thread", e);
      }
      finally {
        // Decrement counter when thread completes
        activeThreadsCounter.decrementAndGet();
      }
    });
  }
  
  /**
   * Submits a callable task for execution in a virtual thread and returns a Future.
   * <p>
   * This method uses the default email thread executor and FakeAlmightySubject.
   *
   * @param <T> the type of the callable's result
   * @param task the callable task to submit
   * @return a Future representing the pending result
   */
  public static <T> Future<T> submit(Callable<T> task) {
    return submit(task, DEFAULT_EXECUTOR, FakeAlmightySubject.TASK_SUBJECT);
  }
  
  /**
   * Submits a callable task for execution in a virtual thread with the specified subject.
   *
   * @param <T> the type of the callable's result
   * @param task the callable task to submit
   * @param subject the security subject to associate with the thread
   * @return a Future representing the pending result
   */
  public static <T> Future<T> submit(Callable<T> task, Subject subject) {
    return submit(task, DEFAULT_EXECUTOR, subject);
  }
  
  /**
   * Submits a callable task for execution in a virtual thread with the specified executor and subject.
   * <p>
   * This method provides full control over the execution environment and returns a Future
   * that can be used to retrieve the result or cancel the task.
   *
   * @param <T> the type of the callable's result
   * @param task the callable task to submit
   * @param executor the executor to use
   * @param subject the security subject to associate with the thread
   * @return a Future representing the pending result
   */
  public static <T> Future<T> submit(Callable<T> task, Executor executor, Subject subject) {
    Preconditions.checkNotNull(task, "Task cannot be null");
    Preconditions.checkNotNull(executor, "Executor cannot be null");
    Preconditions.checkNotNull(subject, "Subject cannot be null");
    
    // Wrap the task to propagate MDC context
    Callable<T> mdcAwareTask = new MDCAwareCallable<>(task);
    
    // Create a CompletableFuture to represent the result
    CompletableFuture<T> future = new CompletableFuture<>();
    
    // Track active threads for monitoring
    activeThreadsCounter.incrementAndGet();
    
    // Execute the task with the subject bound to the thread
    executor.execute(() -> {
      try {
        T result = subject.execute(mdcAwareTask);
        future.complete(result);
      }
      catch (Exception e) {
        log.error("Error executing email callable in virtual thread", e);
        future.completeExceptionally(e);
      }
      finally {
        // Decrement counter when thread completes
        activeThreadsCounter.decrementAndGet();
      }
    });
    
    return future;
  }
  
  /**
   * Executes a supplier in a virtual thread and returns a CompletableFuture.
   * <p>
   * This method is useful for asynchronous operations that produce a result
   * but don't need to throw checked exceptions.
   *
   * @param <T> the type of the supplier's result
   * @param supplier the supplier to execute
   * @return a CompletableFuture representing the pending result
   */
  public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
    return supplyAsync(supplier, DEFAULT_EXECUTOR, FakeAlmightySubject.TASK_SUBJECT);
  }
  
  /**
   * Executes a supplier in a virtual thread with the specified subject.
   *
   * @param <T> the type of the supplier's result
   * @param supplier the supplier to execute
   * @param subject the security subject to associate with the thread
   * @return a CompletableFuture representing the pending result
   */
  public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Subject subject) {
    return supplyAsync(supplier, DEFAULT_EXECUTOR, subject);
  }
  
  /**
   * Executes a supplier in a virtual thread with the specified executor and subject.
   *
   * @param <T> the type of the supplier's result
   * @param supplier the supplier to execute
   * @param executor the executor to use
   * @param subject the security subject to associate with the thread
   * @return a CompletableFuture representing the pending result
   */
  public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier, Executor executor, Subject subject) {
    Preconditions.checkNotNull(supplier, "Supplier cannot be null");
    Preconditions.checkNotNull(executor, "Executor cannot be null");
    Preconditions.checkNotNull(subject, "Subject cannot be null");
    
    // Create a CompletableFuture to represent the result
    CompletableFuture<T> future = new CompletableFuture<>();
    
    // Track active threads for monitoring
    activeThreadsCounter.incrementAndGet();
    
    // Execute the supplier with the subject bound to the thread
    executor.execute(() -> {
      try {
        // Capture MDC context manually since we're using a Supplier
        final var mdcContext = org.sonatype.nexus.thread.internal.MDCUtils.getCopyOfContextMap();
        
        T result = subject.execute(() -> {
          try {
            // Restore MDC context
            org.sonatype.nexus.thread.internal.MDCUtils.setContextMap(mdcContext);
            return supplier.get();
          }
          finally {
            // Clear MDC context
            org.slf4j.MDC.clear();
          }
        });
        
        future.complete(result);
      }
      catch (Exception e) {
        log.error("Error executing email supplier in virtual thread", e);
        future.completeExceptionally(e);
      }
      finally {
        // Decrement counter when thread completes
        activeThreadsCounter.decrementAndGet();
      }
    });
    
    return future;
  }
  
  /**
   * Executes a task with a timeout, cancelling it if it exceeds the specified duration.
   * <p>
   * This method is useful for email operations that should not run indefinitely.
   *
   * @param <T> the type of the callable's result
   * @param task the callable task to execute
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return the task's result
   * @throws Exception if the task throws an exception or times out
   */
  public static <T> T executeWithTimeout(Callable<T> task, long timeout, TimeUnit unit) throws Exception {
    Future<T> future = submit(task);
    try {
      return future.get(timeout, unit);
    }
    catch (TimeoutException e) {
      log.warn("Email operation timed out after {} {}", timeout, unit);
      future.cancel(true);
      throw e;
    }
    catch (Exception e) {
      future.cancel(true);
      throw e;
    }
  }
  
  /**
   * Executes a task with the default timeout (60 seconds).
   *
   * @param <T> the type of the callable's result
   * @param task the callable task to execute
   * @return the task's result
   * @throws Exception if the task throws an exception or times out
   */
  public static <T> T executeWithDefaultTimeout(Callable<T> task) throws Exception {
    return executeWithTimeout(task, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Executes a task with a timeout specified as a Duration.
   *
   * @param <T> the type of the callable's result
   * @param task the callable task to execute
   * @param timeout the maximum time to wait
   * @return the task's result
   * @throws Exception if the task throws an exception or times out
   */
  public static <T> T executeWithTimeout(Callable<T> task, Duration timeout) throws Exception {
    return executeWithTimeout(task, timeout.toMillis(), TimeUnit.MILLISECONDS);
  }
  
  /**
   * Attempts to gracefully shut down the provided executor service.
   * <p>
   * This method is useful for cleaning up custom email thread executors during
   * application shutdown.
   *
   * @param executor the executor service to shut down
   * @param timeout the maximum time to wait for shutdown
   * @param unit the time unit of the timeout argument
   * @return true if the executor terminated successfully, false if the timeout elapsed
   */
  public static boolean shutdownGracefully(ExecutorService executor, long timeout, TimeUnit unit) {
    Preconditions.checkNotNull(executor, "Executor cannot be null");
    
    try {
      executor.shutdown();
      return executor.awaitTermination(timeout, unit);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while waiting for email executor shutdown", e);
      return false;
    }
  }
  
  /**
   * Cancels a future if it is not already completed.
   * <p>
   * This method is useful for cleaning up pending email tasks during shutdown
   * or when an operation is no longer needed.
   *
   * @param future the future to cancel, may be null
   * @param mayInterruptIfRunning whether the thread executing the task should be interrupted
   * @return true if the future was cancelled, false otherwise
   */
  public static boolean cancelIfNotDone(@Nullable Future<?> future, boolean mayInterruptIfRunning) {
    if (future != null && !future.isDone()) {
      return future.cancel(mayInterruptIfRunning);
    }
    return false;
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * <p>
   * This method is useful for conditional logic that should only execute
   * in a virtual thread context.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Gets the current count of active email virtual threads.
   * <p>
   * This method is useful for monitoring and metrics collection.
   *
   * @return the current number of active email virtual threads
   */
  public static int getActiveThreadCount() {
    return activeThreadsCounter.get();
  }
  
  /**
   * Gets the result from a future, handling cancellation gracefully.
   * <p>
   * This method is useful for retrieving results from email tasks that
   * might be cancelled during shutdown.
   *
   * @param <T> the type of the future's result
   * @param future the future to get the result from
   * @param defaultValue the default value to return if the future is cancelled
   * @return the future's result, or the default value if cancelled
   * @throws Exception if the future throws an exception other than CancellationException
   */
  public static <T> T getResultOrDefault(Future<T> future, T defaultValue) throws Exception {
    try {
      return future.get();
    }
    catch (CancellationException e) {
      log.debug("Email task was cancelled, returning default value", e);
      return defaultValue;
    }
  }
}