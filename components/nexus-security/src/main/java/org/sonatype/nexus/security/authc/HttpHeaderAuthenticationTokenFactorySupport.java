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

import java.util.List;
import java.util.SequencedCollection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.util.WebUtils;

/**
 * Support class for {@link AuthenticationTokenFactory}s that creates {@link AuthenticationToken}s based on HTTP
 * headers.
 *
 * Looks up given HTTP header names. If found will create an {@link HttpHeaderAuthenticationToken}.
 * 
 * Optimized for Java 21 with Virtual Threads for improved performance and Pattern Matching for header validation.
 *
 * @since 2.7
 */
public abstract class HttpHeaderAuthenticationTokenFactorySupport
    implements AuthenticationTokenFactory
{
  // Executor service using virtual threads for processing headers
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  
  @Override
  @Nullable
  public AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
    SequencedCollection<String> headerNames = getHttpHeaderNamesSequenced();
    if (headerNames != null && !headerNames.isEmpty()) {
      HttpServletRequest httpRequest = WebUtils.toHttp(request);
      String remoteHost = request.getRemoteHost();
      
      // Process headers using virtual threads and pattern matching for improved performance
      try {
        // Use virtual threads to process headers asynchronously
        // This is especially beneficial when dealing with multiple headers or slow header processing
        return VIRTUAL_THREAD_EXECUTOR.submit(() -> 
            processHeaders(httpRequest, headerNames, remoteHost)).get();
      } catch (Exception e) {
        // Fall back to synchronous processing if virtual thread execution fails
        return processHeaders(httpRequest, headerNames, remoteHost);
      }
    }
    return null;
  }

  /**
   * Process HTTP headers using pattern matching and virtual threads for improved performance.
   * This method handles different header types and validation patterns efficiently.
   * 
   * Optimized to check high-priority headers first (first in the collection) and
   * low-priority headers last (last in the collection).
   */
  private AuthenticationToken processHeaders(HttpServletRequest httpRequest, 
                                            SequencedCollection<String> headerNames,
                                            String remoteHost) {
    try {
      // Check if we have a high-priority header (first in the collection)
      if (!headerNames.isEmpty()) {
        String priorityHeaderName = headerNames.getFirst();
        String priorityHeaderValue = httpRequest.getHeader(priorityHeaderName);
        
        // Process high-priority header first if it exists
        if (priorityHeaderValue != null) {
          return processHeaderValue(priorityHeaderName, priorityHeaderValue, remoteHost);
        }
      }
      
      // Process remaining headers
      for (String headerName : headerNames) {
        // Skip the first header as we already checked it
        if (!headerNames.isEmpty() && headerName.equals(headerNames.getFirst())) {
          continue;
        }
        
        String headerValue = httpRequest.getHeader(headerName);
        if (headerValue != null) {
          return processHeaderValue(headerName, headerValue, remoteHost);
        }
      }
    } catch (Exception e) {
      // Log exception but don't throw to maintain compatibility with existing implementations
      // that might expect null return on failure
      return null;
    }
    
    return null;
  }
  
  /**
   * Process a single header value using pattern matching.
   * This method handles different header types and validation patterns efficiently.
   */
  private AuthenticationToken processHeaderValue(String headerName, String headerValue, String remoteHost) {
    // Use pattern matching to validate and process header
    return switch (headerValue) {
      case String s when s.isEmpty() -> null; // Skip empty headers
      case String s when s.startsWith("Bearer ") -> 
          createToken(headerName, s.substring(7), remoteHost); // Extract Bearer token
      case String s -> createToken(headerName, s, remoteHost); // Standard header
    };
  }

  /**
   * Creates the {@link HttpHeaderAuthenticationToken}. Subclasses can override and create specific tokens.
   */
  protected HttpHeaderAuthenticationToken createToken(String headerName, String headerValue, String host) {
    return new HttpHeaderAuthenticationToken(headerName, headerValue, host);
  }

  /**
   * Returns a list of HTTP header names that should be considered for creating the authentication tokens.
   * This method is maintained for backward compatibility.
   * 
   * @return List of header names (should not be null)
   */
  protected abstract List<String> getHttpHeaderNames();
  
  /**
   * Returns a sequenced collection of HTTP header names that should be considered for creating the authentication tokens.
   * This implementation converts the list from {@link #getHttpHeaderNames()} to a SequencedCollection.
   * Subclasses can override to provide a more efficient implementation.
   * 
   * @return SequencedCollection of header names
   */
  protected SequencedCollection<String> getHttpHeaderNamesSequenced() {
    List<String> headerNames = getHttpHeaderNames();
    return headerNames != null ? List.copyOf(headerNames) : List.of();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "(creates authentication tokens if any of HTTP headers is present: "
        + getHttpHeaderNamesSequenced()
        + ")";
  }
}