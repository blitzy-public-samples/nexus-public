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
package org.junit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JUnit Jupiter extension for testing with Java 21's Virtual Threads.
 * 
 * This extension provides support for running tests with virtual threads instead of platform threads,
 * enabling validation of code that utilizes Java 21's lightweight threading model. It includes utilities
 * for creating virtual thread executors, detecting thread pinning, and measuring virtual thread performance.
 * 
 * Usage examples:
 * 
 * 1. Apply to a test class to run all test methods with virtual threads:
 * ```
 * @ExtendWith(VirtualThreadExtension.class)
 * class MyVirtualThreadTest {
 *   @Test
 *   void testWithVirtualThreads() {
 *     // This test runs on a virtual thread
 *     assertTrue(Thread.currentThread().isVirtual());
 *   }
 * }
 * ```
 * 
 * 2. Apply to specific test methods:
 * ```
 * class MixedThreadTest {
 *   @Test
 *   void testWithPlatformThread() {
 *     // This runs on a platform thread
 *     assertFalse(Thread.currentThread().isVirtual());
 *   }
 *   
 *   @Test
 *   @ExtendWith(VirtualThreadExtension.class)
 *   void testWithVirtualThread() {
 *     // This runs on a virtual thread
 *     assertTrue(Thread.currentThread().isVirtual());
 *   }
 * }
 * ```
 * 
 * 3. Conditionally enable based on system properties:
 * ```
 * // Only runs when -Dtest.virtual.threads=true is set
 * @ExtendWith(VirtualThreadExtension.class)
 * @EnabledIfVirtualThreads
 * class ConditionalVirtualThreadTest {
 *   // Test methods
 * }
 * ```
 * 
 * @since 3.60
 */
