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

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.event.HasAffinity;
import org.sonatype.nexus.common.event.HasLocality;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Entity event.
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

  // Using AtomicReference for thread-safe lazy initialization without synchronization
  private final AtomicReference<Entity> entityRef = new AtomicReference<>();

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
   * @return attached entity, if it exists
   */
  @Nullable
  public <T extends Entity> T getEntity() {
    // Use AtomicReference for thread-safe lazy initialization
    Entity entity = entityRef.get();
    if (entity == null) {
      Entity newEntity = metadata.getEntity().orElse(null);
      if (newEntity != null && entityRef.compareAndSet(null, newEntity)) {
        entity = newEntity;
      } else {
        entity = entityRef.get();
      }
    }
    return (T) entity;
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
    return getClass().getSimpleName() + "{"
        + "metadata=" + metadata
        + ", remoteNodeId=" + remoteNodeId
        + '}';
  }
}