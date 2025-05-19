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
package org.sonatype.nexus.repository.content;

/**
 * Record representing component coordinates for efficient pattern matching.
 * @since 3.41
 */
record ComponentCoordinates(String namespace, String name, String kind, String version, String normalizedVersion) {
  /**
   * Creates component coordinates with the given values.
   */
  public ComponentCoordinates {
    // Ensure non-null values for pattern matching
    namespace = namespace != null ? namespace : "";
    name = name != null ? name : "";
    kind = kind != null ? kind : "";
    version = version != null ? version : "";
    // normalizedVersion can be null when normalization is in progress
  }
  
  /**
   * Creates component coordinates with empty normalized version.
   */
  public ComponentCoordinates(String namespace, String name, String kind, String version) {
    this(namespace, name, kind, version, null);
  }
}

/**
 * Each component represents a unique logical coordinate in a repository.
 *
 * @see Asset
 */
public interface Component
    extends RepositoryContent
{
  /**
   * The component namespace; empty string if the component doesn't have a namespace.
   */
  String namespace();

  /**
   * The component name.
   */
  String name();

  /**
   * The component kind.
   *
   * @since 3.25
   */
  String kind();

  /**
   * The component version; empty string if the component doesn't have a version.
   */
  String version();

  /**
   * The component normalized version; if null then normalizeVersion task is in progress
   */
  String normalizedVersion();

  /**
   * The entity version
   */
  Integer entityVersion();
  
  /**
   * Returns the component coordinates for pattern matching.
   * 
   * @return the component coordinates
   * @since 3.41
   */
  default ComponentCoordinates coordinates() {
    return new ComponentCoordinates(namespace(), name(), kind(), version(), normalizedVersion());
  }
  
  /**
   * Returns the component coordinates without normalized version for pattern matching.
   * 
   * @return the component coordinates without normalized version
   * @since 3.41
   */
  default ComponentCoordinates basicCoordinates() {
    return new ComponentCoordinates(namespace(), name(), kind(), version());
  }

  /**
   * Returns a string representation of the component for external use.
   * 
   * @return string representation of the component
   */
  default String toStringExternal() {
    return "namespace=" + namespace() +
        ", name=" + name() +
        ", version=" + version();
  }
  
  /**
   * Checks if this component matches the given coordinates using pattern matching.
   * 
   * @param coordinates the coordinates to match against
   * @return true if the component matches the coordinates
   * @since 3.41
   */
  default boolean matches(ComponentCoordinates coordinates) {
    return coordinates.namespace().equals(namespace())
        && coordinates.name().equals(name())
        && coordinates.kind().equals(kind())
        && coordinates.version().equals(version());
  }
  
  /**
   * Checks if this component has the same namespace and name as another component.
   * Leverages pattern matching for efficient comparison.
   * 
   * @param other the component to compare with
   * @return true if namespace and name match
   * @since 3.41
   */
  default boolean hasSameNamespaceAndName(Component other) {
    if (other instanceof Component(var ns, var n, var k, var v, var nv)) {
      return namespace().equals(ns) && name().equals(n);
    }
    return false;
  }
}