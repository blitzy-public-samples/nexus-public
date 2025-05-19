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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;

import org.sonatype.nexus.common.app.ManagedLifecycle;
import org.sonatype.nexus.common.stateguard.Guarded;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport;
import org.sonatype.nexus.elasticsearch.internal.ClientProvider;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.common.app.ManagedLifecycle.Phase.SERVICES;
import static org.sonatype.nexus.common.stateguard.StateGuardLifecycleSupport.State.STARTED;

/**
 * Specialized executor service for Elasticsearch search operations using Java 21 Virtual Threads.
 * <p>
 * This executor leverages Virtual Threads to optimize concurrent search request handling,
 * providing improved throughput for I/O-bound search operations without the limitations
 * of traditional thread pools.
 *
 * @since 3.60
 */
@Named
@Singleton
@ManagedLifecycle(phase = SERVICES)
public class VirtualThreadSearchExecutor
    extends StateGuardLifecycleSupport
{
  private final ClientProvider clientProvider;
  
  private ExecutorService executor;

  @Inject
  public VirtualThreadSearchExecutor(final ClientProvider clientProvider) {
    this.clientProvider = checkNotNull(clientProvider);
  }

  /**
   * Initialize the virtual thread executor service.
   */
  @PostConstruct
  public void initialize() {
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Executes a search request asynchronously using a virtual thread.
   *
   * @param searchRequest the Elasticsearch search request to execute
   * @return a CompletableFuture that will be completed with the search response
   */
  @Guarded(by = STARTED)
  public CompletableFuture<SearchResponse> execute(final SearchRequest searchRequest) {
    checkNotNull(searchRequest, "searchRequest cannot be null");
    
    CompletableFuture<SearchResponse> future = new CompletableFuture<>();
    
    executor.execute(() -> {
      try {
        Client client = clientProvider.get();
        client.search(searchRequest, new ActionListener<SearchResponse>() {
          @Override
          public void onResponse(SearchResponse response) {
            future.complete(response);
          }

          @Override
          public void onFailure(Exception e) {
            future.completeExceptionally(e);
          }
        });
      }
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    return future;
  }

  /**
   * Executes a search operation asynchronously using a virtual thread.
   *
   * @param searchOperation the operation that performs the search and returns a result
   * @param <T> the type of result returned by the search operation
   * @return a CompletableFuture that will be completed with the result of the search operation
   */
  @Guarded(by = STARTED)
  public <T> CompletableFuture<T> execute(final Supplier<T> searchOperation) {
    checkNotNull(searchOperation, "searchOperation cannot be null");
    
    CompletableFuture<T> future = new CompletableFuture<>();
    
    executor.execute(() -> {
      try {
        T result = searchOperation.get();
        future.complete(result);
      }
      catch (Exception e) {
        future.completeExceptionally(e);
      }
    });
    
    return future;
  }

  /**
   * Executes a search operation asynchronously using a virtual thread with a timeout.
   *
   * @param searchOperation the operation that performs the search and returns a result
   * @param timeout the maximum time to wait for the operation to complete
   * @param unit the time unit of the timeout argument
   * @param <T> the type of result returned by the search operation
   * @return a CompletableFuture that will be completed with the result of the search operation
   */
  @Guarded(by = STARTED)
  public <T> CompletableFuture<T> execute(final Supplier<T> searchOperation, long timeout, TimeUnit unit) {
    checkNotNull(searchOperation, "searchOperation cannot be null");
    checkNotNull(unit, "timeUnit cannot be null");
    
    CompletableFuture<T> future = execute(searchOperation);
    
    // Apply timeout to the future
    return future.orTimeout(timeout, unit);
  }

  /**
   * Shuts down the executor service, allowing in-progress tasks to complete.
   */
  @PreDestroy
  public void shutdown() {
    if (executor != null && !executor.isShutdown()) {
      executor.shutdown();
      try {
        // Wait a reasonable time for existing tasks to terminate
        if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
          executor.shutdownNow(); // Cancel currently executing tasks
          // Wait a while for tasks to respond to being cancelled
          if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
            log.warn("Executor did not terminate");
          }
        }
      }
      catch (InterruptedException ie) {
        // (Re-)Cancel if current thread also interrupted
        executor.shutdownNow();
        // Preserve interrupt status
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  protected void doStart() throws Exception {
    // Initialize the executor if it hasn't been initialized yet or was shut down
    if (executor == null || executor.isShutdown()) {
      initialize();
    }
  }

  @Override
  protected void doStop() throws Exception {
    shutdown();
  }
}