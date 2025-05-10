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
package org.sonatype.nexus.repository;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;

/**
 * Support class for testing virtual thread behavior in repository operations.
 * 
 * Provides utilities for creating virtual threads, detecting thread pinning, and comparing
 * performance between platform and virtual threads. This class is designed to help validate
 * that repository operations work correctly with Java 21's virtual threads and to identify
 * potential thread pinning issues.
 * 
 * <p>Key features:</p>
 * <ul>
 *   <li>Thread factory creation using Thread.ofVirtual().factory()</li>
 *   <li>Thread pinning detection with stack trace identification</li>
 *   <li>Performance comparison between platform and virtual threads</li>
 *   <li>High concurrency testing support</li>
 *   <li>I/O-bound operation testing with virtual threads</li>
 * </ul>
 * 
 * <p>Example usage:</p>
 * <pre>
 * // Compare performance between platform and virtual threads
 * PerformanceResult result = VirtualThreadTestSupport.compareThreadPerformance(
 *     () -> repository.doSomeOperation(), 1000);
 * log.info("Performance improvement: {}%", result.getImprovementPercentage());
 * 
 * // Test for thread pinning
 * VirtualThreadTestSupport.assertNoPinning(() -> repository.doSomeOperation());
 * 
 * // Run high concurrency test
 * VirtualThreadTestSupport.executeHighConcurrencyTest(
 *     () -> repository.doSomeOperation(), 10000, 60);
 * </pre>
 * 
 * <p>To run tests that use this class, use the {@code @Tag("virtual-threads")} annotation
 * and ensure the test is run with Java 21 or later.</p>
 * 
 * @since 3.60
 */
