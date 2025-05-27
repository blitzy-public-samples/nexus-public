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
 * Event fired to signal that the system should enter a frozen state.
 * <p>
 * This event is part of the system freeze mechanism that manages the lifecycle of freeze operations.
 * When this event is published, listeners will acquire locks and prevent certain operations from
 * proceeding until a corresponding {@link FreezeReleaseEvent} or {@link FreezeForceReleaseEvent}
 * is published.
 * <p>
 * This class is designed to work with Java 21's sealed class pattern as one of the permitted
 * subclasses of the {@link FreezeEvent} sealed hierarchy. It represents the initial event in the
 * freeze lifecycle, carrying a reason for the freeze operation that can be logged or displayed
 * to users.
 * <p>
 * In Java 21 environments, this event can be efficiently pattern-matched in switch expressions:
 * <pre>
 * {@code
 * switch (event) {
 *   case FreezeRequestEvent e -> {
 *     String reason = e.getReason();
 *     log.info(STR."System freeze requested: \{reason}");
 *     handleFreezeRequest(reason);
 *   }
 *   case FreezeReleaseEvent e -> handleNormalRelease();
 *   case FreezeForceReleaseEvent e -> handleForcedRelease();
 * }
 * }</pre>
 * <p>
 * The reason field can be efficiently formatted in logs using Java 21's String Templates (STR):
 * <pre>
 * {@code
 * String reason = event.getReason();
 * log.info(STR."System freeze initiated for: \{reason}");
 * }</pre>
 * <p>
 * Event handlers for this event can be safely executed on virtual threads, as indicated by
 * the {@link VirtualThreadCompatible} annotation on applicable methods in the event hierarchy.
 *
 * @since 3.0
 */
public final class FreezeRequestEvent
    extends FreezeEvent
{
  /**
   * The reason for the system freeze request.
   * <p>
   * This field stores a human-readable explanation of why the system is being frozen,
   * which can be used for logging, auditing, or user notifications.
   */
  private final String reason;

  /**
   * Creates a new freeze request event with the specified reason.
   * <p>
   * This constructor initializes the event with the {@link FreezeEventTypes#FREEZE} type
   * and stores the provided reason for the freeze operation.
   * <p>
   * When published, this event signals to all listeners that certain operations should
   * be suspended until a corresponding release event is published.
   *
   * @param reason a human-readable explanation of why the system is being frozen
   */
  public FreezeRequestEvent(final String reason) {
    super(FreezeEventTypes.FREEZE);
    this.reason = reason;
  }

  /**
   * Returns the reason for this freeze request.
   * <p>
   * The reason provides context about why the system is being frozen, which can be
   * used for logging, auditing, or user notifications.
   * <p>
   * In Java 21 environments, this value can be efficiently formatted using String Templates:
   * <pre>
   * {@code
   * String reason = event.getReason();
   * log.info(STR."System freeze initiated for: \{reason}");
   * }</pre>
   *
   * @return the reason for the freeze request
   */
  @VirtualThreadCompatible
  public String getReason() {
    return reason;
  }
}
