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
 * <p>
 * Note: This class is not suitable for conversion to a Java 21 record because:
 * <ul>
 *   <li>It extends a non-record class (EntityEvent) which has mutable fields</li>
 *   <li>Records cannot extend other classes, only interfaces</li>
 *   <li>The parent class already handles thread safety for event publication</li>
 * </ul>
 * 
 * <p>
 * Thread safety is ensured by the parent class which uses volatile fields and
 * double-checked locking for lazy initialization of the entity field.
 * Event publication is handled through the EventManager which supports
 * asynchronous delivery using Java 21 virtual threads when appropriate.
 *
 * @since 3.1
 */
public class EntityDeletedEvent
    extends EntityEvent
{
  /**
   * Constructs a new entity deleted event.
   *
   * @param metadata the entity metadata for the deleted entity
   */
  public EntityDeletedEvent(final EntityMetadata metadata) {
    super(metadata);
  }
}
