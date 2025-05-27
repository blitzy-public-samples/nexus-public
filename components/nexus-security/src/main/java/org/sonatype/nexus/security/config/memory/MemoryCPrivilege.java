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
package org.sonatype.nexus.security.config.memory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedMap;

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.security.config.CPrivilege;

/**
 * An implementation of {@link CPrivilege} suitable for an in-memory backing store.
 *
 * @since 3.0
 */
public class MemoryCPrivilege
    implements CPrivilege
{
  private String description;

  private String id;

  private String name;

  private Map<String, String> properties;

  private boolean readOnly = false;

  private String type;

  private int version;

  /**
   * Creates a clone of this privilege using Java 21's improved type handling.
   * 
   * @return A deep copy of this privilege
   */
  @Override
  public MemoryCPrivilege clone() {
    try {
      // Use pattern matching for improved type safety
      if (super.clone() instanceof MemoryCPrivilege copy) {
        // Create a deep copy of properties if they exist
        if (this.properties != null) {
          copy.properties = new LinkedHashMap<>(this.properties);
        }
        return copy;
      }
      throw new RuntimeException("Clone did not return expected type");
    }
    catch (CloneNotSupportedException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getDescription() {
    return this.description;
  }

  @Override
  public String getId() {
    return this.id;
  }

  @Override
  public String getName() {
    return this.name;
  }

  /**
   * Gets the properties map, leveraging Java 21 Sequenced Collections for improved performance.
   * 
   * @return A map of property key-value pairs with preserved insertion order
   */
  @Override
  public Map<String, String> getProperties() {
    if (this.properties == null) {
      // Use LinkedHashMap which implements SequencedMap in Java 21
      this.properties = new LinkedHashMap<>();
    }
    return this.properties;
  }

  /**
   * Gets a property value by key, using pattern matching for improved type safety.
   * 
   * @param key The property key to look up
   * @return The property value, or null if not found
   */
  @Override
  public String getProperty(final String key) {
    // Use pattern matching to handle the Map.Entry safely
    if (getProperties().entrySet().stream()
        .filter(entry -> key.equals(entry.getKey()))
        .findFirst()
        .orElse(null) instanceof Map.Entry<String, String>(var k, var value)) {
      return value;
    }
    return null;
  }

  @Override
  public String getType() {
    return this.type;
  }

  @Override
  public int getVersion() {
    return version;
  }

  @Override
  public boolean isReadOnly() {
    return this.readOnly;
  }

  @Override
  public void removeProperty(final String key) {
    getProperties().remove(key);
  }

  @Override
  public void setDescription(final String description) {
    this.description = description;
  }

  @Override
  public void setId(final String id) {
    this.id = id;
  }

  @Override
  public void setName(final String name) {
    this.name = name;
  }

  @Override
  public void setProperties(final Map<String, String> properties) {
    this.properties = properties;
  }

  @Override
  public void setProperty(final String key, final String value) {
    getProperties().put(key, value);
  }

  @Override
  public void setReadOnly(final boolean readOnly) {
    this.readOnly = readOnly;
  }

  @Override
  public void setType(final String type) {
    this.type = type;
  }

  @Override
  public void setVersion(final int version) {
    this.version = version;
  }

  /**
   * Returns a string representation of this privilege using Java 21 String Templates.
   * 
   * @return A formatted string representation of this privilege
   */
  @Override
  public String toString() {
    return STR."""
        {getClass().getSimpleName()}{
          id='{id}',
          name='{name}',
          description='{description}',
          type='{type}',
          properties={properties},
          readOnly={readOnly},
          version='{version}'
        }""";
  }

  /**
   * Builder class for creating MemoryCPrivilege instances with a fluent API.
   * Enhanced with modern collection handling in Java 21.
   */
  public static class MemoryCPrivilegeBuilder {
    private final String id;
    private String description;
    private String name;
    // Use LinkedHashMap which implements SequencedMap in Java 21 for predictable iteration order
    private final SequencedMap<String, String> properties = new LinkedHashMap<>();
    private boolean readOnly = false;
    private String type;
    private int version;

    /**
     * Creates a new builder with the specified ID.
     *
     * @param id The unique identifier for the privilege
     */
    public MemoryCPrivilegeBuilder(final String id) {
      this.id = id;
    }

    /**
     * Sets the description for the privilege.
     *
     * @param description The privilege description
     * @return This builder for method chaining
     */
    public MemoryCPrivilegeBuilder description(final String description) {
      this.description = description;
      return this;
    }

    /**
     * Sets the name for the privilege.
     *
     * @param name The privilege name
     * @return This builder for method chaining
     */
    public MemoryCPrivilegeBuilder name(final String name) {
      this.name = name;
      return this;
    }

    /**
     * Sets the read-only flag for the privilege.
     *
     * @param readOnly Whether the privilege is read-only
     * @return This builder for method chaining
     */
    public MemoryCPrivilegeBuilder readOnly(final boolean readOnly) {
      this.readOnly = readOnly;
      return this;
    }

    /**
     * Sets the type for the privilege.
     *
     * @param type The privilege type
     * @return This builder for method chaining
     */
    public MemoryCPrivilegeBuilder type(final String type) {
      this.type = type;
      return this;
    }

    /**
     * Sets the version for the privilege.
     *
     * @param version The privilege version
     * @return This builder for method chaining
     */
    public MemoryCPrivilegeBuilder version(final int version) {
      this.version = version;
      return this;
    }

    /**
     * Adds a property to the privilege.
     *
     * @param key The property key
     * @param value The property value
     * @return This builder for method chaining
     */
    public MemoryCPrivilegeBuilder property(final String key, final String value) {
      properties.put(key, value);
      return this;
    }

    /**
     * Builds a new MemoryCPrivilege instance with the configured values.
     * Uses record pattern matching for improved property handling.
     *
     * @return A new MemoryCPrivilege instance
     */
    public MemoryCPrivilege build() {
      MemoryCPrivilege privilege = new MemoryCPrivilege();
      privilege.setId(id);
      privilege.setDescription(description);
      privilege.setName(name);
      privilege.setProperties(properties);
      privilege.setReadOnly(readOnly);
      privilege.setType(type);
      privilege.setVersion(version);

      return privilege;
    }
  }
}