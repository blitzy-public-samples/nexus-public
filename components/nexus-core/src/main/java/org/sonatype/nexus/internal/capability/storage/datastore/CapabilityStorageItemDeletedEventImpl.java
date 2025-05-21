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
import org.sonatype.nexus.logging.task.TaskLogging;

import static java.lang.StringTemplate.STR;

/**
 * Implementation of {@link CapabilityStorageItemDeletedEvent} optimized for Java 21.
 * <p>
 * This implementation leverages Virtual Threads for asynchronous event processing,
 * providing non-blocking execution for capability deletion events. It uses Java 21
 * features like pattern matching, string templates, and the enhanced concurrency model.
 *
 * @since 3.60
 */
public class CapabilityStorageItemDeletedEventImpl
    extends CapabilityStorageItemEventSupport
    implements CapabilityStorageItemDeletedEvent
{
  /**
   * Default constructor for deserialization.
   */
  protected CapabilityStorageItemDeletedEventImpl() {
    // deserialization
  }

  /**
   * Constructs a new deleted event from the given capability storage item data.
   * Uses pattern matching for improved type safety.
   *
   * @param item the capability storage item data
   */
  public CapabilityStorageItemDeletedEventImpl(final CapabilityStorageItemData item) {
    super(item);
  }
  
  /**
   * Processes the deletion event asynchronously using a Virtual Thread.
   * This method provides a specialized implementation for deletion events,
   * with appropriate logging and error handling.
   *
   * @param handler the handler to process the deletion event
   * @return a CompletableFuture representing the pending completion of the deletion processing
   */
  public CompletableFuture<Void> processDeleteAsync(Consumer<CapabilityStorageItemDeletedEvent> handler) {
    return processAsync(() -> {
      try {
        TaskLogging.logEvent(STR."Processing capability deletion: \{getCapabilityId()}");
        handler.accept(this);
      }
      catch (Exception e) {
        TaskLogging.logEvent(STR."Error processing capability deletion: \{getCapabilityId()} - \{e.getMessage()}");
        throw e;
      }
    });
  }
}
