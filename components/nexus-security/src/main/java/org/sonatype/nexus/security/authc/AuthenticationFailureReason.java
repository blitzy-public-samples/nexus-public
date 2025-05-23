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
 * The reason why an authentication attempt failed.
 *
 * @since 3.22
 */
public enum AuthenticationFailureReason
{
  /**
   * The user was not found in any security realm.
   */
  USER_NOT_FOUND,
  
  /**
   * The password provided was empty.
   */
  PASSWORD_EMPTY,
  
  /**
   * The credentials provided were incorrect.
   */
  INCORRECT_CREDENTIALS,
  
  /**
   * The user account is disabled.
   */
  DISABLED_ACCOUNT,
  
  /**
   * Authentication failed due to license limitations.
   */
  LICENSE_LIMITATION,
  
  /**
   * The credentials have expired.
   */
  EXPIRED_CREDENTIALS,
  
  /**
   * The reason for authentication failure is unknown.
   */
  UNKNOWN;
  
  /**
   * Returns a user-friendly error message for this authentication failure reason.
   *
   * @return a descriptive error message
   */
  public String getErrorMessage() {
    return switch (this) {
      case USER_NOT_FOUND -> "User not found in any security realm";
      case PASSWORD_EMPTY -> "Password cannot be empty";
      case INCORRECT_CREDENTIALS -> "The provided credentials are incorrect";
      case DISABLED_ACCOUNT -> "The user account is disabled";
      case LICENSE_LIMITATION -> "Authentication failed due to license limitations";
      case EXPIRED_CREDENTIALS -> "The credentials have expired";
      case UNKNOWN -> "Authentication failed due to an unknown reason";
    };
  }
  
  /**
   * Returns a detailed error message with additional context.
   *
   * @param username the username that failed authentication
   * @return a detailed error message with username context
   */
  public String getDetailedErrorMessage(String username) {
    return switch (this) {
      case USER_NOT_FOUND -> "Authentication failed: User '" + username + "' not found in any security realm";
      case PASSWORD_EMPTY -> "Authentication failed for user '" + username + "': Password cannot be empty";
      case INCORRECT_CREDENTIALS -> "Authentication failed for user '" + username + "': The provided credentials are incorrect";
      case DISABLED_ACCOUNT -> "Authentication failed for user '" + username + "': The account is disabled";
      case LICENSE_LIMITATION -> "Authentication failed for user '" + username + "': License limitation reached";
      case EXPIRED_CREDENTIALS -> "Authentication failed for user '" + username + "': The credentials have expired";
      case UNKNOWN -> "Authentication failed for user '" + username + "': Unknown reason";
    };
  }
  
  /**
   * Returns a log-friendly error code for this authentication failure reason.
   *
   * @return an error code suitable for logging
   */
  public String getErrorCode() {
    return switch (this) {
      case USER_NOT_FOUND -> "AUTH-001";
      case PASSWORD_EMPTY -> "AUTH-002";
      case INCORRECT_CREDENTIALS -> "AUTH-003";
      case DISABLED_ACCOUNT -> "AUTH-004";
      case LICENSE_LIMITATION -> "AUTH-005";
      case EXPIRED_CREDENTIALS -> "AUTH-006";
      case UNKNOWN -> "AUTH-999";
    };
  }
  
  /**
   * Determines if the failure is related to invalid credentials.
   *
   * @return true if the failure is related to invalid credentials
   */
  public boolean isCredentialRelated() {
    return switch (this) {
      case PASSWORD_EMPTY, INCORRECT_CREDENTIALS, EXPIRED_CREDENTIALS -> true;
      case USER_NOT_FOUND, DISABLED_ACCOUNT, LICENSE_LIMITATION, UNKNOWN -> false;
    };
  }
  
  /**
   * Determines if the failure is related to account status.
   *
   * @return true if the failure is related to account status
   */
  public boolean isAccountRelated() {
    return switch (this) {
      case DISABLED_ACCOUNT, LICENSE_LIMITATION -> true;
      case USER_NOT_FOUND, PASSWORD_EMPTY, INCORRECT_CREDENTIALS, EXPIRED_CREDENTIALS, UNKNOWN -> false;
    };
  }
}