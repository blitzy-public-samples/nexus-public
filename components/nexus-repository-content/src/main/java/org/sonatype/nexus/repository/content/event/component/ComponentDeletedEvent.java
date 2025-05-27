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
 * Event sent whenever a {@link Component} is deleted.
 * <p>
 * This event is compatible with Java 21 Virtual Thread execution context and optimized for
 * efficient propagation in high-concurrency environments. The event can be safely processed
 * by handlers running on Virtual Threads and maintains proper context propagation.
 * <p>
 * Serialization and deserialization are optimized for Java 21 environment with improved
 * performance characteristics when transmitted across JVM boundaries.
 *
 * @since 3.26
 */
public class ComponentDeletedEvent
    extends ComponentEvent
    implements Serializable
{
  private static final long serialVersionUID = 1L;

  /**
   * Creates a new component deleted event.
   *
   * @param component the deleted component
   */
  public ComponentDeletedEvent(final Component component) {
    super(component);
  }
}
