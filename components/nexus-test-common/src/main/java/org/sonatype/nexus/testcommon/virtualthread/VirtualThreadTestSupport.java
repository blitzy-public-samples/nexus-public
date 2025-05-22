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
package org.sonatype.nexus.testcommon.virtualthread;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for tests involving Java 21 Virtual Threads in Nexus.
 * <p>
 * Provides utilities for creating, managing, and validating Virtual Threads in test environments.
 * This class includes methods to check JVM support for Virtual Threads, create thread factories,
 * execute tasks on Virtual Threads, and wait for their completion.
 *
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);

  /**
   * Checks if the current JVM supports Virtual Threads.
   * <p>
   * This method attempts to create a virtual thread and verifies that it's actually virtual.
   * If virtual threads are not supported, it returns false.
   *
   * @return true if the current JVM supports Virtual Threads, false otherwise
   */
  public static boolean isVirtualThreadSupported() {
    try {
      Thread thread = Thread.ofVirtual().start(() -> { /* no-op */ });
      thread.join();
      return thread.isVirtual();
    }
    catch (Exception e) {
      log.debug("Virtual threads not supported by this JVM", e);
      return false;
    }
  }

  /**
   * Assumes that the current JVM supports Virtual Threads.
   * <p>
   * This method is useful for tests that should be skipped if Virtual Threads are not supported.
   * It uses JUnit's Assumptions to skip the test if Virtual Threads are not available.
   */
  public static void assumeVirtualThreadSupported() {
    Assumptions.assumeTrue(isVirtualThreadSupported(), "This test requires Java 21 Virtual Threads support");
  }

  /**
   * Creates a ThreadFactory that produces Virtual Threads.
   * <p>
   * The created threads will have default settings.
   *
   * @return a ThreadFactory that creates Virtual Threads
   */
  public static ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates a ThreadFactory that produces named Virtual Threads.
   * <p>
   * The created threads will have names with the specified prefix followed by a sequential number.
   *
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates named Virtual Threads
   */
  public static ThreadFactory virtualThreadFactory(final String namePrefix) {
    return Thread.ofVirtual().name(namePrefix, 0).factory();
  }

  /**
   * Creates an ExecutorService that creates a new Virtual Thread for each task.
   * <p>
   * This executor is suitable for running large numbers of tasks that spend most of their
   * time blocked on I/O operations.
   *
   * @return an ExecutorService that uses Virtual Threads
   */
  public static ExecutorService newVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates an ExecutorService that creates named Virtual Threads for each task.
   * <p>
   * This executor is suitable for running large numbers of tasks that spend most of their
   * time blocked on I/O operations. The threads will have names with the specified prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return an ExecutorService that uses named Virtual Threads
   */
  public static ExecutorService newVirtualThreadExecutor(final String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }

  /**
   * Executes a task on a Virtual Thread and waits for its completion.
   * <p>
   * This method creates a new Virtual Thread, runs the specified task on it,
   * and waits for the thread to complete.
   *
   * @param task the task to execute
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runVirtual(final Runnable task) throws InterruptedException {
    Thread thread = Thread.ofVirtual().start(task);
    thread.join();
  }

  /**
   * Executes a task on a Virtual Thread with a timeout.
   * <p>
   * This method creates a new Virtual Thread, runs the specified task on it,
   * and waits for the thread to complete within the specified timeout.
   *
   * @param task the task to execute
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return true if the thread completed within the timeout, false if the timeout elapsed
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static boolean runVirtual(final Runnable task, final long timeout, final TimeUnit unit)
      throws InterruptedException
  {
    Thread thread = Thread.ofVirtual().start(task);
    return thread.join(unit.toMillis(timeout));
  }

  /**
   * Executes a callable on a Virtual Thread and returns its result.
   * <p>
   * This method creates a new Virtual Thread, runs the specified callable on it,
   * and returns the result after the thread completes.
   *
   * @param <T> the type of the callable's result
   * @param callable the callable to execute
   * @return the result of the callable
   * @throws ExecutionException if the callable throws an exception
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static <T> T callVirtual(final Callable<T> callable) throws ExecutionException, InterruptedException {
    CompletableFuture<T> future = new CompletableFuture<>();
    Thread thread = Thread.ofVirtual().start(() -> {
      try {
        future.complete(callable.call());
      }
      catch (Throwable e) {
        future.completeExceptionally(e);
      }
    });
    return future.get();
  }

  /**
   * Executes a callable on a Virtual Thread with a timeout and returns its result.
   * <p>
   * This method creates a new Virtual Thread, runs the specified callable on it,
   * and returns the result if the thread completes within the specified timeout.
   *
   * @param <T> the type of the callable's result
   * @param callable the callable to execute
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return the result of the callable
   * @throws ExecutionException if the callable throws an exception
   * @throws InterruptedException if the current thread is interrupted while waiting
   * @throws TimeoutException if the timeout elapses before the callable completes
   */
  public static <T> T callVirtual(final Callable<T> callable, final long timeout, final TimeUnit unit)
      throws ExecutionException, InterruptedException, TimeoutException
  {
    CompletableFuture<T> future = new CompletableFuture<>();
    Thread thread = Thread.ofVirtual().start(() -> {
      try {
        future.complete(callable.call());
      }
      catch (Throwable e) {
        future.completeExceptionally(e);
      }
    });
    return future.get(timeout, unit);
  }

  /**
   * Executes multiple tasks concurrently on Virtual Threads and waits for all to complete.
   * <p>
   * This method creates a Virtual Thread for each task and waits for all threads to complete.
   *
   * @param tasks the tasks to execute
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runConcurrently(final Runnable... tasks) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(tasks.length);
    Thread[] threads = new Thread[tasks.length];

    for (int i = 0; i < tasks.length; i++) {
      final Runnable task = tasks[i];
      threads[i] = Thread.ofVirtual().start(() -> {
        try {
          task.run();
        }
        finally {
          latch.countDown();
        }
      });
    }

    latch.await();
  }

  /**
   * Executes multiple tasks concurrently on Virtual Threads with a timeout.
   * <p>
   * This method creates a Virtual Thread for each task and waits for all threads to complete
   * within the specified timeout.
   *
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @param tasks the tasks to execute
   * @return true if all tasks completed within the timeout, false if the timeout elapsed
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static boolean runConcurrently(final long timeout, final TimeUnit unit, final Runnable... tasks)
      throws InterruptedException
  {
    CountDownLatch latch = new CountDownLatch(tasks.length);
    Thread[] threads = new Thread[tasks.length];

    for (int i = 0; i < tasks.length; i++) {
      final Runnable task = tasks[i];
      threads[i] = Thread.ofVirtual().start(() -> {
        try {
          task.run();
        }
        finally {
          latch.countDown();
        }
      });
    }

    return latch.await(timeout, unit);
  }

  /**
   * Executes a task multiple times concurrently on Virtual Threads and waits for all to complete.
   * <p>
   * This method creates the specified number of Virtual Threads, each executing the same task,
   * and waits for all threads to complete.
   *
   * @param count the number of times to execute the task
   * @param task the task to execute
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runConcurrently(final int count, final Runnable task) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(count);
    Thread[] threads = new Thread[count];

    for (int i = 0; i < count; i++) {
      threads[i] = Thread.ofVirtual().start(() -> {
        try {
          task.run();
        }
        finally {
          latch.countDown();
        }
      });
    }

    latch.await();
  }

  /**
   * Executes a task supplier multiple times concurrently on Virtual Threads and waits for all to complete.
   * <p>
   * This method creates the specified number of Virtual Threads, each executing a task provided by the supplier,
   * and waits for all threads to complete.
   *
   * @param count the number of tasks to execute
   * @param taskSupplier the supplier of tasks to execute
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runConcurrently(final int count, final Supplier<Runnable> taskSupplier)
      throws InterruptedException
  {
    CountDownLatch latch = new CountDownLatch(count);
    Thread[] threads = new Thread[count];

    for (int i = 0; i < count; i++) {
      threads[i] = Thread.ofVirtual().start(() -> {
        try {
          taskSupplier.get().run();
        }
        finally {
          latch.countDown();
        }
      });
    }

    latch.await();
  }

  /**
   * Executes a callable multiple times concurrently on Virtual Threads and returns all results.
   * <p>
   * This method creates the specified number of Virtual Threads, each executing the same callable,
   * and returns an array of futures that can be used to retrieve the results.
   *
   * @param <T> the type of the callable's result
   * @param count the number of times to execute the callable
   * @param callable the callable to execute
   * @return an array of futures representing the pending results of the callables
   */
  public static <T> Future<T>[] callConcurrently(final int count, final Callable<T> callable) {
    ExecutorService executor = newVirtualThreadExecutor();
    try {
      @SuppressWarnings("unchecked")
      Future<T>[] futures = new Future[count];
      for (int i = 0; i < count; i++) {
        futures[i] = executor.submit(callable);
      }
      return futures;
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Executes a callable supplier multiple times concurrently on Virtual Threads and returns all results.
   * <p>
   * This method creates the specified number of Virtual Threads, each executing a callable provided by the supplier,
   * and returns an array of futures that can be used to retrieve the results.
   *
   * @param <T> the type of the callable's result
   * @param count the number of callables to execute
   * @param callableSupplier the supplier of callables to execute
   * @return an array of futures representing the pending results of the callables
   */
  public static <T> Future<T>[] callConcurrently(final int count, final Supplier<Callable<T>> callableSupplier) {
    ExecutorService executor = newVirtualThreadExecutor();
    try {
      @SuppressWarnings("unchecked")
      Future<T>[] futures = new Future[count];
      for (int i = 0; i < count; i++) {
        futures[i] = executor.submit(callableSupplier.get());
      }
      return futures;
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the execution time of a task running on a Virtual Thread.
   * <p>
   * This method executes the specified task on a Virtual Thread and returns the execution time in milliseconds.
   *
   * @param task the task to execute
   * @return the execution time in milliseconds
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static long measureExecutionTime(final Runnable task) throws InterruptedException {
    long startTime = System.currentTimeMillis();
    runVirtual(task);
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Detects if a task running on a Virtual Thread experiences thread pinning.
   * <p>
   * Thread pinning occurs when a Virtual Thread is pinned to its carrier thread, preventing the carrier
   * from executing other Virtual Threads. This typically happens when the Virtual Thread executes code
   * inside a synchronized block or method, or when it calls native methods.
   * <p>
   * This method is useful for identifying potential performance bottlenecks in code using Virtual Threads.
   * Note that this detection is best-effort and may not catch all pinning scenarios.
   *
   * @param task the task to execute and check for pinning
   * @return true if pinning was detected, false otherwise
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static boolean detectThreadPinning(final Runnable task) throws InterruptedException {
    // Enable thread pinning detection via JVM flag
    String previousValue = System.getProperty("jdk.tracePinnedThreads");
    try {
      System.setProperty("jdk.tracePinnedThreads", "full");
      
      // Create a concurrent task that will run alongside the main task
      AtomicInteger concurrentExecutions = new AtomicInteger(0);
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch endLatch = new CountDownLatch(1);
      
      // Start the main task
      Thread mainThread = Thread.ofVirtual().start(() -> {
        try {
          startLatch.countDown(); // Signal that the main thread has started
          task.run();
        }
        finally {
          endLatch.countDown(); // Signal that the main thread has completed
        }
      });
      
      // Wait for the main thread to start
      startLatch.await();
      
      // Start multiple concurrent tasks to detect pinning
      int probeCount = 10;
      Thread[] probeThreads = new Thread[probeCount];
      for (int i = 0; i < probeCount; i++) {
        probeThreads[i] = Thread.ofVirtual().start(() -> {
          concurrentExecutions.incrementAndGet();
        });
      }
      
      // Wait for the main thread to complete
      endLatch.await();
      
      // If fewer than expected concurrent executions occurred, pinning may have happened
      return concurrentExecutions.get() < probeCount;
    }
    finally {
      // Restore the previous system property value
      if (previousValue == null) {
        System.clearProperty("jdk.tracePinnedThreads");
      }
      else {
        System.setProperty("jdk.tracePinnedThreads", previousValue);
      }
    }
  }
}