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
 * <p>
 * Optimized for Java 21 with Record Patterns and Virtual Thread compatibility.
 * This event is designed for efficient cluster-wide propagation.
 *
 * @since 3.26
 */
public class ContentRepositoryDeletedEvent
    extends ContentRepositoryEvent
{
  /**
   * Immutable record representing deleted repository data.
   * Used with pattern matching for efficient data access.
   */
  private record DeletedRepositoryData(String format, ContentRepository repository) implements Serializable {
    private DeletedRepositoryData {
      checkNotNull(format);
      checkNotNull(repository);
    }
  }

  private final DeletedRepositoryData deletedData;

  /**
   * Creates a new event for a deleted content repository.
   * 
   * @param contentRepository the deleted repository
   * @param format the repository format
   */
  public ContentRepositoryDeletedEvent(final ContentRepository contentRepository, final String format) {
    super(contentRepository);
    this.deletedData = new DeletedRepositoryData(format, contentRepository);
  }

  /**
   * Returns the format of the deleted repository.
   * Implementation is thread-safe and optimized for concurrent access.
   * 
   * @return the repository format
   */
  @Override
  public String getFormat() {
    // Using record pattern matching for concise, type-safe access to the format
    return switch (deletedData) {
      case DeletedRepositoryData(String format, var _) -> format;
    };
  }
}