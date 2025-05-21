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
 * Event fired to request that the system enters a frozen state.
 * <p>
 * This event is part of the system freeze mechanism that temporarily suspends certain operations
 * to ensure data consistency during maintenance or backup operations.
 * <p>
 * Compatible with Java 21 runtime environment and designed to work with the sealed class pattern
 * when the base {@link FreezeEvent} class is updated to use this feature.
 *
 * @since 3.0
 */
public class FreezeRequestEvent
    extends FreezeEvent
{
  private final String reason;

  /**
   * Creates a new freeze request event with the specified reason.
   * <p>
   * The reason is used for auditing and logging purposes to document why the system
   * was placed in a frozen state.
   *
   * @param reason a human-readable description of why the system is being frozen
   */
  public FreezeRequestEvent(final String reason) {
    super(FreezeEventTypes.FREEZE);
    this.reason = reason;
  }

  /**
   * Returns the reason for this freeze request.
   * <p>
   * When logging this reason, Java 21 String Templates should be used for structured logging
   * with proper escaping and context preservation.
   *
   * @return the reason provided when creating this event
   */
  public String getReason() {
    return reason;
  }
}