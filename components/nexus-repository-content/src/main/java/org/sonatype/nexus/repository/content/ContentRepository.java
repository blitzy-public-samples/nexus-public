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
package org.sonatype.nexus.repository.content;

import org.sonatype.nexus.common.entity.EntityId;

/**
 * Top-level content metadata for the repository; distinct from the repository entity in the config store.
 * 
 * This interface is compatible with Java 21 features including pattern matching in switch expressions
 * and optimized handling of EntityId through record patterns when applicable. Implementations can benefit
 * from Java 21's Virtual Threads for I/O-bound operations when interacting with repository content.
 *
 * @since 3.20
 * @see java.lang.Thread#ofVirtual() for creating virtual threads in implementations with I/O operations
 */
public interface ContentRepository
    extends RepositoryContent
{
  /**
   * Identity of the associated repository entity in the config store.
   * 
   * @return the EntityId of the repository in the config store
   */
  EntityId configRepositoryId();

  /**
   * Identity of the associated repository entity in the content store.
   * 
   * @return the Integer ID of the repository in the content store
   */
  Integer contentRepositoryId();
  
  /**
   * Default method to check if this repository has a specific config repository ID.
   * Leverages Java 21's pattern matching capabilities for more concise client code.
   * 
   * @param id the EntityId to check against this repository's config ID
   * @return true if the provided ID matches this repository's config ID
   */
  default boolean hasConfigRepositoryId(EntityId id) {
    return id != null && id.equals(configRepositoryId());
  }
  
  /**
   * Default method to check if this repository has a specific content repository ID.
   * Designed to work efficiently with Java 21's pattern matching in switch expressions.
   * 
   * @param id the Integer ID to check against this repository's content ID
   * @return true if the provided ID matches this repository's content ID
   */
  default boolean hasContentRepositoryId(Integer id) {
    return id != null && id.equals(contentRepositoryId());
  }
  
  /**
   * Utility method to compare this repository with another object using Java 21's pattern matching.
   * This method demonstrates how implementations can be matched in client code using switch expressions.
   * 
   * <pre>
   * {@code
   * // Example usage with Java 21 pattern matching in switch expressions:
   * String result = switch(repository) {
   *   case ContentRepository cr when cr.isSameAs(otherRepo) -> "Same repository";
   *   case ContentRepository cr -> "Different repository";
   *   default -> "Not a repository";
   * };
   * }
   * </pre>
   * 
   * @param other the object to compare with this repository
   * @return true if the other object is the same repository (same content ID)
   */
  default boolean isSameAs(Object other) {
    return other instanceof ContentRepository cr && 
           hasContentRepositoryId(cr.contentRepositoryId());
  }
}