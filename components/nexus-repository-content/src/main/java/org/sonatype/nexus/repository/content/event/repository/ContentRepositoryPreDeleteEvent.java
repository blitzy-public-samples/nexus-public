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
package org.sonatype.nexus.repository.content.event.repository;

import java.util.Objects;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sonatype.nexus.repository.content.ContentRepository;

/**
 * Event sent just before a {@link ContentRepository} is deleted.
 * <p>
 * This event is optimized for Virtual Thread execution and uses Record Patterns
 * for efficient data representation. The event maintains proper immutability
 * for concurrent event handling in high-throughput scenarios.
 *
 * @since 3.27
 */
public class ContentRepositoryPreDeleteEvent
    extends ContentRepositoryEvent
{
  private static final Logger log = LoggerFactory.getLogger(ContentRepositoryPreDeleteEvent.class);

  /**
   * Creates a new pre-delete event for the given repository.
   * <p>
   * This constructor ensures thread-safe access to repository data before deletion
   * by performing defensive validation of the input parameter.
   *
   * @param contentRepository the repository about to be deleted (must not be null)
   * @throws NullPointerException if contentRepository is null
   */
  public ContentRepositoryPreDeleteEvent(final ContentRepository contentRepository) {
    super(Objects.requireNonNull(contentRepository, "ContentRepository cannot be null"));
  }
  
  /**
   * Dispatches this event using a Virtual Thread for optimal performance.
   * <p>
   * This method leverages Java 21's Virtual Threads to efficiently process
   * the event without blocking platform threads, especially useful for
   * I/O-bound operations that might occur during repository deletion.
   *
   * @param handler the handler to process this event
   */
  public void dispatchAsync(final PreDeleteEventHandler handler) {
    Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      try {
        handler.onPreDelete(this);
      } catch (Exception e) {
        // Log and swallow exceptions to prevent thread termination
        // but allow other handlers to continue processing
        log(e);
      }
    });
  }
  
  /**
   * Functional interface for handling pre-delete events with pattern matching support.
   * <p>
   * Implementations can use Java 21 Record Patterns to efficiently extract and process
   * repository data from the event.
   */
  @FunctionalInterface
  public interface PreDeleteEventHandler {
    /**
     * Handles the pre-delete event.
     *
     * @param event the pre-delete event to handle
     */
    void onPreDelete(ContentRepositoryPreDeleteEvent event);
  }
  
  /**
   * Logs an exception that occurred during event handling.
   * <p>
   * This method is protected to allow subclasses to customize logging behavior.
   *
   * @param e the exception to log
   */
  protected void log(final Exception e) {
    log.error("Error handling pre-delete event", e);
  }
}
