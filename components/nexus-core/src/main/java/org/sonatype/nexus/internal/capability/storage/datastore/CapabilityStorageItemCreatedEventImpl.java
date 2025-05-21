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
import java.util.function.Consumer;

import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemCreatedEvent;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;

/**
 * Implementation of {@link CapabilityStorageItemCreatedEvent} that supports asynchronous processing
 * using Java 21 Virtual Threads for improved concurrency and performance.
 *
 * @since 3.60
 */
public class CapabilityStorageItemCreatedEventImpl
    extends CapabilityStorageItemEventSupport
    implements CapabilityStorageItemCreatedEvent
{
  protected CapabilityStorageItemCreatedEventImpl() {
    // deserialization
  }

  public CapabilityStorageItemCreatedEventImpl(final CapabilityStorageItemData item) {
    super(item);
  }
  
  /**
   * Processes this event asynchronously using a Java 21 Virtual Thread.
   * Virtual Threads provide lightweight concurrency with minimal overhead,
   * allowing for efficient handling of many concurrent events.
   *
   * @param handler the consumer that will process this event
   * @return a CompletableFuture that completes when the event processing is done
   */
  public CompletableFuture<Void> processAsync(final Consumer<CapabilityStorageItemCreatedEvent> handler) {
    return CompletableFuture.runAsync(
        () -> handler.accept(this),
        Executors.newVirtualThreadPerTaskExecutor());
  }
  
  /**
   * Processes this event asynchronously using a Java 21 Virtual Thread,
   * preserving the current thread context (like MDC logging context).
   *
   * @param handler the consumer that will process this event
   * @param contextPreserver a runnable that sets up the thread context before processing
   * @return a CompletableFuture that completes when the event processing is done
   */
  public CompletableFuture<Void> processAsyncWithContext(
      final Consumer<CapabilityStorageItemCreatedEvent> handler,
      final Runnable contextPreserver) {
    return CompletableFuture.runAsync(
        () -> {
          try {
            // Set up thread context (MDC, etc.)
            contextPreserver.run();
            // Process the event
            handler.accept(this);
          } finally {
            // Any cleanup can be done here if needed
          }
        },
        Executors.newVirtualThreadPerTaskExecutor());
  }
}