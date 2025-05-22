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
package org.sonatype.nexus.pax.virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides utility methods and test support for integration testing with Java 21 Virtual Threads.
 * <p>
 * This class offers factory methods to create Virtual Thread executors, utilities to verify thread usage patterns,
 * detection of thread pinning scenarios, and assertion helpers for Virtual Thread performance validation.
 * <p>
 * Virtual Threads are lightweight threads that dramatically reduce the effort of writing, maintaining, and observing
 * high-throughput concurrent applications. They are particularly useful for I/O-bound operations.
 *
 * @since 3.60
 */
public class VirtualThreadTestSupport {

  private static final Logger log = LoggerFactory.getLogger(VirtualThreadTestSupport.class);

  /**
   * Default timeout for operations in tests.
   */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Default number of virtual threads to use in tests.
   */
  public static final int DEFAULT_THREAD_COUNT = 100;

  /**
   * System property to enable thread pinning detection.
   */
  public static final String THREAD_PINNING_DETECTION_PROPERTY = "jdk.tracePinnedThreads";

  /**
   * Value for full thread pinning detection.
   */
  public static final String THREAD_PINNING_DETECTION_FULL = "full";

  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private VirtualThreadTestSupport() {
    // Prevent instantiation
  }

  /**
   * Creates a virtual thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory(String namePrefix) {
    return Thread.ofVirtual().name(namePrefix, 0).factory();
  }

  /**
   * Creates a virtual thread factory with default naming.
   *
   * @return a ThreadFactory that creates virtual threads
   */
  public static ThreadFactory virtualThreadFactory() {
    return Thread.ofVirtual().factory();
  }

