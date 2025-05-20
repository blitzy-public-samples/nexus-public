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
package org.sonatype.nexus.security.authc.apikey;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.security.authc.NexusApiKeyAuthenticationToken;

/**
 * API-Key extractor component. It extracts the format specific API-Key if possible. Can use multiple headers to
 * extract key as needed. Extracted key will end up as {@link NexusApiKeyAuthenticationToken} credential and with this
 * named component' name as token principal. This implies that a realm implementation must exist that handles {@link
 * NexusApiKeyAuthenticationToken}s that has {@link NexusApiKeyAuthenticationToken#getPrincipal()} equal as name of
 * named component implementing this interface.
 * <p>
 * Implementations of this interface should be designed to be compatible with Java 21 Virtual Threads. This means:
 * <ul>
 *   <li>Avoid using thread-local storage that might not be properly propagated across Virtual Thread scheduling points</li>
 *   <li>Ensure any blocking operations are Virtual Thread aware to prevent carrier thread pinning</li>
 *   <li>Consider using non-blocking I/O operations when performing network or file operations</li>
 *   <li>Avoid synchronization on objects that might be held across Virtual Thread scheduling points</li>
 * </ul>
 * <p>
 * Implementations can leverage Java 21 pattern matching features for more concise and readable code when handling
 * different types of requests or headers. For example:
 * <pre>
 * // Using pattern matching with instanceof for type checking
 * if (request instanceof HttpServletRequest httpRequest && httpRequest.getHeader("X-API-Key") != null) {
 *     return httpRequest.getHeader("X-API-Key");
 * }
 * 
 * // Using pattern matching in switch expressions for different request types
 * return switch (request) {
 *     case HttpServletRequest httpRequest when httpRequest.getHeader("X-API-Key") != null ->
 *         httpRequest.getHeader("X-API-Key");
 *     case HttpServletRequest httpRequest when httpRequest.getParameter("apiKey") != null ->
 *         httpRequest.getParameter("apiKey");
 *     default -> null;
 * };
 * </pre>
 */
public interface ApiKeyExtractor
{
  /**
   * Attempts to extract API key as string, whatever part (or parts) of the {@link HttpServletRequest}
   * it needs and returns the extracted key, or returns {@code null}.
   * <p>
   * This method should be implemented to be compatible with Java 21 Virtual Threads to ensure optimal
   * performance in high-concurrency scenarios. Implementations should avoid operations that could cause
   * thread pinning, such as blocking I/O operations without proper Virtual Thread support.
   *
   * @param request The HTTP servlet request to extract the API key from
   * @return The extracted API key as a string, or {@code null} if no API key could be extracted
   */
  @Nullable
  String extract(HttpServletRequest request);
}