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

/**
 * The reason why an authentication attempt failed.
 *
 * @since 3.22
 */
public enum AuthenticationFailureReason
{
  USER_NOT_FOUND,
  PASSWORD_EMPTY,
  INCORRECT_CREDENTIALS,
  DISABLED_ACCOUNT,
  LICENSE_LIMITATION,
  EXPIRED_CREDENTIALS,
  UNKNOWN;
  
  /**
   * Returns a user-friendly description of the authentication failure reason.
   * 
   * @return a user-friendly description
   */
  public String getDescription() {
    return switch (this) {
      case USER_NOT_FOUND -> "User account not found";
      case PASSWORD_EMPTY -> "Password is empty";
      case INCORRECT_CREDENTIALS -> "Incorrect credentials provided";
      case DISABLED_ACCOUNT -> "User account is disabled";
      case LICENSE_LIMITATION -> "License limitation reached";
      case EXPIRED_CREDENTIALS -> "Credentials have expired";
      case UNKNOWN -> "Unknown authentication failure";
    };
  }
  
  /**
   * Demonstrates pattern matching for switch with an Object parameter.
   * Returns a description based on the type of the object and its value.
   * 
   * @param obj the object to match against
   * @return a description based on the object type and value
   */
  public static String describeFailure(Object obj) {
    return switch (obj) {
      case AuthenticationFailureReason reason -> reason.getDescription();
      case String s when s.equalsIgnoreCase("user_not_found") -> USER_NOT_FOUND.getDescription();
      case String s when s.equalsIgnoreCase("password_empty") -> PASSWORD_EMPTY.getDescription();
      case String s -> "Unrecognized failure reason: " + s;
      case Set<?> set when !set.isEmpty() -> "Multiple failure reasons: " + set;
      case null -> "No failure reason provided";
      default -> "Unsupported failure reason type: " + obj.getClass().getSimpleName();
    };
  }
  
  /**
   * Creates an error message for the given authentication failure reason using String Templates.
   * This demonstrates the use of Java 21's String Templates feature.
   * 
   * @param username the username that failed authentication
   * @return an error message with the username and failure reason
   */
  public String createErrorMessage(String username) {
    return STR."Authentication failed for user '\{username}': \{getDescription()}";
  }
  
  /**
   * Creates a detailed error message with additional context using String Templates.
   * 
   * @param username the username that failed authentication
   * @param attemptCount the number of failed authentication attempts
   * @param ipAddress the IP address from which the authentication attempt was made
   * @return a detailed error message with all context information
   */
  public String createDetailedErrorMessage(String username, int attemptCount, String ipAddress) {
    return STR."Authentication failed for user '\{username}' (attempt #\{attemptCount} from IP \{ipAddress}): \{getDescription()}";
  }
}