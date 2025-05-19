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

import javax.annotation.Nullable;

/**
 * Entity updated event.
 * 
 * Uses Java 21 pattern matching for safer metadata handling.
 *
 * @since 3.1
 */
public class EntityUpdatedEvent
    extends EntityEvent
{
  /**
   * Creates a new entity updated event.
   *
   * @param metadata the entity metadata
   */
  public EntityUpdatedEvent(final EntityMetadata metadata) {
    super(metadata);
  }
  
  /**
   * Gets the entity with pattern matching for safer type handling.
   * 
   * @param <T> the entity type
   * @return the entity, or null if it doesn't exist
   */
  @Nullable
  @Override
  public <T extends Entity> T getEntity() {
    // Use pattern matching to safely get and cast the entity
    Object entity = super.getEntity();
    if (entity instanceof T t) {
      return t;
    }
    return null;
  }
  
  /**
   * Gets the entity type with pattern matching for safer type handling.
   * 
   * @param <T> the entity type
   * @return the entity type, or null if it doesn't exist
   */
  @Nullable
  @Override
  public <T extends Entity> Class<T> getEntityType() {
    // Use pattern matching to safely get and cast the entity type
    Class<?> type = super.getEntityType();
    if (type instanceof Class<? extends Entity> entityType) {
      // Safe to cast because we've verified it's a Class<? extends Entity>
      @SuppressWarnings("unchecked")
      Class<T> result = (Class<T>) entityType;
      return result;
    }
    return null;
  }
}