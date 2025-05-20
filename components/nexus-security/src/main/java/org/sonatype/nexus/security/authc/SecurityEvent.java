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
 * Base record for all security-related events in the Nexus security subsystem.
 * Provides immutable storage of principal and realm information.
 *
 * @since 3.0
 */
public sealed abstract record SecurityEvent(
    String principal,
    String realm
) implements Serializable permits LoginEvent, LogoutEvent {

  /**
   * Returns a string representation of this security event using Java 21 String Templates.
   */
  @Override
  public String toString() {
    return STR."\{getClass().getSimpleName()}[principal=\{principal}, realm=\{realm}]"; 
  }
}