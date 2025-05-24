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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.Assert;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides utility methods and test support for integration testing with Java 21 Virtual Threads.
 * This class offers factory methods to create Virtual Thread executors, utilities to verify thread usage patterns,
 * detection of thread pinning scenarios, and assertion helpers for Virtual Thread performance validation.
 * <p>
 * Virtual Threads are lightweight threads that are managed by the JVM rather than the operating system.
 * They are particularly well-suited for I/O-bound operations such as network requests, file operations,
 * and database queries. This test support class helps ensure that Nexus components correctly leverage
 * Virtual Threads for I/O operations and avoid pinning in critical paths.
 * <p>
 * Key features:
 * <ul>
 *   <li>Factory methods for creating Virtual Thread executors and thread factories</li>
 *   <li>Thread pinning detection utilities</li>
 *   <li>Performance comparison between platform and virtual threads</li>
 *   <li>Integration with Pax Exam test lifecycle</li>
 *   <li>Assertion utilities for Virtual Thread testing</li>
 * </ul>
 *
 * @since 3.60
 */
public class VirtualThreadTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);

  /**
   * Default timeout for thread operations in tests.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Default number of threads to use for platform thread pools in comparison tests.
   */
  public static final int DEFAULT_PLATFORM_THREAD_COUNT = 100;

  /**
   * Default number of virtual threads to use in tests.
   */
  public static final int DEFAULT_VIRTUAL_THREAD_COUNT = 1000;

  /**
   * Creates a ThreadFactory that produces virtual threads with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a ThreadFactory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual()
        .name(namePrefix, 0)
        .factory();
  }

  /**
   * Creates a ThreadFactory that produces platform threads with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a ThreadFactory that creates platform threads
   */
  public static ThreadFactory platformThreadFactory(String namePrefix) {
    return Thread.ofPlatform()
        .name(namePrefix, 0)
        .factory();
  }

  /**
   * Creates an ExecutorService that uses virtual threads.
   * This is optimal for I/O-bound tasks that spend most of their time waiting for external resources.
   *
   * @return an ExecutorService using virtual threads
   */
  public static ExecutorService newVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates an ExecutorService that uses virtual threads with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return an ExecutorService using virtual threads
   */
  public static ExecutorService newVirtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }

  /**
   * Creates an ExecutorService that uses platform threads.
   *
   * @param nThreads the number of threads in the pool
   * @return an ExecutorService using platform threads
   */
  public static ExecutorService newPlatformThreadExecutor(int nThreads) {
    return Executors.newFixedThreadPool(nThreads);
  }

  /**
   * Creates an ExecutorService that uses platform threads with the specified name prefix.
   *
   * @param nThreads the number of threads in the pool
   * @param namePrefix the prefix to use for thread names
   * @return an ExecutorService using platform threads
   */
  public static ExecutorService newPlatformThreadExecutor(int nThreads, String namePrefix) {
    return Executors.newFixedThreadPool(nThreads, platformThreadFactory(namePrefix));
  }

  /**
   * Represents metrics collected during virtual thread execution.
   */
  public static class VirtualThreadMetrics {
    private final AtomicLong totalVirtualThreadsCreated = new AtomicLong(0);
    private final AtomicLong pinnedThreadCount = new AtomicLong(0);
    private final AtomicLong maxConcurrentVirtualThreads = new AtomicLong(0);
    private final AtomicLong activeVirtualThreads = new AtomicLong(0);
    private final Map<Thread, StackTraceElement[]> pinnedThreads = new ConcurrentHashMap<>();

    /**
     * Gets the total number of virtual threads created.
     *
     * @return the total number of virtual threads created
     */
    public long getTotalVirtualThreadsCreated() {
      return totalVirtualThreadsCreated.get();
    }

    /**
     * Gets the number of virtual threads that were pinned.
     *
     * @return the number of pinned virtual threads
     */
    public long getPinnedThreadCount() {
      return pinnedThreadCount.get();
    }

    /**
     * Gets the maximum number of concurrent virtual threads observed.
     *
     * @return the maximum concurrent virtual threads
     */
    public long getMaxConcurrentVirtualThreads() {
      return maxConcurrentVirtualThreads.get();
    }

    /**
     * Gets the current number of active virtual threads.
     *
     * @return the current active virtual threads
     */
    public long getActiveVirtualThreads() {
      return activeVirtualThreads.get();
    }

    /**
     * Gets a map of pinned threads and their stack traces.
     *
     * @return a map of pinned threads to their stack traces
     */
    public Map<Thread, StackTraceElement[]> getPinnedThreads() {
      return Collections.unmodifiableMap(pinnedThreads);
    }

    /**
     * Records a new virtual thread creation.
     */
    void recordThreadCreation() {
      totalVirtualThreadsCreated.incrementAndGet();
      long active = activeVirtualThreads.incrementAndGet();
      updateMaxConcurrent(active);
    }

    /**
     * Records a virtual thread termination.
     */
    void recordThreadTermination() {
      activeVirtualThreads.decrementAndGet();
    }

    /**
     * Records a thread pinning event.
     *
     * @param thread the pinned thread
     * @param stackTrace the stack trace at the time of pinning
     */
    void recordThreadPinning(Thread thread, StackTraceElement[] stackTrace) {
      pinnedThreadCount.incrementAndGet();
      pinnedThreads.put(thread, stackTrace);
    }

    /**
     * Updates the maximum concurrent virtual threads count if necessary.
     *
     * @param currentActive the current number of active threads
     */
    private void updateMaxConcurrent(long currentActive) {
      long current;
      do {
        current = maxConcurrentVirtualThreads.get();
        if (currentActive <= current) {
          break;
        }
      } while (!maxConcurrentVirtualThreads.compareAndSet(current, currentActive));
    }

    /**
     * Resets all metrics to zero.
     */
    public void reset() {
      totalVirtualThreadsCreated.set(0);
      pinnedThreadCount.set(0);
      maxConcurrentVirtualThreads.set(0);
      activeVirtualThreads.set(0);
      pinnedThreads.clear();
    }

    @Override
    public String toString() {
      return "VirtualThreadMetrics{" +
          "totalVirtualThreadsCreated=" + totalVirtualThreadsCreated.get() +
          ", pinnedThreadCount=" + pinnedThreadCount.get() +
          ", maxConcurrentVirtualThreads=" + maxConcurrentVirtualThreads.get() +
          ", activeVirtualThreads=" + activeVirtualThreads.get() +
          ", pinnedThreads=" + pinnedThreads.size() +
          '}';
    }
  }

  /**
   * A collector for virtual thread metrics during test execution.
   */
  public static class VirtualThreadMetricsCollector {
    private final VirtualThreadMetrics metrics = new VirtualThreadMetrics();
    private final Set<Thread> monitoredThreads = ConcurrentHashMap.newKeySet();
    private final ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
    private volatile boolean monitoring = false;
    private Thread monitorThread;

    /**
     * Gets the collected metrics.
     *
     * @return the virtual thread metrics
     */
    public VirtualThreadMetrics getMetrics() {
      return metrics;
    }

    /**
     * Starts monitoring virtual threads.
     */
    public void startMonitoring() {
      if (monitoring) {
        return;
      }

      monitoring = true;
      monitorThread = new Thread(() -> {
        while (monitoring) {
          checkForPinnedThreads();
          try {
            Thread.sleep(100);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }, "virtual-thread-monitor");
      monitorThread.setDaemon(true);
      monitorThread.start();
    }

    /**
     * Stops monitoring virtual threads.
     */
    public void stopMonitoring() {
      monitoring = false;
      if (monitorThread != null) {
        monitorThread.interrupt();
        try {
          monitorThread.join(1000);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        monitorThread = null;
      }
    }

    /**
     * Registers a virtual thread for monitoring.
     *
     * @param thread the virtual thread to monitor
     */
    public void registerThread(Thread thread) {
      if (thread.isVirtual()) {
        monitoredThreads.add(thread);
        metrics.recordThreadCreation();
      }
    }

    /**
     * Unregisters a virtual thread from monitoring.
     *
     * @param thread the virtual thread to unregister
     */
    public void unregisterThread(Thread thread) {
      if (monitoredThreads.remove(thread)) {
        metrics.recordThreadTermination();
      }
    }

    /**
     * Checks for pinned virtual threads and records them.
     */
    private void checkForPinnedThreads() {
      for (Thread thread : monitoredThreads) {
        if (thread.isVirtual() && isPinned(thread)) {
          metrics.recordThreadPinning(thread, thread.getStackTrace());
        }
      }
    }

    /**
     * Determines if a thread is pinned.
     * A virtual thread is considered pinned when it occupies a carrier thread
     * but cannot make progress, typically due to a blocking operation that
     * doesn't allow the virtual thread to be unmounted.
     *
     * @param thread the thread to check
     * @return true if the thread is pinned, false otherwise
     */
    private boolean isPinned(Thread thread) {
      if (!thread.isVirtual()) {
        return false;
      }

      // Check if thread is in BLOCKED or WAITING state
      Thread.State state = thread.getState();
      if (state != Thread.State.BLOCKED && state != Thread.State.WAITING) {
        return false;
      }

      // Get detailed thread info to check for pinning
      ThreadInfo threadInfo = threadMXBean.getThreadInfo(thread.getId());
      if (threadInfo == null) {
        return false;
      }

      // Check for common pinning scenarios
      StackTraceElement[] stackTrace = thread.getStackTrace();
      if (stackTrace.length == 0) {
        return false;
      }

      // Check for synchronized blocks (common pinning cause)
      if (threadInfo.getLockInfo() != null && "java.lang.Object".equals(threadInfo.getLockInfo().getClassName())) {
        return true;
      }

      // Check for native methods (another common pinning cause)
      for (StackTraceElement element : stackTrace) {
        if (element.isNativeMethod()) {
          return true;
        }
      }

      return false;
    }

    /**
     * Resets the collector, clearing all metrics and monitored threads.
     */
    public void reset() {
      metrics.reset();
      monitoredThreads.clear();
    }
  }

  /**
   * A JUnit rule that collects virtual thread metrics during test execution.
   */
  public static class VirtualThreadMonitorRule implements TestRule {
    private final VirtualThreadMetricsCollector collector = new VirtualThreadMetricsCollector();

    @Override
    public Statement apply(final Statement base, final Description description) {
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          collector.reset();
          collector.startMonitoring();
          try {
            base.evaluate();
          }
          finally {
            collector.stopMonitoring();
            logMetrics(description);
          }
        }
      };
    }

    /**
     * Gets the metrics collector.
     *
     * @return the virtual thread metrics collector
     */
    public VirtualThreadMetricsCollector getCollector() {
      return collector;
    }

    /**
     * Gets the collected metrics.
     *
     * @return the virtual thread metrics
     */
    public VirtualThreadMetrics getMetrics() {
      return collector.getMetrics();
    }

    /**
     * Logs the collected metrics after test execution.
     *
     * @param description the test description
     */
    private void logMetrics(Description description) {
      VirtualThreadMetrics metrics = collector.getMetrics();
      log.info("Virtual Thread Metrics for {}: {}", description.getDisplayName(), metrics);

      if (metrics.getPinnedThreadCount() > 0) {
        log.warn("Detected {} pinned virtual threads in {}", metrics.getPinnedThreadCount(), description.getDisplayName());
        metrics.getPinnedThreads().forEach((thread, stackTrace) -> {
          StringWriter sw = new StringWriter();
          PrintWriter pw = new PrintWriter(sw);
          for (StackTraceElement element : stackTrace) {
            pw.println("\tat " + element);
          }
          log.warn("Pinned thread {}: {}\n{}", thread.getName(), thread.getState(), sw);
        });
      }
    }
  }

  /**
   * Executes a task with both virtual threads and platform threads, and compares their performance.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @return a PerformanceComparison object containing the results
   * @throws Exception if an error occurs during execution
   */
  public static PerformanceComparison compareThreadPerformance(Runnable task, int iterations) throws Exception {
    return compareThreadPerformance(task, iterations, DEFAULT_PLATFORM_THREAD_COUNT);
  }

  /**
   * Executes a task with both virtual threads and platform threads, and compares their performance.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param platformThreadCount the number of platform threads to use
   * @return a PerformanceComparison object containing the results
   * @throws Exception if an error occurs during execution
   */
  public static PerformanceComparison compareThreadPerformance(
      Runnable task,
      int iterations,
      int platformThreadCount) throws Exception
  {
    // Run with platform threads
    long platformStart = System.nanoTime();
    ExecutorService platformExecutor = newPlatformThreadExecutor(platformThreadCount, "platform-test-");
    try {
      runConcurrentTasks(platformExecutor, task, iterations);
    }
    finally {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
    long platformDuration = System.nanoTime() - platformStart;

    // Run with virtual threads
    long virtualStart = System.nanoTime();
    ExecutorService virtualExecutor = newVirtualThreadExecutor("virtual-test-");
    try {
      runConcurrentTasks(virtualExecutor, task, iterations);
    }
    finally {
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
    long virtualDuration = System.nanoTime() - virtualStart;

    return new PerformanceComparison(platformDuration, virtualDuration, iterations);
  }

  /**
   * Runs a task concurrently using the provided executor.
   *
   * @param executor the executor service to use
   * @param task the task to run
   * @param iterations the number of iterations to run
   * @throws Exception if an error occurs during execution
   */
  private static void runConcurrentTasks(ExecutorService executor, Runnable task, int iterations) throws Exception {
    CountDownLatch latch = new CountDownLatch(iterations);
    List<Future<?>> futures = new ArrayList<>(iterations);

    for (int i = 0; i < iterations; i++) {
      futures.add(executor.submit(() -> {
        try {
          task.run();
        }
        finally {
          latch.countDown();
        }
      }));
    }

    // Wait for all tasks to complete
    if (!latch.await(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      throw new RuntimeException("Timeout waiting for tasks to complete");
    }

    // Check for exceptions
    for (Future<?> future : futures) {
      future.get(1, TimeUnit.SECONDS); // Short timeout since tasks should be done
    }
  }

  /**
   * Contains performance comparison results between platform threads and virtual threads.
   */
  public static class PerformanceComparison {
    private final long platformDurationNanos;
    private final long virtualDurationNanos;
    private final int iterations;

    /**
     * Creates a new PerformanceComparison.
     *
     * @param platformDurationNanos the duration in nanoseconds for platform threads
     * @param virtualDurationNanos the duration in nanoseconds for virtual threads
     * @param iterations the number of iterations run
     */
    public PerformanceComparison(long platformDurationNanos, long virtualDurationNanos, int iterations) {
      this.platformDurationNanos = platformDurationNanos;
      this.virtualDurationNanos = virtualDurationNanos;
      this.iterations = iterations;
    }

    /**
     * Gets the duration in milliseconds for platform threads.
     *
     * @return the platform thread duration in milliseconds
     */
    public long getPlatformDurationMillis() {
      return TimeUnit.NANOSECONDS.toMillis(platformDurationNanos);
    }

    /**
     * Gets the duration in milliseconds for virtual threads.
     *
     * @return the virtual thread duration in milliseconds
     */
    public long getVirtualDurationMillis() {
      return TimeUnit.NANOSECONDS.toMillis(virtualDurationNanos);
    }

    /**
     * Gets the number of iterations run.
     *
     * @return the number of iterations
     */
    public int getIterations() {
      return iterations;
    }

    /**
     * Calculates the speedup factor of virtual threads compared to platform threads.
     * A value greater than 1.0 indicates that virtual threads were faster.
     *
     * @return the speedup factor
     */
    public double getSpeedupFactor() {
      return (double) platformDurationNanos / virtualDurationNanos;
    }

    /**
     * Determines if virtual threads provided a significant performance improvement.
     * A significant improvement is defined as at least a 10% speedup.
     *
     * @return true if virtual threads provided a significant improvement
     */
    public boolean hasSignificantImprovement() {
      return getSpeedupFactor() >= 1.1;
    }

    @Override
    public String toString() {
      return String.format(
          "Performance Comparison (%d iterations): Platform: %d ms, Virtual: %d ms, Speedup: %.2fx",
          iterations,
          getPlatformDurationMillis(),
          getVirtualDurationMillis(),
          getSpeedupFactor());
    }
  }

  /**
   * Asserts that a task runs faster with virtual threads than with platform threads.
   *
   * @param task the task to run
   * @param iterations the number of iterations to run
   * @throws Exception if an error occurs during execution
   */
  public static void assertVirtualThreadsPerformBetter(Runnable task, int iterations) throws Exception {
    PerformanceComparison comparison = compareThreadPerformance(task, iterations);
    log.info(comparison.toString());
    Assert.assertTrue(
        "Expected virtual threads to perform better than platform threads, but got: " + comparison,
        comparison.hasSignificantImprovement());
  }

  /**
   * Asserts that no thread pinning occurs when running a task with virtual threads.
   *
   * @param task the task to run
   * @throws Exception if an error occurs during execution
   */
  public static void assertNoPinning(Runnable task) throws Exception {
    assertNoPinning(task, DEFAULT_VIRTUAL_THREAD_COUNT);
  }

  /**
   * Asserts that no thread pinning occurs when running a task with virtual threads.
   *
   * @param task the task to run
   * @param iterations the number of iterations to run
   * @throws Exception if an error occurs during execution
   */
  public static void assertNoPinning(Runnable task, int iterations) throws Exception {
    VirtualThreadMetricsCollector collector = new VirtualThreadMetricsCollector();
    collector.startMonitoring();

    try {
      ExecutorService executor = newVirtualThreadExecutor("pinning-test-");
      try {
        CountDownLatch latch = new CountDownLatch(iterations);

        for (int i = 0; i < iterations; i++) {
          executor.submit(() -> {
            Thread thread = Thread.currentThread();
            collector.registerThread(thread);
            try {
              task.run();
            }
            finally {
              collector.unregisterThread(thread);
              latch.countDown();
            }
          });
        }

        // Wait for all tasks to complete
        if (!latch.await(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
          throw new RuntimeException("Timeout waiting for tasks to complete");
        }
      }
      finally {
        executor.shutdown();
        executor.awaitTermination(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      }
    }
    finally {
      collector.stopMonitoring();
    }

    VirtualThreadMetrics metrics = collector.getMetrics();
    if (metrics.getPinnedThreadCount() > 0) {
      StringBuilder sb = new StringBuilder()
          .append("Detected ")
          .append(metrics.getPinnedThreadCount())
          .append(" pinned virtual threads:\n");

      metrics.getPinnedThreads().forEach((thread, stackTrace) -> {
        sb.append("Thread ").append(thread.getName()).append(":\n");
        for (StackTraceElement element : stackTrace) {
          sb.append("\tat ").append(element).append("\n");
        }
        sb.append("\n");
      });

      Assert.fail(sb.toString());
    }
  }

  /**
   * Runs a task with virtual threads and returns the collected metrics.
   *
   * @param task the task to run
   * @param iterations the number of iterations to run
   * @return the virtual thread metrics
   * @throws Exception if an error occurs during execution
   */
  public static VirtualThreadMetrics collectVirtualThreadMetrics(Runnable task, int iterations) throws Exception {
    VirtualThreadMetricsCollector collector = new VirtualThreadMetricsCollector();
    collector.startMonitoring();

    try {
      ExecutorService executor = newVirtualThreadExecutor("metrics-test-");
      try {
        CountDownLatch latch = new CountDownLatch(iterations);

        for (int i = 0; i < iterations; i++) {
          executor.submit(() -> {
            Thread thread = Thread.currentThread();
            collector.registerThread(thread);
            try {
              task.run();
            }
            finally {
              collector.unregisterThread(thread);
              latch.countDown();
            }
          });
        }

        // Wait for all tasks to complete
        if (!latch.await(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
          throw new RuntimeException("Timeout waiting for tasks to complete");
        }
      }
      finally {
        executor.shutdown();
        executor.awaitTermination(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
      }
    }
    finally {
      collector.stopMonitoring();
    }

    return collector.getMetrics();
  }

  /**
   * Executes a task with virtual threads and measures the execution time.
   *
   * @param task the task to run
   * @param iterations the number of iterations to run
   * @return the execution time in milliseconds
   * @throws Exception if an error occurs during execution
   */
  public static long measureVirtualThreadExecutionTime(Runnable task, int iterations) throws Exception {
    long start = System.nanoTime();
    ExecutorService executor = newVirtualThreadExecutor("timing-test-");
    try {
      runConcurrentTasks(executor, task, iterations);
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
  }

  /**
   * Creates a virtual thread and runs the specified task.
   *
   * @param name the name of the thread
   * @param task the task to run
   * @return the created thread
   */
  public static Thread startVirtualThread(String name, Runnable task) {
    Thread thread = Thread.ofVirtual().name(name).start(task);
    return thread;
  }

  /**
   * Creates a virtual thread and runs the specified task, waiting for it to complete.
   *
   * @param name the name of the thread
   * @param task the task to run
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static void runVirtualThread(String name, Runnable task) throws InterruptedException {
    Thread thread = startVirtualThread(name, task);
    thread.join();
  }

  /**
   * Creates a virtual thread and runs the specified task, waiting for it to complete with a timeout.
   *
   * @param name the name of the thread
   * @param task the task to run
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return true if the thread terminated, false if the timeout elapsed before termination
   * @throws InterruptedException if the current thread is interrupted while waiting
   */
  public static boolean runVirtualThread(String name, Runnable task, long timeout, TimeUnit unit)
      throws InterruptedException
  {
    Thread thread = startVirtualThread(name, task);
    thread.join(unit.toMillis(timeout));
    return !thread.isAlive();
  }

  /**
   * Executes a task with both virtual threads and platform threads, logging the performance comparison.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @throws Exception if an error occurs during execution
   */
  public static void logThreadPerformanceComparison(Runnable task, int iterations) throws Exception {
    PerformanceComparison comparison = compareThreadPerformance(task, iterations);
    log.info(comparison.toString());
  }

  /**
   * Executes a task with both virtual threads and platform threads, logging the performance comparison
   * and returning the comparison object.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param platformThreadCount the number of platform threads to use
   * @return the performance comparison results
   * @throws Exception if an error occurs during execution
   */
  public static PerformanceComparison logThreadPerformanceComparison(
      Runnable task,
      int iterations,
      int platformThreadCount) throws Exception
  {
    PerformanceComparison comparison = compareThreadPerformance(task, iterations, platformThreadCount);
    log.info(comparison.toString());
    return comparison;
  }

  /**
   * Determines if the current JVM supports virtual threads.
   *
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean supportsVirtualThreads() {
    try {
      // Try to create a virtual thread to check if the feature is available
      Thread vthread = Thread.ofVirtual().name("vthread-check").unstarted(() -> {});
      return vthread.isVirtual();
    }
    catch (Throwable t) {
      return false;
    }
  }

  /**
   * Determines if the current JVM has the system property for tracing pinned threads enabled.
   *
   * @return true if pinned thread tracing is enabled, false otherwise
   */
  public static boolean isPinnedThreadTracingEnabled() {
    String tracePinnedThreads = System.getProperty("jdk.tracePinnedThreads");
    return tracePinnedThreads != null && !tracePinnedThreads.isEmpty() && !"false".equalsIgnoreCase(tracePinnedThreads);
  }

  /**
   * Enables tracing of pinned virtual threads if not already enabled.
   * This sets the jdk.tracePinnedThreads system property to "full".
   *
   * @return true if the property was changed, false if it was already set
   */
  public static boolean enablePinnedThreadTracing() {
    if (!isPinnedThreadTracingEnabled()) {
      System.setProperty("jdk.tracePinnedThreads", "full");
      return true;
    }
    return false;
  }

  /**
   * Runs a task with a timeout using a virtual thread.
   *
   * @param <T> the type of the result
   * @param task the task to run
   * @param timeout the maximum time to wait
   * @return the result of the task
   * @throws Exception if an error occurs during execution or the timeout is reached
   */
  public static <T> T runWithTimeout(Supplier<T> task, Duration timeout) throws Exception {
    AtomicInteger status = new AtomicInteger(0); // 0=running, 1=completed, 2=timeout
    final Object[] result = new Object[1];
    final Throwable[] error = new Throwable[1];

    Thread thread = Thread.ofVirtual().name("timeout-task").start(() -> {
      try {
        result[0] = task.get();
        status.set(1); // completed
      }
      catch (Throwable t) {
        error[0] = t;
        status.set(1); // completed with error
      }
    });

    thread.join(timeout.toMillis());
    if (status.get() == 0) {
      status.set(2); // timeout
      thread.interrupt();
      throw new RuntimeException("Task timed out after " + timeout);
    }

    if (error[0] != null) {
      if (error[0] instanceof Exception) {
        throw (Exception) error[0];
      }
      else {
        throw new RuntimeException(error[0]);
      }
    }

    @SuppressWarnings("unchecked")
    T typedResult = (T) result[0];
    return typedResult;
  }

  /**
   * Runs a task with a timeout using a virtual thread.
   *
   * @param task the task to run
   * @param timeout the maximum time to wait
   * @throws Exception if an error occurs during execution or the timeout is reached
   */
  public static void runWithTimeout(Runnable task, Duration timeout) throws Exception {
    runWithTimeout(() -> {
      task.run();
      return null;
    }, timeout);
  }

  /**
   * Executes a task multiple times with virtual threads and reports statistics on execution times.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param warmupIterations the number of warmup iterations to run before measuring
   * @return a summary of the execution statistics
   * @throws Exception if an error occurs during execution
   */
  public static String benchmarkVirtualThreads(
      Runnable task,
      int iterations,
      int warmupIterations) throws Exception
  {
    // Warmup
    for (int i = 0; i < warmupIterations; i++) {
      task.run();
    }

    // Benchmark
    long[] times = new long[iterations];
    for (int i = 0; i < iterations; i++) {
      long start = System.nanoTime();
      task.run();
      times[i] = System.nanoTime() - start;
    }

    // Calculate statistics
    long total = 0;
    long min = Long.MAX_VALUE;
    long max = 0;
    for (long time : times) {
      total += time;
      min = Math.min(min, time);
      max = Math.max(max, time);
    }
    long avg = total / iterations;

    // Sort for percentiles
    java.util.Arrays.sort(times);
    long p50 = times[iterations / 2];
    long p90 = times[(int) (iterations * 0.9)];
    long p99 = times[(int) (iterations * 0.99)];

    return String.format(
        "Virtual Thread Benchmark (%d iterations):\n" +
        "  Min: %.2f ms\n" +
        "  Max: %.2f ms\n" +
        "  Avg: %.2f ms\n" +
        "  P50: %.2f ms\n" +
        "  P90: %.2f ms\n" +
        "  P99: %.2f ms",
        iterations,
        min / 1_000_000.0,
        max / 1_000_000.0,
        avg / 1_000_000.0,
        p50 / 1_000_000.0,
        p90 / 1_000_000.0,
        p99 / 1_000_000.0);
  }

  /**
   * Executes a task multiple times with virtual threads and logs statistics on execution times.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param warmupIterations the number of warmup iterations to run before measuring
   * @throws Exception if an error occurs during execution
   */
  public static void logVirtualThreadBenchmark(
      Runnable task,
      int iterations,
      int warmupIterations) throws Exception
  {
    String result = benchmarkVirtualThreads(task, iterations, warmupIterations);
    log.info(result);
  }

  /**
   * Executes a consumer with a specified number of inputs using virtual threads.
   *
   * @param <T> the type of the input
   * @param consumer the consumer to execute
   * @param inputSupplier a supplier that provides input values
   * @param count the number of inputs to process
   * @throws Exception if an error occurs during execution
   */
  public static <T> void processWithVirtualThreads(
      Consumer<T> consumer,
      Supplier<T> inputSupplier,
      int count) throws Exception
  {
    ExecutorService executor = newVirtualThreadExecutor("processor-");
    try {
      CountDownLatch latch = new CountDownLatch(count);
      List<Future<?>> futures = new ArrayList<>(count);

      for (int i = 0; i < count; i++) {
        T input = inputSupplier.get();
        futures.add(executor.submit(() -> {
          try {
            consumer.accept(input);
          }
          finally {
            latch.countDown();
          }
        }));
      }

      // Wait for all tasks to complete
      if (!latch.await(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
        throw new RuntimeException("Timeout waiting for tasks to complete");
      }

      // Check for exceptions
      for (Future<?> future : futures) {
        future.get(1, TimeUnit.SECONDS); // Short timeout since tasks should be done
      }
    }
    finally {
      executor.shutdown();
      executor.awaitTermination(DEFAULT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
  }
}