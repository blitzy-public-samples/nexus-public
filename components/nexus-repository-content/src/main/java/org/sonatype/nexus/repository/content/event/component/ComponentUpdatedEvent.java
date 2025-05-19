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
 * Event sent whenever a {@link Component} is updated.
 *
 * @since 3.26
 */
public class ComponentUpdatedEvent
    extends ComponentEvent
    implements Serializable
{
  private static final long serialVersionUID = 1L;

  /**
   * Creates a new ComponentUpdatedEvent.
   *
   * @param component the updated component
   */
  public ComponentUpdatedEvent(final Component component) {
    super(component);
  }

  /**
   * Factory method to create a new ComponentUpdatedEvent optimized for Virtual Thread execution.
   * This method ensures the event is properly initialized for propagation across thread boundaries.
   *
   * @param component the updated component
   * @return a new ComponentUpdatedEvent instance
   */
  public static ComponentUpdatedEvent of(final Component component) {
    return new ComponentUpdatedEvent(component);
  }
}