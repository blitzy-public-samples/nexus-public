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
import java.time.Instant;
import java.util.Set;

import org.sonatype.nexus.security.ClientInfo;

import static java.util.Collections.emptySet;

// FIXME: Sort out why we have 2 events: NexusAuthenticationEvent and AuthenticationEvent

/**
 * Event fired when authentication validation is performed (someone tries to log in).
 * <p>
 * This class uses Java 21 Record feature for immutability and concise representation.
 * Consumers can use Record Patterns for type-safe, destructuring access to the event data:
 * <pre>
 * if (event instanceof NexusAuthenticationEvent(var clientInfo, var successful, var eventDate, var reasons)) {
 *   // Use the extracted components directly
 *   if (successful) {
 *     log.info("User {} logged in at {}", clientInfo.getUserid(), eventDate);
 *   } else {
 *     log.warn("Login failed for user {} with reasons: {}", clientInfo.getUserid(), reasons);
 *   }
 * }
 * </pre>
 */
public record NexusAuthenticationEvent(
    ClientInfo clientInfo,
    boolean successful,
    Instant eventDate,
    Set<AuthenticationFailureReason> authenticationFailureReasons) 
    implements Serializable
{
  private static final long serialVersionUID = 1L;

  /**
   * Constructor with successful flag and empty failure reasons.
   */
  public NexusAuthenticationEvent(
      final ClientInfo info,
      final boolean successful)
  {
    this(info, successful, Instant.now(), emptySet());
  }

  /**
   * Constructor with successful flag and specified failure reasons.
   */
  public NexusAuthenticationEvent(
      final ClientInfo info,
      final boolean successful,
      final Set<AuthenticationFailureReason> authenticationFailureReasons)
  {
    this(info, successful, Instant.now(), authenticationFailureReasons);
  }

  /**
   * Returns whether the authentication was successful.
   * 
   * @return true if authentication was successful, false otherwise
   */
  public boolean isSuccessful() {
    return successful;
  }
  
  /**
   * Returns the client information.
   * Provided for backward compatibility with pre-record code.
   * 
   * @return the client information
   */
  public ClientInfo getClientInfo() {
    return clientInfo;
  }
  
  /**
   * Returns the event date.
   * Provided for backward compatibility with pre-record code.
   * 
   * @return the event date as an Instant
   */
  public Instant getEventDate() {
    return eventDate;
  }
  
  /**
   * Returns the authentication failure reasons.
   * Provided for backward compatibility with pre-record code.
   * 
   * @return the set of authentication failure reasons
   */
  public Set<AuthenticationFailureReason> getAuthenticationFailureReasons() {
    return authenticationFailureReasons;
  }

  /**
   * Returns a formatted string representation of this event using Java 21 String Templates.
   */
  @Override
  public String toString() {
    String userInfo = clientInfo != null ? clientInfo.getUserid() : "unknown";
    String ipInfo = clientInfo != null ? clientInfo.getRemoteIP() : "unknown";
    
    return STR."""
        NexusAuthenticationEvent {
          user: \{userInfo}
          ip: \{ipInfo}
          successful: \{successful}
          timestamp: \{eventDate}
          \{successful ? "" : "failure reasons: " + authenticationFailureReasons}
        }""";
  }
}