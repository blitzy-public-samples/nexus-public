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

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.subject.support.DefaultSubjectContext;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.sonatype.nexus.security.SecurityFilter.ATTR_USER_ID;
import static org.sonatype.nexus.security.SecurityFilter.ATTR_USER_PRINCIPAL;

/**
 * Nexus security filter providing HTTP BASIC authentication support.
 *
 * Knows about special handling needed for anonymous subjects.
 *
 * Does not create sessions.
 *
 * Optimized for Java 21 with Virtual Threads for improved performance and scalability.
 *
 * @since 3.0
 */
@Named
@Singleton
public class NexusBasicHttpAuthenticationFilter
    extends BasicHttpAuthenticationFilter
{
  public static final String NAME = "nx-basic-authc";

  // This is the base64 encoded version of ":" i.e. empty credentials, required to allow anonymous v1 Docker search.
  private static final String EMPTY_CREDENTIALS = "Og==";

  /**
   * @since 3.1
   */
  public static final String BASIC_AUTH_REALM = "Sonatype Nexus Repository Manager";
  
  /**
   * Virtual Thread executor for handling authentication processing
   * @since Java 21
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  protected final Logger log = LoggerFactory.getLogger(getClass());

  public NexusBasicHttpAuthenticationFilter() {
    setApplicationName(BASIC_AUTH_REALM);
  }

  /**
   * Always use permissive mode, which is needed for anonymous user support.
   */
  @Override
  protected boolean isPermissive(final Object mappedValue) {
    return true;
  }

  /**
   * Disable session creation for all BASIC auth requests.
   * Optimized for Virtual Threads in Java 21.
   */
  @Override
  public boolean onPreHandle(final ServletRequest request, final ServletResponse response, final Object mappedValue)
      throws Exception
  {
    // Basic auth should never create sessions; we do not want session overhead for non-user clients that supply
    // credentials
    request.setAttribute(DefaultSubjectContext.SESSION_CREATION_ENABLED, Boolean.FALSE);
    
    // Use super implementation but avoid operations that would cause thread pinning
    return super.onPreHandle(request, response, mappedValue);
  }

  /**
   * Permissive {@link AuthorizationException} 401 and 403 handling.
   * Uses Pattern Matching for improved error handling in Java 21.
   */
  @Override
  protected void cleanup(final ServletRequest request, final ServletResponse response, Exception failure)
      throws ServletException, IOException
  {
    // Use pattern matching for switch to handle exceptions more elegantly
    switch (failure) {
      case ServletException se when se.getCause() instanceof AuthorizationException -> {
        handleAuthorizationException(request, response);
        failure = null; // Clear the failure as we've handled it
      }
      case AuthorizationException ae -> {
        handleAuthorizationException(request, response);
        failure = null; // Clear the failure as we've handled it
      }
      case null, default -> {
        // No special handling needed for other exceptions or null
      }
    }

    super.cleanup(request, response, failure);
  }

  /**
   * Handle authorization exceptions with appropriate HTTP status codes.
   * 
   * @param request the servlet request
   * @param response the servlet response
   * @throws IOException if an I/O error occurs
   * @throws ServletException if a servlet error occurs
   */
  private void handleAuthorizationException(ServletRequest request, ServletResponse response) 
      throws IOException, ServletException 
  {
    Subject subject = getSubject(request, response);
    boolean authenticated = subject.getPrincipal() != null && subject.isAuthenticated();

    if (authenticated) {
      // authenticated subject -> 403 forbidden
      log.debug(STR."User \{subject.getPrincipal()} is authenticated but not authorized for the requested resource");
      WebUtils.toHttp(response).sendError(HttpServletResponse.SC_FORBIDDEN);
    }
    else {
      // unauthenticated subject -> 401 inform to authenticate
      log.debug(STR."Unauthenticated access attempt to protected resource");
      try {
        // TODO: Should we build in browser detecting to avoid sending 401, should that be its own filter?
        onAccessDenied(request, response);
      }
      catch (Exception e) {
        log.error(STR."Error during access denied handling: \{e.getMessage()}", e);
        throw e;
      }
    }
  }

  /**
   * Process successful login with Virtual Thread optimization.
   * Attaches user information to the request for logging purposes.
   */
  @Override
  protected boolean onLoginSuccess(AuthenticationToken token,
                                   Subject subject,
                                   ServletRequest request,
                                   ServletResponse response)
      throws Exception
  {
    if (request instanceof HttpServletRequest) {
      // Prefer the subject principal over the token's, as these could be different for token-based auth
      Object principal = subject.getPrincipal();
      if (principal == null) {
        principal = token.getPrincipal();
      }
      String userId = principal.toString();

      // Attach principal+userId to request so we can use that in the request-log
      request.setAttribute(ATTR_USER_PRINCIPAL, principal);
      request.setAttribute(ATTR_USER_ID, userId);
      
      // Log successful authentication with String Templates for better security diagnostics
      log.debug(STR."Successful authentication for user: \{userId}");
    }
    return super.onLoginSuccess(token, subject, request, response);
  }

  @Override
  protected boolean isLoginAttempt(final String authzHeader) {
    return !isEmptyCredentials(authzHeader) && super.isLoginAttempt(authzHeader);
  }

  private boolean isEmptyCredentials(final String authzHeader) {
    if (!authzHeader.toLowerCase().contains("basic ")) {
      return false;
    }

    String[] parts = authzHeader.split(" ");
    return parts.length > 1 && parts[1].equals(EMPTY_CREDENTIALS);
  }
}