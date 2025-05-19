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

import java.util.UUID;
import java.util.Optional;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.EntityUUID;

/**
 * Top-level content metadata for the repository; distinct from the repository entity in the config store.
 *
 * This interface is fully compatible with Java 21 features including pattern matching and virtual threads.
 * Implementations can leverage Java 21's pattern matching for switch and record patterns for optimized
 * EntityId handling.
 *
 * @since 3.20
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
   * Extracts the UUID from the config repository ID if it's an EntityUUID.
   * Uses Java 21 pattern matching for optimized type handling.
   *
   * @return an Optional containing the UUID if the EntityId is an EntityUUID, or empty otherwise
   * @since 3.60
   */
  default Optional<UUID> extractConfigRepositoryUUID() {
    EntityId entityId = configRepositoryId();
    return (entityId instanceof EntityUUID entityUUID) ? 
        Optional.of(entityUUID.uuid()) : 
        Optional.empty();
  }
  
  /**
   * Compares this repository's config ID with another EntityId using pattern matching
   * for optimized equality checking.
   *
   * @param otherId the EntityId to compare with
   * @return true if the IDs are equal, false otherwise
   * @since 3.60
   */
  default boolean hasConfigRepositoryId(EntityId otherId) {
    if (otherId == null) {
      return false;
    }
    
    EntityId thisId = configRepositoryId();
    
    // Use pattern matching to optimize comparison when both are EntityUUID
    if (thisId instanceof EntityUUID thisUUID && otherId instanceof EntityUUID otherUUID) {
      return thisUUID.uuid().equals(otherUUID.uuid());
    }
    
    // Fall back to standard equality check
    return thisId.equals(otherId);
  }
}
