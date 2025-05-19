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
package org.sonatype.nexus.repository.content.fluent;

import java.util.Optional;

/**
 * Fluent API to create/find a component; at this point we already know the component name.
 * <p>
 * Implementation classes can leverage Java 21 pattern matching features for more expressive type handling:
 * <ul>
 *   <li>Pattern Matching for switch: Implementations can use switch expressions with type patterns to handle
 *       different component types more elegantly.</li>
 *   <li>Record Patterns: When component data is stored in records, implementations can use record patterns
 *       to directly access component fields in a type-safe manner.</li>
 * </ul>
 * <p>
 * Example implementation using pattern matching (Java 21):
 * <pre>{@code
 * // Using pattern matching for switch with component types
 * String processComponent(FluentComponent component) {
 *   return switch(component) {
 *     case MavenComponent mc when mc.getVersion().contains("SNAPSHOT") -> 
 *         "Maven snapshot: " + mc.getVersion();
 *     case MavenComponent mc -> 
 *         "Maven release: " + mc.getVersion();
 *     case DockerComponent dc -> 
 *         "Docker image: " + dc.getTag();
 *     default -> 
 *         "Unknown component type";
 *   };
 * }
 * 
 * // Using record patterns with component metadata
 * void handleComponentMetadata(ComponentMetadata metadata) {
 *   if (metadata instanceof ComponentRecord(String name, String version, String namespace)) {
 *     // Direct access to deconstructed record fields
 *     processComponent(name, version, namespace);
 *   }
 * }
 * }</pre>
 *
 * @since 3.21
 */
public interface FluentComponentBuilder
{
  /**
   * Continue building the component using the given namespace.
   * <p>
   * Implementation note: This method's return type supports pattern matching in Java 21,
   * allowing for more expressive type handling in switch expressions.
   */
  FluentComponentBuilder namespace(String namespace);

  /**
   * Continue building the component using the given kind.
   * <p>
   * Implementation note: This method's return type supports pattern matching in Java 21,
   * allowing for more expressive type handling in switch expressions.
   *
   * @since 3.25
   */
  FluentComponentBuilder kind(String kind);

  /**
   * Set {@code kind} only if a value is present.
   * <p>
   * Implementation note: This method's return type supports pattern matching in Java 21,
   * allowing for more expressive type handling in switch expressions.
   *
   * @since 3.29
   */
  FluentComponentBuilder kind(Optional<String> optionalKind);

  /**
   * Continue building the component using the given version.
   * <p>
   * Implementation note: This method's return type supports pattern matching in Java 21,
   * allowing for more expressive type handling in switch expressions.
   */
  FluentComponentBuilder version(String version);

  /**
   * Continue building the component using the given normalized_version.
   * <p>
   * Implementation note: This method's return type supports pattern matching in Java 21,
   * allowing for more expressive type handling in switch expressions.
   */
  FluentComponentBuilder normalizedVersion(String normalizedVersion);

  /**
   * Continue building the component using the given format attributes.
   * <p>
   * Implementation note: This method's return type supports pattern matching in Java 21,
   * allowing for more expressive type handling in switch expressions.
   *
   * @since 3.31
   */
  FluentComponentBuilder attributes(String key, Object value);

  /**
   * Gets the full component using the details built so far; if it doesn't exist then it is created.
   * <p>
   * The returned {@link FluentComponent} can be used with Java 21 pattern matching in switch expressions
   * to handle different component types in a more expressive way.
   */
  FluentComponent getOrCreate();

  /**
   * Find if a component exists using the details built so far.
   * <p>
   * The returned {@link Optional<FluentComponent>} can be unwrapped and used with Java 21 pattern matching
   * in switch expressions to handle different component types in a more expressive way.
   */
  Optional<FluentComponent> find();
}