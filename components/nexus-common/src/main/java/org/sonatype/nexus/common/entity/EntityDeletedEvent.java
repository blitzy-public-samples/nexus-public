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

/**
 * Entity deleted event.
 *
 * @since 3.1
 */
public class EntityDeletedEvent
    extends EntityEvent
{
  /**
   * Creates a new entity deleted event.
   *
   * @param metadata the entity metadata for the deleted entity
   * @throws IllegalArgumentException if metadata is null or not of the expected type
   */
  public EntityDeletedEvent(final Object metadata) {
    super(metadata instanceof EntityMetadata entityMetadata ? entityMetadata 
        : throwInvalidMetadata(metadata));
  }
  
  /**
   * Helper method to throw an exception for invalid metadata.
   *
   * @param metadata the invalid metadata object
   * @return never returns, always throws exception
   * @throws IllegalArgumentException always thrown with appropriate message
   */
  private static EntityMetadata throwInvalidMetadata(final Object metadata) {
    throw new IllegalArgumentException("Expected EntityMetadata but got: " 
        + (metadata != null ? metadata.getClass().getName() : "null"));
  }
}