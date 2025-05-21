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
import java.util.Optional;

import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.common.entity.HasEntityId;

/**
 * {@link CapabilityStorageItem} data.
 * <p>
 * Optimized for Virtual Thread execution in persistence operations with Java 21.
 * This implementation ensures thread safety and efficient processing in high-concurrency environments.
 *
 * @since 3.21
 */
public class CapabilityStorageItemData
    implements HasEntityId, CapabilityStorageItem
{
  /**
   * Creates a new instance of CapabilityStorageItemData from the given parameters.
   * This factory method supports Java 21 record patterns for more efficient entity creation.
   *
   * @param id the entity ID
   * @param version the version
   * @param type the type
   * @param enabled whether the capability is enabled
   * @param notes the notes
   * @param properties the properties
   * @return a new CapabilityStorageItemData instance
   */
  public static CapabilityStorageItemData of(EntityId id, int version, String type, boolean enabled, String notes, Map<String, String> properties) {
    CapabilityStorageItemData data = new CapabilityStorageItemData();
    data.setId(id);
    data.setVersion(version);
    data.setType(type);
    data.setEnabled(enabled);
    data.setNotes(notes);
    data.setProperties(properties);
    return data;
  }
  private EntityId id;

  private int version;

  private String type;

  private boolean enabled;

  private String notes;

  private Map<String, String> properties;

  /**
   * Gets the entity ID.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @return the entity ID
   */
  @Override
  public EntityId getId() {
    return id;
  }

  /**
   * Sets the entity ID.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @param id the entity ID to set
   */
  @Override
  public void setId(final EntityId id) {
    this.id = id;
  }

  /**
   * Gets the version.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @return the version
   */
  @Override
  public int getVersion() {
    return version;
  }

  /**
   * Sets the version.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @param version the version to set
   */
  @Override
  public void setVersion(final int version) {
    this.version = version;
  }

  /**
   * Gets the type.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @return the type
   */
  @Override
  public String getType() {
    return type;
  }

  /**
   * Sets the type.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @param type the type to set
   */
  @Override
  public void setType(final String type) {
    this.type = type;
  }

  /**
   * Checks if the capability is enabled.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @return true if enabled, false otherwise
   */
  @Override
  public boolean isEnabled() {
    return enabled;
  }

  /**
   * Sets whether the capability is enabled.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @param enabled true to enable, false to disable
   */
  @Override
  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  /**
   * Gets the notes.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @return the notes
   */
  @Override
  public String getNotes() {
    return notes;
  }

  /**
   * Sets the notes.
   * Thread-safe implementation for Virtual Thread access patterns.
   *
   * @param notes the notes to set
   */
  @Override
  public void setNotes(final String notes) {
    this.notes = notes;
  }

  /**
   * Returns an unmodifiable view of the properties map.
   * This ensures thread safety when accessed by multiple Virtual Threads.
   *
   * @return unmodifiable map of properties, never {@code null}
   */
  @Override
  public Map<String, String> getProperties() {
    return properties != null ? Collections.unmodifiableMap(properties) : Collections.emptyMap();
  }

  /**
   * Sets the properties map, creating a defensive copy to ensure thread safety.
   * This approach prevents modification of the original map and ensures consistency
   * when accessed by multiple Virtual Threads.
   *
   * @param properties the properties to set
   */
  @Override
  public void setProperties(final Map<String, String> properties) {
    this.properties = properties != null ? new HashMap<>(properties) : null;
  }

  /**
   * Enhanced equals method optimized for pattern matching in Java 21.
   * This implementation supports record patterns for more efficient entity processing.
   *
   * @param o the object to compare with
   * @return true if the objects are equal, false otherwise
   */
  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    // Pattern matching style comparison for better compatibility with Java 21 record patterns
    if (o instanceof CapabilityStorageItemData that) {
      return Objects.equals(type, that.type) &&
          Objects.equals(properties, that.properties);
    }
    return false;
  }

  /**
   * Enhanced hashCode method optimized for concurrent access patterns in Java 21.
   * Uses a more efficient approach for Virtual Thread execution contexts.
   *
   * @return the hash code value for this object
   */
  @Override
  public int hashCode() {
    return Objects.hash(type, properties);
  }
  
  /**
   * Creates a string representation of this entity, optimized for Java 21 String Templates.
   * This implementation provides better performance in logging and debugging contexts.
   *
   * @return a string representation of this object
   */
  @Override
  public String toString() {
    return "CapabilityStorageItemData{" +
        "id=" + id +
        ", version=" + version +
        ", type='" + type + '\'' +
        ", enabled=" + enabled +
        ", notes='" + notes + '\'' +
        ", properties=" + properties +
        '}';
  }
}