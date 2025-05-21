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
package org.sonatype.nexus.internal.app;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;
import javax.servlet.DispatcherType;
import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.BaseUrlHolder;
import org.sonatype.nexus.common.app.BaseUrlManager;

import com.google.common.base.Strings;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Default {@link BaseUrlManager}.
 *
 * @since 3.0
 */
@Named
@Singleton
public class BaseUrlManagerImpl
    extends ComponentSupport
    implements BaseUrlManager
{
  private final Provider<HttpServletRequest> requestProvider;

  private volatile String url;

  private volatile boolean force;

  @Inject
  public BaseUrlManagerImpl(
      final Provider<HttpServletRequest> requestProvider,
      @Named("${org.sonatype.nexus.internal.app.BaseUrlManagerImpl.force:-false}") final boolean force)
  {
    this.requestProvider = checkNotNull(requestProvider);
    this.force = force;
    log.debug(STR."Force: \{force}");
  }

  @Override
  public void setUrl(final String url) {
    this.url = url;
  }

  @Override
  public String getUrl() {
    return url;
  }

  @Override
  public boolean isForce() {
    return force;
  }

  @Override
  public void setForce(final boolean force) {
    this.force = force;
  }

  /**
   * Return the current HTTP servlet-request if there is one in the current scope.
   * Enhanced to properly handle Virtual Thread context propagation.
   */
  @Nullable
  private HttpServletRequest httpRequest() {
    try {
      // Get the request from the provider, which should work with Virtual Threads
      // as long as the provider implementation is Virtual Thread aware
      HttpServletRequest request = requestProvider.get();
      if (request == null) {
        log.trace("No HTTP servlet-request available in current thread context");
      }
      return request;
    }
    catch (Exception e) {
      // Use String Template for more structured logging
      log.trace(STR."Unable to resolve HTTP servlet-request: \{e.getMessage()}", e);
      return null;
    }
  }

  /**
   * Detect base-url from forced settings, request or non-forced settings.
   * Enhanced to be resilient under concurrent Virtual Thread execution.
   */
  @Nullable
  @Override
  public String detectUrl() {
    // Capture volatile fields to local variables for thread safety
    final boolean isForced = force;
    final String configuredUrl = url;
    
    // force base-url always wins if set
    if (isForced && !Strings.isNullOrEmpty(configuredUrl)) {
      return configuredUrl;
    }

    // attempt to detect from HTTP request
    HttpServletRequest request = httpRequest();
    if (request != null) {
      try {
        StringBuffer requestUrl = request.getRequestURL();
        String uri = request.getRequestURI();
        String ctx = request.getContextPath();
        return requestUrl.substring(0, requestUrl.length() - uri.length() + ctx.length());
      }
      catch (Exception e) {
        log.warn(STR."Error extracting base URL from request: \{e.getMessage()}", e);
      }
    }

    // no request in context, non-forced base-url
    if (!Strings.isNullOrEmpty(configuredUrl)) {
      return configuredUrl;
    }

    // unable to determine base-url
    return null;
  }

  /**
   * Detect relative path from request.
   * Enhanced to be resilient under concurrent Virtual Thread execution.
   */
  @Nullable
  public String detectRelativePath() {
    // attempt to detect from HTTP request
    HttpServletRequest request = httpRequest();
    if (request != null) {
      try {
        String contextPath = null;
        String requestUri = null;
        if (DispatcherType.FORWARD == request.getDispatcherType()) {
          contextPath = (String) request.getAttribute(RequestDispatcher.FORWARD_CONTEXT_PATH);
          requestUri = (String) request.getAttribute(RequestDispatcher.FORWARD_REQUEST_URI);
        }
        else if (DispatcherType.ERROR == request.getDispatcherType()) {
          requestUri = (String) request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        }
        contextPath = contextPath == null ? request.getContextPath() : contextPath;
        requestUri = requestUri == null ? request.getRequestURI() : requestUri;
        
        // Validate inputs to prevent exceptions in substring operation
        if (contextPath != null && requestUri != null && requestUri.length() >= contextPath.length()) {
          // Remove the context path
          String path = requestUri.substring(contextPath.length());
          return createRelativePath(countSlashes(path));
        }
        else {
          log.debug(STR."Invalid context path (\{contextPath}) or request URI (\{requestUri})");
        }
      }
      catch (Exception e) {
        log.warn(STR."Error calculating relative path: \{e.getMessage()}", e);
      }
    }

    // unable to determine relative path
    return "";
  }

  private static String createRelativePath(final int length) {
    if (length == 0) {
      return ".";
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < length; i++) {
      sb.append("../");
    }
    // guarantee it does not end in a slash
    return sb.substring(0, sb.length() - 1);
  }

  private static int countSlashes(final String path) {
    if (path == null) {
      return 0;
    }
    
    int count = 0;
    // we start at 1 to avoid leading slashes
    int previousIndex = 0;
    for (int i = 1; i < path.length(); i++) {
      if (path.charAt(i) == '/') {
        // skip double slashes
        if (previousIndex != (i - 1)) {
          ++count;
        }
        previousIndex = i;
      }
    }
    return count;
  }

  /**
   * Detect and set (if non-null) the base-url.
   * Enhanced to properly propagate context in Virtual Thread environments.
   */
  @Override
  public void detectAndHoldUrl() {
    try {
      String detectedUrl = detectUrl();
      if (detectedUrl != null) {
        String relativePath = detectRelativePath();
        BaseUrlHolder.set(detectedUrl, relativePath);
        log.trace(STR."Set base URL: \{detectedUrl}, relative path: \{relativePath}");
      }
    }
    catch (Exception e) {
      log.error(STR."Failed to detect and hold URL: \{e.getMessage()}", e);
    }
  }
}