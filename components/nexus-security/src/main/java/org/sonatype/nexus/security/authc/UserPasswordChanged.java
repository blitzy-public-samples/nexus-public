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

import java.io.Serializable;

/**
 * An event fired when the user's password has changed.
 * <p>
 * This is an immutable record that represents a password change event in the security system.
 * It is used by the authentication system to trigger necessary actions after a password change,
 * such as clearing security caches to ensure the new password takes effect immediately.
 * <p>
 * As a record, this class provides built-in immutability, equals/hashCode, and pattern matching
 * capabilities for more maintainable event handling in the security subsystem.
 *
 * @since 3.13
 */
public record UserPasswordChanged(String userId, boolean clearCache) implements Serializable {
  
  /**
   * Creates a new password changed event with cache clearing enabled by default.
   *
   * @param userId the ID of the user whose password has changed
   */
  public UserPasswordChanged(final String userId) {
    this(userId, true);
  }
  
  /**
   * Returns a string representation of this event using Java 21 String Templates.
   * This provides improved logging and debugging capabilities.
   *
   * @return a string representation of this event
   */
  @Override
  public String toString() {
    return STR."UserPasswordChanged[userId=\{userId}, clearCache=\{clearCache}]";
  }
}