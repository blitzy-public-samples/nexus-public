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
 * Event fired to signal that the system should exit a frozen state normally.
 * <p>
 * This event is part of the system freeze mechanism that manages the lifecycle of freeze operations.
 * When this event is published, listeners will release locks and allow normal operations to resume
 * in an orderly manner, completing any pending operations before fully releasing the freeze state.
 * <p>
 * This class is designed to work with Java 21's sealed class pattern as one of the permitted
 * subclasses of the {@link FreezeEvent} sealed hierarchy. It represents the normal completion
 * event in the freeze lifecycle, signaling that the system can safely resume normal operations.
 * <p>
 * In Java 21 environments, this event can be efficiently pattern-matched in switch expressions:
 * <pre>
 * {@code
 * switch (event) {
 *   case FreezeRequestEvent e -> handleFreezeRequest(e.getReason());
 *   case FreezeReleaseEvent e -> handleNormalRelease();
 *   case FreezeForceReleaseEvent e -> handleForcedRelease();
 * }
 * }</pre>
 * <p>
 * For systems that need to distinguish between normal and forced releases, this event
 * represents the standard, controlled release path as opposed to the {@link FreezeForceReleaseEvent}
 * which bypasses normal release procedures.
 * <p>
 * Event handlers for this event can be safely executed on virtual threads, as indicated by
 * the {@link VirtualThreadCompatible} annotation on applicable methods in the event hierarchy.
 * This allows for more efficient processing of freeze events in high-concurrency scenarios.
 *
 * @since 3.0
 */
public final class FreezeReleaseEvent
    extends FreezeEvent
{
  /**
   * Creates a new freeze release event.
   * <p>
   * This constructor initializes the event with the {@link FreezeEventTypes#RELEASE} type,
   * indicating a normal, controlled release of a system freeze.
   * <p>
   * When published, this event signals to all listeners that normal operations should resume
   * in an orderly manner, completing any pending operations before fully releasing the freeze state.
   */
  public FreezeReleaseEvent() {
    super(FreezeEventTypes.RELEASE);
  }
}
