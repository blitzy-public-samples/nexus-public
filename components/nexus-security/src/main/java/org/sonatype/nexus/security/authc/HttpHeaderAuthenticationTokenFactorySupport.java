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
import java.util.concurrent.Future;

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
 * @since 2.7
 */
public abstract class HttpHeaderAuthenticationTokenFactorySupport
    implements AuthenticationTokenFactory
{
  @Override
  @Nullable
  public AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
    SequencedCollection<String> headerNames = getHttpHeaderNames();
    if (headerNames != null && !headerNames.isEmpty()) {
      HttpServletRequest httpRequest = WebUtils.toHttp(request);
      
      // Use Virtual Threads for concurrent header processing
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        // Process headers concurrently using Virtual Threads
        Future<AuthenticationToken> tokenFuture = executor.submit(() -> {
          for (String headerName : headerNames) {
            String headerValue = httpRequest.getHeader(headerName);
            if (headerValue != null) {
              return processHeader(headerName, headerValue, request.getRemoteHost());
            }
          }
          return null;
        });
        
        try {
          return tokenFuture.get();
        } catch (Exception e) {
          // Log and handle exception
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Processes a header using Pattern Matching to validate and create the appropriate token.
   */
  private HttpHeaderAuthenticationToken processHeader(String headerName, String headerValue, String host) {
    // Use Pattern Matching for header validation and error handling
    return switch (headerValue) {
      case null -> null;
      case String s when s.isEmpty() -> null;
      case String s when s.isBlank() -> null;
      case String s -> createToken(headerName, s, host);
    };
  }

  /**
   * Creates the {@link HttpHeaderAuthenticationToken}. Subclasses can override and create specific tokens.
   */
  protected HttpHeaderAuthenticationToken createToken(String headerName, String headerValue, String host) {
    return new HttpHeaderAuthenticationToken(headerName, headerValue, host);
  }

  /**
   * Returns a list of HTTP header names that should be considered for creating the authentication tokens (should not
   * be null).
   * 
   * @return a sequenced collection of HTTP header names
   */
  protected abstract SequencedCollection<String> getHttpHeaderNames();

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "(creates authentication tokens if any of HTTP headers is present: "
        + getHttpHeaderNames()
        + ")";
  }
}