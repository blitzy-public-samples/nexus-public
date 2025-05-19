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

// This class is intentionally uber-simple, DO NOT add more helpers to this implementation.

/**
 * Abstract implementation of {@link Entity} that provides a thread-safe metadata field.
 * 
 * <p>The metadata field is declared as volatile to ensure visibility across threads,
 * including virtual threads in Java 21. This guarantees that any thread reading the
 * metadata field will see the most recent write by any other thread.</p>
 * 
 * @see EntityHelper
 * @since 3.7
 */
public abstract class AbstractEntity
    implements Entity
{
  // Volatile ensures memory visibility across threads (including virtual threads in Java 21)
  // This guarantees that reads will always see the most recent write to this field
  private transient volatile EntityMetadata metadata;

  @Nullable
  public EntityMetadata getEntityMetadata() {
    return metadata;
  }

  public void setEntityMetadata(@Nullable final EntityMetadata metadata) {
    this.metadata = metadata;
  }
}