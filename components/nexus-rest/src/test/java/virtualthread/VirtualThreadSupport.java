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
package virtualthread;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Utility class that provides standardized support for virtual thread testing in the REST component.
 * <p>
 * This class facilitates creating and managing virtual threads in tests, detecting thread pinning scenarios,
 * validating thread context propagation across virtual threads, and supporting structured concurrency testing.
 *
 * @since 3.60
 */
public class VirtualThreadSupport
{
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Checks if the current JVM supports virtual threads.
   *
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean isVirtualThreadSupported() {
    try {
      // Try to create a virtual thread to check if the feature is supported
      Thread virtualThread = Thread.ofVirtual().start(() -> {});
      virtualThread.join();
      return true;
    }
    catch (Exception e) {
      return false;
    }
  }

  /**
   * Runs the given task in a virtual thread and returns the result.
   *
   * @param <T> the type of the result
   * @param task the task to run
   * @return the result of the task
   * @throws RuntimeException if the task throws an exception
   */
  public static <T> T runInVirtualThread(Callable<T> task) {
    return runInVirtualThread(task, DEFAULT_TIMEOUT);
  }

  /**
   * Runs the given task in a virtual thread and returns the result, with a specified timeout.
   *
   * @param <T> the type of the result
   * @param task the task to run
   * @param timeout the maximum time to wait for the task to complete
   * @return the result of the task
   * @throws RuntimeException if the task throws an exception or times out
   */
  public static <T> T runInVirtualThread(Callable<T> task, Duration timeout) {
    try {
      Future<T> future = Thread.ofVirtual().name("virtual-test-thread").start(task);
      return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Virtual thread execution was interrupted", e);
    }
    catch (ExecutionException e) {
      throw new RuntimeException("Virtual thread execution failed", e.getCause());
    }
    catch (TimeoutException e) {
      throw new RuntimeException("Virtual thread execution timed out after " + timeout, e);
    }
  }

  /**
   * Executes the given task in a virtual thread and returns a CompletableFuture.
   *
   * @param <T> the type of the result
   * @param supplier the supplier to run
   * @return a CompletableFuture that will complete with the result of the task
   */
  public static <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      return CompletableFuture.supplyAsync(supplier, executor);
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
   * Detects if the given thread is a virtual thread.
   *
   * @param thread the thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread(Thread thread) {
    return thread.isVirtual();
  }

  /**
   * Detects thread pinning by executing a task that would normally cause pinning and measuring execution time.
   * Significantly longer execution time indicates potential thread pinning.
   *
   * @param task the task to test for pinning
   * @return true if thread pinning is detected, false otherwise
   */
  public static boolean detectThreadPinning(Runnable task) {
    // First run without synchronization to establish baseline
    long baselineStart = System.nanoTime();
    runInVirtualThread(() -> {
      task.run();
      return null;
    });
    long baselineDuration = System.nanoTime() - baselineStart;

    // Run with synchronization that would cause pinning
    long pinnedStart = System.nanoTime();
    Object lock = new Object();
    runInVirtualThread(() -> {
      synchronized (lock) {
        task.run();
      }
      return null;
    });
    long pinnedDuration = System.nanoTime() - pinnedStart;

    // If pinned execution takes significantly longer (3x), consider it pinned
    return pinnedDuration > baselineDuration * 3;
  }

  /**
   * Validates that thread context is properly propagated to virtual threads.
   *
   * @param <T> the type of the context value
   * @param contextSupplier a supplier that retrieves a context value from the current thread
   * @param expectedValue the expected value of the context in the virtual thread
   * @return true if the context is properly propagated, false otherwise
   */
  public static <T> boolean validateThreadContextPropagation(
      Supplier<T> contextSupplier,
      T expectedValue) {
    T[] valueInVirtualThread = (T[]) new Object[1];
    
    runInVirtualThread(() -> {
      valueInVirtualThread[0] = contextSupplier.get();
      return null;
    });
    
    return expectedValue == null ?
        valueInVirtualThread[0] == null :
        expectedValue.equals(valueInVirtualThread[0]);
  }

  /**
   * Creates a virtual thread with the specified name.
   *
   * @param name the name of the thread
   * @param runnable the task to run in the thread
   * @return the created thread (not started)
   */
  public static Thread createVirtualThread(String name, Runnable runnable) {
    return Thread.ofVirtual().name(name).unstarted(runnable);
  }

  /**
   * Creates and starts a virtual thread with the specified name.
   *
   * @param name the name of the thread
   * @param runnable the task to run in the thread
   * @return the started thread
   */
  public static Thread startVirtualThread(String name, Runnable runnable) {
    return Thread.ofVirtual().name(name).start(runnable);
  }

  /**
   * Creates a virtual thread executor for running multiple tasks concurrently.
   *
   * @return a virtual thread per task executor
   */
  public static AutoCloseable createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Utility method to test JAX-RS resources with virtual threads.
   * This simulates how JAX-RS resources would behave when executed in a virtual thread environment.
   *
   * @param <T> the type of the result
   * @param resourceOperation the operation to execute on the JAX-RS resource
   * @return the result of the resource operation
   */
  public static <T> T testJaxRsResourceWithVirtualThread(Callable<T> resourceOperation) {
    return runInVirtualThread(resourceOperation);
  }
}