@Tag("virtual-threads")
public class VirtualThreadTestSupport
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);
  
  /**
   * Checks if the current JVM supports virtual threads.
   * 
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean isVirtualThreadsSupported() {
    try {
      // Try to create a virtual thread to check if it's supported
      Thread vt = Thread.ofVirtual().start(() -> {});
      vt.join();
      return true;
    }
    catch (Throwable t) {
      log.debug("Virtual threads not supported", t);
      return false;
    }
  }
  /**
   * Creates a ThreadFactory that produces virtual threads.
   * 
   * @return a ThreadFactory that creates virtual threads
   */
  public static ThreadFactory createVirtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }
  
  /**
   * Creates a ThreadFactory that produces virtual threads with the specified name prefix.
   * 
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates virtual threads with the specified name prefix
   */
  public static ThreadFactory createVirtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix).factory();
  }
  
  /**
   * Creates a ThreadFactory that produces platform threads.
   * 
   * @return a ThreadFactory that creates platform threads
   */
  public static ThreadFactory createPlatformThreadFactory() {
    return Thread.ofPlatform().factory();
  }
  
  /**
   * Creates a ThreadFactory that produces platform threads with the specified name prefix.
   * 
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates platform threads with the specified name prefix
   */
  public static ThreadFactory createPlatformThreadFactory(String namePrefix) {
    return Thread.ofPlatform().name(namePrefix).factory();
  }
  
  /**
   * Creates an ExecutorService that creates a new virtual thread for each task.
   * 
   * @return an ExecutorService that uses virtual threads
   */
  public static ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates an ExecutorService that creates a new virtual thread with the specified name prefix for each task.
   * 
   * @param namePrefix the prefix for thread names
   * @return an ExecutorService that uses virtual threads with the specified name prefix
   */
  public static ExecutorService createVirtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(namePrefix).factory());
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
   * Runs a task with both platform and virtual threads and compares their performance.
   * 
   * @param task the task to run
   * @param iterations the number of iterations to run the task
   * @return a PerformanceResult containing the execution times for both thread types
   * @throws Exception if an error occurs during execution
   */
  public static PerformanceResult compareThreadPerformance(Runnable task, int iterations) throws Exception {
    log.info("Comparing performance between platform and virtual threads ({} iterations)", iterations);
    
    // Run with platform threads
    log.info("Running with platform threads...");
    long platformTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())) {
        for (int i = 0; i < iterations; i++) {
          executor.submit(task);
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
      }
      return null;
    });
    log.info("Platform thread execution time: {} ms", platformTime);
    
    // Run with virtual threads
    log.info("Running with virtual threads...");
    long virtualTime = measureExecutionTime(() -> {
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        for (int i = 0; i < iterations; i++) {
          executor.submit(task);
        }
        executor.shutdown();
        executor.awaitTermination(1, TimeUnit.MINUTES);
      }
      return null;
    });
    log.info("Virtual thread execution time: {} ms", virtualTime);
    
    PerformanceResult result = new PerformanceResult(platformTime, virtualTime);
    log.info("Performance comparison result: {}", result);
    return result;
  }
  
  /**
   * Measures the execution time of a callable task.
   * 
   * @param task the task to measure
   * @return the execution time in milliseconds
   * @throws Exception if an error occurs during execution
   */
  public static long measureExecutionTime(Callable<Void> task) throws Exception {
    long startTime = System.currentTimeMillis();
    task.call();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Executes a high concurrency test using virtual threads.
   * 
   * @param task the task to execute concurrently
   * @param concurrency the number of concurrent threads to use
   * @param timeoutSeconds the maximum time to wait for all tasks to complete
   * @throws Exception if an error occurs during execution or if the timeout is exceeded
   */
  public static void executeHighConcurrencyTest(Runnable task, int concurrency, int timeoutSeconds) throws Exception {
    log.info("Executing high concurrency test with {} virtual threads (timeout: {} seconds)", concurrency, timeoutSeconds);
    
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    long startTime = System.currentTimeMillis();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            task.run();
          }
          catch (Exception e) {
            log.error("Error in virtual thread task", e);
            errorCount.incrementAndGet();
            firstException.compareAndSet(null, e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
      if (!completed) {
        throw new Exception("High concurrency test timed out after " + timeoutSeconds + " seconds");
      }
      
      if (errorCount.get() > 0) {
        Exception e = firstException.get();
        throw new Exception("High concurrency test failed with " + errorCount.get() + " errors", e);
      }
    }
    
    long duration = System.currentTimeMillis() - startTime;
    log.info("High concurrency test completed successfully in {} ms", duration);
  }
  
  /**
   * Detects thread pinning by analyzing the stack trace of the current thread.
   * 
   * @return a PinningInfo object containing information about pinning, or null if no pinning is detected
   */
  public static PinningInfo detectThreadPinning() {
    if (!isVirtualThread()) {
      return null; // Only virtual threads can be pinned
    }
    
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    
    // Look for evidence of pinning in the stack trace
    for (int i = 0; i < stackTrace.length; i++) {
      StackTraceElement element = stackTrace[i];
      
      // Check for synchronized blocks/methods
      if ((element.getMethodName().contains("monitor") || 
           element.getMethodName().contains("synchronized")) && 
          element.getClassName().startsWith("java.lang.")) {
        log.debug("Detected synchronized block pinning at: {}", element);
        return new PinningInfo(PinningReason.SYNCHRONIZED, element);
      }
      
      // Check for native methods
      if (element.isNativeMethod()) {
        log.debug("Detected native method pinning at: {}", element);
        return new PinningInfo(PinningReason.NATIVE_METHOD, element);
      }
      
      // Check for VirtualThread pinning indicators
      if (element.getClassName().equals("java.lang.VirtualThread") && 
          element.getMethodName().contains("onPinned")) {
        log.debug("Detected explicit pinning indicator at: {}", element);
        // Look for the actual cause in the next few frames
        for (int j = i + 1; j < Math.min(i + 5, stackTrace.length); j++) {
          StackTraceElement causeElement = stackTrace[j];
          if (!causeElement.getClassName().startsWith("java.lang.") && 
              !causeElement.getClassName().startsWith("jdk.internal.")) {
            return new PinningInfo(PinningReason.SYNCHRONIZED, causeElement);
          }
        }
        return new PinningInfo(PinningReason.SYNCHRONIZED, element);
      }
    }
    
    return null;
  }
  
  /**
   * Enables thread pinning detection by setting the appropriate JVM system property.
   * This should be called before running tests that need to detect pinning.
   * 
   * @param mode the pinning detection mode ("full" or "short")
   */
  public static void enablePinningDetection(String mode) {
    if (!"full".equals(mode) && !"short".equals(mode)) {
      throw new IllegalArgumentException("Mode must be 'full' or 'short'");
    }
    System.setProperty("jdk.tracePinnedThreads", mode);
    log.info("Enabled virtual thread pinning detection with mode: {}", mode);
  }
  
  /**
   * Disables thread pinning detection by clearing the JVM system property.
   */
  public static void disablePinningDetection() {
    System.clearProperty("jdk.tracePinnedThreads");
    log.info("Disabled virtual thread pinning detection");
  }
  
  /**
   * Asserts that a task does not cause thread pinning when executed in a virtual thread.
   * 
   * @param task the task to test
   * @throws Exception if the task causes thread pinning or another error occurs
   */
  public static void assertNoPinning(Runnable task) throws Exception {
    AtomicReference<PinningInfo> pinningInfo = new AtomicReference<>();
    AtomicReference<Exception> taskException = new AtomicReference<>();
    
    log.info("Testing for thread pinning...");
    
    // Enable pinning detection for this test
    String originalPinningMode = System.getProperty("jdk.tracePinnedThreads");
    try {
      enablePinningDetection("full");
      
      Thread thread = Thread.ofVirtual().name("pinning-test").start(() -> {
        try {
          PinningInfo info = detectThreadPinning();
          if (info != null) {
            pinningInfo.set(info);
            return;
          }
          task.run();
          info = detectThreadPinning();
          pinningInfo.set(info);
        }
        catch (Exception e) {
          taskException.set(e);
        }
      });
      
      thread.join();
      
      // Check if the task threw an exception
      if (taskException.get() != null) {
        throw new Exception("Task threw an exception", taskException.get());
      }
      
      // Check if pinning was detected
      if (pinningInfo.get() != null) {
        PinningInfo info = pinningInfo.get();
        log.error("Thread pinning detected: {} at {}", info.reason, info.location);
        Assertions.fail("Thread pinning detected: " + info.reason + " at " + info.location);
      }
      
      log.info("No thread pinning detected");
    }
    finally {
      // Restore original pinning detection setting
      if (originalPinningMode != null) {
        System.setProperty("jdk.tracePinnedThreads", originalPinningMode);
      }
      else {
        disablePinningDetection();
      }
    }
  }
  
  /**
   * Enum representing the reason for thread pinning.
   */
  public enum PinningReason {
    SYNCHRONIZED("Synchronized block or method"),
    NATIVE_METHOD("Native method call"),
    FOREIGN_FUNCTION("Foreign function call");
    
    private final String description;
    
    PinningReason(String description) {
      this.description = description;
    }
    
    @Override
    public String toString() {
      return description;
    }
  }
  
  /**
   * Class representing information about thread pinning.
   */
  public static class PinningInfo {
    public final PinningReason reason;
    public final StackTraceElement location;
    
    public PinningInfo(PinningReason reason, StackTraceElement location) {
      this.reason = reason;
      this.location = location;
    }
  }
  
  /**
   * Executes a task with virtual threads using CompletableFuture for I/O-bound operations.
   * 
   * @param task the task to execute
   * @param concurrency the number of concurrent operations
   * @param timeoutSeconds the maximum time to wait for all tasks to complete
   * @throws Exception if an error occurs during execution or if the timeout is exceeded
   */
  public static void executeIOBoundTest(Runnable task, int concurrency, int timeoutSeconds) throws Exception {
    log.info("Executing I/O-bound test with {} virtual threads (timeout: {} seconds)", concurrency, timeoutSeconds);
    
    CompletableFuture<?>[] futures = new CompletableFuture[concurrency];
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> firstException = new AtomicReference<>();
    
    long startTime = System.currentTimeMillis();
    
    for (int i = 0; i < concurrency; i++) {
      futures[i] = CompletableFuture.runAsync(
          task, 
          Executors.newVirtualThreadPerTaskExecutor()
      ).exceptionally(ex -> {
        log.error("Error in I/O-bound task", ex);
        errorCount.incrementAndGet();
        if (ex instanceof Exception) {
          firstException.compareAndSet(null, (Exception) ex);
        }
        return null;
      });
    }
    
    // Wait for all futures to complete
    CompletableFuture.allOf(futures).orTimeout(timeoutSeconds, TimeUnit.SECONDS).join();
    
    if (errorCount.get() > 0) {
      Exception e = firstException.get();
      throw new Exception("I/O-bound test failed with " + errorCount.get() + " errors", e);
    }
    
    long duration = System.currentTimeMillis() - startTime;
    log.info("I/O-bound test completed successfully in {} ms", duration);
  }
  
  /**
   * Class representing the performance comparison results between platform and virtual threads.
   */
  public static class PerformanceResult {
    public final long platformThreadTimeMs;
    public final long virtualThreadTimeMs;
    public final double improvementFactor;
    
    public PerformanceResult(long platformThreadTimeMs, long virtualThreadTimeMs) {
      this.platformThreadTimeMs = platformThreadTimeMs;
      this.virtualThreadTimeMs = virtualThreadTimeMs;
      this.improvementFactor = platformThreadTimeMs > 0 ? 
          (double) platformThreadTimeMs / virtualThreadTimeMs : 0;
    }
    
    /**
     * Checks if virtual threads performed better than platform threads.
     * 
     * @return true if virtual threads were faster, false otherwise
     */
    public boolean virtualThreadsFaster() {
      return virtualThreadTimeMs < platformThreadTimeMs;
    }
    
    /**
     * Gets the improvement percentage of virtual threads over platform threads.
     * 
     * @return the improvement percentage (positive if virtual threads were faster)
     */
    public double getImprovementPercentage() {
      if (platformThreadTimeMs == 0) {
        return 0;
      }
      return ((platformThreadTimeMs - virtualThreadTimeMs) / (double) platformThreadTimeMs) * 100.0;
    }
    
    @Override
    public String toString() {
      return String.format("Platform threads: %d ms, Virtual threads: %d ms, Improvement: %.2f%%", 
          platformThreadTimeMs, virtualThreadTimeMs, getImprovementPercentage());
    }
  }
}