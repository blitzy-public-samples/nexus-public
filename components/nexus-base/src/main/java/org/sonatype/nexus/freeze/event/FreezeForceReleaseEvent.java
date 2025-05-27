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
package org.sonatype.nexus.freeze.event;

/**
 * Event fired when a system freeze is forcibly released.
 * <p>
 * This event is triggered when a system freeze is released through administrative
 * intervention or an emergency procedure, rather than through the normal release process.
 * <p>
 * As a permitted subclass of the sealed {@link FreezeEvent} class, this event can be
 * efficiently handled using Java 21's pattern matching in switch expressions:
 * <pre>{@code
 * void handleFreezeEvent(FreezeEvent event) {
 *   switch (event) {
 *     case FreezeForceReleaseEvent e -> {
 *       log.warn("System freeze force-released");
 *       // Handle forced release
 *     }
 *     case FreezeRequestEvent e -> { /* handle request */ }
 *     case FreezeReleaseEvent e -> { /* handle normal release */ }
 *   }
 * }
 * }</pre>
 * <p>
 * This class is designed to be compatible with Java 21's virtual threads and can be safely
 * used in high-concurrency scenarios. Event handlers processing this event type can be
 * executed on virtual threads without blocking concerns.
 *
 * @since 3.0
 */
@FreezeEvent.VirtualThreadCompatible("Safe for processing on virtual threads with no blocking operations")
public final class FreezeForceReleaseEvent
    extends FreezeEvent
{
  /**
   * Creates a new force release event.
   * <p>
   * This constructor initializes the event with the {@link FreezeEventTypes#FORCE_RELEASE} type.
   */
  public FreezeForceReleaseEvent() {
    super(FreezeEventTypes.FORCE_RELEASE);
  }
}