public class VirtualThreadExtension implements ExecutionCondition, BeforeAllCallback, TestExecutionExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(VirtualThreadExtension.class);
  
  private static final String VIRTUAL_THREADS_PROPERTY = "test.virtual.threads";
  private static final String THREAD_PINNING_PROPERTY = "jdk.tracePinnedThreads";
  private static final String EXTENSION_NAMESPACE = "org.sonatype.nexus.virtualthread";
  
  private static final AtomicInteger threadCounter = new AtomicInteger(0);
  
  /**
   * Metrics for virtual thread execution.
   */
  public static class VirtualThreadMetrics {
    private final AtomicLong totalVirtualThreadsCreated = new AtomicLong(0);
    private final AtomicLong pinnedThreadCount = new AtomicLong(0);
    private final AtomicLong maxConcurrentThreads = new AtomicLong(0);
    private final AtomicLong activeThreadCount = new AtomicLong(0);
    private final AtomicLong totalExecutionTimeNanos = new AtomicLong(0);
    
    /**
     * Gets the total number of virtual threads created.
     *
     * @return the total virtual thread count
     */
    public long getTotalVirtualThreadsCreated() {
      return totalVirtualThreadsCreated.get();
    }
    
    /**
     * Gets the number of thread pinning events detected.
     *
     * @return the pinned thread count
     */
    public long getPinnedThreadCount() {
      return pinnedThreadCount.get();
    }
    
    /**
     * Gets the maximum number of concurrent virtual threads observed.
     *
     * @return the maximum concurrent thread count
     */
    public long getMaxConcurrentThreads() {
      return maxConcurrentThreads.get();
    }
    
    /**
     * Gets the total execution time of all virtual threads in nanoseconds.
     *
     * @return the total execution time in nanoseconds
     */
    public long getTotalExecutionTimeNanos() {
      return totalExecutionTimeNanos.get();
    }
    
    /**
     * Records a new virtual thread creation.
     */
    void recordThreadCreation() {
      totalVirtualThreadsCreated.incrementAndGet();
      long active = activeThreadCount.incrementAndGet();
      updateMaxConcurrent(active);
    }
    
    /**
     * Records a virtual thread completion.
     */
    void recordThreadCompletion() {
      activeThreadCount.decrementAndGet();
    }
    
    /**
     * Records a thread pinning event.
     */
    void recordThreadPinning() {
      pinnedThreadCount.incrementAndGet();
    }
    
    /**
     * Records execution time for a virtual thread.
     *
     * @param nanos the execution time in nanoseconds
     */
    void recordExecutionTime(long nanos) {
      totalExecutionTimeNanos.addAndGet(nanos);
    }
    
    private void updateMaxConcurrent(long active) {
      long current;
      do {
        current = maxConcurrentThreads.get();
        if (active <= current) {
          break;
        }
      } while (!maxConcurrentThreads.compareAndSet(current, active));
    }
    
    /**
     * Resets all metrics to zero.
     */
    public void reset() {
      totalVirtualThreadsCreated.set(0);
      pinnedThreadCount.set(0);
      maxConcurrentThreads.set(0);
      activeThreadCount.set(0);
      totalExecutionTimeNanos.set(0);
    }
    
    @Override
    public String toString() {
      return "VirtualThreadMetrics{" +
          "totalVirtualThreadsCreated=" + totalVirtualThreadsCreated.get() +
          ", pinnedThreadCount=" + pinnedThreadCount.get() +
          ", maxConcurrentThreads=" + maxConcurrentThreads.get() +
          ", activeThreadCount=" + activeThreadCount.get() +
          ", totalExecutionTimeNanos=" + totalExecutionTimeNanos.get() +
          "}";
    }
  }
  
  /**
   * Annotation to conditionally enable tests only when virtual threads are supported and enabled.
   */
  @Target({ElementType.TYPE, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface EnabledIfVirtualThreads {
  }
  
  /**
   * Annotation to mark tests that should measure virtual thread performance metrics.
   */
  @Target({ElementType.TYPE, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface MeasureVirtualThreadPerformance {
  }
  
  /**
   * Annotation to mark tests that should detect thread pinning issues.
   */
  @Target({ElementType.TYPE, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  public @interface DetectThreadPinning {
  }
  
  /**
   * Checks if the current JVM supports virtual threads.
   *
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean isVirtualThreadSupported() {
    try {
      Thread virtualThread = Thread.ofVirtual().start(() -> {});
      virtualThread.join();
      return true;
    }
    catch (Exception e) {
      log.debug("Virtual threads are not supported in this JVM", e);
      return false;
    }
  }
  
  /**
   * Checks if virtual threads are enabled for testing via system property.
   *
   * @return true if virtual threads are enabled, false otherwise
   */
  public static boolean isVirtualThreadEnabled() {
    return Boolean.getBoolean(VIRTUAL_THREADS_PROPERTY);
  }
  
  /**
   * Creates a thread factory that produces virtual threads with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return a thread factory that creates virtual threads
   */
  public static ThreadFactory createVirtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual()
        .name(namePrefix, threadCounter.incrementAndGet())
        .factory();
  }
  
  /**
   * Creates an executor service that uses virtual threads.
   *
   * @return an executor service that creates a new virtual thread for each task
   */
  public static ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates an executor service that uses virtual threads with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return an executor service that creates a new virtual thread for each task
   */
  public static ExecutorService createVirtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(createVirtualThreadFactory(namePrefix));
  }
  
  /**
   * Creates an executor service that uses virtual threads and collects metrics.
   *
   * @param namePrefix the prefix for thread names
   * @param metrics the metrics collector
   * @return an executor service that creates a new virtual thread for each task
   */
  public static ExecutorService createVirtualThreadExecutor(String namePrefix, VirtualThreadMetrics metrics) {
    ThreadFactory baseFactory = createVirtualThreadFactory(namePrefix);
    
    ThreadFactory instrumentedFactory = r -> {
      metrics.recordThreadCreation();
      long startTime = System.nanoTime();
      
      return baseFactory.newThread(() -> {
        try {
          r.run();
        }
        finally {
          metrics.recordExecutionTime(System.nanoTime() - startTime);
          metrics.recordThreadCompletion();
        }
      });
    };
    
    return Executors.newThreadPerTaskExecutor(instrumentedFactory);
  }
  
  /**
   * Executes a task on a virtual thread and returns the result.
   *
   * @param <T> the type of the result
   * @param supplier the supplier that produces the result
   * @return the result of the task
   * @throws Exception if the task execution fails
   */
  public static <T> T supplyFromVirtualThread(Supplier<T> supplier) throws Exception {
    Thread thread = Thread.ofVirtual().start(() -> {});
    thread.join();
    
    return supplier.get();
  }
  
  /**
   * Compares the performance of virtual threads vs platform threads for a given task.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param concurrency the number of concurrent threads
   * @return a comparison result with execution times
   * @throws Exception if the task execution fails
   */
  public static PerformanceComparison compareThreadPerformance(
      Runnable task, int iterations, int concurrency) throws Exception {
    
    // Measure platform threads
    long platformStart = System.nanoTime();
    ExecutorService platformExecutor = Executors.newFixedThreadPool(concurrency);
    try {
      for (int i = 0; i < iterations; i++) {
        platformExecutor.submit(task);
      }
    }
    finally {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES);
    }
    long platformDuration = System.nanoTime() - platformStart;
    
    // Measure virtual threads
    long virtualStart = System.nanoTime();
    ExecutorService virtualExecutor = createVirtualThreadExecutor();
    try {
      for (int i = 0; i < iterations; i++) {
        virtualExecutor.submit(task);
      }
    }
    finally {
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.MINUTES);
    }
    long virtualDuration = System.nanoTime() - virtualStart;
    
    return new PerformanceComparison(platformDuration, virtualDuration);
  }
  
  /**
   * Result of comparing platform threads vs virtual threads performance.
   */
  public static class PerformanceComparison {
    private final long platformThreadDurationNanos;
    private final long virtualThreadDurationNanos;
    
    PerformanceComparison(long platformThreadDurationNanos, long virtualThreadDurationNanos) {
      this.platformThreadDurationNanos = platformThreadDurationNanos;
      this.virtualThreadDurationNanos = virtualThreadDurationNanos;
    }
    
    /**
     * Gets the duration of the platform thread execution in nanoseconds.
     *
     * @return the platform thread duration
     */
    public long getPlatformThreadDurationNanos() {
      return platformThreadDurationNanos;
    }
    
    /**
     * Gets the duration of the virtual thread execution in nanoseconds.
     *
     * @return the virtual thread duration
     */
    public long getVirtualThreadDurationNanos() {
      return virtualThreadDurationNanos;
    }
    
    /**
     * Gets the platform thread duration as a Duration object.
     *
     * @return the platform thread duration
     */
    public Duration getPlatformThreadDuration() {
      return Duration.ofNanos(platformThreadDurationNanos);
    }
    
    /**
     * Gets the virtual thread duration as a Duration object.
     *
     * @return the virtual thread duration
     */
    public Duration getVirtualThreadDuration() {
      return Duration.ofNanos(virtualThreadDurationNanos);
    }
    
    /**
     * Calculates the speedup factor of virtual threads compared to platform threads.
     *
     * @return the speedup factor (values > 1 indicate virtual threads are faster)
     */
    public double getSpeedupFactor() {
      return (double) platformThreadDurationNanos / virtualThreadDurationNanos;
    }
    
    /**
     * Checks if virtual threads were faster than platform threads.
     *
     * @return true if virtual threads were faster, false otherwise
     */
    public boolean isVirtualThreadsFaster() {
      return virtualThreadDurationNanos < platformThreadDurationNanos;
    }
    
    @Override
    public String toString() {
      return String.format(
          "Performance Comparison: Platform=%s, Virtual=%s, Speedup=%.2fx%s",
          getPlatformThreadDuration(),
          getVirtualThreadDuration(),
          getSpeedupFactor(),
          isVirtualThreadsFaster() ? " (Virtual threads faster)" : " (Platform threads faster)");
    }
  }
  
  private final VirtualThreadMetrics metrics = new VirtualThreadMetrics();
  
  /**
   * Gets the metrics collector for this extension instance.
   *
   * @return the virtual thread metrics
   */
  public VirtualThreadMetrics getMetrics() {
    return metrics;
  }
  
  @Override
  public void beforeAll(ExtensionContext context) {
    if (shouldMeasurePerformance(context)) {
      metrics.reset();
      log.info("Virtual thread performance measurement enabled for {}", context.getDisplayName());
    }
    
    if (shouldDetectThreadPinning(context)) {
      enableThreadPinningDetection();
      log.info("Thread pinning detection enabled for {}", context.getDisplayName());
    }
    
    // Store metrics in extension context for retrieval in tests
    getStore(context).put(VirtualThreadMetrics.class, metrics);
  }
  
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    // Check if the test is conditionally enabled based on virtual thread support
    if (isAnnotated(context, EnabledIfVirtualThreads.class)) {
      if (!isVirtualThreadSupported()) {
        return ConditionEvaluationResult.disabled(
            "Test disabled because virtual threads are not supported in this JVM");
      }
      
      if (!isVirtualThreadEnabled()) {
        return ConditionEvaluationResult.disabled(
            "Test disabled because virtual threads are not enabled (set -D" + 
                VIRTUAL_THREADS_PROPERTY + "=true to enable)");
      }
    }
    
    return ConditionEvaluationResult.enabled("Virtual thread conditions satisfied");
  }
  
  @Override
  public void handleTestExecutionException(ExtensionContext context, Throwable throwable) throws Throwable {
    if (shouldDetectThreadPinning(context) && metrics.getPinnedThreadCount() > 0) {
      log.warn("Thread pinning detected during test execution: {} pinned threads", 
          metrics.getPinnedThreadCount());
    }
    
    if (shouldMeasurePerformance(context)) {
      log.info("Virtual thread metrics for {}: {}", context.getDisplayName(), metrics);
    }
    
    throw throwable;
  }
  
  /**
   * Enables thread pinning detection by setting the jdk.tracePinnedThreads system property.
   */
  private void enableThreadPinningDetection() {
    String current = System.getProperty(THREAD_PINNING_PROPERTY);
    if (current == null || current.isEmpty()) {
      System.setProperty(THREAD_PINNING_PROPERTY, "full");
      log.debug("Enabled thread pinning detection with -D{}=full", THREAD_PINNING_PROPERTY);
    }
  }
  
  /**
   * Checks if the test should measure virtual thread performance.
   *
   * @param context the extension context
   * @return true if performance measurement is enabled, false otherwise
   */
  private boolean shouldMeasurePerformance(ExtensionContext context) {
    return isAnnotated(context, MeasureVirtualThreadPerformance.class);
  }
  
  /**
   * Checks if the test should detect thread pinning issues.
   *
   * @param context the extension context
   * @return true if thread pinning detection is enabled, false otherwise
   */
  private boolean shouldDetectThreadPinning(ExtensionContext context) {
    return isAnnotated(context, DetectThreadPinning.class);
  }
  
  /**
   * Checks if the test class or method is annotated with the specified annotation.
   *
   * @param context the extension context
   * @param annotationType the annotation type to check for
   * @return true if the annotation is present, false otherwise
   */
  private boolean isAnnotated(ExtensionContext context, Class<? extends java.lang.annotation.Annotation> annotationType) {
    // Check method first, then class
    Optional<Method> testMethod = context.getTestMethod();
    if (testMethod.isPresent() && testMethod.get().isAnnotationPresent(annotationType)) {
      return true;
    }
    
    Optional<Class<?>> testClass = context.getTestClass();
    return testClass.isPresent() && testClass.get().isAnnotationPresent(annotationType);
  }
  
  /**
   * Gets the extension store for this extension.
   *
   * @param context the extension context
   * @return the extension store
   */
  private ExtensionContext.Store getStore(ExtensionContext context) {
    return context.getStore(ExtensionContext.Namespace.create(EXTENSION_NAMESPACE, getClass()));
  }
  
  /**
   * Retrieves the metrics collector from the extension context.
   *
   * @param context the extension context
   * @return the virtual thread metrics, or null if not available
   */
  public static VirtualThreadMetrics getMetricsFromContext(ExtensionContext context) {
    return context.getStore(ExtensionContext.Namespace.create(EXTENSION_NAMESPACE, VirtualThreadExtension.class))
        .get(VirtualThreadMetrics.class, VirtualThreadMetrics.class);
  }
}