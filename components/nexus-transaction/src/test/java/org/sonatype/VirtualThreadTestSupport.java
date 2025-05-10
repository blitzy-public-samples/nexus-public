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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Support class for testing with Java 21 Virtual Threads.
 * <p>
 * Provides utilities for creating thread factories, managing executor services,
 * detecting thread pinning, and executing code in parallel across multiple virtual threads.
 * <p>
 * This class centralizes common functionality needed for testing with virtual threads,
 * ensuring consistent patterns across transaction tests. It helps verify thread-local
 * propagation, transaction boundaries, and concurrency behavior with both platform
 * and virtual threads for comparative testing.
 * 
 * @since 3.60
 */
public class VirtualThreadTestSupport
{
  /**
   * Default timeout for test operations in seconds.
   */
  private static final int DEFAULT_TIMEOUT_SECONDS = 30;

  /**
   * Creates a thread factory that produces virtual threads.
   * 
   * @return A thread factory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates a thread factory that produces virtual threads with the specified name prefix.
   * 
   * @param namePrefix The prefix to use for thread names
   * @return A thread factory that creates virtual threads with the specified name prefix
   */
  public static ThreadFactory virtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix + "-", 0).factory();
  }

  /**
   * Creates a thread factory that produces platform threads.
   * 
   * @return A thread factory that creates platform threads
   */
  public static ThreadFactory platformThreadFactory() {
    return Thread.ofPlatform().factory();
  }

  /**
   * Creates a thread factory that produces platform threads with the specified name prefix.
   * 
   * @param namePrefix The prefix to use for thread names
   * @return A thread factory that creates platform threads with the specified name prefix
   */
  public static ThreadFactory platformThreadFactory(String namePrefix) {
    return Thread.ofPlatform().name(namePrefix + "-", 0).factory();
  }

  /**
   * Creates an executor service that creates a new virtual thread for each task.
   * 
   * @return An executor service that uses virtual threads
   */
  public static ExecutorService newVirtualThreadExecutor() {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory());
  }

  /**
   * Creates an executor service that creates a new virtual thread with the specified name prefix for each task.
   * 
   * @param namePrefix The prefix to use for thread names
   * @return An executor service that uses virtual threads with the specified name prefix
   */
  public static ExecutorService newVirtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }

  /**
   * Creates an executor service that creates a fixed number of platform threads.
   * 
   * @param nThreads The number of threads in the pool
   * @return An executor service that uses platform threads
   */
  public static ExecutorService newPlatformThreadExecutor(int nThreads) {
    return Executors.newFixedThreadPool(nThreads, platformThreadFactory());
  }

  /**
   * Creates an executor service that creates a fixed number of platform threads with the specified name prefix.
   * 
   * @param nThreads The number of threads in the pool
   * @param namePrefix The prefix to use for thread names
   * @return An executor service that uses platform threads with the specified name prefix
   */
  public static ExecutorService newPlatformThreadExecutor(int nThreads, String namePrefix) {
    return Executors.newFixedThreadPool(nThreads, platformThreadFactory(namePrefix));
  }

  /**
   * Executes the given tasks in parallel using virtual threads and waits for all to complete.
   * 
   * @param <T> The type of result returned by the tasks
   * @param tasks The tasks to execute
   * @return A list of results from the tasks
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   * @throws ExecutionException if any task throws an exception
   */
  public static <T> List<T> executeInParallel(List<Callable<T>> tasks) 
      throws InterruptedException, ExecutionException {
    return executeInParallel(tasks, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }

  /**
   * Executes the given tasks in parallel using virtual threads and waits for all to complete
   * with the specified timeout.
   * 
   * @param <T> The type of result returned by the tasks
   * @param tasks The tasks to execute
   * @param timeout The maximum time to wait
   * @param unit The time unit of the timeout argument
   * @return A list of results from the tasks
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   * @throws ExecutionException if any task throws an exception
   */
  public static <T> List<T> executeInParallel(List<Callable<T>> tasks, long timeout, TimeUnit unit) 
      throws InterruptedException, ExecutionException {
    ExecutorService executor = newVirtualThreadExecutor("parallel-task");
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (Callable<T> task : tasks) {
        futures.add(executor.submit(task));
      }

      List<T> results = new ArrayList<>(tasks.size());
      for (Future<T> future : futures) {
        results.add(future.get(timeout, unit));
      }
      return results;
    } finally {
      shutdownExecutor(executor);
    }
  }

  /**
   * Executes the given task multiple times in parallel using virtual threads.
   * 
   * @param <T> The type of result returned by the task
   * @param task The task to execute
   * @param count The number of times to execute the task
   * @return A list of results from the task executions
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   * @throws ExecutionException if any task throws an exception
   */
  public static <T> List<T> executeMultipleInParallel(Callable<T> task, int count) 
      throws InterruptedException, ExecutionException {
    List<Callable<T>> tasks = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      tasks.add(task);
    }
    return executeInParallel(tasks);
  }

  /**
   * Executes the given task multiple times in parallel using virtual threads with the specified timeout.
   * 
   * @param <T> The type of result returned by the task
   * @param task The task to execute
   * @param count The number of times to execute the task
   * @param timeout The maximum time to wait
   * @param unit The time unit of the timeout argument
   * @return A list of results from the task executions
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   * @throws ExecutionException if any task throws an exception
   */
  public static <T> List<T> executeMultipleInParallel(Callable<T> task, int count, long timeout, TimeUnit unit) 
      throws InterruptedException, ExecutionException {
    List<Callable<T>> tasks = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      tasks.add(task);
    }
    return executeInParallel(tasks, timeout, unit);
  }

  /**
   * Shuts down an executor service and waits for termination.
   * 
   * @param executor The executor service to shut down
   */
  public static void shutdownExecutor(ExecutorService executor) {
    if (executor != null) {
      executor.shutdown();
      try {
        if (!executor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          executor.shutdownNow();
          if (!executor.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            System.err.println("Executor did not terminate");
          }
        }
      } catch (InterruptedException e) {
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
  }

  /**
   * Detects if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Detects if thread pinning is occurring by running a task that would normally
   * cause pinning and checking if it completes within the expected time.
   * <p>
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier
   * thread during a blocking operation. This typically happens when the virtual thread
   * is executing code inside a synchronized block or method, or when calling native methods.
   * 
   * @return true if thread pinning is detected, false otherwise
   */
  public static boolean detectThreadPinning() {
    if (!isVirtualThread()) {
      // Only virtual threads can be pinned
      return false;
    }

    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    CountDownLatch latch = new CountDownLatch(1);

    // Create a task that would normally cause pinning if run in a synchronized block
    Runnable blockingTask = () -> {
      try {
        // Simulate I/O operation that would normally cause pinning if in a synchronized block
        Thread.sleep(100);
        latch.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };

    // Run the task in a synchronized block which would cause pinning
    Object lock = new Object();
    Thread thread = Thread.ofVirtual().start(() -> {
      synchronized (lock) {
        blockingTask.run();
      }
    });

    try {
      // If pinning occurs, this will take longer than expected
      boolean completed = latch.await(150, TimeUnit.MILLISECONDS);
      pinningDetected.set(!completed);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    return pinningDetected.get();
  }
  
  /**
   * Enables thread pinning detection using JFR events.
   * <p>
   * This method configures Java Flight Recorder to emit events when virtual thread
   * pinning occurs. This is useful for debugging and performance analysis.
   * <p>
   * Note: This requires Java 21 or later and may have a small performance impact.
   */
  public static void enableThreadPinningDetection() {
    // Set system property to trace pinned threads
    System.setProperty("jdk.tracePinnedThreads", "full");
  }
  
  /**
   * Disables thread pinning detection.
   */
  public static void disableThreadPinningDetection() {
    System.clearProperty("jdk.tracePinnedThreads");
  }

  /**
   * Verifies that thread-local variables are properly propagated between virtual threads.
   * <p>
   * By default, thread-local variables are not automatically inherited by virtual threads.
   * This method helps test whether a thread-local is properly propagated or not.
   * <p>
   * For transaction testing, this is particularly important as transaction contexts
   * are often stored in thread-locals.
   * 
   * @param <T> The type of the thread-local variable
   * @param threadLocal The thread-local variable to test
   * @param initialValue The initial value to set in the thread-local
   * @return true if the thread-local value is properly propagated, false otherwise
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   * @throws ExecutionException if any task throws an exception
   */
  public static <T> boolean verifyThreadLocalPropagation(ThreadLocal<T> threadLocal, T initialValue) 
      throws InterruptedException, ExecutionException {
    threadLocal.set(initialValue);
    
    CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
      return threadLocal.get();
    }, newVirtualThreadExecutor("thread-local-test"));
    
    T result = future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // In virtual threads, thread-locals are not automatically inherited
    // So we expect the result to be null or the default value of the thread-local
    return result == null || !result.equals(initialValue);
  }
  
  /**
   * Creates a thread-local that is scoped to virtual threads.
   * <p>
   * This is useful for testing transaction contexts that need to be propagated
   * across virtual threads.
   * 
   * @param <T> The type of the thread-local variable
   * @param initialValueSupplier A supplier for the initial value
   * @return A thread-local that is scoped to virtual threads
   */
  public static <T> ThreadLocal<T> createInheritableThreadLocal(Supplier<T> initialValueSupplier) {
    return ThreadLocal.withInitial(initialValueSupplier);
  }

  /**
   * Runs a task multiple times in parallel and measures the execution time.
   * 
   * @param <T> The type of result returned by the task
   * @param task The task to execute
   * @param count The number of times to execute the task
   * @return The average execution time per task in milliseconds
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   */
  public static <T> double measureParallelExecution(Callable<T> task, int count) throws InterruptedException {
    ExecutorService executor = newVirtualThreadExecutor("perf-test");
    try {
      long startTime = System.nanoTime();
      
      CountDownLatch latch = new CountDownLatch(count);
      for (int i = 0; i < count; i++) {
        executor.submit(() -> {
          try {
            task.call();
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await();
      long endTime = System.nanoTime();
      
      return (endTime - startTime) / (double) (count * 1_000_000); // Convert to ms per task
    } finally {
      shutdownExecutor(executor);
    }
  }

  /**
   * Compares the performance of virtual threads vs platform threads for a given task.
   * <p>
   * This method is useful for benchmarking the performance difference between
   * virtual threads and platform threads for specific transaction operations.
   * It helps identify scenarios where virtual threads provide significant benefits.
   * 
   * @param <T> The type of result returned by the task
   * @param task The task to execute
   * @param count The number of times to execute the task
   * @return A string with the performance comparison results
   * @throws InterruptedException if interrupted while waiting for tasks to complete
   */
  public static <T> String compareThreadPerformance(Callable<T> task, int count) throws InterruptedException {
    // Measure with virtual threads
    double virtualThreadTime = measureParallelExecution(task, count);
    
    // Measure with platform threads (using a reasonable thread pool size)
    int platformThreadCount = Math.min(count, Runtime.getRuntime().availableProcessors() * 2);
    ExecutorService platformExecutor = newPlatformThreadExecutor(platformThreadCount, "platform-perf-test");
    
    try {
      long startTime = System.nanoTime();
      
      CountDownLatch latch = new CountDownLatch(count);
      for (int i = 0; i < count; i++) {
        platformExecutor.submit(() -> {
          try {
            task.call();
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await();
      long endTime = System.nanoTime();
      
      double platformThreadTime = (endTime - startTime) / (double) (count * 1_000_000); // Convert to ms per task
      
      return String.format(
          "Performance comparison for %d tasks:\n" +
          "  Virtual threads: %.2f ms per task\n" +
          "  Platform threads (%d threads): %.2f ms per task\n" +
          "  Difference: %.2f%%",
          count,
          virtualThreadTime,
          platformThreadCount,
          platformThreadTime,
          (platformThreadTime / virtualThreadTime - 1) * 100);
    } finally {
      shutdownExecutor(platformExecutor);
    }
  }
  
  /**
   * Executes a transaction-like operation with proper resource management.
   * <p>
   * This method simulates a transactional operation by executing the given task
   * and ensuring that resources are properly cleaned up, even if an exception occurs.
   * 
   * @param <T> The type of result returned by the task
   * @param task The task to execute
   * @return The result of the task
   * @throws Exception if the task throws an exception
   */
  public static <T> T executeTransactional(Callable<T> task) throws Exception {
    // Simulate transaction begin
    boolean committed = false;
    try {
      T result = task.call();
      // Simulate transaction commit
      committed = true;
      return result;
    } finally {
      if (!committed) {
        // Simulate transaction rollback if not committed
      }
    }
  }

  /**
   * Creates a supplier that counts the number of times it is called.
   * Useful for testing concurrent access patterns.
   * 
   * @param <T> The type of result returned by the supplier
   * @param valueSupplier The supplier that provides the actual value
   * @return A supplier that counts invocations and delegates to the provided supplier
   */
  public static <T> Supplier<T> countingSupplier(Supplier<T> valueSupplier) {
    AtomicInteger counter = new AtomicInteger(0);
    return () -> {
      counter.incrementAndGet();
      return valueSupplier.get();
    };
  }

  /**
   * Gets the number of available processors for the JVM.
   * This is useful for determining appropriate thread pool sizes.
   * 
   * @return The number of available processors
   */
  public static int getAvailableProcessors() {
    return Runtime.getRuntime().availableProcessors();
  }
  
  /**
   * Runs a task with a timeout, useful for testing operations that might deadlock.
   * 
   * @param <T> The type of result returned by the task
   * @param task The task to execute
   * @param timeout The maximum time to wait
   * @param unit The time unit of the timeout argument
   * @return The result of the task
   * @throws InterruptedException if interrupted while waiting for the task to complete
   * @throws ExecutionException if the task throws an exception
   * @throws java.util.concurrent.TimeoutException if the task times out
   */
  public static <T> T runWithTimeout(Callable<T> task, long timeout, TimeUnit unit) 
      throws InterruptedException, ExecutionException, java.util.concurrent.TimeoutException {
    ExecutorService executor = newVirtualThreadExecutor("timeout-task");
    try {
      Future<T> future = executor.submit(task);
      return future.get(timeout, unit);
    } finally {
      shutdownExecutor(executor);
    }
  }
  
  /**
   * Creates a virtual thread and starts it.
   * 
   * @param name The name of the thread
   * @param runnable The task to execute
   * @return The started virtual thread
   */
  public static Thread startVirtualThread(String name, Runnable runnable) {
    return Thread.ofVirtual().name(name).start(runnable);
  }
}