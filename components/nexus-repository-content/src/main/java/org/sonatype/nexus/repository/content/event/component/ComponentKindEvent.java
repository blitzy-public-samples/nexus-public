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
package org.sonatype.nexus.repository.content.event.component;

import java.io.Serializable;

import org.sonatype.nexus.repository.content.Component;

/**
 * Event sent whenever a {@link Component}'s kind is updated.
 * Enhanced with Java 21 pattern matching for kind type checks and Virtual Thread compatibility.
 *
 * @since 3.26
 */
public class ComponentKindEvent
    extends ComponentUpdatedEvent
    implements Serializable
{
  private static final long serialVersionUID = 1L;

  /**
   * Creates a new component kind event.
   *
   * @param component the component whose kind was updated
   */
  public ComponentKindEvent(final Component component) {
    super(component);
  }

  /**
   * Checks if the component kind matches the specified kind using pattern matching.
   *
   * @param expectedKind the kind to check against
   * @return true if the component's kind matches the expected kind
   */
  public boolean matchesKind(final String expectedKind) {
    return switch (getComponent().kind()) {
      case String kind when kind.equals(expectedKind) -> true;
      default -> false;
    };
  }

  /**
   * Categorizes the component kind using pattern matching.
   *
   * @return a category description based on the component kind
   */
  public String categorizeKind() {
    return switch (getComponent().kind()) {
      case String kind when kind.isEmpty() -> "uncategorized";
      case String kind when kind.startsWith("maven") -> "maven-related";
      case String kind when kind.startsWith("npm") -> "npm-related";
      case String kind when kind.startsWith("docker") -> "docker-related";
      case String kind when kind.startsWith("nuget") -> "nuget-related";
      case String kind when kind.startsWith("raw") -> "raw-content";
      case String kind when kind.startsWith("apt") -> "apt-related";
      case String kind when kind.startsWith("yum") -> "yum-related";
      case String kind when kind.startsWith("pypi") -> "python-related";
      case String kind when kind.startsWith("rubygems") -> "ruby-related";
      case String kind when kind.startsWith("helm") -> "helm-related";
      case String kind when kind.startsWith("r") -> "r-related";
      case String kind when kind.startsWith("p2") -> "p2-related";
      case String kind when kind.startsWith("conda") -> "conda-related";
      case String kind when kind.startsWith("go") -> "go-related";
      default -> "other";
    };
  }

  /**
   * Checks if the component kind is of a specific category using pattern matching.
   *
   * @param category the category to check
   * @return true if the component kind belongs to the specified category
   */
  public boolean isKindOfCategory(final String category) {
    return switch (category) {
      case "maven" -> matchesKindPattern(k -> k.startsWith("maven"));
      case "npm" -> matchesKindPattern(k -> k.startsWith("npm"));
      case "docker" -> matchesKindPattern(k -> k.startsWith("docker"));
      case "nuget" -> matchesKindPattern(k -> k.startsWith("nuget"));
      case "raw" -> matchesKindPattern(k -> k.startsWith("raw"));
      case "apt" -> matchesKindPattern(k -> k.startsWith("apt"));
      case "yum" -> matchesKindPattern(k -> k.startsWith("yum"));
      case "pypi" -> matchesKindPattern(k -> k.startsWith("pypi"));
      case "rubygems" -> matchesKindPattern(k -> k.startsWith("rubygems"));
      case "helm" -> matchesKindPattern(k -> k.startsWith("helm"));
      case "r" -> matchesKindPattern(k -> k.startsWith("r"));
      case "p2" -> matchesKindPattern(k -> k.startsWith("p2"));
      case "conda" -> matchesKindPattern(k -> k.startsWith("conda"));
      case "go" -> matchesKindPattern(k -> k.startsWith("go"));
      case "empty" -> matchesKindPattern(String::isEmpty);
      default -> false;
    };
  }

  /**
   * Utility method to check if the component kind matches a pattern using a functional interface.
   *
   * @param matcher the function to match against the kind
   * @return true if the component kind matches the pattern
   */
  private boolean matchesKindPattern(java.util.function.Predicate<String> matcher) {
    return switch (getComponent().kind()) {
      case String kind when matcher.test(kind) -> true;
      default -> false;
    };
  }

  /**
   * Returns a formatted description of the component kind event using Java 21 String templates.
   *
   * @return a formatted description of the event
   */
  public String getFormattedDescription() {
    Component component = getComponent();
    String kind = component.kind();
    String category = categorizeKind();
    
    return STR."Component \{component.namespace()}:\{component.name()}:\{component.version()} "
        + STR."kind updated to '\{kind}' (\{category})";
  }

  @Override
  public String toString() {
    return STR."ComponentKindEvent{component=\{getComponent()}, kind=\{getComponent().kind()}}";
  }
}
