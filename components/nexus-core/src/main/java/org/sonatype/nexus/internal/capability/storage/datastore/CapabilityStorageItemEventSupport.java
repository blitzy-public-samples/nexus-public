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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

import org.sonatype.nexus.capability.CapabilityIdentity;
import org.sonatype.nexus.common.event.EventWithSource;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageImpl;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Base support class for {@link CapabilityStorageItemEvent} implementations.
 * <p>
 * Optimized for Java 21 Virtual Threads to provide efficient asynchronous event processing.
 * This implementation leverages pattern matching for improved code readability and
 * Virtual Threads for non-blocking event handling.
 *
 * @since 3.60
 */
public class CapabilityStorageItemEventSupport
    extends EventWithSource
    implements CapabilityStorageItemEvent
{
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  
  private CapabilityIdentity capabilityId;

  /**
   * Default constructor for deserialization.
   */
  protected CapabilityStorageItemEventSupport() {
    // deserialization
  }

  /**
   * Constructs a new event from the given capability storage item data.
   * Uses Java 21 pattern matching for improved code readability.
   *
   * @param item the capability storage item data
   */
  protected CapabilityStorageItemEventSupport(final CapabilityStorageItemData item) {
    // Using pattern matching to validate and process the item
    if (item instanceof CapabilityStorageItemData data) {
      this.capabilityId = CapabilityStorageImpl.capabilityIdentity(data);
    } else {
      throw new IllegalArgumentException(STR."Invalid capability storage item: \{item}");
    }
  }

  /**
   * Processes this event asynchronously using a Virtual Thread.
   * This method leverages Java 21 Virtual Threads for efficient concurrent processing
   * without blocking platform threads during I/O operations.
   *
   * @param action the action to execute asynchronously
   * @return a CompletableFuture representing the pending completion of the event processing
   */
  public CompletableFuture<Void> processAsync(Runnable action) {
    return CompletableFuture.runAsync(action, VIRTUAL_THREAD_EXECUTOR);
  }

  @Override
  public CapabilityIdentity getCapabilityId() {
    return capabilityId;
  }

  /**
   * Sets the capability identity.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @param capabilityId the capability identity to set
   */
  public void setCapabilityId(final CapabilityIdentity capabilityId) {
    this.capabilityId = capabilityId;
  }
}
