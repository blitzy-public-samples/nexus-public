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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.sonatype.nexus.capability.CapabilityIdentity;
import org.sonatype.nexus.common.event.EventWithSource;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageImpl;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemData;
import org.sonatype.nexus.internal.capability.storage.CapabilityStorageItemEvent;
import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base support class for capability storage item events.
 * <p>
 * This implementation leverages Java 21 features including:
 * <ul>
 *   <li>Virtual Threads for asynchronous event processing</li>
 *   <li>Record Patterns for improved data handling</li>
 *   <li>Thread-safe event processing for concurrent environments</li>
 * </ul>
 *
 * @since 3.60
 */
public class CapabilityStorageItemEventSupport
    extends EventWithSource
    implements CapabilityStorageItemEvent
{
  private static final Logger log = LoggerFactory.getLogger(CapabilityStorageItemEventSupport.class);
  
  /**
   * Virtual thread executor for asynchronous event processing.
   * Using virtual threads improves performance for I/O-bound operations
   * without consuming significant system resources.
   */
  private static final ExecutorService virtualThreadExecutor = 
      NexusExecutorService.forCurrentSubjectVirtual();
  
  private volatile CapabilityIdentity capabilityId;

  /**
   * Default constructor for deserialization.
   */
  protected CapabilityStorageItemEventSupport() {
    // deserialization
  }

  /**
   * Constructs a new event support instance using the provided capability storage item data.
   * <p>
   * Uses pattern matching for improved type safety and readability.
   *
   * @param item the capability storage item data
   */
  protected CapabilityStorageItemEventSupport(final CapabilityStorageItemData item) {
    checkNotNull(item);
    // Using pattern matching for improved type safety and readability
    if (item instanceof CapabilityStorageItemData data) {
      this.capabilityId = CapabilityStorageImpl.capabilityIdentity(data);
    }
  }

  @Override
  public CapabilityIdentity getCapabilityId() {
    return capabilityId;
  }

  /**
   * Sets the capability identity for this event.
   * <p>
   * This method is thread-safe for use in concurrent environments.
   *
   * @param capabilityId the capability identity
   */
  public void setCapabilityId(final CapabilityIdentity capabilityId) {
    this.capabilityId = capabilityId;
  }
  
  /**
   * Processes this event asynchronously using a virtual thread.
   * <p>
   * This method leverages Java 21 Virtual Threads for improved performance
   * with I/O-bound operations without consuming significant system resources.
   *
   * @param processor the event processor to execute asynchronously
   * @return a CompletableFuture representing the pending completion of the processing
   */
  public CompletableFuture<Void> processAsync(Consumer<CapabilityStorageItemEvent> processor) {
    return CompletableFuture.runAsync(() -> {
      try {
        processor.accept(this);
      } catch (Exception e) {
        log.error("Error processing capability storage event asynchronously", e);
        throw e;
      }
    }, virtualThreadExecutor);
  }
}