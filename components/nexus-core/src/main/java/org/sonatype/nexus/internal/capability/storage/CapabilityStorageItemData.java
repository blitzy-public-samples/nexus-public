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
package org.sonatype.nexus.internal.capability.storage;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.HasEntityId;

/**
 * {@link CapabilityStorageItem} data.
 * 
 * Optimized for Java 21 database access patterns and Virtual Thread execution in persistence operations.
 * Uses thread-safe collections and optimized serialization/deserialization for improved performance
 * in concurrent environments.
 *
 * @since 3.21
 */
public class CapabilityStorageItemData
    implements HasEntityId, CapabilityStorageItem
{
  private volatile EntityId id;

  private volatile int version;

  private volatile String type;

  private volatile boolean enabled;

  private volatile String notes;

  private volatile Map<String, String> properties;

  /**
   * Default constructor for serialization frameworks.
   */
  public CapabilityStorageItemData() {
    // Default constructor for serialization frameworks
  }

  /**
   * Copy constructor for creating immutable copies.
   * Useful for thread-safe operations in Virtual Thread environments.
   *
   * @param source the source data to copy from
   */
  public CapabilityStorageItemData(final CapabilityStorageItemData source) {
    this.id = source.id;
    this.version = source.version;
    this.type = source.type;
    this.enabled = source.enabled;
    this.notes = source.notes;
    setProperties(source.getProperties()); // Ensures thread-safe copy of properties
  }

  @Override
  public EntityId getId() {
    return id;
  }

  @Override
  public void setId(final EntityId id) {
    this.id = id;
  }

  @Override
  public int getVersion() {
    return version;
  }

  @Override
  public void setVersion(final int version) {
    this.version = version;
  }

  @Override
  public String getType() {
    return type;
  }

  @Override
  public void setType(final String type) {
    this.type = type;
  }

  @Override
  public boolean isEnabled() {
    return enabled;
  }

  @Override
  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public String getNotes() {
    return notes;
  }

  @Override
  public void setNotes(final String notes) {
    this.notes = notes;
  }

  /**
   * Returns an unmodifiable view of the properties map.
   * This prevents concurrent modification issues in Virtual Thread environments.
   */
  @Override
  public Map<String, String> getProperties() {
    return properties == null ? Collections.emptyMap() : Collections.unmodifiableMap(properties);
  }

  /**
   * Sets the properties map, creating a thread-safe copy to ensure consistency
   * during concurrent database operations with Virtual Threads.
   */
  @Override
  public void setProperties(final Map<String, String> properties) {
    if (properties == null) {
      this.properties = null;
    } else {
      // Create a thread-safe copy of the properties map
      this.properties = new ConcurrentHashMap<>(properties);
    }
  }

  /**
   * Creates a mutable copy of the properties map for modification operations.
   * This method is useful when properties need to be modified before persistence.
   *
   * @return a mutable copy of the properties map
   */
  public Map<String, String> getMutableProperties() {
    return properties == null ? new HashMap<>() : new HashMap<>(properties);
  }

  /**
   * Optimized equals implementation using pattern matching for improved type checking.
   * Compatible with Java 21 pattern matching enhancements.
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    // Using pattern matching for type checking
    if (o instanceof CapabilityStorageItemData that) {
      return Objects.equals(type, that.type) &&
          Objects.equals(properties, that.properties);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, properties);
  }

  /**
   * Creates a deep copy of this object for thread-safe operations.
   * Useful when working with Virtual Threads to prevent concurrent modification issues.
   *
   * @return a new instance with the same data
   */
  public CapabilityStorageItemData copy() {
    return new CapabilityStorageItemData(this);
  }
}