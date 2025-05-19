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
import org.sonatype.nexus.repository.content.ComponentCoordinates;

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
   * Creates a new ComponentKindEvent.
   *
   * @param component the component with updated kind
   */
  public ComponentKindEvent(final Component component) {
    super(component);
  }

  /**
   * Factory method to create a new ComponentKindEvent optimized for Virtual Thread execution.
   * This method ensures the event is properly initialized for propagation across thread boundaries.
   *
   * @param component the component with updated kind
   * @return a new ComponentKindEvent instance
   */
  public static ComponentKindEvent of(final Component component) {
    return new ComponentKindEvent(component);
  }

  /**
   * Checks if the component's kind matches the specified kind using pattern matching.
   *
   * @param kind the kind to check against
   * @return true if the component's kind matches
   */
  public boolean isKind(final String kind) {
    Component component = getComponent();
    return switch (component) {
      case Component c when kind.equals(c.kind()) -> true;
      default -> false;
    };
  }

  /**
   * Checks if the component's kind is one of the specified kinds using pattern matching.
   *
   * @param kinds the kinds to check against
   * @return true if the component's kind matches any of the specified kinds
   */
  public boolean isKindOneOf(final String... kinds) {
    String componentKind = getComponent().kind();
    for (String kind : kinds) {
      if (kind.equals(componentKind)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Gets the component's kind using pattern matching.
   *
   * @return the component's kind
   */
  public String getKind() {
    return switch (getComponent().coordinates()) {
      case ComponentCoordinates(var namespace, var name, var kind, var version, var normalizedVersion) -> kind;
    };
  }

  /**
   * Returns a formatted string representation of the component kind event using string templates.
   *
   * @return formatted string representation
   */
  @Override
  public String toString() {
    Component component = getComponent();
    return STR."ComponentKindEvent{component=\{component.toStringExternal()}, kind=\{component.kind()}}";
  }
}
