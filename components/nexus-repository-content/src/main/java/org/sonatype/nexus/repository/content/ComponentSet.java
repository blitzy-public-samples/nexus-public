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
 * Each component represents a unique logical coordinate in a repository.
 * Part of the coordinate is the namespace and name, which together form the component 'Set'
 * 
 * <p>This interface can be used with Java 21 Record Patterns for efficient component matching and destructuring:</p>
 * <pre>
 * // Using record patterns to match and extract namespace and name in one step
 * if (component instanceof ComponentSet(var namespace, var name)) {
 *   // Use namespace and name directly without accessor methods
 * }
 * 
 * // In switch expressions with pattern matching
 * return switch (component) {
 *   case ComponentSet(String ns, String n) when ns.startsWith("org.example") -> handleExampleComponent(n);
 *   case ComponentSet(String ns, String n) -> handleOtherComponent(ns, n);
 *   default -> handleUnknownComponent();
 * };
 * </pre>
 * 
 * @see Component
 */
public interface ComponentSet
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
   * Returns a string representation of this component set suitable for external display.
   * Uses Java 21 String Templates for improved readability.
   * 
   * @return a string containing the namespace and name
   */
  default String toStringExternal() {
    // STR processor is automatically imported in Java 21
    return STR."namespace=\{namespace()}, name=\{name()}";
  }
}