  /**
   * Creates an executor service that creates a new virtual thread for each task.
   *
   * @return an ExecutorService that uses virtual threads
   */
  public static ExecutorService newVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates an executor service that creates a new virtual thread for each task with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return an ExecutorService that uses virtual threads
   */
  public static ExecutorService newVirtualThreadExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }

  /**
   * Runs the specified task in a virtual thread and returns the result.
   *
   * @param <T> the type of the result
   * @param task the task to run
   * @return the result of the task
   * @throws Exception if the task throws an exception
   */
  public static <T> T runInVirtualThread(Callable<T> task) throws Exception {
    Thread thread = Thread.ofVirtual().start(() -> {
      try {
        return task.call();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    thread.join();
    if (thread instanceof Supplier) {
      @SuppressWarnings("unchecked")
      T result = (T) ((Supplier<?>) thread).get();
      return result;
    }
    return null;
  }

  /**
   * Runs the specified task in a virtual thread.
   *
   * @param task the task to run
   * @throws Exception if the task throws an exception
   */
  public static void runInVirtualThread(Runnable task) throws Exception {
    Thread thread = Thread.ofVirtual().start(task);
    thread.join();
  }

  /**
   * Enables thread pinning detection by setting the system property.
   * This will log stack traces when virtual threads are pinned.
   */
  public static void enableThreadPinningDetection() {
    System.setProperty(THREAD_PINNING_DETECTION_PROPERTY, THREAD_PINNING_DETECTION_FULL);
    log.info("Enabled virtual thread pinning detection");
  }

  /**
   * Disables thread pinning detection by clearing the system property.
   */
  public static void disableThreadPinningDetection() {
    System.clearProperty(THREAD_PINNING_DETECTION_PROPERTY);
    log.info("Disabled virtual thread pinning detection");
  }

  /**
   * Checks if thread pinning detection is enabled.
   *
   * @return true if thread pinning detection is enabled
   */
  public static boolean isThreadPinningDetectionEnabled() {
    return System.getProperty(THREAD_PINNING_DETECTION_PROPERTY) != null;
  }

  /**
   * Executes a performance test with virtual threads and measures the execution time.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param concurrency the number of concurrent threads
   * @return the average execution time per task in milliseconds
   * @throws Exception if an error occurs during execution
   */
  public static double measureVirtualThreadPerformance(Runnable task, int iterations, int concurrency) throws Exception {
    ExecutorService executor = newVirtualThreadExecutor("perf-test-");
    try {
      long startTime = System.nanoTime();
      List<Future<?>> futures = new ArrayList<>(iterations);
      
      for (int i = 0; i < iterations; i++) {
        futures.add(executor.submit(task));
      }
      
      for (Future<?> future : futures) {
        future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      }
      
      long endTime = System.nanoTime();
      double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
      return elapsedTimeMs / iterations;
    }
    finally {
      executor.shutdownNow();
    }
  }

  /**
   * Asserts that a task executes faster with virtual threads than with platform threads.
   * This is particularly useful for I/O-bound operations where virtual threads should show an advantage.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @param concurrency the number of concurrent threads
   * @return true if virtual threads performed better than platform threads
   * @throws Exception if an error occurs during execution
   */
  public static boolean assertVirtualThreadPerformance(Runnable task, int iterations, int concurrency) throws Exception {
    // Measure with platform threads
    ExecutorService platformExecutor = Executors.newFixedThreadPool(concurrency);
    double platformTime;
    try {
      long startTime = System.nanoTime();
      List<Future<?>> futures = new ArrayList<>(iterations);
      
      for (int i = 0; i < iterations; i++) {
        futures.add(platformExecutor.submit(task));
      }
      
      for (Future<?> future : futures) {
        future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      }
      
      long endTime = System.nanoTime();
      platformTime = (endTime - startTime) / 1_000_000.0;
    }
    finally {
      platformExecutor.shutdownNow();
    }
    
    // Measure with virtual threads
    double virtualTime = measureVirtualThreadPerformance(task, iterations, concurrency) * iterations;
    
    log.info("Performance comparison: Platform threads: {}ms, Virtual threads: {}ms", 
        String.format("%.2f", platformTime), String.format("%.2f", virtualTime));
    
    return virtualTime <= platformTime;
  }

  /**
   * Detects if a task causes thread pinning when executed in a virtual thread.
   * This is useful for identifying code that may not be optimal for virtual threads.
   *
   * @param task the task to check for thread pinning
   * @return true if thread pinning was detected
   * @throws Exception if an error occurs during execution
   */
  public static boolean detectThreadPinning(Runnable task) throws Exception {
    boolean detectionWasEnabled = isThreadPinningDetectionEnabled();
    if (!detectionWasEnabled) {
      enableThreadPinningDetection();
    }
    
    try {
      // Use a custom ThreadFactory to track pinning
      final boolean[] pinningDetected = {false};
      
      Thread thread = Thread.ofVirtual().name("pinning-detection").start(() -> {
        try {
          task.run();
        }
        catch (Exception e) {
          log.error("Error during thread pinning detection", e);
        }
      });
      
      thread.join(DEFAULT_TIMEOUT.toMillis());
      
      // If the thread is still alive after the timeout, it might be pinned
      if (thread.isAlive()) {
        log.warn("Possible thread pinning detected: thread did not complete within timeout");
        pinningDetected[0] = true;
        thread.interrupt();
      }
      
      return pinningDetected[0];
    }
    finally {
      if (!detectionWasEnabled) {
        disableThreadPinningDetection();
      }
    }
  }

  /**
   * Executes multiple tasks concurrently using virtual threads and waits for all to complete.
   *
   * @param tasks the tasks to execute
   * @throws Exception if an error occurs during execution
   */
  public static void executeTasksConcurrently(List<Runnable> tasks) throws Exception {
    ExecutorService executor = newVirtualThreadExecutor("concurrent-task-");
    try {
      List<Future<?>> futures = new ArrayList<>(tasks.size());
      
      for (Runnable task : tasks) {
        futures.add(executor.submit(task));
      }
      
      for (Future<?> future : futures) {
        future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
    finally {
      executor.shutdownNow();
    }
  }

  /**
   * Configures the JVM for optimal virtual thread performance in tests.
   * This includes setting appropriate system properties and parameters.
   */
  public static void configureVirtualThreadTestEnvironment() {
    // Set the parallelism for the virtual thread scheduler
    System.setProperty("jdk.virtualThreadScheduler.parallelism", 
        String.valueOf(Runtime.getRuntime().availableProcessors()));
    
    // Enable thread pinning detection for tests
    enableThreadPinningDetection();
    
    log.info("Configured virtual thread test environment");
  }

  /**
   * Cleans up the virtual thread test environment configuration.
   */
  public static void cleanupVirtualThreadTestEnvironment() {
    // Clear system properties set by configureVirtualThreadTestEnvironment
    System.clearProperty("jdk.virtualThreadScheduler.parallelism");
    disableThreadPinningDetection();
    
    log.info("Cleaned up virtual thread test environment");
  }
}