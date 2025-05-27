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

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.sonatype.nexus.repository.content.Component;

/**
 * Event sent just before a {@link Component} is deleted.
 * <p>
 * This event is optimized for Java 21 Virtual Thread execution context and can be safely
 * processed by event handlers running on Virtual Threads. The event propagation is designed
 * to work efficiently in high-concurrency environments by leveraging non-blocking operations.
 *
 * @since 3.27
 */
public class ComponentPreDeleteEvent
    extends ComponentEvent
{
  /**
   * Virtual Thread executor for asynchronous event processing.
   * This allows event handlers to efficiently process events without blocking platform threads.
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Creates a new component pre-delete event.
   *
   * @param component the component being deleted
   */
  public ComponentPreDeleteEvent(final Component component) {
    super(component);
  }

  /**
   * Submits an asynchronous task to be executed in a Virtual Thread context.
   * This method is useful for event handlers that need to perform I/O operations
   * in response to this event without blocking the event dispatch thread.
   *
   * @param task the task to execute asynchronously
   */
  public void submitVirtualThreadTask(final Runnable task) {
    VIRTUAL_THREAD_EXECUTOR.execute(task);
  }

  /**
   * Returns the component that will be deleted.
   * This method is optimized for use with Java 21 Pattern Matching.
   *
   * @return the component being deleted
   */
  @Override
  public Component getComponent() {
    return super.getComponent();
  }
}