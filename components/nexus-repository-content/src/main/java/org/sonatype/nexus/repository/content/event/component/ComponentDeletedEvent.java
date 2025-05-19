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

import java.io.Serial;

import org.sonatype.nexus.repository.content.Component;

/**
 * Event sent whenever a {@link Component} is deleted.
 * <p>
 * Optimized for Java 21 Virtual Thread execution context with improved serialization
 * and event propagation capabilities.
 *
 * @since 3.26
 */
public class ComponentDeletedEvent
    extends ComponentEvent
{
  @Serial
  private static final long serialVersionUID = 1L;

  /**
   * Creates a new component deleted event.
   *
   * @param component the deleted component
   */
  public ComponentDeletedEvent(final Component component) {
    super(component);
  }

  @Override
  public String toString() {
    return STR."ComponentDeletedEvent{component=\{getComponent()}} \{super.toString()}";
  }
}