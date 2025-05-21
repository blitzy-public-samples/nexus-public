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
 * Event fired to signal that the system should forcefully exit a frozen state.
 * <p>
 * This event is part of the system freeze mechanism that manages the lifecycle of freeze operations.
 * When this event is published, listeners will immediately release locks and allow normal operations to resume,
 * bypassing any pending operations or safeguards that might be in place during a normal release.
 * <p>
 * This class is designed to work with Java 21's sealed class pattern when the base {@link FreezeEvent}
 * class is updated to use this feature. It represents one of the permitted subclasses in the
 * freeze event hierarchy, specifically handling forced release operations that override normal
 * release procedures.
 * <p>
 * In Java 21 environments, this event can be efficiently pattern-matched in switch expressions:
 * <pre>
 * {@code
 * switch (event) {
 *   case FreezeReleaseEvent e -> handleNormalRelease();
 *   case FreezeForceReleaseEvent e -> handleForcedRelease();
 *   case FreezeRequestEvent e -> handleFreezeRequest(e.getReason());
 *   default -> throw new IllegalStateException("Unknown freeze event type");
 * }
 * }</pre>
 *
 * @since 3.0
 */
public final class FreezeForceReleaseEvent
    extends FreezeEvent
{
  /**
   * Creates a new freeze force release event.
   * <p>
   * This constructor initializes the event with the {@link FreezeEventTypes#FORCE_RELEASE} type,
   * indicating an administrative override or emergency release of a system freeze.
   * <p>
   * When published, this event signals to all listeners that normal operations should resume
   * immediately, bypassing any pending operations or safeguards that might be in place during
   * a normal release.
   */
  public FreezeForceReleaseEvent() {
    super(FreezeEventTypes.FORCE_RELEASE);
  }
}
