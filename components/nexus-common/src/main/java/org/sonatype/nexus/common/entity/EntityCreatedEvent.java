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
 * Entity created event.
 * 
 * This class is not suitable for conversion to a record because it extends EntityEvent,
 * which contains mutable state and custom thread-safety mechanisms. Records cannot extend
 * non-record classes and are designed for immutable data carriers.
 * 
 * Thread-safety is inherited from the parent class which uses volatile fields and ReentrantLock
 * for concurrent access patterns optimized for Java 21.
 *
 * @since 3.1
 */
public class EntityCreatedEvent
    extends EntityEvent
{
  /**
   * Creates a new entity created event.
   *
   * @param metadata the metadata of the created entity (non-null)
   */
  public EntityCreatedEvent(final EntityMetadata metadata) {
    super(metadata);
  }
}