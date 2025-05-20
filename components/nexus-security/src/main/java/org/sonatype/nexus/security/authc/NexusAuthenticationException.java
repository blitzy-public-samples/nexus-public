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

import java.util.Set;

import org.apache.shiro.authc.AccountException;

/**
 * Exception with details about a failed authentication attempt.
 *
 * @since 3.22
 */
public class NexusAuthenticationException
    extends AccountException
{
  private static final long serialVersionUID = 1L;

  private final Set<AuthenticationFailureReason> authenticationFailureReasons;

  /**
   * Constructs a new exception with the specified cause and authentication failure reasons.
   *
   * @param cause the cause message for the exception
   * @param authenticationFailureReasons the set of reasons why authentication failed
   */
  public NexusAuthenticationException(
      final String cause,
      final Set<AuthenticationFailureReason> authenticationFailureReasons)
  {
    super(cause);
    this.authenticationFailureReasons = authenticationFailureReasons;
  }

  /**
   * Returns the set of reasons why authentication failed.
   *
   * @return the set of authentication failure reasons
   */
  public Set<AuthenticationFailureReason> getAuthenticationFailureReasons() {
    return authenticationFailureReasons;
  }

  /**
   * Creates a formatted message using String Templates that includes all failure reasons.
   * 
   * @return a detailed error message including all failure reasons
   * @since 3.60
   */
  @Override
  public String getMessage() {
    if (authenticationFailureReasons == null || authenticationFailureReasons.isEmpty()) {
      return super.getMessage();
    }
    
    return STR."""
        Authentication failed: {super.getMessage()}
        Failure reasons: {authenticationFailureReasons}
        """;
  }
}