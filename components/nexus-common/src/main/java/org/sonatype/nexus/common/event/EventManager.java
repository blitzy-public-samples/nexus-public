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
package org.sonatype.nexus.common.event;

import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;

/**
 * Event manager.
 *
 * The event manager provides a mechanism for components to publish events and register handlers
 * for those events. Events are processed asynchronously, allowing for high-throughput, non-blocking
 * event processing.
 *
 * With Java 21, the event manager can utilize Virtual Threads for event processing, which provides
 * significant benefits:
 * - Lightweight threads with minimal memory overhead (compared to platform threads)
 * - Ability to handle thousands of concurrent events efficiently
 * - Improved throughput for I/O-bound event handlers
 * - Reduced contention and better resource utilization
 *
 * @see EventAware
 * @since 3.0
 */
@SuppressWarnings("deprecation")
public interface EventManager
    extends EventBus
{
  /**
   * Registers an event handler with the event manager.
   *
   * @param handler to be registered
   *
   * @since 3.2
   */
  @Override
  void register(Object handler);

  /**
   * Unregisters an event handler from the event manager.
   *
   * @param handler to be unregistered
   *
   * @since 3.2
   */
  @Override
  void unregister(Object handler);

  /**
   * Posts an event. The event manager will notify all previously registered handlers about this event.
   * 
   * Depending on the implementation, this may use platform threads or virtual threads for processing.
   *
   * @param event an event
   *
   * @since 3.2
   */
  @Override
  void post(Object event);

  /**
   * Posts an event asynchronously using Virtual Threads when available. This method is optimized for
   * high-throughput event processing with minimal resource overhead.
   * 
   * When Virtual Threads are enabled, each event is processed on its own dedicated virtual thread,
   * allowing for thousands of concurrent events to be processed efficiently without blocking.
   * 
   * When Virtual Threads are not available, this falls back to the standard event processing mechanism.
   *
   * @param event an event
   * @return a CompletableFuture that completes when the event has been processed
   * 
   * @since 3.30
   */
  CompletableFuture<Void> postAsync(Object event);

  /**
   * Checks if Virtual Thread execution is active for event processing.
   * 
   * Virtual Threads provide significant benefits for event processing, including:
   * - Lightweight threads with minimal memory overhead
   * - Ability to handle thousands of concurrent events efficiently
   * - Improved throughput for I/O-bound event handlers
   * - Reduced contention and better resource utilization
   *
   * @return true if Virtual Threads are enabled for event processing
   * 
   * @since 3.30
   */
  boolean isVirtualThreadsEnabled();

  /**
   * Used by UTs and ITs only to "wait for calm period" when all async event handlers have finished.
   */
  @VisibleForTesting
  boolean isCalmPeriod();

  /**
   * Is {@link HasAffinity} support enabled?
   *
   * @since 3.11
   */
  boolean isAffinityEnabled();
}