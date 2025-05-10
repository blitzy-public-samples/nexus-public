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
   *
   * @param entity The entity to check (must not be null)
   * @return true if the entity has metadata, false otherwise
   */
  public static boolean hasMetadata(final Entity entity) {
    checkNotNull(entity);
    return entity.getEntityMetadata() != null;
  }

  /**
   * Returns metadata for entity.
   *
   * @param entity The entity to get metadata from (must not be null)
   * @return The entity metadata (never null)
   * @throws IllegalStateException if the entity has no metadata
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
   * @param entity The entity to check (must not be null)
   * @return true if the entity is detached, false otherwise
   */
  public static boolean isDetached(final Entity entity) {
    return metadata(entity) instanceof DetachedEntityMetadata;
  }

  /**
   * Returns id of entity.
   *
   * @param entity The entity to get ID from (must not be null)
   * @return The entity ID (never null)
   * @throws IllegalStateException if the entity has no ID
   */
  @Nonnull
  public static EntityId id(final Entity entity) {
    EntityId id = metadata(entity).getId();
    // sanity id should never be null
    checkState(id != null, "Missing entity-id");
    return id;
  }

  /**
   * Creates a DetachedEntityId from the given string ID.
   *
   * @param id The string ID to convert (must not be null)
   * @return A new DetachedEntityId instance
   */
  @Nonnull
  public static EntityId id(final String id) {
    return new DetachedEntityId(checkNotNull(id, "ID cannot be null"));
  }

  /**
   * Returns version of entity.
   *
   * @param entity The entity to get version from (must not be null)
   * @return The entity version (never null)
   * @throws IllegalStateException if the entity has no version
   */
  @Nonnull
  public static EntityVersion version(final Entity entity) {
    EntityVersion version = metadata(entity).getVersion();
    // sanity version should never be null
    checkState(version != null, "Missing entity-version");
    return version;
  }

  /**
   * Clears metadata from the given object if it's an Entity.
   *
   * @param entity The object to clear metadata from (may be null)
   * @since 3.20
   */
  public static void clearMetadata(final Object entity) {
    if (entity instanceof Entity entityObj) {
      entityObj.setEntityMetadata(null);
    }
  }
}