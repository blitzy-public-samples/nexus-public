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
package org.sonatype.nexus.repository.rest.api;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Provides a centralized Virtual Thread executor service for REST API operations.
 * <p>
 * This service leverages Java 21's Virtual Threads to efficiently handle I/O-bound operations
 * without the overhead of traditional platform threads. It is particularly useful for REST API
 * calls, database access, and blob storage operations that involve waiting for external resources.
 * </p>
 * <p>
 * Virtual Threads are lightweight threads managed by the JVM rather than the OS, allowing for
 * thousands of concurrent operations with minimal resource consumption. This implementation
 * significantly improves throughput and reduces latency for REST API operations by eliminating
 * the bottleneck of limited platform thread pools.
 * </p>
 * <p>
 * Key benefits of using Virtual Threads for REST operations:
 * <ul>
 *   <li>Reduced memory footprint compared to platform threads</li>
 *   <li>Improved throughput for I/O-bound operations</li>
 *   <li>Simplified concurrency model without complex thread pool management</li>
 *   <li>Better resource utilization during blocking operations</li>
 * </ul>
 * </p>
 * <p>
 * Usage example for REST API operations:
 * <pre>
 * {@code
 * @Inject
 * private VirtualThreadExecutorService virtualThreadExecutor;
 * 
 * public void handleRequest(Request request) {
 *     // Execute an I/O-bound operation asynchronously
 *     CompletableFuture<Response> future = virtualThreadExecutor.supplyAsync(() -> {
 *         // This code runs in a virtual thread
 *         return callExternalService(request);
 *     });
 *     
 *     // Process the result when it's available
 *     future.thenAccept(response -> {
 *         // Handle the response
 *         processResponse(response);
 *     });
 * }
 * }
 * </pre>
 * </p>
 *
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadExecutorService extends ComponentSupport
{
  private final ExecutorService executor;

  /**
   * Creates a new VirtualThreadExecutorService with a virtual thread per task executor.
   * <p>
   * This constructor initializes the service with Java 21's {@code Executors.newVirtualThreadPerTaskExecutor()},
   * which creates a new virtual thread for each submitted task. Unlike traditional thread pools,
   * this approach does not limit concurrency based on a fixed number of threads, allowing for
   * much higher throughput for I/O-bound operations.
   * </p>
   */
  public VirtualThreadExecutorService() {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    log.info("Virtual Thread Executor Service initialized");
  }

  /**
   * Executes a task asynchronously using a virtual thread.
   * <p>
   * This method is ideal for I/O-bound operations like REST API calls, database queries,
   * or file operations that spend most of their time waiting for external resources.
   * </p>
   *
   * @param task the task to execute
   * @return a Future representing the result of the task
   */
  public Future<?> submit(Runnable task) {
    return executor.submit(task);
  }

  /**
   * Executes a task asynchronously using a virtual thread and returns a result.
   * <p>
   * This method is particularly useful for operations that need to return a value,
   * such as fetching data from a repository or external service. The virtual thread
   * implementation ensures efficient execution even when the operation blocks waiting
   * for I/O or external resources.
   * </p>
   *
   * @param <T> the type of the task's result
   * @param task the task to execute
   * @return a Future representing the result of the task
   */
  public <T> Future<T> submit(Callable<T> task) {
    return executor.submit(task);
  }

  /**
   * Executes a task asynchronously using a virtual thread and returns a predetermined result.
   * <p>
   * This method is useful when you need to execute a task that doesn't produce a result itself,
   * but you want to associate a specific result value with its successful completion. This can
   * be helpful for tasks that perform side effects (like updating a database) where you want to
   * return a status or confirmation object upon completion.
   * </p>
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * OperationStatus status = new OperationStatus(OperationStatus.SUCCESS);
   * Future<OperationStatus> future = virtualThreadExecutorService.submit(() -> {
   *     // Perform some operation that doesn't return a value
   *     repository.updateMetadata(metadata);
   * }, status);
   * 
   * // Later, when you need the result
   * OperationStatus result = future.get(); // Will return the status object when the task completes
   * }
   * </pre>
   * </p>
   *
   * @param <T> the type of the task's result
   * @param task the task to execute
   * @param result the result to return upon successful completion
   * @return a Future representing the result of the task
   */
  public <T> Future<T> submit(Runnable task, T result) {
    return executor.submit(task, result);
  }

  /**
   * Executes a task asynchronously using a virtual thread and returns a CompletableFuture.
   * <p>
   * This method is useful for composing asynchronous operations in a functional style.
   * CompletableFuture provides a rich API for chaining operations, handling errors, and
   * combining results from multiple asynchronous tasks. Using virtual threads as the execution
   * mechanism ensures that these operations are performed efficiently, even when they involve
   * blocking I/O.
   * </p>
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * virtualThreadExecutorService.supplyAsync(() -> repositoryManager.get(repositoryName))
   *     .thenApply(repository -> repository.facet(HttpClientFacet.class))
   *     .thenCompose(httpClient -> httpClient.get(uri))
   *     .thenAccept(response -> processResponse(response));
   * }
   * </pre>
   * </p>
   *
   * @param <T> the type of the task's result
   * @param supplier the supplier to execute
   * @return a CompletableFuture representing the result of the task
   */
  public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier) {
    return CompletableFuture.supplyAsync(supplier, executor);
  }

  /**
   * Executes a task asynchronously using a virtual thread and returns a CompletableFuture.
   * <p>
   * This method is useful for composing asynchronous operations that don't return a result.
   * It's particularly well-suited for fire-and-forget operations like sending notifications,
   * updating metrics, or performing cleanup tasks that don't need to block the calling thread.
   * </p>
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * virtualThreadExecutorService.runAsync(() -> {
   *     // Perform some I/O-bound operation that doesn't return a value
   *     auditService.recordAccess(repository, asset);
   * });
   * }
   * </pre>
   * </p>
   *
   * @param runnable the runnable to execute
   * @return a CompletableFuture representing the completion of the task
   */
  public CompletableFuture<Void> runAsync(Runnable runnable) {
    return CompletableFuture.runAsync(runnable, executor);
  }

  /**
   * Wraps an existing CompletableFuture to ensure its dependent stages execute on virtual threads.
   * <p>
   * This method is particularly useful when integrating with existing code that returns CompletableFutures
   * but doesn't use virtual threads. By wrapping the CompletableFuture, you ensure that all subsequent
   * operations are executed using virtual threads, which is beneficial for I/O-bound operations.
   * </p>
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * // Existing code that returns a CompletableFuture but doesn't use virtual threads
   * CompletableFuture<Response> responseFuture = existingService.fetchDataAsync(uri);
   * 
   * // Wrap the future to ensure subsequent operations use virtual threads
   * virtualThreadExecutorService.wrapCompletableFuture(responseFuture)
   *     .thenApply(response -> processResponse(response))
   *     .thenAccept(result -> storeResult(result));
   * }
   * </pre>
   * </p>
   *
   * @param <T> the type of the CompletableFuture's result
   * @param future the CompletableFuture to wrap
   * @return a new CompletableFuture that will execute dependent stages on virtual threads
   */
  public <T> CompletableFuture<T> wrapCompletableFuture(CompletableFuture<T> future) {
    return future.thenApplyAsync(result -> result, executor);
  }

  /**
   * Executes a task with proper exception handling and returns a CompletableFuture.
   * <p>
   * This method logs any exceptions that occur during execution and wraps them in a RuntimeException.
   * It's particularly useful for REST API operations where you want to ensure that exceptions are
   * properly logged and don't silently fail.
   * </p>
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * virtualThreadExecutorService.supplyAsyncWithExceptionHandling(() -> {
   *     return repositoryManager.get(repositoryName).facet(HttpClientFacet.class).get(uri);
   * }).thenAccept(response -> {
   *     // Process successful response
   * }).exceptionally(ex -> {
   *     // Handle exception (already logged by the service)
   *     return null;
   * });
   * }
   * </pre>
   * </p>
   *
   * @param <T> the type of the task's result
   * @param supplier the supplier to execute
   * @return a CompletableFuture representing the result of the task
   */
  public <T> CompletableFuture<T> supplyAsyncWithExceptionHandling(Supplier<T> supplier) {
    return CompletableFuture.supplyAsync(supplier, executor)
        .exceptionally(ex -> {
          log.error("Error executing task on virtual thread", ex);
          throw new RuntimeException("Error executing task on virtual thread", ex);
        });
  }
  
  /**
   * Executes a runnable task with proper exception handling and returns a CompletableFuture.
   * <p>
   * This method logs any exceptions that occur during execution and wraps them in a RuntimeException.
   * It's designed for fire-and-forget operations where you don't need a return value but still want
   * proper exception handling and logging.
   * </p>
   * <p>
   * Example usage:
   * <pre>
   * {@code
   * virtualThreadExecutorService.runAsyncWithExceptionHandling(() -> {
   *     // Perform some I/O-bound operation that doesn't return a value
   *     repository.facet(HttpClientFacet.class).put(uri, content);
   * });
   * }
   * </pre>
   * </p>
   *
   * @param runnable the runnable to execute
   * @return a CompletableFuture representing the completion of the task
   */
  public CompletableFuture<Void> runAsyncWithExceptionHandling(Runnable runnable) {
    return CompletableFuture.runAsync(runnable, executor)
        .exceptionally(ex -> {
          log.error("Error executing task on virtual thread", ex);
          throw new RuntimeException("Error executing task on virtual thread", ex);
          });
  }

  /**
   * Returns the underlying ExecutorService.
   * <p>
   * This method provides direct access to the virtual thread executor service. It should be used
   * with caution as it bypasses the exception handling provided by other methods in this class.
   * </p>
   * <p>
   * Typical use cases for accessing the executor directly include:
   * <ul>
   *   <li>Integration with third-party libraries that accept an ExecutorService</li>
   *   <li>Advanced concurrency patterns not covered by the convenience methods</li>
   *   <li>Custom exception handling requirements</li>
   * </ul>
   * </p>
   *
   * @return the underlying ExecutorService that creates virtual threads
   */
  public ExecutorService getExecutor() {
    return executor;
  }

  /**
   * Shuts down the executor service, allowing for graceful termination of virtual threads.
   * This method is automatically called when the component is destroyed.
   */
  /**
   * Shuts down the executor service, allowing for graceful termination of virtual threads.
   * This method is automatically called when the component is destroyed.
   */
  @PreDestroy
  public void shutdown() {
    log.info("Shutting down Virtual Thread Executor Service");
    executor.shutdown();
    try {
      // Wait for existing tasks to terminate
      if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
        log.warn("Virtual Thread Executor did not terminate in the specified time.");
        // Cancel currently executing tasks
        executor.shutdownNow();
        // Wait a while for tasks to respond to being cancelled
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
          log.error("Virtual Thread Executor did not terminate.");
        }
      }
    }
    catch (InterruptedException e) {
      // (Re-)Cancel if current thread also interrupted
      executor.shutdownNow();
      // Preserve interrupt status
      Thread.currentThread().interrupt();
      log.warn("Shutdown of Virtual Thread Executor was interrupted", e);
    }
    log.info("Virtual Thread Executor Service shutdown complete");
  }
}