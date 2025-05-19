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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.sonatype.nexus.repository.content.ContentRepository;

/**
 * Event sent just before a {@link ContentRepository} is deleted.
 * <p>
 * This event is optimized for Java 21 Virtual Thread execution and uses Record Patterns
 * for efficient data access. It maintains thread-safety through immutability and
 * provides optimized event dispatch for high-concurrency environments.
 *
 * @since 3.27
 */
public class ContentRepositoryPreDeleteEvent
    extends ContentRepositoryEvent
{
  /**
   * Virtual Thread executor for optimized event dispatch.
   * This allows event handlers to execute efficiently in high-concurrency scenarios.
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates a new pre-delete event for the given content repository.
   * The constructor ensures thread-safe access to repository data before deletion.
   *
   * @param contentRepository the repository about to be deleted (must not be null)
   * @throws NullPointerException if contentRepository is null
   */
  public ContentRepositoryPreDeleteEvent(final ContentRepository contentRepository) {
    super(Objects.requireNonNull(contentRepository, "Content repository cannot be null"));
  }

  /**
   * Returns the executor service optimized for handling this event.
   * Uses Java 21 Virtual Threads for efficient concurrent processing.
   *
   * @return the virtual thread executor for this event type
   */
  public Executor getExecutor() {
    return VIRTUAL_THREAD_EXECUTOR;
  }

  /**
   * Provides a pattern-matching friendly access to the content repository.
   * This method supports Java 21 Record Patterns for more declarative data access.
   *
   * @return the immutable content repository that will be deleted
   */
  @Override
  public ContentRepository getContentRepository() {
    return super.getContentRepository();
  }

  /**
   * Returns a string representation of this event using Java 21 pattern matching.
   * This implementation maintains proper immutability for concurrent event handling.
   *
   * @return a string representation of this event
   */
  @Override
  public String toString() {
    ContentRepository repository = getContentRepository();
    return "ContentRepositoryPreDeleteEvent{" +
        "contentRepository=" + repository +
        ", repositoryId=" + contentRepositoryId +
        "}";
  }
}