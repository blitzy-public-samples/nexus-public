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
package org.sonatype.nexus.repository.rest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * Provide a set of {@link SearchMapping}s.
 * 
 * <p>With Java 21 support, this interface now provides methods for leveraging Virtual Threads
 * to efficiently process search mappings with high concurrency and minimal overhead.</p>
 *
 * @since 3.7
 */
public interface SearchMappings
{
  /**
   * Get the search mappings.
   * 
   * @return an iterable of search mappings
   */
  Iterable<SearchMapping> get();

  /**
   * Process search mappings asynchronously using Java 21 Virtual Threads.
   * This method enables high-concurrency processing of search mappings with minimal overhead,
   * which is particularly beneficial for I/O-bound operations like search indexing or query mapping.
   *
   * <p>Example usage:</p>
   * <pre>
   * searchMappings.processAsync(mappings -> {
   *   // Process each mapping asynchronously
   *   return processMapping(mappings);
   * }).thenAccept(results -> {
   *   // Handle the processed results
   * });
   * </pre>
   *
   * <p>Best practices for Virtual Threads:</p>
   * <ul>
   *   <li>Use for I/O-bound operations rather than CPU-intensive tasks</li>
   *   <li>Avoid thread-local variables that might cause memory leaks with many threads</li>
   *   <li>Don't manually manage or pool Virtual Threads - let the JVM handle their lifecycle</li>
   *   <li>Be aware that synchronized blocks can cause carrier thread pinning</li>
   * </ul>
   *
   * @param <R> the type of the result
   * @param processor the function to process each mapping
   * @return a CompletableFuture that will be completed with the result of the processing
   * @since 3.60
   */
  default <R> CompletableFuture<R> processAsync(Function<Iterable<SearchMapping>, R> processor) {
    return CompletableFuture.supplyAsync(() -> processor.apply(get()), getVirtualThreadExecutor());
  }

  /**
   * Process each search mapping individually and asynchronously using Java 21 Virtual Threads.
   * This method is useful when each mapping needs to be processed independently and in parallel.
   *
   * <p>Example usage:</p>
   * <pre>
   * searchMappings.processEachAsync(mapping -> {
   *   // Process a single mapping
   *   return processOneMapping(mapping);
   * }).thenAccept(results -> {
   *   // Handle the list of processed results
   * });
   * </pre>
   *
   * @param <R> the type of the result for each mapping
   * @param processor the function to process each individual mapping
   * @return a CompletableFuture that will be completed with a list of results, one for each mapping
   * @since 3.60
   */
  default <R> CompletableFuture<Iterable<R>> processEachAsync(Function<SearchMapping, R> processor) {
    return CompletableFuture.supplyAsync(() -> {
      // Create a stream of CompletableFutures, each processing one mapping
      var futures = StreamSupport.stream(get().spliterator(), false)
          .map(mapping -> CompletableFuture.supplyAsync(
              () -> processor.apply(mapping), getVirtualThreadExecutor()))
          .toList();
      
      // Collect all results
      var results = futures.stream()
          .map(CompletableFuture::join)
          .toList();
      
      return results;
    }, getVirtualThreadExecutor());
  }

  /**
   * Get a Virtual Thread executor for concurrent operations.
   * Virtual Threads are a lightweight implementation of threads that allow for high concurrency
   * with minimal resource overhead, making them ideal for I/O-bound operations.
   *
   * <p>This method provides a default implementation that creates a new Virtual Thread per task.
   * Implementations can override this method to provide custom executor configurations if needed.</p>
   *
   * <p>Virtual Threads in Java 21 provide several advantages over platform threads:</p>
   * <ul>
   *   <li>Much lower memory footprint (approximately 1KB vs 2MB per thread)</li>
   *   <li>Can scale to millions of concurrent threads</li>
   *   <li>Automatically yield when blocked on I/O operations</li>
   *   <li>No need for complex thread pool sizing or management</li>
   * </ul>
   *
   * @return an executor that creates a new virtual thread for each task
   * @since 3.60
   */
  default Executor getVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}