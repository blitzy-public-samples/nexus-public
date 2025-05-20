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
package org.sonatype.nexus.security.authc;

import org.sonatype.nexus.common.event.Event;

/**
 * An event fired when the user's password has changed.
 *
 * @since 3.13
 */
public record UserPasswordChanged(
    String userId,
    boolean clearCache
) implements Event
{
  /**
   * Constructs a new event with clearCache set to true by default.
   *
   * @param userId the ID of the user whose password changed
   */
  public UserPasswordChanged(final String userId) {
    this(userId, true);
  }

  /**
   * Returns a string representation of this event using Java 21 String Templates.
   * The userId is partially masked for security purposes.
   */
  @Override
  public String toString() {
    // Use String Template to create a more readable representation
    // Mask part of the userId for security in logs
    String maskedUserId = userId != null && userId.length() > 3 ?
        userId.substring(0, 2) + "***" + (userId.length() > 5 ? userId.substring(userId.length() - 2) : "") :
        userId;
    
    return STR."UserPasswordChanged[userId=\{maskedUserId}, clearCache=\{clearCache}]";
  }
}