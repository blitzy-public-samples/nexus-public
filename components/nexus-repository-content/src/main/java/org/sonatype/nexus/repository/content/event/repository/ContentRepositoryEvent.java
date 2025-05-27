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

import org.sonatype.nexus.repository.content.ContentRepository;
import org.sonatype.nexus.repository.content.store.ContentStoreEvent;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.repository.content.store.InternalIds.contentRepositoryId;

/**
 * Base {@link ContentRepository} event.
 *
 * <p>This class is designed to be thread-safe and optimized for use with Java 21 Virtual Threads.
 * It maintains immutable state and can be safely published across threads without additional synchronization.</p>
 *
 * @since 3.26
 */
public class ContentRepositoryEvent
    extends ContentStoreEvent
{
  private final ContentRepository contentRepository;

  /**
   * Creates a new event for the given repository.
   * 
   * @param contentRepository the repository (must not be null)
   * @throws NullPointerException if contentRepository is null
   */
  protected ContentRepositoryEvent(final ContentRepository contentRepository) {
    super(contentRepositoryId(contentRepository));
    this.contentRepository = checkNotNull(contentRepository);
  }

  /**
   * Returns the repository associated with this event.
   * 
   * <p>This method is thread-safe and can be called concurrently from multiple threads,
   * including Virtual Threads, without additional synchronization.</p>
   *
   * @return the repository (never null)
   */
  public ContentRepository getContentRepository() {
    return contentRepository;
  }

  @Override
  public String toString() {
    return STR."ContentRepositoryEvent{contentRepository=\{contentRepository}} \{super.toString()}";
  }
}