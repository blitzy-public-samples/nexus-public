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

import java.time.Instant;
import java.util.Date;
import java.util.Set;

import org.sonatype.nexus.security.ClientInfo;

import static java.lang.StringTemplate.STR;
import static java.util.Collections.emptySet;

// FIXME: Sort out why we have 2 events: NexusAuthenticationEvent and AuthenticationEvent

/**
 * Event fired when authentication validation is performed (someone tries to log in).
 * <p>
 * This class is implemented as a record for immutability and better pattern matching support in Java 21.
 */
public record NexusAuthenticationEvent(
    ClientInfo clientInfo,
    boolean successful,
    Instant eventDate,
    Set<AuthenticationFailureReason> authenticationFailureReasons)
{
  /**
   * Creates an authentication event with the specified client info and success status.
   * Uses the current timestamp and an empty set of failure reasons.
   *
   * @param info the client information
   * @param successful whether authentication was successful
   */
  public NexusAuthenticationEvent(
      final ClientInfo info,
      final boolean successful)
  {
    this(info, successful, Instant.now(), emptySet());
  }

  /**
   * Creates an authentication event with the specified client info, success status, and failure reasons.
   * Uses the current timestamp.
   *
   * @param info the client information
   * @param successful whether authentication was successful
   * @param authenticationFailureReasons the reasons for authentication failure, if any
   */
  public NexusAuthenticationEvent(
      final ClientInfo info,
      final boolean successful,
      final Set<AuthenticationFailureReason> authenticationFailureReasons)
  {
    this(info, successful, Instant.now(), authenticationFailureReasons);
  }
  
  /**
   * @return the client information
   */
  @Override
  public ClientInfo clientInfo() {
    return clientInfo;
  }
  
  /**
   * @return whether authentication was successful
   */
  @Override
  public boolean successful() {
    return successful;
  }
  
  /**
   * @return the timestamp when this event occurred
   */
  @Override
  public Instant eventDate() {
    return eventDate;
  }
  
  /**
   * @return the reasons for authentication failure, if any
   */
  @Override
  public Set<AuthenticationFailureReason> authenticationFailureReasons() {
    return authenticationFailureReasons;
  }
  
  /**
   * Legacy method to maintain backward compatibility with code expecting a Date.
   * 
   * @return the event date as a legacy Date object
   */
  public Date getEventDate() {
    return Date.from(eventDate);
  }
  
  /**
   * Legacy method to maintain backward compatibility with code expecting getClientInfo().
   * 
   * @return the client information
   */
  public ClientInfo getClientInfo() {
    return clientInfo;
  }
  
  /**
   * Legacy method to maintain backward compatibility with code expecting isSuccessful().
   * 
   * @return whether authentication was successful
   */
  public boolean isSuccessful() {
    return successful;
  }
  
  /**
   * Legacy method to maintain backward compatibility with code expecting getAuthenticationFailureReasons().
   * 
   * @return the reasons for authentication failure, if any
   */
  public Set<AuthenticationFailureReason> getAuthenticationFailureReasons() {
    return authenticationFailureReasons;
  }
  
  @Override
  public String toString() {
    String status = successful ? "successful" : "failed";
    String userId = clientInfo.getUserid() != null ? clientInfo.getUserid() : "<anonymous>";
    String failureInfo = successful || authenticationFailureReasons.isEmpty() ? ""
        : STR." (reasons: \{authenticationFailureReasons})";
    
    return STR."NexusAuthenticationEvent[\{status} authentication for user '\{userId}' from \{clientInfo.getRemoteIP()}\{failureInfo}]";
  }
}
