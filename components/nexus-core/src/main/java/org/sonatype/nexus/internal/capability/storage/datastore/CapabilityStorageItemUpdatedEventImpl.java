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
import java.util.function.Consumer;

import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemUpdatedEvent;
import org.sonatype.nexus.thread.internal.MDCUtils;

import static java.lang.StringTemplate.STR;

/**
 * Implementation of {@link CapabilityStorageItemUpdatedEvent} that leverages Java 21 features
 * for improved performance and concurrency.
 * <p>
 * This implementation uses Virtual Threads for asynchronous event processing, allowing
 * for efficient handling of many concurrent events without blocking platform threads.
 * It also utilizes pattern matching and String Templates for improved code readability
 * and maintainability.
 *
 * @since 3.60
 */
public class CapabilityStorageItemUpdatedEventImpl
    extends CapabilityStorageItemEventSupport
    implements CapabilityStorageItemUpdatedEvent
{
  /**
   * Default constructor for deserialization.
   */
  protected CapabilityStorageItemUpdatedEventImpl() {
    // deserialization
  }

  /**
   * Constructs a new updated event from the given capability storage item data.
   *
   * @param item the capability storage item data
   */
  public CapabilityStorageItemUpdatedEventImpl(final CapabilityStorageItemData item) {
    super(item);
  }

  /**
   * Processes this update event asynchronously using a Virtual Thread.
   * This method leverages Java 21 Virtual Threads for efficient concurrent processing
   * without blocking platform threads during I/O operations.
   *
   * @param consumer the consumer that will process this event
   * @return a CompletableFuture representing the pending completion of the event processing
   */
  public CompletableFuture<Void> processUpdateAsync(Consumer<CapabilityStorageItemUpdatedEvent> consumer) {
    return processAsync(() -> {
      // Ensure MDC context is properly set for Virtual Thread execution
      try {
        MDCUtils.withMdcContext(() -> {
          consumer.accept(this);
          return null;
        }).run();
      } catch (Exception e) {
        // Log the exception with proper context using String Templates
        throw new RuntimeException(STR."Error processing capability update event: \{e.getMessage()}", e);
      }
    });
  }

  /**
   * Returns a string representation of this event, using Java 21 String Templates
   * for improved readability and performance.
   *
   * @return a string representation of this event
   */
  @Override
  public String toString() {
    return STR."CapabilityStorageItemUpdatedEvent{capabilityId=\{getCapabilityId()}, local=\{isLocal()}}";
  }
}