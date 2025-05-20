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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that provides standardized support for virtual thread testing in the REST component.
 * <p>
 * This class facilitates creating and managing virtual threads in tests, detecting thread pinning scenarios,
 * validating thread context propagation across virtual threads, and supporting structured concurrency testing.
 * <p>
 * Virtual threads are lightweight threads introduced in Java 21 that allow for high concurrency with minimal
 * resource usage, particularly beneficial for I/O-bound operations like REST API calls.
 *
 * @since 3.60
 */
public class VirtualThreadSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadSupport.class);

  private static final boolean VIRTUAL_THREADS_SUPPORTED = isVirtualThreadsSupported();

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

  /**
   * Checks if virtual threads are supported in the current JVM.
   *
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean isVirtualThreadsSupported() {
    try {
      // Try to access the ofVirtual method to check if virtual threads are supported
      Thread.class.getMethod("ofVirtual");
      return true;
    }
    catch (NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * Creates a virtual thread factory.
   *
   * @return a thread factory that creates virtual threads, or platform threads if virtual threads are not supported
   */
  public static ThreadFactory virtualThreadFactory() {
    if (VIRTUAL_THREADS_SUPPORTED) {
      return Thread.ofVirtual().factory();
    }
    else {
      log.warn("Virtual threads not supported in this JVM. Using platform threads instead.");
      return Thread.ofPlatform().factory();
    }
  }

  /**
   * Creates an executor service that uses virtual threads.
   *
   * @return an executor service that creates a new virtual thread for each task
   */
  public static ExecutorService newVirtualThreadExecutor() {
    if (VIRTUAL_THREADS_SUPPORTED) {
      return Executors.newVirtualThreadPerTaskExecutor();
    }
    else {
      log.warn("Virtual threads not supported in this JVM. Using cached thread pool instead.");
      return Executors.newCachedThreadPool();
    }
  }

  /**
   * Runs a task in a virtual thread and returns the result.
   *
   * @param <T> the type of the result
   * @param task the task to run
   * @return the result of the task
   * @throws RuntimeException if the task throws an exception
   */
  public static <T> T runInVirtualThread(Callable<T> task) {
    try (ExecutorService executor = newVirtualThreadExecutor()) {
      Future<T> future = executor.submit(task);
      return future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing task in virtual thread", e);
    }
  }

  /**
   * Runs a task in a virtual thread.
   *
   * @param task the task to run
   */
  public static void runInVirtualThread(Runnable task) {
    try (ExecutorService executor = newVirtualThreadExecutor()) {
      Future<?> future = executor.submit(task);
      future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing task in virtual thread", e);
    }
  }

  /**
   * Runs multiple tasks in parallel using virtual threads.
   *
   * @param <T> the type of the result
   * @param tasks the tasks to run
   * @return a list of results from the tasks
   * @throws RuntimeException if any task throws an exception
   */
  public static <T> List<T> runInParallelVirtualThreads(List<Callable<T>> tasks) {
    try (ExecutorService executor = newVirtualThreadExecutor()) {
      List<Future<T>> futures = executor.invokeAll(tasks, DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      List<T> results = new ArrayList<>(futures.size());
      for (Future<T> future : futures) {
        results.add(future.get());
      }
      return results;
    }
    catch (Exception e) {
      throw new RuntimeException("Error executing tasks in parallel virtual threads", e);
    }
  }

  /**
   * Detects if a virtual thread is pinned when executing the given task.
   * <p>
   * Thread pinning occurs when a virtual thread is "stuck" to its carrier thread, preventing the scheduler
   * from reallocating it to other virtual threads. Common causes include synchronized blocks/methods and
   * native/foreign function calls.
   *
   * @param task the task to check for pinning
   * @return true if pinning is detected, false otherwise
   */
  public static boolean detectThreadPinning(Runnable task) {
    if (!VIRTUAL_THREADS_SUPPORTED) {
      log.warn("Virtual threads not supported in this JVM. Cannot detect thread pinning.");
      return false;
    }

    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    AtomicReference<Thread> virtualThread = new AtomicReference<>();

    // Create a thread that will monitor for pinning
    Thread monitorThread = new Thread(() -> {
      try {
        // Wait a bit for the virtual thread to start
        Thread.sleep(100);
        Thread vt = virtualThread.get();
        if (vt != null) {
          // Check if the thread is mounted (pinned)
          // This is a simplistic check and may not catch all pinning scenarios
          // For production use, JFR events or jdk.tracePinnedThreads would be better
          StackTraceElement[] stackTrace = vt.getStackTrace();
          for (StackTraceElement element : stackTrace) {
            // Look for indicators of pinning in the stack trace
            if (element.getClassName().contains("VirtualThread") && 
                (element.getMethodName().contains("parkOnCarrierThread") ||
                 element.getMethodName().contains("onPinned"))) {
              pinningDetected.set(true);
              log.warn("Thread pinning detected in virtual thread: {}", vt.getName());
              break;
            }
          }
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    try (ExecutorService executor = newVirtualThreadExecutor()) {
      Future<?> future = executor.submit(() -> {
        virtualThread.set(Thread.currentThread());
        task.run();
      });

      monitorThread.start();
      future.get(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      monitorThread.join(1000);
    }
    catch (Exception e) {
      log.error("Error while detecting thread pinning", e);
    }

    return pinningDetected.get();
  }

  /**
   * Validates that thread context is properly propagated across virtual threads.
   * <p>
   * This is important for maintaining security contexts, transaction boundaries, etc., across virtual threads.
   *
   * @param <T> the type of the context value
   * @param contextSupplier a supplier that provides the context value from the current thread
   * @param expectedValue the expected context value
   * @param task the task to run in a virtual thread
   * @return true if the context is properly propagated, false otherwise
   */
  public static <T> boolean validateThreadContextPropagation(
      Supplier<T> contextSupplier,
      T expectedValue,
      Runnable task) {
    AtomicReference<T> contextInVirtualThread = new AtomicReference<>();

    runInVirtualThread(() -> {
      contextInVirtualThread.set(contextSupplier.get());
      task.run();
    });

    T actualValue = contextInVirtualThread.get();
    boolean isValid = expectedValue == null ? actualValue == null : expectedValue.equals(actualValue);

    if (!isValid) {
      log.warn("Thread context propagation failed. Expected: {}, Actual: {}", expectedValue, actualValue);
    }

    return isValid;
  }

  /**
   * Executes a REST API call in a virtual thread and returns the response.
   * <p>
   * This method is useful for testing JAX-RS resources with virtual threads.
   *
   * @param <T> the type of the response entity
   * @param apiCall the REST API call to execute
   * @return the response from the API call
   */
  public static <T> Response executeRestApiInVirtualThread(Callable<Response> apiCall) {
    return runInVirtualThread(apiCall);
  }

  /**
   * Executes multiple REST API calls in parallel using virtual threads.
   * <p>
   * This method is useful for testing concurrent access to JAX-RS resources.
   *
   * @param apiCalls the REST API calls to execute
   * @return a list of responses from the API calls
   */
  public static List<Response> executeRestApisInParallel(List<Callable<Response>> apiCalls) {
    return runInParallelVirtualThreads(apiCalls);
  }

  /**
   * Executes a task using structured concurrency with virtual threads.
   * <p>
   * Structured concurrency treats groups of related tasks running in different threads as a single unit of work,
   * streamlining error handling and cancellation, improving reliability, and enhancing observability.
   *
   * @param <T> the type of the result
   * @param task the task to execute, which can fork subtasks using the provided scope
   * @return the result of the task
   * @throws RuntimeException if the task throws an exception
   */
  @SuppressWarnings("preview")
  public static <T> T executeWithStructuredConcurrency(Consumer<StructuredTaskScope<T>> task) {
    if (!VIRTUAL_THREADS_SUPPORTED) {
      throw new UnsupportedOperationException("Structured concurrency requires Java 21 or later");
    }

    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      AtomicReference<T> result = new AtomicReference<>();
      task.accept((StructuredTaskScope<T>) scope);
      scope.join();
      scope.throwIfFailed();
      return result.get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Structured concurrency task was interrupted", e);
    }
    catch (ExecutionException e) {
      throw new RuntimeException("Error in structured concurrency task", e.getCause());
    }
  }

  /**
   * A simplified version of structured concurrency for testing REST APIs.
   * <p>
   * This method executes multiple REST API calls concurrently and returns the first successful response,
   * or throws an exception if all calls fail.
   *
   * @param apiCalls the REST API calls to execute
   * @return the first successful response
   * @throws RuntimeException if all API calls fail
   */
  @SuppressWarnings("preview")
  public static Response executeFirstSuccessfulRestApi(List<Callable<Response>> apiCalls) {
    if (!VIRTUAL_THREADS_SUPPORTED) {
      throw new UnsupportedOperationException("Structured concurrency requires Java 21 or later");
    }

    try (var scope = new StructuredTaskScope.ShutdownOnSuccess<Response>()) {
      for (Callable<Response> apiCall : apiCalls) {
        scope.fork(apiCall);
      }
      scope.join();
      return scope.result();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("REST API execution was interrupted", e);
    }
    catch (ExecutionException e) {
      throw new RuntimeException("All REST API calls failed", e.getCause());
    }
  }
}