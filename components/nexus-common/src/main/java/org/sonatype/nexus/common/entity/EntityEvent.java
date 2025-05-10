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
import java.util.concurrent.locks.ReentrantLock;

import org.sonatype.nexus.common.event.HasAffinity;
import org.sonatype.nexus.common.event.HasLocality;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Entity event.
 * 
 * This class is not suitable for conversion to a record because:
 * 1. It's an abstract class (records can't be abstract)
 * 2. It has mutable fields (records are immutable)
 * 3. It has custom lazy-loading logic for the entity field
 *
 * @since 3.1
 */
@SuppressWarnings("unchecked")
public abstract class EntityEvent
    implements HasAffinity, HasLocality
{
  private final EntityMetadata metadata;

  private String remoteNodeId;

  private String affinity;

  // Using volatile for visibility across threads
  private volatile Entity entity;
  
  // Lock for entity initialization - more efficient than synchronized block in Java 21
  private final ReentrantLock entityLock = new ReentrantLock();

  public EntityEvent(final EntityMetadata metadata) {
    this.metadata = checkNotNull(metadata);
  }

  public EntityId getId() {
    return metadata.getId();
  }

  public EntityVersion getVersion() {
    return metadata.getVersion();
  }

  /**
   * @return {@code true} if this is a local event
   *
   * @since 3.1
   */
  @Override
  public boolean isLocal() {
    return remoteNodeId == null;
  }

  /**
   * @param remoteNodeId the remote node that sent this event; {@code null} if event is local
   *
   * @since 3.1
   */
  public void setRemoteNodeId(@Nullable final String remoteNodeId) {
    this.remoteNodeId = remoteNodeId;
  }

  /**
   * @return the remote node that sent this event; {@code null} if event is local
   *
   * @since 3.1
   */
  @Nullable
  public String getRemoteNodeId() {
    return remoteNodeId;
  }

  /**
   * @return attached entity type, if it exists
   */
  @Nullable
  public <T extends Entity> Class<T> getEntityType() {
    return metadata.<T>getEntityType().orElse(null);
  }

  /**
   * Returns the attached entity, if it exists.
   * Uses an optimized lazy loading pattern with ReentrantLock for better performance in Java 21.
   * 
   * @return attached entity, if it exists
   */
  @Nullable
  public <T extends Entity> T getEntity() {
    // Fast path - check if entity is already initialized
    Entity result = entity;
    if (result == null) {
      // Slow path - acquire lock and initialize if needed
      entityLock.lock();
      try {
        result = entity;
        if (result == null) {
          // Initialize entity only once
          entity = result = metadata.getEntity().orElse(null);
        }
      } finally {
        entityLock.unlock();
      }
    }
    return (T) result;
  }

  @Override
  public String getAffinity() {
    return affinity;
  }

  /**
   * Declares the affinity for this event.
   *
   * @since 3.11
   */
  public void setAffinity(final String affinity) {
    this.affinity = affinity;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "metadata=" + metadata +
        ", remoteNodeId=" + remoteNodeId +
        '}';
  }
}