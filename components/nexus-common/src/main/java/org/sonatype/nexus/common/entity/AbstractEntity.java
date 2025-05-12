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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import javax.annotation.Nullable;

// This class is intentionally uber-simple, DO NOT add more helpers to this implementation.

/**
 * Abstract implementation of {@link Entity} that provides thread-safe management of entity metadata
 * using Java 21 concurrency primitives.
 *
 * @see EntityHelper
 * @since 3.7
 */
public abstract class AbstractEntity
    implements Entity
{
  /**
   * Entity metadata field, managed with a VarHandle for improved thread-safety and performance in Java 21.
   * The field remains transient to prevent serialization of metadata.
   */
  private transient EntityMetadata metadata;
  
  /**
   * VarHandle for thread-safe access to the metadata field.
   * This provides the same memory visibility guarantees as volatile but with potentially better performance.
   */
  private static final VarHandle METADATA;
  
  static {
    try {
      METADATA = MethodHandles.lookup().findVarHandle(AbstractEntity.class, "metadata", EntityMetadata.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @Nullable
  public EntityMetadata getEntityMetadata() {
    // Use getVolatile to maintain the same memory visibility guarantees as the previous volatile field
    return (EntityMetadata) METADATA.getVolatile(this);
  }

  public void setEntityMetadata(@Nullable final EntityMetadata metadata) {
    // Use setVolatile to maintain the same memory visibility guarantees as the previous volatile field
    METADATA.setVolatile(this, metadata);
  }
}