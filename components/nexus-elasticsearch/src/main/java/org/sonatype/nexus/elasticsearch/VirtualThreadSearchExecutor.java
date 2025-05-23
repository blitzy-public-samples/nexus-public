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
package org.sonatype.nexus.elasticsearch;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.annotation.PreDestroy;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;

import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Specialized executor service for Elasticsearch search operations using Java 21 Virtual Threads.
 * <p>
 * This executor leverages Java 21's Virtual Threads to optimize concurrent search request handling,
 * providing significantly improved throughput for I/O-bound search operations without the limitations
 * of traditional thread pools.
 * </p>
 * <p>
 * Key benefits of using Virtual Threads for Elasticsearch operations:
 * <ul>
 *   <li>Eliminates thread pool sizing and configuration concerns</li>
 *   <li>Supports thousands of concurrent search operations with minimal resource consumption</li>
 *   <li>Automatically adapts to varying load patterns without manual tuning</li>
 *   <li>Reduces latency by eliminating thread pool queuing delays</li>
 *   <li>Simplifies code by using a non-blocking, future-based API</li>
 * </ul>
 * </p>
 * <p>
 * This component is lifecycle-managed and will be started during the SERVICES phase.
 * It automatically creates the Virtual Thread executor during startup and prevents new
 * task submissions during shutdown.
 * </p>
 *
 * @since 3.60
 */
