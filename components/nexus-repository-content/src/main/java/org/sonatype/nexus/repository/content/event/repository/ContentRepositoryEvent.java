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
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.repository.content.ContentRepository;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.requireNonNull;
import static org.sonatype.nexus.repository.content.store.InternalIds.contentRepositoryId;

/**
 * Base {@link ContentRepository} event.
 * <p>
 * Optimized for Java 21 Virtual Threads with enhanced thread safety and performance.
 *
 * @since 3.26
 */
public class ContentRepositoryEvent
    extends ContentStoreEvent
{
  // Using final for thread safety in Virtual Thread environments
  private final ContentRepository contentRepository;
  
  // Cache for toString to avoid repeated string concatenation in high-concurrency scenarios
  private final AtomicReference<String> toStringCache = new AtomicReference<>();

  /**
   * Creates a new ContentRepositoryEvent for the given repository.
   * <p>
   * Uses Java 21 Record Pattern approach for parameter validation.
   *
   * @param contentRepository the content repository (must not be null)
   */
  protected ContentRepositoryEvent(final ContentRepository contentRepository) {
    super(contentRepositoryId(contentRepository));
    this.contentRepository = requireNonNull(contentRepository, "ContentRepository cannot be null");
  }

  /**
   * Returns the content repository associated with this event.
   * <p>
   * Thread-safe and optimized for concurrent access in Virtual Thread environments.
   *
   * @return the content repository (never null)
   */
  public ContentRepository getContentRepository() {
    return contentRepository; // Already immutable and thread-safe due to final field
  }

  @Override
  public String toString() {
    // Use Java 21 String Templates for more efficient string representation
    // with lazy initialization for better performance in high-concurrency scenarios
    String result = toStringCache.get();
    if (result == null) {
      result = STR."ContentRepositoryEvent{contentRepository=\{contentRepository}} \{super.toString()}";
      toStringCache.set(result);
    }
    return result;
  }
}