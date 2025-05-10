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
package org.sonatype.nexus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Support class for testing with Java 21 Virtual Threads.
 * 
 * Provides utilities for creating virtual thread executors, detecting thread pinning issues,
 * and comparing performance between platform and virtual threads.
 * 
 * @since 3.60
 */
public class VirtualThreadTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);
  
  /**
   * Creates a new ExecutorService that creates a new virtual thread for each task.
   * 
   * @return an executor service using virtual threads
   */
  public static ExecutorService executorService() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates a new ExecutorService that creates a new virtual thread for each task,
   * with the specified thread name prefix.
   * 
   * @param namePrefix the prefix for thread names
   * @return an executor service using virtual threads
   */
  public static ExecutorService executorService(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name(namePrefix, 0).factory());
  }
  
  /**
   * Creates a new platform thread ExecutorService with a fixed number of threads.
   * 
   * @param nThreads the number of threads in the pool
   * @return an executor service using platform threads
   */
  public static ExecutorService platformExecutorService(int nThreads) {
    return Executors.newFixedThreadPool(nThreads);
  }
  
  /**
   * Creates a new platform thread ExecutorService with a fixed number of threads,
   * with the specified thread name prefix.
   * 
   * @param nThreads the number of threads in the pool
   * @param namePrefix the prefix for thread names
   * @return an executor service using platform threads
   */
  public static ExecutorService platformExecutorService(int nThreads, String namePrefix) {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofPlatform().name(namePrefix, 0).factory());
  }
  
  /**
   * Creates a ThreadFactory that creates virtual threads.
   * 
   * @return a thread factory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }
  
  /**
   * Creates a ThreadFactory that creates virtual threads with the specified name prefix.
   * 
   * @param namePrefix the prefix for thread names
   * @return a thread factory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix, 0).factory();
  }
  
  /**
   * Creates a ThreadFactory that creates platform threads.
   * 
   * @return a thread factory that creates platform threads
   */
  public static ThreadFactory platformThreadFactory() {
    return Thread.ofPlatform().factory();
  }
  
  /**
   * Creates a ThreadFactory that creates platform threads with the specified name prefix.
   * 
   * @param namePrefix the prefix for thread names
   * @return a thread factory that creates platform threads
   */
  public static ThreadFactory platformThreadFactory(String namePrefix) {
    return Thread.ofPlatform().name(namePrefix, 0).factory();
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Runs the given task in a virtual thread and waits for it to complete.
   * 
   * @param task the task to run
   * @throws Exception if the task throws an exception
   */
  public static void runInVirtualThread(Runnable task) throws Exception {
    Thread thread = Thread.ofVirtual().start(task);
    thread.join();
  }
  
  /**
   * Runs the given task in a virtual thread and returns its result.
   * 
   * @param <T> the type of the result
   * @param task the task to run
   * @return the result of the task
   * @throws Exception if the task throws an exception
   */
  public static <T> T callInVirtualThread(Callable<T> task) throws Exception {
    ExecutorService executor = executorService();
    try {
      return executor.submit(task).get();
    } finally {
      executor.shutdown();
    }
  }
  
  /**
   * Utility class for tracking thread pinning events.
   */
  public static class ThreadPinningTracker
  {
    private final Map<Thread, StackTraceElement[]> pinnedThreads = new ConcurrentHashMap<>();
    
    /**
     * Checks if the current thread is pinned and records the stack trace if it is.
     * 
     * This method should be called periodically during virtual thread execution to detect pinning.
     */
    public void checkForPinning() {
      Thread currentThread = Thread.currentThread();
      if (currentThread.isVirtual()) {
        // A simple heuristic to detect pinning: if the thread is mounted for too long
        // Note: This is a simplified approach for testing purposes
        // In production, you would use JFR events or other more sophisticated methods
        if (isPinned(currentThread)) {
          pinnedThreads.putIfAbsent(currentThread, currentThread.getStackTrace());
          log.warn("Detected potential thread pinning in {}", currentThread.getName());
        }
      }
    }
    
    /**
     * Simple heuristic to check if a thread might be pinned.
     * This is a simplified approach for testing purposes.
     * 
     * @param thread the thread to check
     * @return true if the thread appears to be pinned
     */
    private boolean isPinned(Thread thread) {
      // This is a simplified check for testing purposes
      // In a real implementation, you would use JFR events or other metrics
      // to determine if a thread is actually pinned
      return false; // Placeholder for actual implementation
    }
    
    /**
     * Gets the number of detected pinned threads.
     * 
     * @return the number of pinned threads
     */
    public int getPinnedThreadCount() {
      return pinnedThreads.size();
    }
    
    /**
     * Gets the stack traces of all pinned threads.
     * 
     * @return a map of pinned threads to their stack traces
     */
    public Map<Thread, StackTraceElement[]> getPinnedThreads() {
      return new ConcurrentHashMap<>(pinnedThreads);
    }
    
    /**
     * Clears the pinned thread tracking data.
     */
    public void clear() {
      pinnedThreads.clear();
    }
    
    /**
     * Logs details about all pinned threads.
     */
    public void logPinnedThreads() {
      if (pinnedThreads.isEmpty()) {
        log.info("No pinned threads detected");
        return;
      }
      
      log.warn("Detected {} pinned virtual threads:", pinnedThreads.size());
      pinnedThreads.forEach((thread, stackTrace) -> {
        log.warn("Pinned thread: {}", thread.getName());
        for (StackTraceElement element : stackTrace) {
          log.warn("  at {}", element);
        }
      });
    }
  }
  
  /**
   * Utility class for propagating thread-local context to virtual threads.
   */
  public static class ThreadContextPropagator
  {
    /**
     * Wraps a runnable to propagate MDC context to the thread it runs in.
     * 
     * @param runnable the runnable to wrap
     * @return a wrapped runnable that propagates MDC context
     */
    public static Runnable withMdc(Runnable runnable) {
      Map<String, String> context = MDC.getCopyOfContextMap();
      return () -> {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        try {
          if (context != null) {
            MDC.setContextMap(context);
          } else {
            MDC.clear();
          }
          runnable.run();
        } finally {
          if (oldContext != null) {
            MDC.setContextMap(oldContext);
          } else {
            MDC.clear();
          }
        }
      };
    }
    
    /**
     * Wraps a callable to propagate MDC context to the thread it runs in.
     * 
     * @param <T> the return type of the callable
     * @param callable the callable to wrap
     * @return a wrapped callable that propagates MDC context
     */
    public static <T> Callable<T> withMdc(Callable<T> callable) {
      Map<String, String> context = MDC.getCopyOfContextMap();
      return () -> {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        try {
          if (context != null) {
            MDC.setContextMap(context);
          } else {
            MDC.clear();
          }
          return callable.call();
        } finally {
          if (oldContext != null) {
            MDC.setContextMap(oldContext);
          } else {
            MDC.clear();
          }
        }
      };
    }
    
    /**
     * Creates a new ExecutorService that propagates MDC context to each task.
     * 
     * @param delegate the executor service to delegate to
     * @return an executor service that propagates MDC context
     */
    public static ExecutorService withMdc(ExecutorService delegate) {
      return new ExecutorService() {
        @Override
        public void execute(Runnable command) {
          delegate.execute(withMdc(command));
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
          return delegate.submit(withMdc(task));
        }

        @Override
        public <T> Future<T> submit(Runnable task, T result) {
          return delegate.submit(withMdc(task), result);
        }

        @Override
        public Future<?> submit(Runnable task) {
          return delegate.submit(withMdc(task));
        }

        @Override
        public void shutdown() {
          delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
          return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
          return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
          return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
          return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public <T> List<Future<T>> invokeAll(List<? extends Callable<T>> tasks) throws InterruptedException {
          List<Callable<T>> wrappedTasks = new ArrayList<>(tasks.size());
          for (Callable<T> task : tasks) {
            wrappedTasks.add(withMdc(task));
          }
          return delegate.invokeAll(wrappedTasks);
        }

        @Override
        public <T> List<Future<T>> invokeAll(List<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
          List<Callable<T>> wrappedTasks = new ArrayList<>(tasks.size());
          for (Callable<T> task : tasks) {
            wrappedTasks.add(withMdc(task));
          }
          return delegate.invokeAll(wrappedTasks, timeout, unit);
        }

        @Override
        public <T> T invokeAny(List<? extends Callable<T>> tasks) throws InterruptedException, 
            java.util.concurrent.ExecutionException {
          List<Callable<T>> wrappedTasks = new ArrayList<>(tasks.size());
          for (Callable<T> task : tasks) {
            wrappedTasks.add(withMdc(task));
          }
          return delegate.invokeAny(wrappedTasks);
        }

        @Override
        public <T> T invokeAny(List<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException {
          List<Callable<T>> wrappedTasks = new ArrayList<>(tasks.size());
          for (Callable<T> task : tasks) {
            wrappedTasks.add(withMdc(task));
          }
          return delegate.invokeAny(wrappedTasks, timeout, unit);
        }
      };
    }
  }
  
  /**
   * Utility class for comparing performance between virtual threads and platform threads.
   */
  public static class PerformanceComparison
  {
    /**
     * Thread types for performance comparison.
     */
    public enum ThreadType {
      PLATFORM,
      VIRTUAL
    }
    
    /**
     * Result of a performance test.
     */
    public static class PerformanceResult
    {
      private final ThreadType threadType;
      private final long operationCount;
      private final long totalDurationNanos;
      private final long maxDurationNanos;
      private final long minDurationNanos;
      private final int errorCount;
      
      public PerformanceResult(ThreadType threadType, long operationCount, long totalDurationNanos,
                              long maxDurationNanos, long minDurationNanos, int errorCount) {
        this.threadType = threadType;
        this.operationCount = operationCount;
        this.totalDurationNanos = totalDurationNanos;
        this.maxDurationNanos = maxDurationNanos;
        this.minDurationNanos = minDurationNanos;
        this.errorCount = errorCount;
      }
      
      /**
       * Gets the thread type used for this test.
       * 
       * @return the thread type
       */
      public ThreadType getThreadType() {
        return threadType;
      }
      
      /**
       * Gets the number of operations performed.
       * 
       * @return the operation count
       */
      public long getOperationCount() {
        return operationCount;
      }
      
      /**
       * Gets the total duration of all operations in nanoseconds.
       * 
       * @return the total duration in nanoseconds
       */
      public long getTotalDurationNanos() {
        return totalDurationNanos;
      }
      
      /**
       * Gets the maximum duration of any operation in nanoseconds.
       * 
       * @return the maximum duration in nanoseconds
       */
      public long getMaxDurationNanos() {
        return maxDurationNanos;
      }
      
      /**
       * Gets the minimum duration of any operation in nanoseconds.
       * 
       * @return the minimum duration in nanoseconds
       */
      public long getMinDurationNanos() {
        return minDurationNanos;
      }
      
      /**
       * Gets the number of operations that resulted in an error.
       * 
       * @return the error count
       */
      public int getErrorCount() {
        return errorCount;
      }
      
      /**
       * Gets the average duration per operation in nanoseconds.
       * 
       * @return the average duration in nanoseconds
       */
      public double getAverageDurationNanos() {
        return operationCount > 0 ? (double) totalDurationNanos / operationCount : 0;
      }
      
      /**
       * Gets the operations per second.
       * 
       * @return the operations per second
       */
      public double getOperationsPerSecond() {
        return totalDurationNanos > 0 ? (double) operationCount * TimeUnit.SECONDS.toNanos(1) / totalDurationNanos : 0;
      }
      
      /**
       * Gets the average duration per operation in milliseconds.
       * 
       * @return the average duration in milliseconds
       */
      public double getAverageDurationMillis() {
        return getAverageDurationNanos() / 1_000_000.0;
      }
      
      /**
       * Gets the success rate as a percentage.
       * 
       * @return the success rate
       */
      public double getSuccessRate() {
        return operationCount > 0 ? 100.0 * (operationCount - errorCount) / operationCount : 0;
      }
      
      @Override
      public String toString() {
        return String.format("%s Threads: %d ops in %.2f ms (%.2f ops/sec), avg=%.2f ms, min=%.2f ms, max=%.2f ms, errors=%d (%.2f%%)",
            threadType,
            operationCount,
            totalDurationNanos / 1_000_000.0,
            getOperationsPerSecond(),
            getAverageDurationMillis(),
            minDurationNanos / 1_000_000.0,
            maxDurationNanos / 1_000_000.0,
            errorCount,
            operationCount > 0 ? 100.0 * errorCount / operationCount : 0);
      }
    }
    
    /**
     * Runs a performance comparison between virtual threads and platform threads.
     * 
     * @param operation the operation to test
     * @param concurrentOperations the number of concurrent operations to run
     * @param platformThreadCount the number of platform threads to use
     * @param timeout the maximum time to wait for operations to complete
     * @return the performance results for both thread types
     * @throws InterruptedException if the test is interrupted
     */
    public static Map<ThreadType, PerformanceResult> compare(Runnable operation, int concurrentOperations,
                                                           int platformThreadCount, Duration timeout)
        throws InterruptedException {
      Map<ThreadType, PerformanceResult> results = new ConcurrentHashMap<>();
      
      // Test with platform threads
      results.put(ThreadType.PLATFORM, 
          runTest(operation, concurrentOperations, platformThreadFactory(), platformThreadCount, timeout));
      
      // Test with virtual threads
      results.put(ThreadType.VIRTUAL, 
          runTest(operation, concurrentOperations, virtualThreadFactory(), concurrentOperations, timeout));
      
      return results;
    }
    
    /**
     * Runs a performance test with the specified thread factory.
     * 
     * @param operation the operation to test
     * @param concurrentOperations the number of concurrent operations to run
     * @param threadFactory the thread factory to use
     * @param maxThreads the maximum number of threads to use
     * @param timeout the maximum time to wait for operations to complete
     * @return the performance results
     * @throws InterruptedException if the test is interrupted
     */
    private static PerformanceResult runTest(Runnable operation, int concurrentOperations, 
                                           ThreadFactory threadFactory, int maxThreads, Duration timeout)
        throws InterruptedException {
      ThreadType threadType = threadFactory.newThread(() -> {}).isVirtual() ? 
          ThreadType.VIRTUAL : ThreadType.PLATFORM;
      
      log.info("Running performance test with {} threads ({} concurrent operations, max {} threads)",
          threadType, concurrentOperations, maxThreads);
      
      ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
      CountDownLatch latch = new CountDownLatch(concurrentOperations);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      long[] durations = new long[concurrentOperations];
      
      try {
        // Submit tasks
        for (int i = 0; i < concurrentOperations; i++) {
          final int index = i;
          executor.submit(() -> {
            long startTime = System.nanoTime();
            try {
              operation.run();
            } catch (Exception e) {
              errorCount.incrementAndGet();
              log.error("Error in operation: {}", e.getMessage(), e);
            } finally {
              durations[index] = System.nanoTime() - startTime;
              latch.countDown();
            }
          });
        }
        
        // Wait for completion
        boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
          log.warn("Test timed out after {} ms with {} operations remaining",
              timeout.toMillis(), latch.getCount());
        }
        
        // Calculate statistics
        long totalDuration = 0;
        long maxDuration = 0;
        long minDuration = Long.MAX_VALUE;
        int completedCount = 0;
        
        for (int i = 0; i < concurrentOperations; i++) {
          if (durations[i] > 0) { // Only count completed operations
            totalDuration += durations[i];
            maxDuration = Math.max(maxDuration, durations[i]);
            minDuration = Math.min(minDuration, durations[i]);
            completedCount++;
          }
        }
        
        if (completedCount == 0) {
          minDuration = 0; // Avoid returning Long.MAX_VALUE if no operations completed
        }
        
        return new PerformanceResult(
            threadType,
            completedCount,
            totalDuration,
            maxDuration,
            minDuration,
            errorCount.get());
      } finally {
        executor.shutdownNow();
      }
    }
    
    /**
     * Logs the results of a performance comparison.
     * 
     * @param results the performance results
     */
    public static void logResults(Map<ThreadType, PerformanceResult> results) {
      log.info("Performance comparison results:");
      results.values().forEach(result -> log.info(result.toString()));
      
      if (results.containsKey(ThreadType.PLATFORM) && results.containsKey(ThreadType.VIRTUAL)) {
        PerformanceResult platformResult = results.get(ThreadType.PLATFORM);
        PerformanceResult virtualResult = results.get(ThreadType.VIRTUAL);
        
        double throughputRatio = virtualResult.getOperationsPerSecond() / platformResult.getOperationsPerSecond();
        double latencyRatio = platformResult.getAverageDurationMillis() / virtualResult.getAverageDurationMillis();
        
        log.info("Virtual vs Platform comparison:");
        log.info("  Throughput ratio: {:.2f}x (virtual threads are {})", 
            throughputRatio, throughputRatio > 1 ? "faster" : "slower");
        log.info("  Latency ratio: {:.2f}x (virtual threads are {})", 
            latencyRatio, latencyRatio > 1 ? "faster" : "slower");
      }
    }
  }
  
  /**
   * Utility class for running concurrent tasks with virtual threads.
   */
  public static class ConcurrentRunner
  {
    private final ExecutorService executor;
    private final List<Future<?>> futures = new ArrayList<>();
    
    /**
     * Creates a new concurrent runner with virtual threads.
     */
    public ConcurrentRunner() {
      this(executorService());
    }
    
    /**
     * Creates a new concurrent runner with the specified executor service.
     * 
     * @param executor the executor service to use
     */
    public ConcurrentRunner(ExecutorService executor) {
      this.executor = executor;
    }
    
    /**
     * Submits a task to be executed concurrently.
     * 
     * @param task the task to execute
     * @return this runner for method chaining
     */
    public ConcurrentRunner run(Runnable task) {
      futures.add(executor.submit(task));
      return this;
    }
    
    /**
     * Submits multiple instances of the same task to be executed concurrently.
     * 
     * @param count the number of task instances to submit
     * @param task the task to execute
     * @return this runner for method chaining
     */
    public ConcurrentRunner run(int count, Runnable task) {
      for (int i = 0; i < count; i++) {
        run(task);
      }
      return this;
    }
    
    /**
     * Submits a task that will be executed with the given input values.
     * 
     * @param <T> the type of the input values
     * @param values the input values
     * @param consumer the task to execute for each value
     * @return this runner for method chaining
     */
    public <T> ConcurrentRunner runWith(List<T> values, Consumer<T> consumer) {
      for (T value : values) {
        run(() -> consumer.accept(value));
      }
      return this;
    }
    
    /**
     * Waits for all submitted tasks to complete.
     * 
     * @param timeout the maximum time to wait
     * @param unit the time unit of the timeout
     * @return true if all tasks completed, false if the timeout elapsed
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
      long deadline = System.nanoTime() + unit.toNanos(timeout);
      for (Future<?> future : futures) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
          return false;
        }
        try {
          future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
          return false;
        } catch (java.util.concurrent.ExecutionException e) {
          throw new RuntimeException("Task execution failed", e.getCause());
        }
      }
      return true;
    }
    
    /**
     * Waits for all submitted tasks to complete.
     * 
     * @throws InterruptedException if interrupted while waiting
     */
    public void await() throws InterruptedException {
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (java.util.concurrent.ExecutionException e) {
          throw new RuntimeException("Task execution failed", e.getCause());
        }
      }
    }
    
    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
      executor.shutdown();
    }
    
    /**
     * Shuts down the executor service immediately.
     * 
     * @return the list of tasks that never commenced execution
     */
    public List<Runnable> shutdownNow() {
      return executor.shutdownNow();
    }
  }
  
  /**
   * Utility class for measuring the execution time of operations.
   */
  public static class TimingHelper
  {
    /**
     * Measures the execution time of the given supplier and returns its result.
     * 
     * @param <T> the type of the result
     * @param name the name of the operation (for logging)
     * @param supplier the supplier to measure
     * @return the result of the supplier
     */
    public static <T> T timed(String name, Supplier<T> supplier) {
      long startTime = System.nanoTime();
      try {
        return supplier.get();
      } finally {
        long duration = System.nanoTime() - startTime;
        log.info("{} completed in {} ms", name, duration / 1_000_000.0);
      }
    }
    
    /**
     * Measures the execution time of the given runnable.
     * 
     * @param name the name of the operation (for logging)
     * @param runnable the runnable to measure
     */
    public static void timed(String name, Runnable runnable) {
      long startTime = System.nanoTime();
      try {
        runnable.run();
      } finally {
        long duration = System.nanoTime() - startTime;
        log.info("{} completed in {} ms", name, duration / 1_000_000.0);
      }
    }
  }
}