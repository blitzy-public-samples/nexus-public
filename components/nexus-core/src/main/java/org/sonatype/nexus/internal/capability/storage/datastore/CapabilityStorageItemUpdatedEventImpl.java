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
package org.sonatype.nexus.internal.capability.storage.datastore;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.common.base.Preconditions;

import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemUpdatedEvent;

/**
 * Implementation of {@link CapabilityStorageItemUpdatedEvent} that leverages Java 21 Virtual Threads
 * for asynchronous event processing. Virtual Threads provide lightweight concurrency with minimal overhead,
 * making them ideal for I/O-bound operations like event handling.
 * 
 * <p>This implementation offers several methods for processing events asynchronously using Virtual Threads,
 * including single-handler processing, parallel processing with multiple handlers, and result transformation.</p>
 * 
 * @since 3.60
 */
public class CapabilityStorageItemUpdatedEventImpl
    extends CapabilityStorageItemEventSupport
    implements CapabilityStorageItemUpdatedEvent, AutoCloseable
{
  protected CapabilityStorageItemUpdatedEventImpl() {
    // deserialization
  }

  public CapabilityStorageItemUpdatedEventImpl(final CapabilityStorageItemData item) {
    super(item);
  }
  
  /**
   * Process this event asynchronously using a Java 21 Virtual Thread.
   * 
   * @param handler the event handler to process this event
   * @return a CompletableFuture that completes when the event processing is done
   */
  public CompletableFuture<Void> processAsync(Consumer<CapabilityStorageItemUpdatedEvent> handler) {
    Preconditions.checkNotNull(handler, "Event handler cannot be null");
    return CompletableFuture.runAsync(() -> handler.accept(this), 
        Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Process this event asynchronously using a Java 21 Virtual Thread with a timeout.
   * 
   * @param handler the event handler to process this event
   * @param timeout the maximum time to wait for the event processing
   * @param unit the time unit of the timeout argument
   * @return a CompletableFuture that completes when the event processing is done or times out
   */
  public CompletableFuture<Void> processAsync(Consumer<CapabilityStorageItemUpdatedEvent> handler, long timeout, TimeUnit unit) {
    Preconditions.checkNotNull(handler, "Event handler cannot be null");
    Preconditions.checkArgument(timeout > 0, "Timeout must be positive");
    Preconditions.checkNotNull(unit, "TimeUnit cannot be null");
    
    var future = CompletableFuture.runAsync(() -> handler.accept(this),
        Executors.newVirtualThreadPerTaskExecutor());
    
    return future.orTimeout(timeout, unit);
  }
  
  /**
   * Process this event asynchronously using a Java 21 Virtual Thread and transform the result.
   * 
   * @param <R> the type of the result
   * @param transformer the function to transform this event into a result
   * @return a CompletableFuture that completes with the transformed result
   */
  public <R> CompletableFuture<R> processAsyncWithResult(Function<CapabilityStorageItemUpdatedEvent, R> transformer) {
    Preconditions.checkNotNull(transformer, "Transformer function cannot be null");
    return CompletableFuture.supplyAsync(() -> transformer.apply(this),
        Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Process this event asynchronously using a Java 21 Virtual Thread, transform the result, with a timeout.
   * 
   * @param <R> the type of the result
   * @param transformer the function to transform this event into a result
   * @param timeout the maximum time to wait for the event processing
   * @param unit the time unit of the timeout argument
   * @return a CompletableFuture that completes with the transformed result or times out
   */
  public <R> CompletableFuture<R> processAsyncWithResult(
      Function<CapabilityStorageItemUpdatedEvent, R> transformer, long timeout, TimeUnit unit) {
    Preconditions.checkNotNull(transformer, "Transformer function cannot be null");
    Preconditions.checkArgument(timeout > 0, "Timeout must be positive");
    Preconditions.checkNotNull(unit, "TimeUnit cannot be null");
    
    var future = CompletableFuture.supplyAsync(() -> transformer.apply(this),
        Executors.newVirtualThreadPerTaskExecutor());
    
    return future.orTimeout(timeout, unit);
  }
  
  /**
   * Executes multiple handlers for this event in parallel using Virtual Threads.
   * 
   * @param handlers the event handlers to process this event
   * @return a CompletableFuture that completes when all handlers have completed
   */
  public CompletableFuture<Void> processParallel(Iterable<Consumer<CapabilityStorageItemUpdatedEvent>> handlers) {
    Preconditions.checkNotNull(handlers, "Handlers iterable cannot be null");
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a CompletableFuture for each handler
    var futures = new java.util.ArrayList<CompletableFuture<Void>>();
    for (var handler : handlers) {
      if (handler != null) {
        futures.add(CompletableFuture.runAsync(() -> handler.accept(this), executor));
      }
    }
    
    // Return a CompletableFuture that completes when all handlers have completed
    return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
  }
  
  /**
   * Executes multiple handlers for this event in parallel using Virtual Threads with a timeout.
   * 
   * @param handlers the event handlers to process this event
   * @param timeout the maximum time to wait for all handlers to complete
   * @param unit the time unit of the timeout argument
   * @return a CompletableFuture that completes when all handlers have completed or times out
   */
  public CompletableFuture<Void> processParallel(
      Iterable<Consumer<CapabilityStorageItemUpdatedEvent>> handlers, long timeout, TimeUnit unit) {
    Preconditions.checkNotNull(handlers, "Handlers iterable cannot be null");
    Preconditions.checkArgument(timeout > 0, "Timeout must be positive");
    Preconditions.checkNotNull(unit, "TimeUnit cannot be null");
    
    var future = processParallel(handlers);
    return future.orTimeout(timeout, unit);
  }
  
  /**
   * Implements AutoCloseable to allow using this event in try-with-resources blocks.
   * This can be useful for ensuring proper cleanup of resources associated with the event.
   */
  @Override
  public void close() {
    // Currently no resources to clean up, but this provides a hook for future extensions
    // and allows using this event in try-with-resources blocks
  }
}