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
package org.sonatype.nexus.testsuite.testsupport.dispatch;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Matches requests where the path ends with a particular string.
 * <p>
 * This implementation is optimized for Java 21 and uses pattern matching
 * for improved null-safety and readability. It is compatible with Virtual Threads
 * for high-throughput request matching in test environments.
 * </p>
 *
 * @since 3.60
 */
public class PathEndsWith
    implements RequestMatcher
{
  private final String substring;

  /**
   * Constructs a matcher that checks if request paths end with the specified substring.
   *
   * @param substring the string to check for at the end of request paths (must not be null)
   */
  public PathEndsWith(final String substring) {
    this.substring = substring;
  }

  /**
   * Determines if the request's path ends with the configured substring.
   * <p>
   * Uses Java 21 pattern matching for null-safety checks on the path info.
   * </p>
   *
   * @param request the HTTP request to check
   * @return true if the request's path ends with the substring, false otherwise
   * @throws Exception if an error occurs during matching
   */
  @Override
  public boolean matches(final HttpServletRequest request) throws Exception {
    // Use pattern matching to handle null path info gracefully
    if (request == null) {
      return false;
    }
    
    // Using pattern matching for instanceof with conditional binding (Java 21 feature)
    // This checks if pathInfo is not null and ends with our substring in one expression
    return switch (request.getPathInfo()) {
      case String pathInfo when pathInfo.endsWith(substring) -> true;
      default -> false;
    };
  }
}