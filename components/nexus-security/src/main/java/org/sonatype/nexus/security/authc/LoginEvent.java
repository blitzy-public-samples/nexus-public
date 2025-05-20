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
 * Event fired when a user successfully logs in.
 *
 * @since 3.0
 */
public final record LoginEvent(
    String principal,
    String realm
) extends SecurityEvent(principal, realm) implements Serializable {
  
  /**
   * Creates a new login event with the specified principal and realm.
   * 
   * @param principal the user principal that logged in
   * @param realm the authentication realm used for login
   */
  public LoginEvent {
    // Compact constructor for validation if needed
    if (principal == null || principal.isEmpty()) {
      throw new IllegalArgumentException("Principal cannot be null or empty");
    }
    if (realm == null || realm.isEmpty()) {
      throw new IllegalArgumentException("Realm cannot be null or empty");
    }
  }
}
