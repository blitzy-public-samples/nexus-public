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

/**
 * Event fired when a user successfully logs in.
 *
 * @since 3.0
 */
public final record LoginEvent(
    String principal,
    String realm
) implements java.io.Serializable, SecurityEvent {
  
  /**
   * Pattern matching example for working with LoginEvent records.
   * 
   * @param event The security event to check
   * @return true if this is a login event with a non-empty principal
   */
  public static boolean isValidLoginEvent(SecurityEvent event) {
    return switch(event) {
      case LoginEvent(String principal, String realm) when principal != null && !principal.isEmpty() -> true;
      default -> false;
    };
  }
}