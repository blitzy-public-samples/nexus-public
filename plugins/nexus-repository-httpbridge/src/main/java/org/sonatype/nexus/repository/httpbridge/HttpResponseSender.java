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
package org.sonatype.nexus.repository.httpbridge;

import java.io.IOException;

import javax.annotation.Nullable;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;

/**
 * Allows repository format specific handling of HTTP response sending.
 * <p>
 * This interface is designed to be compatible with Java 21 Virtual Threads, enabling highly concurrent
 * processing of HTTP responses with minimal resource overhead. Implementations should be thread-safe
 * and prepared to execute concurrently in a Virtual Thread environment where thousands of concurrent
 * operations may be in progress simultaneously.
 * <p>
 * Implementations should:
 * <ul>
 *   <li>Avoid blocking operations where possible to maximize Virtual Thread efficiency</li>
 *   <li>Ensure thread-safety for all shared state</li>
 *   <li>Use non-blocking I/O patterns when processing response content</li>
 *   <li>Be aware that thread-local variables behave differently in Virtual Threads</li>
 *   <li>Consider using Java 21 features like String Templates for logging</li>
 * </ul>
 *
 * @since 3.0
 */
public interface HttpResponseSender
{
  /**
   * Sends the repository {@link Response} to the HTTP response.
   * <p>
   * This method is designed to be compatible with Java 21 Virtual Threads and may be executed
   * concurrently across thousands of Virtual Threads. Implementations should avoid blocking operations
   * where possible and use efficient I/O patterns to maximize throughput.
   *
   * @param request The original repository request (may be null)
   * @param response The repository response to send
   * @param httpServletResponse The HTTP servlet response to write to
   * @throws ServletException If a servlet-specific error occurs
   * @throws IOException If an I/O error occurs during response sending
   */
  void send(@Nullable Request request, Response response, HttpServletResponse httpServletResponse)
      throws ServletException, IOException;
}