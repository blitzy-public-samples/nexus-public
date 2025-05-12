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
 * Matches incoming servlet requests.
 * <p>
 * This interface is compatible with Java 21 and can be used with Virtual Threads
 * for improved performance in I/O-bound request handling implementations.
 * </p>
 */
@FunctionalInterface
public interface RequestMatcher
{
  /**
   * Determines if this matcher matches the given request.
   *
   * @param request the HTTP request to match against
   * @return true if the request matches, false otherwise
   * @throws Exception if an error occurs during matching
   */
  boolean matches(HttpServletRequest request) throws Exception;
}
