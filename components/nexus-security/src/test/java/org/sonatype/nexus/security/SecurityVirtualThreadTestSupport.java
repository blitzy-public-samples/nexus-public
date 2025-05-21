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
package org.sonatype.nexus.security;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.Assertions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base test support class for security operations using Java 21 Virtual Threads.
 * Provides utilities for creating and managing Virtual Threads, detecting thread pinning,
 * and benchmarking Virtual Thread performance against platform threads.
 *
 * @since 3.60
 */
public abstract class SecurityVirtualThreadTestSupport
    extends AbstractSecurityTest
{
  private static final Logger log = LoggerFactory.getLogger(SecurityVirtualThreadTestSupport.class);

  private static final int DEFAULT_THREAD_COUNT = 100;
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Creates a Virtual Thread factory with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return a ThreadFactory that creates virtual threads
   */
  protected ThreadFactory createVirtualThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger(0);
    return Thread.ofVirtual()
        .name(namePrefix, counter::getAndIncrement)
        .factory();
  }

  /**
   * Creates an ExecutorService that creates a new virtual thread for each task.
   *
   * @return an ExecutorService using virtual threads
   */
  protected ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Creates an ExecutorService that creates a new virtual thread for each task with the specified name prefix.
   *
   * @param namePrefix the prefix for thread names
   * @return an ExecutorService using virtual threads
   */
  protected ExecutorService createVirtualThreadExecutor(final String namePrefix) {
    return Executors.newThreadPerTaskExecutor(createVirtualThreadFactory(namePrefix));
  }

  /**
   * Creates an ExecutorService that creates platform threads.
   *
   * @param namePrefix the prefix for thread names
   * @param threadCount the number of threads in the pool
   * @return an ExecutorService using platform threads
   */
  protected ExecutorService createPlatformThreadExecutor(final String namePrefix, final int threadCount) {
    return Executors.newFixedThreadPool(threadCount, 
        Thread.ofPlatform().name(namePrefix, 0).factory());
  }

  /**
   * Executes a task in a virtual thread and returns the result.
   *
   * @param <T> the type of the result
   * @param task the task to execute
   * @return the result of the task
   * @throws ExecutionException if the task throws an exception
   * @throws InterruptedException if the current thread is interrupted
   */
  protected <T> T runInVirtualThread(final Callable<T> task) throws ExecutionException, InterruptedException {
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      Future<T> future = executor.submit(task);
      return future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Executes a task in a virtual thread.
   *
   * @param task the task to execute
   * @throws ExecutionException if the task throws an exception
   * @throws InterruptedException if the current thread is interrupted
   */
  protected void runInVirtualThread(final Runnable task) throws ExecutionException, InterruptedException {
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      Future<?> future = executor.submit(task);
      future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  /**
   * Detects if a task causes thread pinning when executed in a virtual thread.
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically due to synchronized blocks or native methods.
   *
   * @param task the task to check for pinning
   * @return true if the task causes thread pinning, false otherwise
   */
  protected boolean detectThreadPinning(final Runnable task) {
    try (ExecutorService executor = createVirtualThreadExecutor("pinning-detector-")) {
      // First run to warm up
      executor.submit(task).get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      
      // Run multiple tasks concurrently to detect pinning
      // If pinning occurs, we'll see carrier thread exhaustion with enough concurrent tasks
      int availableProcessors = Runtime.getRuntime().availableProcessors();
      int taskCount = availableProcessors * 4; // More tasks than available processors
      
      Future<?>[] futures = new Future<?>[taskCount];
      for (int i = 0; i < taskCount; i++) {
        futures[i] = executor.submit(task);
      }
      
      // Wait for all tasks to complete
      try {
        for (Future<?> future : futures) {
          future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        return false; // No pinning detected
      }
      catch (Exception e) {
        log.warn("Possible thread pinning detected: {}", e.getMessage());
        return true; // Pinning likely occurred
      }
    }
    catch (Exception e) {
      log.error("Error during thread pinning detection", e);
      return true; // Assume pinning for safety
    }
  }

  /**
   * Compares the performance of executing a task using virtual threads versus platform threads.
   *
   * @param <T> the type of the result
   * @param task the task to benchmark
   * @param iterations the number of iterations to run
   * @return a BenchmarkResult containing the performance metrics
   */
  protected <T> BenchmarkResult<T> benchmarkVirtualVsPlatformThreads(
      final Supplier<T> task, final int iterations) {
    
    BenchmarkResult<T> result = new BenchmarkResult<>();
    
    // Benchmark platform threads
    try (ExecutorService platformExecutor = 
        createPlatformThreadExecutor("platform-benchmark-", DEFAULT_THREAD_COUNT)) {
      
      long platformStart = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        Future<T> future = platformExecutor.submit(task::get);
        result.platformResults.add(future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      }
      result.platformThreadTime = Duration.ofNanos(System.nanoTime() - platformStart);
    }
    catch (Exception e) {
      log.error("Error during platform thread benchmark", e);
    }
    
    // Benchmark virtual threads
    try (ExecutorService virtualExecutor = createVirtualThreadExecutor("virtual-benchmark-")) {
      long virtualStart = System.nanoTime();
      for (int i = 0; i < iterations; i++) {
        Future<T> future = virtualExecutor.submit(task::get);
        result.virtualResults.add(future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
      }
      result.virtualThreadTime = Duration.ofNanos(System.nanoTime() - virtualStart);
    }
    catch (Exception e) {
      log.error("Error during virtual thread benchmark", e);
    }
    
    return result;
  }

  /**
   * Tests that security context is properly propagated to a virtual thread.
   *
   * @param subject the subject to associate with the current thread
   * @param task the task to execute in a virtual thread
   * @throws ExecutionException if the task throws an exception
   * @throws InterruptedException if the current thread is interrupted
   */
  protected void testSecurityContextPropagation(final Subject subject, final Runnable task) 
      throws ExecutionException, InterruptedException {
    
    // Associate subject with the current thread
    SecurityUtils.setSubject(subject);
    
    try (ExecutorService executor = createVirtualThreadExecutor("security-context-")) {
      Future<?> future = executor.submit(() -> {
        // Verify the subject is available in the virtual thread
        Subject currentSubject = SecurityUtils.getSubject();
        Assertions.assertNotNull(currentSubject, "Subject should be propagated to virtual thread");
        Assertions.assertEquals(subject.getPrincipal(), currentSubject.getPrincipal(), 
            "Subject principal should match in virtual thread");
        
        // Execute the task
        task.run();
      });
      
      future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
    finally {
      // Clean up
      SecurityUtils.setSubject(null);
    }
  }

  /**
   * Tests authentication in a virtual thread.
   *
   * @param username the username to authenticate
   * @param password the password to authenticate with
   * @return true if authentication succeeds, false otherwise
   */
  protected boolean testAuthenticationInVirtualThread(final String username, final String password) {
    try {
      return runInVirtualThread(() -> {
        try {
          getSecuritySystem().authenticate(new UsernamePasswordToken(username, password));
          return true;
        }
        catch (AuthenticationException e) {
          log.debug("Authentication failed in virtual thread", e);
          return false;
        }
      });
    }
    catch (Exception e) {
      log.error("Error during virtual thread authentication test", e);
      return false;
    }
  }

  /**
   * Tests authorization in a virtual thread.
   *
   * @param subject the subject to check permissions for
   * @param permission the permission to check
   * @return true if the subject has the permission, false otherwise
   */
  protected boolean testAuthorizationInVirtualThread(final Subject subject, final String permission) {
    try {
      return runInVirtualThread(() -> {
        SecurityUtils.setSubject(subject);
        try {
          return getSecuritySystem().hasPermission(subject.getPrincipal(), permission);
        }
        finally {
          SecurityUtils.setSubject(null);
        }
      });
    }
    catch (Exception e) {
      log.error("Error during virtual thread authorization test", e);
      return false;
    }
  }

  /**
   * Class to hold benchmark results comparing virtual and platform threads.
   *
   * @param <T> the type of the result
   */
  public static class BenchmarkResult<T> {
    private Duration platformThreadTime = Duration.ZERO;
    private Duration virtualThreadTime = Duration.ZERO;
    private final java.util.List<T> platformResults = new java.util.ArrayList<>();
    private final java.util.List<T> virtualResults = new java.util.ArrayList<>();

    /**
     * Gets the time taken by platform threads.
     *
     * @return the platform thread execution time
     */
    public Duration getPlatformThreadTime() {
      return platformThreadTime;
    }

    /**
     * Gets the time taken by virtual threads.
     *
     * @return the virtual thread execution time
     */
    public Duration getVirtualThreadTime() {
      return virtualThreadTime;
    }

    /**
     * Gets the results from platform thread execution.
     *
     * @return the list of results from platform threads
     */
    public java.util.List<T> getPlatformResults() {
      return platformResults;
    }

    /**
     * Gets the results from virtual thread execution.
     *
     * @return the list of results from virtual threads
     */
    public java.util.List<T> getVirtualResults() {
      return virtualResults;
    }

    /**
     * Calculates the speedup factor of virtual threads compared to platform threads.
     *
     * @return the speedup factor (values greater than 1 indicate virtual threads are faster)
     */
    public double getSpeedupFactor() {
      if (platformThreadTime.isZero() || virtualThreadTime.isZero()) {
        return 0.0;
      }
      return (double) platformThreadTime.toNanos() / virtualThreadTime.toNanos();
    }

    /**
     * Returns a string representation of the benchmark results.
     *
     * @return a string containing the benchmark metrics
     */
    @Override
    public String toString() {
      return String.format(
          "BenchmarkResult{platformThreadTime=%s, virtualThreadTime=%s, speedupFactor=%.2f}",
          platformThreadTime, virtualThreadTime, getSpeedupFactor());
    }
  }
}