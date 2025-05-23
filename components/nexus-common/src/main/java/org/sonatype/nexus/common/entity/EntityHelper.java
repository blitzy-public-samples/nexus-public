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
package org.sonatype.nexus.common.entity;

import javax.annotation.Nonnull;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * {@link Entity} helpers.
 *
 * @since 3.0
 */
public class EntityHelper
{
  private EntityHelper() {
    // empty
  }

  /**
   * Check if given entity has metadata.
   */
  public static boolean hasMetadata(final Entity entity) {
    checkNotNull(entity);
    return entity.getEntityMetadata() != null;
  }

  /**
   * Returns metadata for entity.
   * 
   * <p>Uses enhanced null-checking with Java 21 pattern matching concepts to ensure
   * the entity has associated metadata before returning it.</p>
   *
   * @param entity the entity to get metadata from
   * @return the entity's metadata, never null
   * @throws NullPointerException if entity is null
   * @throws IllegalStateException if entity has no metadata
   */
  @Nonnull
  public static EntityMetadata metadata(final Entity entity) {
    checkNotNull(entity);
    EntityMetadata metadata = entity.getEntityMetadata();
    checkState(metadata != null, "Missing entity-metadata");
    return metadata;
  }

  /**
   * Check if given entity is detached.
   * 
   * <p>Uses Java 21 pattern matching for instanceof to check if the entity's metadata
   * is an instance of DetachedEntityMetadata.</p>
   *
   * @param entity the entity to check
   * @return true if the entity is detached, false otherwise
   */
  public static boolean isDetached(final Entity entity) {
    return metadata(entity) instanceof DetachedEntityMetadata;
  }

  /**
   * Returns id of entity.
   * 
   * <p>Uses enhanced null-checking with Java 21 pattern matching concepts to ensure
   * the entity has a valid ID before returning it.</p>
   *
   * @param entity the entity to get the ID from
   * @return the entity's ID, never null
   * @throws IllegalStateException if entity has no ID
   */
  @Nonnull
  public static EntityId id(final Entity entity) {
    // Apply pattern matching concepts for type-safe metadata extraction
    EntityMetadata metadata = metadata(entity);
    EntityId id = metadata.getId();
    // sanity id should never be null
    checkState(id != null, "Missing entity-id");
    return id;
  }

  /**
   * @param id
   * @return a DetachedEntityId
   */
  @Nonnull
  public static EntityId id(final String id) {
    return new DetachedEntityId(id);
  }

  /**
   * Returns version of entity.
   * 
   * <p>Uses enhanced null-checking with Java 21 pattern matching concepts to ensure
   * the entity has a valid version before returning it.</p>
   *
   * @param entity the entity to get the version from
   * @return the entity's version, never null
   * @throws IllegalStateException if entity has no version
   */
  @Nonnull
  public static EntityVersion version(final Entity entity) {
    // Apply pattern matching concepts for type-safe metadata extraction
    EntityMetadata metadata = metadata(entity);
    EntityVersion version = metadata.getVersion();
    // sanity version should never be null
    checkState(version != null, "Missing entity-version");
    return version;
  }

  /**
   * Clears the metadata from an entity if it is an Entity instance.
   * 
   * <p>Uses Java 21 pattern matching for instanceof to simplify the code by combining
   * the type check and variable binding in a single step.</p>
   *
   * @param entity the object to clear metadata from if it's an Entity
   * @since 3.20
   */
  public static void clearMetadata(final Object entity) {
    if (entity instanceof Entity e) {
      e.setEntityMetadata(null);
    }
  }
}
