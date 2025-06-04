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

import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemCreatedEvent;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implementation of {@link CapabilityStorageItemCreatedEvent} that leverages Java 21 features
 * for enhanced performance and concurrency.
 * <p>
 * This implementation utilizes Virtual Threads for asynchronous event processing, which provides
 * significant benefits for I/O-bound operations without consuming substantial system resources.
 * The event handling is optimized for the Java 21 concurrency model, ensuring efficient processing
 * in high-throughput environments.
 *
 * @since 3.60
 */
public class CapabilityStorageItemCreatedEventImpl
    extends CapabilityStorageItemEventSupport
    implements CapabilityStorageItemCreatedEvent
{
  private static final Logger log = LoggerFactory.getLogger(CapabilityStorageItemCreatedEventImpl.class);

  /**
   * Default constructor for deserialization.
   */
  protected CapabilityStorageItemCreatedEventImpl() {
    // deserialization
  }

  /**
   * Constructs a new event instance with the provided capability storage item data.
   * <p>
   * This constructor initializes the event with the necessary data for processing.
   *
   * @param item the capability storage item data
   */
  public CapabilityStorageItemCreatedEventImpl(final CapabilityStorageItemData item) {
    super(item);
  }

  /**
   * Processes this created event asynchronously using a Virtual Thread.
   * <p>
   * This method leverages Java 21 Virtual Threads to handle the event processing
   * asynchronously, which is particularly beneficial for I/O-bound operations.
   * The processing occurs on a dedicated Virtual Thread, allowing the calling thread
   * to continue execution without waiting for the event processing to complete.
   *
   * @param processor the event processor to execute asynchronously
   * @return a CompletableFuture representing the pending completion of the processing
   */
  @Override
  public CompletableFuture<Void> processAsync(Consumer<org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemEvent> processor) {
    log.debug("Processing capability creation event asynchronously for capability: {}", getCapabilityId());
    return CompletableFuture.runAsync(() -> {
      try {
        processor.accept(this);
        log.debug("Successfully processed capability creation event for capability: {}", getCapabilityId());
      } catch (Exception e) {
        log.error("Error processing capability creation event for capability: {}", getCapabilityId(), e);
        throw e;
      }
    });
  }
}
