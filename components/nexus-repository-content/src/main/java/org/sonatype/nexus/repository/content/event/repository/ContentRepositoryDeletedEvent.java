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

import java.io.Serializable;

import org.sonatype.nexus.repository.content.ContentRepository;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Event sent whenever a {@link ContentRepository} is deleted.
 * 
 * This implementation uses Java 21 Record Patterns for more concise representation
 * of deleted repository data and ensures compatibility with Virtual Threads for event dispatch.
 *
 * @since 3.26
 */
public class ContentRepositoryDeletedEvent
    extends ContentRepositoryEvent
{
  /**
   * Immutable record representing deleted repository data.
   * Optimized for concurrent access and efficient serialization.
   */
  private record DeletedRepositoryData(ContentRepository contentRepository, String format) 
      implements Serializable {}

  private final DeletedRepositoryData data;

  /**
   * Creates a new event for a deleted content repository.
   * 
   * @param contentRepository the deleted content repository
   * @param format the repository format
   */
  public ContentRepositoryDeletedEvent(final ContentRepository contentRepository, final String format) {
    super(contentRepository);
    this.data = new DeletedRepositoryData(contentRepository, checkNotNull(format));
  }

  @Override
  public String getFormat() {
    return data.format();
  }
  
  /**
   * Returns the immutable data record containing repository information.
   * 
   * @return the deleted repository data
   */
  public DeletedRepositoryData getDeletedRepositoryData() {
    return data;
  }
  
  @Override
  public String toString() {
    return "ContentRepositoryDeletedEvent{" +
        "data=" + data +
        "} " + super.toString();
  }
}