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

import java.util.Arrays;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.HostAuthenticationToken;

import static java.lang.StringTemplate.STR;

/**
 * {@link AuthenticationToken} that contains credentials from a known API-Key.
 * <p>
 * This implementation uses char[] for credentials to allow secure handling and
 * proper clearing of sensitive data from memory.
 */
public class NexusApiKeyAuthenticationToken
    implements HostAuthenticationToken
{
  private Object principal;

  private final char[] credentials;

  private final String host;

  /**
   * Creates a new authentication token with the given principal, credentials, and host.
   *
   * @param principal the principal identifying the user
   * @param credentials the credentials verifying the user identity
   * @param host the host from which the authentication attempt originates
   */
  public NexusApiKeyAuthenticationToken(final Object principal, final char[] credentials, final String host) {
    this.principal = principal;
    // Create a defensive copy of the credentials to prevent external modification
    this.credentials = (credentials != null) ? Arrays.copyOf(credentials, credentials.length) : null;
    this.host = host;
  }

  /**
   * Returns the principal, or subject, of the authentication token.
   * 
   * @return the principal
   */
  public Object getPrincipal() {
    return principal;
  }

  /**
   * Returns the credentials for the authentication token.
   * <p>
   * Note: The returned array should not be modified and should be cleared
   * after use for security reasons.
   * 
   * @return the credentials as a char array
   */
  public Object getCredentials() {
    return credentials;
  }

  /**
   * Returns the host from which the authentication attempt originates.
   * 
   * @return the host name or IP address
   */
  public String getHost() {
    return host;
  }

  /**
   * Assigns a new account identity to the current authentication token.
   * 
   * @param principal the new principal to set
   */
  public void setPrincipal(final Object principal) {
    this.principal = principal;
  }
  
  /**
   * Clears the credentials from memory for security purposes.
   * This method should be called when the credentials are no longer needed.
   */
  public void clearCredentials() {
    if (credentials != null) {
      Arrays.fill(credentials, '\0');
    }
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for improved security logging
    // Intentionally not including credentials in the string representation
    return STR."""{getClass().getName()} - {getPrincipal()}{host != null ? STR." ({host})" : ""}"""; 
  }
}