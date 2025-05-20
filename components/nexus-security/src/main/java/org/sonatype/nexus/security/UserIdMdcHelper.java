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
package org.sonatype.nexus.security;

import com.google.common.base.Strings;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.security.UserIdHelper.SYSTEM;
import static org.sonatype.nexus.security.UserIdHelper.UNKNOWN;

/**
 * Helper to set the {@code userId} MDC attribute.
 * Optimized for Virtual Thread compatibility in Java 21.
 *
 * @since 2.7.2
 */
public class UserIdMdcHelper
{
  private UserIdMdcHelper() {
    // empty
  }

  private static final Logger log = LoggerFactory.getLogger(UserIdMdcHelper.class);

  public static final String KEY = "userId";

  /**
   * Checks if a userId is set in the MDC context.
   * 
   * @return true if a valid userId is set, false otherwise
   */
  public static boolean isSet() {
    String userId = MDC.get(KEY);
    return !(Strings.isNullOrEmpty(userId) || UNKNOWN.equals(userId));
  }

  /**
   * Sets the userId in MDC if not already set.
   * Safe for use with Virtual Threads.
   */
  public static void setIfNeeded() {
    if (!isSet()) {
      set();
    }
  }

  /**
   * Sets the userId in MDC based on the provided subject.
   * Safe for use with Virtual Threads.
   * 
   * @param subject the subject to extract userId from
   */
  public static void set(final Subject subject) {
    checkNotNull(subject);
    String userId = UserIdHelper.get(subject);
    log.trace(STR."Set: \{userId}");
    MDC.put(KEY, userId);
  }

  /**
   * Sets the userId in MDC from the current security context.
   * Safe for use with Virtual Threads.
   */
  public static void set() {
    MDC.put(KEY, UserIdHelper.get());
  }

  /**
   * Sets the userId in MDC to UNKNOWN.
   * Safe for use with Virtual Threads.
   * 
   * @since 3.0
   */
  public static void unknown() {
    MDC.put(KEY, UNKNOWN);
  }

  /**
   * Sets the userId in MDC to SYSTEM.
   * Safe for use with Virtual Threads.
   * 
   * @since 3.0
   */
  public static void system() {
    MDC.put(KEY, SYSTEM);
  }

  /**
   * Removes the userId from MDC.
   * Always call this method when done with the MDC context to prevent memory leaks,
   * especially important with Virtual Threads.
   */
  public static void unset() {
    MDC.remove(KEY);
  }
}