@Named
@Singleton
@ManagedLifecycle(phase = SERVICES)
public class VirtualThreadSearchExecutor
    extends StateGuardLifecycleSupport
{
  private Executor virtualThreadExecutor;
  
  private final AtomicBoolean active = new AtomicBoolean(true);

  /**
   * Creates a new executor using Java 21 Virtual Threads.
   * <p>
   * This implementation leverages the Java 21 Virtual Threads feature to provide
   * a highly scalable execution environment for I/O-bound Elasticsearch operations.
   * Virtual Threads are lightweight threads that are managed by the JVM rather than
   * the operating system, allowing for much higher concurrency with minimal overhead.
   * </p>
   */
  public VirtualThreadSearchExecutor() {
    // Initialization will happen in doStart()
  }
  
  @Override
  protected void doStart() throws Exception {
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    this.active.set(true);
    log.info("Started Virtual Thread executor for Elasticsearch search operations");
  }
  
  @Override
  protected void doStop() throws Exception {
    shutdown();
    log.info("Stopped Virtual Thread executor for Elasticsearch search operations");
  }

  /**
   * Executes a search operation using Virtual Threads.
   * <p>
   * This method offloads the Elasticsearch search operation to a Virtual Thread,
   * which is ideal for I/O-bound operations like search requests. The operation
   * is executed asynchronously and returns a CompletableFuture that will be completed
   * when the search operation finishes.
   * </p>
   * <p>
   * Example usage:
   * </p>
   * <pre>
   * {@code
   * // Inject the executor and client
   * @Inject
   * private VirtualThreadSearchExecutor searchExecutor;
   * 
   * @Inject
   * private Provider<Client> clientProvider;
   * 
   * // Use in a service method
   * public CompletableFuture<SearchResponse> search(SearchRequest request) {
   *   return searchExecutor.execute(clientProvider.get(), request);
   * }
   * }
   * </pre>
   *
   * @param client the Elasticsearch client
   * @param searchRequest the search request to execute
   * @return a CompletableFuture that will complete with the search response
   * @throws IllegalStateException if the executor has been shut down or not started
   * @throws NullPointerException if client or searchRequest is null
   */
  public CompletableFuture<SearchResponse> execute(final Client client, final SearchRequest searchRequest) {
    checkNotNull(client, "Elasticsearch client cannot be null");
    checkNotNull(searchRequest, "Search request cannot be null");
    
    ensureStarted();
    
    if (!active.get()) {
      throw new IllegalStateException("Virtual Thread executor has been shut down");
    }
    
    CompletableFuture<SearchResponse> future = new CompletableFuture<>();
    
    virtualThreadExecutor.execute(() -> {
      try {
        log.debug("Executing search request on virtual thread: {}", Thread.currentThread());
        client.search(searchRequest, new ActionListener<SearchResponse>() {
          @Override
          public void onResponse(SearchResponse response) {
            log.debug("Search request completed successfully with {} hits", 
                response.getHits().getTotalHits().value);
            future.complete(response);
          }

          @Override
          public void onFailure(Exception e) {
            log.debug("Search request failed: {}", e.getMessage());
            future.completeExceptionally(e);
          }
        });
      }
      catch (Exception e) {
        log.error("Error executing search request: {}", e.getMessage(), e);
        future.completeExceptionally(e);
      }
    });
    
    return future;
  }
  
  /**
   * Executes a search operation supplier using Virtual Threads.
   * <p>
   * This method is useful when you need to perform additional operations before executing the search,
   * or when you need to transform the search results before returning them. The supplier will be
   * executed on a Virtual Thread, making it ideal for operations that involve I/O or other blocking
   * operations.
   * </p>
   * <p>
   * Example usage:
   * </p>
   * <pre>
   * {@code
   * CompletableFuture<List<String>> results = executor.supplyAsync(() -> {
   *   SearchResponse response = client.search(request).actionGet();
   *   return extractIdsFromResponse(response);
   * });
   * }
   * </pre>
   *
   * @param <T> the type of result
   * @param supplier the supplier that will produce the result
   * @return a CompletableFuture that will complete with the result
   * @throws IllegalStateException if the executor has been shut down
   * @throws NullPointerException if supplier is null
   */
  public <T> CompletableFuture<T> supplyAsync(final Supplier<T> supplier) {
    checkNotNull(supplier, "Supplier cannot be null");
    
    ensureStarted();
    
    if (!active.get()) {
      throw new IllegalStateException("Virtual Thread executor has been shut down");
    }
    
    return CompletableFuture.supplyAsync(() -> {
      try {
        log.debug("Executing supplier on virtual thread: {}", Thread.currentThread());
        return supplier.get();
      }
      catch (Exception e) {
        log.error("Error in supplier execution: {}", e.getMessage(), e);
        throw e;
      }
    }, virtualThreadExecutor);
  }
  
  /**
   * Executes a runnable task using Virtual Threads.
   * <p>
   * This method is useful for fire-and-forget operations or when you don't need a result
   * from the operation. The runnable will be executed on a Virtual Thread, making it ideal
   * for operations that involve I/O or other blocking operations.
   * </p>
   * <p>
   * Example usage:
   * </p>
   * <pre>
   * {@code
   * CompletableFuture<Void> future = executor.runAsync(() -> {
   *   client.admin().indices().refresh(new RefreshRequest()).actionGet();
   * });
   * }
   * </pre>
   *
   * @param runnable the task to execute
   * @return a CompletableFuture that will complete when the task is done
   * @throws IllegalStateException if the executor has been shut down
   * @throws NullPointerException if runnable is null
   */
  public CompletableFuture<Void> runAsync(final Runnable runnable) {
    checkNotNull(runnable, "Runnable cannot be null");
    
    ensureStarted();
    
    if (!active.get()) {
      throw new IllegalStateException("Virtual Thread executor has been shut down");
    }
    
    return CompletableFuture.runAsync(() -> {
      try {
        log.debug("Executing runnable on virtual thread: {}", Thread.currentThread());
        runnable.run();
      }
      catch (Exception e) {
        log.error("Error in runnable execution: {}", e.getMessage(), e);
        throw e;
      }
    }, virtualThreadExecutor);
  }

  /**
   * Shuts down the executor service.
   * <p>
   * This method is automatically called when the bean is destroyed or when the component
   * is stopped. It marks the executor as inactive, which prevents new tasks from being submitted.
   * Any tasks that are already running will continue to completion.
   * </p>
   * <p>
   * Note that Virtual Threads are automatically managed by the JVM, so there's no need to
   * explicitly shut them down. This method simply prevents new tasks from being submitted.
   * </p>
   */
  @PreDestroy
  public void shutdown() {
    if (active.compareAndSet(true, false)) {
      log.info("Shutting down Virtual Thread executor for Elasticsearch search operations");
    }
  }
  
  /**
   * Checks if the executor is active.
   *
   * @return true if the executor is active, false otherwise
   */
  public boolean isActive() {
    return active.get();
  }
}