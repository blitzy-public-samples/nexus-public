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

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.sonatype.nexus.repository.content.Component;

/**
 * Event sent just before a {@link Component} is deleted.
 * <p>
 * This event is designed to be compatible with Java 21 Virtual Thread execution context.
 * It is immutable and thread-safe, making it suitable for concurrent processing across
 * thread boundaries. The event can be safely propagated and handled in asynchronous contexts.
 *
 * @since 3.27
 */
public class ComponentPreDeleteEvent
    extends ComponentEvent
{
  private final Instant deletionTimestamp;

  /**
   * Creates a new pre-delete event for the given component.
   *
   * @param component the component being deleted
   */
  public ComponentPreDeleteEvent(final Component component) {
    super(component);
    this.deletionTimestamp = Instant.now();
  }

  /**
   * Returns the timestamp when this deletion event was created.
   *
   * @return the deletion timestamp
   */
  public Instant getDeletionTimestamp() {
    return deletionTimestamp;
  }

  /**
   * Factory method to create and dispatch a ComponentPreDeleteEvent in a Virtual Thread context.
   * This method leverages Java 21 Virtual Threads for efficient event creation and propagation.
   *
   * @param component the component being deleted
   * @param eventConsumer the consumer that will process the event
   */
  public static void fireAsync(final Component component, final java.util.function.Consumer<ComponentPreDeleteEvent> eventConsumer) {
    Executor executor = Executors.newVirtualThreadPerTaskExecutor();
    executor.execute(() -> {
      ComponentPreDeleteEvent event = new ComponentPreDeleteEvent(component);
      eventConsumer.accept(event);
    });
  }

  @Override
  public String toString() {
    return STR."""
        ComponentPreDeleteEvent{
          component=\{getComponent()}
          deletionTimestamp=\{deletionTimestamp}
        } \{super.toString()}
        """;
  }
}
