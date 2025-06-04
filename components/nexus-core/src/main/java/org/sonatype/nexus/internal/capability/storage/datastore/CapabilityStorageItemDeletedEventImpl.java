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
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemDeletedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemEvent;

/**
 * Implementation of {@link CapabilityStorageItemDeletedEvent} that leverages Java 21 features
 * for improved performance and concurrency.
 * <p>
 * This implementation uses Virtual Threads for asynchronous event processing, which provides
 * better scalability for I/O-bound operations without consuming significant system resources.
 *
 * @since 3.60
 */
public class CapabilityStorageItemDeletedEventImpl
    extends CapabilityStorageItemEventSupport
    implements CapabilityStorageItemDeletedEvent
{
  private static final Logger log = LoggerFactory.getLogger(CapabilityStorageItemDeletedEventImpl.class);

  /**
   * Default constructor for deserialization.
   */
  protected CapabilityStorageItemDeletedEventImpl() {
    // deserialization
  }

  /**
   * Constructs a new deleted event instance with the provided capability storage item data.
   * <p>
   * Uses pattern matching for improved type safety and readability.
   *
   * @param item the capability storage item data
   */
  public CapabilityStorageItemDeletedEventImpl(final CapabilityStorageItemData item) {
    super(item);
  }
  
  /**
   * Processes this deletion event asynchronously using a virtual thread.
   * <p>
   * This method leverages Java 21 Virtual Threads for improved performance with I/O-bound operations
   * without consuming significant system resources. It extends the base implementation with
   * deletion-specific logging and error handling.
   *
   * @param processor the event processor to execute asynchronously
   * @return a CompletableFuture representing the pending completion of the processing
   */
  @Override
  public CompletableFuture<Void> processAsync(Consumer<CapabilityStorageItemEvent> processor) {
    if (log.isDebugEnabled()) {
      log.debug(STR."Processing deletion event for capability ID: \{getCapabilityId()}");
    }
    
    return CompletableFuture.runAsync(() -> {
      try {
        processor.accept(this);
        if (log.isDebugEnabled()) {
          log.debug(STR."Successfully processed deletion event for capability ID: \{getCapabilityId()}");
        }
      } 
      catch (Exception e) {
        log.error(STR."Error processing capability deletion event for ID: \{getCapabilityId()}", e);
        throw e;
      }
    });
  }
}
