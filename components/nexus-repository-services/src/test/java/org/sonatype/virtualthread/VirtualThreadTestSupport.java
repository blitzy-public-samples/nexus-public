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
package org.sonatype.virtualthread;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repository services-specific test support for Java 21 Virtual Threads.
 * <p>
 * Extends the common {@link VirtualThreadTestSupport} with repository-specific utilities for testing
 * virtual threads in the repository services context. This includes performance measurement tools,
 * thread pinning detection, and execution helpers for running operations with configurable concurrency levels.
 *
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);

  /**
   * Tag for tests that use Java 21 features.
   */
  @Tag("java21")
  @Test
  public @interface Java21Test {
  }

  /**
   * Tag for tests that specifically test virtual thread functionality.
   */
  @Tag("virtualthread")
  @Test
  public @interface VirtualThreadTest {
  }

  /**
   * Creates a ThreadFactory that produces platform threads.
   * <p>
   * This is useful for comparative testing between platform and virtual threads.
   *
   * @return a ThreadFactory that creates platform threads
   */
  public static ThreadFactory platformThreadFactory() {
    return Thread.ofPlatform().factory();
  }

  /**
   * Creates a ThreadFactory that produces named platform threads.
   * <p>
   * This is useful for comparative testing between platform and virtual threads.
   *
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates named platform threads
   */
  public static ThreadFactory platformThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = Thread.ofPlatform().unstarted(r);
      thread.setName(namePrefix + "-" + counter.getAndIncrement());
      return thread;
    };
  }

  /**
   * Creates an ExecutorService that uses platform threads.
   * <p>
   * This is useful for comparative testing between platform and virtual threads.
   *
   * @param corePoolSize the number of threads to keep in the pool
   * @return an ExecutorService that uses platform threads
   */
  public static ExecutorService newPlatformThreadExecutor(final int corePoolSize) {
    return Executors.newFixedThreadPool(corePoolSize, platformThreadFactory());
  }

  /**
   * Creates an ExecutorService that uses named platform threads.
   * <p>
   * This is useful for comparative testing between platform and virtual threads.
   *
   * @param corePoolSize the number of threads to keep in the pool
   * @param namePrefix the prefix for thread names
   * @return an ExecutorService that uses named platform threads
   */
  public static ExecutorService newPlatformThreadExecutor(final int corePoolSize, final String namePrefix) {
    return Executors.newFixedThreadPool(corePoolSize, platformThreadFactory(namePrefix));
  }

  /**
   * Measures the throughput of a task executed multiple times concurrently using virtual threads.
   * <p>
   * This method executes the specified task the specified number of times concurrently using virtual threads,
   * and returns the number of operations completed per second.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @return the number of operations completed per second
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static double measureVirtualThreadThroughput(final Runnable task, final int iterations)
      throws InterruptedException
  {
    return measureThroughput(task, iterations, true);
  }

  /**
   * Measures the throughput of a task executed multiple times concurrently using platform threads.
   * <p>
   * This method executes the specified task the specified number of times concurrently using platform threads,
   * and returns the number of operations completed per second.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param threadPoolSize the number of platform threads to use
   * @return the number of operations completed per second
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static double measurePlatformThreadThroughput(final Runnable task, final int iterations, final int threadPoolSize)
      throws InterruptedException
  {
    ExecutorService executor = newPlatformThreadExecutor(threadPoolSize);
    try {
      return measureThroughput(executor, task, iterations);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the throughput of a task executed multiple times concurrently.
   * <p>
   * This method executes the specified task the specified number of times concurrently using either
   * virtual threads or platform threads, and returns the number of operations completed per second.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return the number of operations completed per second
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static double measureThroughput(final Runnable task, final int iterations, final boolean useVirtualThreads)
      throws InterruptedException
  {
    ExecutorService executor = useVirtualThreads
        ? newVirtualThreadExecutor()
        : newPlatformThreadExecutor(Runtime.getRuntime().availableProcessors());
    try {
      return measureThroughput(executor, task, iterations);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the throughput of a task executed multiple times concurrently using the specified executor.
   * <p>
   * This method executes the specified task the specified number of times concurrently using the specified
   * executor, and returns the number of operations completed per second.
   *
   * @param executor the executor to use
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @return the number of operations completed per second
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static double measureThroughput(final ExecutorService executor, final Runnable task, final int iterations)
      throws InterruptedException
  {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(iterations);

    // Submit all tasks
    for (int i = 0; i < iterations; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for the start signal
          task.run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the tasks and measure the time
    long startTime = System.nanoTime();
    startLatch.countDown();
    completionLatch.await();
    long endTime = System.nanoTime();

    // Calculate throughput (operations per second)
    double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
    return iterations / durationSeconds;
  }

  /**
   * Measures the latency of a task executed multiple times using virtual threads.
   * <p>
   * This method executes the specified task the specified number of times using virtual threads,
   * and returns statistics about the latency of the operations.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @return a LatencyStats object containing statistics about the latency of the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static LatencyStats measureVirtualThreadLatency(final Runnable task, final int iterations)
      throws InterruptedException
  {
    return measureLatency(task, iterations, true);
  }

  /**
   * Measures the latency of a task executed multiple times using platform threads.
   * <p>
   * This method executes the specified task the specified number of times using platform threads,
   * and returns statistics about the latency of the operations.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param threadPoolSize the number of platform threads to use
   * @return a LatencyStats object containing statistics about the latency of the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static LatencyStats measurePlatformThreadLatency(final Runnable task, final int iterations, final int threadPoolSize)
      throws InterruptedException
  {
    ExecutorService executor = newPlatformThreadExecutor(threadPoolSize);
    try {
      return measureLatency(executor, task, iterations);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the latency of a task executed multiple times.
   * <p>
   * This method executes the specified task the specified number of times using either
   * virtual threads or platform threads, and returns statistics about the latency of the operations.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return a LatencyStats object containing statistics about the latency of the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static LatencyStats measureLatency(final Runnable task, final int iterations, final boolean useVirtualThreads)
      throws InterruptedException
  {
    ExecutorService executor = useVirtualThreads
        ? newVirtualThreadExecutor()
        : newPlatformThreadExecutor(Runtime.getRuntime().availableProcessors());
    try {
      return measureLatency(executor, task, iterations);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the latency of a task executed multiple times using the specified executor.
   * <p>
   * This method executes the specified task the specified number of times using the specified
   * executor, and returns statistics about the latency of the operations.
   *
   * @param executor the executor to use
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @return a LatencyStats object containing statistics about the latency of the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static LatencyStats measureLatency(final ExecutorService executor, final Runnable task, final int iterations)
      throws InterruptedException
  {
    long[] latencies = new long[iterations];
    CountDownLatch completionLatch = new CountDownLatch(iterations);

    // Submit all tasks
    for (int i = 0; i < iterations; i++) {
      final int index = i;
      executor.submit(() -> {
        long startTime = System.nanoTime();
        try {
          task.run();
        }
        finally {
          latencies[index] = System.nanoTime() - startTime;
          completionLatch.countDown();
        }
      });
    }

    // Wait for all tasks to complete
    completionLatch.await();

    // Calculate latency statistics
    return new LatencyStats(latencies);
  }

  /**
   * Measures the memory usage of a task executed multiple times concurrently using virtual threads.
   * <p>
   * This method executes the specified task the specified number of times concurrently using virtual threads,
   * and returns statistics about the memory usage during the operations.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @return a MemoryStats object containing statistics about the memory usage during the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static MemoryStats measureVirtualThreadMemoryUsage(final Runnable task, final int iterations)
      throws InterruptedException
  {
    return measureMemoryUsage(task, iterations, true);
  }

  /**
   * Measures the memory usage of a task executed multiple times concurrently using platform threads.
   * <p>
   * This method executes the specified task the specified number of times concurrently using platform threads,
   * and returns statistics about the memory usage during the operations.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param threadPoolSize the number of platform threads to use
   * @return a MemoryStats object containing statistics about the memory usage during the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static MemoryStats measurePlatformThreadMemoryUsage(final Runnable task, final int iterations, final int threadPoolSize)
      throws InterruptedException
  {
    ExecutorService executor = newPlatformThreadExecutor(threadPoolSize);
    try {
      return measureMemoryUsage(executor, task, iterations);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the memory usage of a task executed multiple times concurrently.
   * <p>
   * This method executes the specified task the specified number of times concurrently using either
   * virtual threads or platform threads, and returns statistics about the memory usage during the operations.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return a MemoryStats object containing statistics about the memory usage during the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static MemoryStats measureMemoryUsage(final Runnable task, final int iterations, final boolean useVirtualThreads)
      throws InterruptedException
  {
    ExecutorService executor = useVirtualThreads
        ? newVirtualThreadExecutor()
        : newPlatformThreadExecutor(Runtime.getRuntime().availableProcessors());
    try {
      return measureMemoryUsage(executor, task, iterations);
    }
    finally {
      executor.shutdown();
    }
  }

  /**
   * Measures the memory usage of a task executed multiple times concurrently using the specified executor.
   * <p>
   * This method executes the specified task the specified number of times concurrently using the specified
   * executor, and returns statistics about the memory usage during the operations.
   *
   * @param executor the executor to use
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @return a MemoryStats object containing statistics about the memory usage during the operations
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static MemoryStats measureMemoryUsage(final ExecutorService executor, final Runnable task, final int iterations)
      throws InterruptedException
  {
    // Force garbage collection before starting
    System.gc();
    Thread.sleep(100); // Give GC a chance to run

    // Record initial memory usage
    long initialMemory = getUsedMemory();

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(iterations);

    // Submit all tasks
    for (int i = 0; i < iterations; i++) {
      executor.submit(() -> {
        try {
          startLatch.await(); // Wait for the start signal
          task.run();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the tasks
    startLatch.countDown();

    // Record peak memory usage during execution
    long peakMemory = initialMemory;
    while (!completionLatch.await(10, TimeUnit.MILLISECONDS)) {
      long currentMemory = getUsedMemory();
      peakMemory = Math.max(peakMemory, currentMemory);
    }

    // Force garbage collection after completion
    System.gc();
    Thread.sleep(100); // Give GC a chance to run

    // Record final memory usage
    long finalMemory = getUsedMemory();

    return new MemoryStats(initialMemory, peakMemory, finalMemory);
  }

  /**
   * Gets the current used memory in bytes.
   *
   * @return the current used memory in bytes
   */
  private static long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  /**
   * Executes a task multiple times concurrently with a throttling mechanism.
   * <p>
   * This method executes the specified task the specified number of times concurrently using virtual threads,
   * but limits the number of concurrent executions to the specified concurrency level using a semaphore.
   *
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param concurrencyLevel the maximum number of concurrent executions
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runThrottled(final Runnable task, final int iterations, final int concurrencyLevel)
      throws InterruptedException
  {
    Semaphore semaphore = new Semaphore(concurrencyLevel);
    CountDownLatch completionLatch = new CountDownLatch(iterations);

    for (int i = 0; i < iterations; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          semaphore.acquire();
          try {
            task.run();
          }
          finally {
            semaphore.release();
            completionLatch.countDown();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          completionLatch.countDown();
        }
      });
    }

    completionLatch.await();
  }

  /**
   * Executes a task supplier multiple times concurrently with a throttling mechanism.
   * <p>
   * This method executes a task provided by the specified supplier the specified number of times
   * concurrently using virtual threads, but limits the number of concurrent executions to the
   * specified concurrency level using a semaphore.
   *
   * @param taskSupplier the supplier of tasks to execute
   * @param iterations the number of times to execute a task from the supplier
   * @param concurrencyLevel the maximum number of concurrent executions
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runThrottled(final Supplier<Runnable> taskSupplier, final int iterations, final int concurrencyLevel)
      throws InterruptedException
  {
    Semaphore semaphore = new Semaphore(concurrencyLevel);
    CountDownLatch completionLatch = new CountDownLatch(iterations);

    for (int i = 0; i < iterations; i++) {
      Thread.ofVirtual().start(() -> {
        try {
          semaphore.acquire();
          try {
            taskSupplier.get().run();
          }
          finally {
            semaphore.release();
            completionLatch.countDown();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          completionLatch.countDown();
        }
      });
    }

    completionLatch.await();
  }

  /**
   * Executes a callable multiple times concurrently with a throttling mechanism.
   * <p>
   * This method executes the specified callable the specified number of times concurrently using virtual threads,
   * but limits the number of concurrent executions to the specified concurrency level using a semaphore.
   *
   * @param <T> the type of the callable's result
   * @param callable the callable to execute
   * @param iterations the number of times to execute the callable
   * @param concurrencyLevel the maximum number of concurrent executions
   * @return a list of futures representing the pending results of the callables
   */
  public static <T> List<Future<T>> callThrottled(final Callable<T> callable, final int iterations, final int concurrencyLevel) {
    Semaphore semaphore = new Semaphore(concurrencyLevel);
    List<Future<T>> futures = new ArrayList<>(iterations);

    for (int i = 0; i < iterations; i++) {
      CompletableFuture<T> future = new CompletableFuture<>();
      futures.add(future);

      Thread.ofVirtual().start(() -> {
        try {
          semaphore.acquire();
          try {
            future.complete(callable.call());
          }
          catch (Throwable e) {
            future.completeExceptionally(e);
          }
          finally {
            semaphore.release();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          future.completeExceptionally(e);
        }
      });
    }

    return futures;
  }

  /**
   * Executes a callable supplier multiple times concurrently with a throttling mechanism.
   * <p>
   * This method executes a callable provided by the specified supplier the specified number of times
   * concurrently using virtual threads, but limits the number of concurrent executions to the
   * specified concurrency level using a semaphore.
   *
   * @param <T> the type of the callable's result
   * @param callableSupplier the supplier of callables to execute
   * @param iterations the number of times to execute a callable from the supplier
   * @param concurrencyLevel the maximum number of concurrent executions
   * @return a list of futures representing the pending results of the callables
   */
  public static <T> List<Future<T>> callThrottled(final Supplier<Callable<T>> callableSupplier, final int iterations, final int concurrencyLevel) {
    Semaphore semaphore = new Semaphore(concurrencyLevel);
    List<Future<T>> futures = new ArrayList<>(iterations);

    for (int i = 0; i < iterations; i++) {
      CompletableFuture<T> future = new CompletableFuture<>();
      futures.add(future);

      Thread.ofVirtual().start(() -> {
        try {
          semaphore.acquire();
          try {
            future.complete(callableSupplier.get().call());
          }
          catch (Throwable e) {
            future.completeExceptionally(e);
          }
          finally {
            semaphore.release();
          }
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          future.completeExceptionally(e);
        }
      });
    }

    return futures;
  }

  /**
   * Detects thread pinning in a task running on a virtual thread and logs the stack trace if pinning is detected.
   * <p>
   * This method executes the specified task on a virtual thread and monitors it for thread pinning.
   * If pinning is detected, it logs the stack trace of the pinned thread.
   *
   * @param task the task to execute and check for pinning
   * @return true if pinning was detected, false otherwise
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static boolean detectAndLogThreadPinning(final Runnable task) throws InterruptedException {
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
      boolean pinningDetected = concurrentExecutions.get() < probeCount;
      if (pinningDetected) {
        log.warn("Thread pinning detected in task: {}", task);
        // Get the stack trace of the main thread if it's still alive
        if (mainThread.isAlive()) {
          StackTraceElement[] stackTrace = mainThread.getStackTrace();
          log.warn("Stack trace of pinned thread:");
          for (StackTraceElement element : stackTrace) {
            log.warn("  at {}", element);
          }
        }
      }
      return pinningDetected;
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

  /**
   * Detects thread pinning in a task running on a virtual thread and executes a consumer with the stack trace if pinning is detected.
   * <p>
   * This method executes the specified task on a virtual thread and monitors it for thread pinning.
   * If pinning is detected, it executes the specified consumer with the stack trace of the pinned thread.
   *
   * @param task the task to execute and check for pinning
   * @param pinnedStackTraceConsumer the consumer to execute with the stack trace if pinning is detected
   * @return true if pinning was detected, false otherwise
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static boolean detectThreadPinning(final Runnable task, final Consumer<StackTraceElement[]> pinnedStackTraceConsumer)
      throws InterruptedException
  {
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
      boolean pinningDetected = concurrentExecutions.get() < probeCount;
      if (pinningDetected && pinnedStackTraceConsumer != null) {
        // Get the stack trace of the main thread if it's still alive
        if (mainThread.isAlive()) {
          pinnedStackTraceConsumer.accept(mainThread.getStackTrace());
        }
      }
      return pinningDetected;
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

  /**
   * Monitors thread pinning in a task running on a virtual thread using JFR events.
   * <p>
   * This method executes the specified task on a virtual thread and monitors it for thread pinning
   * using Java Flight Recorder (JFR) events. If pinning is detected, it logs the details of the pinning event.
   *
   * @param task the task to execute and monitor for pinning
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void monitorThreadPinningWithJFR(final Runnable task) throws InterruptedException {
    // This is a placeholder for JFR-based thread pinning monitoring
    // In a real implementation, this would use JFR Event Streaming API to monitor for VirtualThreadPinned events
    // However, this requires additional dependencies and configuration
    log.warn("JFR-based thread pinning monitoring is not implemented yet");
    runVirtual(task);
  }

  /**
   * Measures the CPU time used by a task running on a virtual thread.
   * <p>
   * This method executes the specified task on a virtual thread and returns the CPU time used by the thread.
   *
   * @param task the task to execute
   * @return the CPU time used by the thread in nanoseconds, or -1 if CPU time measurement is not supported
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static long measureCpuTime(final Runnable task) throws InterruptedException {
    ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    if (!threadMXBean.isThreadCpuTimeSupported()) {
      log.warn("Thread CPU time measurement is not supported on this JVM");
      runVirtual(task);
      return -1;
    }

    AtomicLong cpuTime = new AtomicLong(-1);
    Thread thread = Thread.ofVirtual().start(() -> {
      long threadId = Thread.currentThread().threadId();
      long startCpuTime = threadMXBean.getThreadCpuTime(threadId);
      if (startCpuTime == -1) {
        log.warn("Could not measure CPU time for thread {}", threadId);
        return;
      }

      task.run();

      long endCpuTime = threadMXBean.getThreadCpuTime(threadId);
      if (endCpuTime != -1) {
        cpuTime.set(endCpuTime - startCpuTime);
      }
    });

    thread.join();
    return cpuTime.get();
  }

  /**
   * Statistics about the latency of operations.
   */
  public static class LatencyStats {
    private final long minLatency;
    private final long maxLatency;
    private final double avgLatency;
    private final long p50Latency;
    private final long p90Latency;
    private final long p99Latency;

    /**
     * Creates a new LatencyStats object from an array of latencies.
     *
     * @param latencies the latencies in nanoseconds
     */
    public LatencyStats(final long[] latencies) {
      // Sort latencies for percentile calculations
      java.util.Arrays.sort(latencies);

      this.minLatency = latencies[0];
      this.maxLatency = latencies[latencies.length - 1];

      // Calculate average
      double sum = 0;
      for (long latency : latencies) {
        sum += latency;
      }
      this.avgLatency = sum / latencies.length;

      // Calculate percentiles
      this.p50Latency = percentile(latencies, 50);
      this.p90Latency = percentile(latencies, 90);
      this.p99Latency = percentile(latencies, 99);
    }

    /**
     * Calculates the specified percentile from an array of latencies.
     *
     * @param latencies the latencies in nanoseconds
     * @param percentile the percentile to calculate (0-100)
     * @return the specified percentile
     */
    private long percentile(final long[] latencies, final int percentile) {
      int index = (int) Math.ceil(percentile / 100.0 * latencies.length) - 1;
      return latencies[index];
    }

    /**
     * Gets the minimum latency in nanoseconds.
     *
     * @return the minimum latency in nanoseconds
     */
    public long getMinLatencyNanos() {
      return minLatency;
    }

    /**
     * Gets the maximum latency in nanoseconds.
     *
     * @return the maximum latency in nanoseconds
     */
    public long getMaxLatencyNanos() {
      return maxLatency;
    }

    /**
     * Gets the average latency in nanoseconds.
     *
     * @return the average latency in nanoseconds
     */
    public double getAvgLatencyNanos() {
      return avgLatency;
    }

    /**
     * Gets the 50th percentile (median) latency in nanoseconds.
     *
     * @return the 50th percentile latency in nanoseconds
     */
    public long getP50LatencyNanos() {
      return p50Latency;
    }

    /**
     * Gets the 90th percentile latency in nanoseconds.
     *
     * @return the 90th percentile latency in nanoseconds
     */
    public long getP90LatencyNanos() {
      return p90Latency;
    }

    /**
     * Gets the 99th percentile latency in nanoseconds.
     *
     * @return the 99th percentile latency in nanoseconds
     */
    public long getP99LatencyNanos() {
      return p99Latency;
    }

    /**
     * Gets the minimum latency in milliseconds.
     *
     * @return the minimum latency in milliseconds
     */
    public double getMinLatencyMillis() {
      return minLatency / 1_000_000.0;
    }

    /**
     * Gets the maximum latency in milliseconds.
     *
     * @return the maximum latency in milliseconds
     */
    public double getMaxLatencyMillis() {
      return maxLatency / 1_000_000.0;
    }

    /**
     * Gets the average latency in milliseconds.
     *
     * @return the average latency in milliseconds
     */
    public double getAvgLatencyMillis() {
      return avgLatency / 1_000_000.0;
    }

    /**
     * Gets the 50th percentile (median) latency in milliseconds.
     *
     * @return the 50th percentile latency in milliseconds
     */
    public double getP50LatencyMillis() {
      return p50Latency / 1_000_000.0;
    }

    /**
     * Gets the 90th percentile latency in milliseconds.
     *
     * @return the 90th percentile latency in milliseconds
     */
    public double getP90LatencyMillis() {
      return p90Latency / 1_000_000.0;
    }

    /**
     * Gets the 99th percentile latency in milliseconds.
     *
     * @return the 99th percentile latency in milliseconds
     */
    public double getP99LatencyMillis() {
      return p99Latency / 1_000_000.0;
    }

    @Override
    public String toString() {
      return String.format(
          "LatencyStats{min=%.2f ms, max=%.2f ms, avg=%.2f ms, p50=%.2f ms, p90=%.2f ms, p99=%.2f ms}",
          getMinLatencyMillis(), getMaxLatencyMillis(), getAvgLatencyMillis(),
          getP50LatencyMillis(), getP90LatencyMillis(), getP99LatencyMillis());
    }
  }

  /**
   * Statistics about memory usage during operations.
   */
  public static class MemoryStats {
    private final long initialMemory;
    private final long peakMemory;
    private final long finalMemory;

    /**
     * Creates a new MemoryStats object.
     *
     * @param initialMemory the initial memory usage in bytes
     * @param peakMemory the peak memory usage in bytes
     * @param finalMemory the final memory usage in bytes
     */
    public MemoryStats(final long initialMemory, final long peakMemory, final long finalMemory) {
      this.initialMemory = initialMemory;
      this.peakMemory = peakMemory;
      this.finalMemory = finalMemory;
    }

    /**
     * Gets the initial memory usage in bytes.
     *
     * @return the initial memory usage in bytes
     */
    public long getInitialMemory() {
      return initialMemory;
    }

    /**
     * Gets the peak memory usage in bytes.
     *
     * @return the peak memory usage in bytes
     */
    public long getPeakMemory() {
      return peakMemory;
    }

    /**
     * Gets the final memory usage in bytes.
     *
     * @return the final memory usage in bytes
     */
    public long getFinalMemory() {
      return finalMemory;
    }

    /**
     * Gets the memory increase during the operations in bytes.
     *
     * @return the memory increase during the operations in bytes
     */
    public long getMemoryIncrease() {
      return peakMemory - initialMemory;
    }

    /**
     * Gets the memory retained after the operations in bytes.
     *
     * @return the memory retained after the operations in bytes
     */
    public long getMemoryRetained() {
      return finalMemory - initialMemory;
    }

    /**
     * Gets the initial memory usage in megabytes.
     *
     * @return the initial memory usage in megabytes
     */
    public double getInitialMemoryMB() {
      return initialMemory / (1024.0 * 1024.0);
    }

    /**
     * Gets the peak memory usage in megabytes.
     *
     * @return the peak memory usage in megabytes
     */
    public double getPeakMemoryMB() {
      return peakMemory / (1024.0 * 1024.0);
    }

    /**
     * Gets the final memory usage in megabytes.
     *
     * @return the final memory usage in megabytes
     */
    public double getFinalMemoryMB() {
      return finalMemory / (1024.0 * 1024.0);
    }

    /**
     * Gets the memory increase during the operations in megabytes.
     *
     * @return the memory increase during the operations in megabytes
     */
    public double getMemoryIncreaseMB() {
      return getMemoryIncrease() / (1024.0 * 1024.0);
    }

    /**
     * Gets the memory retained after the operations in megabytes.
     *
     * @return the memory retained after the operations in megabytes
     */
    public double getMemoryRetainedMB() {
      return getMemoryRetained() / (1024.0 * 1024.0);
    }

    @Override
    public String toString() {
      return String.format(
          "MemoryStats{initial=%.2f MB, peak=%.2f MB, final=%.2f MB, increase=%.2f MB, retained=%.2f MB}",
          getInitialMemoryMB(), getPeakMemoryMB(), getFinalMemoryMB(),
          getMemoryIncreaseMB(), getMemoryRetainedMB());
    }
  }
}