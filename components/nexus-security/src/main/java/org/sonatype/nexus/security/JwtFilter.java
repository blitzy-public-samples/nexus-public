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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.thread.NexusThreadFactory;

import org.apache.shiro.web.servlet.AdviceFilter;
import org.apache.shiro.web.util.WebUtils;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.stream;
import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;
import static org.sonatype.nexus.security.JwtHelper.JWT_COOKIE_NAME;

/**
 * Filter to verify and refresh JWT cookie
 *
 * @since 3.38
 */
@Named
@Singleton
public class JwtFilter
    extends AdviceFilter
{
  public static final String NAME = "nx-jwt";

  private final JwtHelper jwtHelper;

  private final List<JwtRefreshExemption> jwtExemptPaths;

  private final ExecutorService virtualThreadExecutor;

  @Inject
  public JwtFilter(final JwtHelper jwtHelper,
                   final List<JwtRefreshExemption> jwtExemptPaths) {
    this.jwtHelper = checkNotNull(jwtHelper);
    this.jwtExemptPaths = jwtExemptPaths;
    this.virtualThreadExecutor = newVirtualThreadPerTaskExecutor(
        new NexusThreadFactory("jwt-filter", "JWT Filter Virtual Thread"));
  }
  
  /**
   * Determines if the request is exempt from JWT processing based on its path.
   * Uses Java 21 String processing enhancements for more efficient path matching.
   *
   * @param request The HTTP servlet request
   * @return true if the request is exempt from JWT processing, false otherwise
   */
  private boolean isExemptRequest(final HttpServletRequest request) {
    String requestPath = request.getServletPath();
    // Use enhanced String processing with method references for more efficient path matching
    return jwtExemptPaths.stream()
        .map(JwtRefreshExemption::getPath)
        .anyMatch(exemptPath -> requestPath.indexOf(exemptPath) >= 0);
  }

  @Override
  protected boolean preHandle(final ServletRequest request, final ServletResponse response) throws Exception {
    HttpServletRequest servletRequest = (HttpServletRequest) request;
    Cookie[] cookies = servletRequest.getCookies();

    if ((cookies != null) && !isExemptRequest(servletRequest)) {
      // Use Virtual Thread to process JWT cookie verification and refresh
      return virtualThreadExecutor.submit(() -> processJwtCookie(cookies, request, response)).get();
    }
    return true;
  }

  /**
   * Process JWT cookie verification and refresh using a Virtual Thread.
   * This method handles the extraction, verification, and refresh of JWT cookies.
   *
   * @param cookies The cookies from the HTTP request
   * @param request The servlet request
   * @param response The servlet response
   * @return true if processing should continue, false if the request should be stopped
   */
  private boolean processJwtCookie(Cookie[] cookies, ServletRequest request, ServletResponse response) {
    // Use pattern matching with enhanced switch expression to find and process JWT cookie
    Optional<Cookie> jwtCookie = stream(cookies)
        .filter(cookie -> JWT_COOKIE_NAME.equals(cookie.getName()))
        .findFirst();

    // Use pattern matching to handle the cookie presence
    return switch (jwtCookie.orElse(null)) {
      case Cookie cookie when cookie != null && !Strings2.isEmpty(cookie.getValue()) -> {
        try {
          // Verify and refresh the JWT token
          Cookie refreshedToken = jwtHelper.verifyAndRefreshJwtCookie(cookie.getValue(), request.isSecure());
          WebUtils.toHttp(response).addCookie(refreshedToken);
          yield true;
        } 
        catch (JwtVerificationException e) {
          // Expire the cookie in case of any issues while JWT verification
          cookie.setValue("");
          cookie.setMaxAge(0);
          WebUtils.toHttp(response).addCookie(cookie);
          yield false;
        }
      }
      default -> true; // No JWT cookie or empty value, continue processing
    };
  }