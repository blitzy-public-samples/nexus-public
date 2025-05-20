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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

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

import static java.lang.StringTemplate.STR;
import static org.sonatype.nexus.security.SecurityFilter.ATTR_USER_ID;
import static org.sonatype.nexus.security.SecurityFilter.ATTR_USER_PRINCIPAL;

/**
 * Nexus security filter providing HTTP BASIC authentication support.
 *
 * Knows about special handling needed for anonymous subjects.
 *
 * Does not create sessions.
 *
 * Optimized for Java 21 with Virtual Threads for improved performance.
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

  protected final Logger log = LoggerFactory.getLogger(getClass());
  
  // Virtual Thread executor for handling authentication processing
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

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
   * Optimized with Virtual Threads for improved performance.
   */
  @Override
  public boolean onPreHandle(final ServletRequest request, final ServletResponse response, final Object mappedValue)
      throws Exception
  {
    // Basic auth should never create sessions; we do not want session overhead for non-user clients that supply
    // credentials
    request.setAttribute(DefaultSubjectContext.SESSION_CREATION_ENABLED, Boolean.FALSE);

    // Use Virtual Threads for any blocking operations during authentication processing
    return virtualThreadExecutor.submit(() -> {
      try {
        return super.onPreHandle(request, response, mappedValue);
      } catch (Exception e) {
        // Re-throw the exception to be handled by the caller
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        } else {
          throw new RuntimeException(e);
        }
      }
    }).get();
  }

  /**
   * Permissive {@link AuthorizationException} 401 and 403 handling.
   * Enhanced with Pattern Matching for improved error responses.
   */
  @Override
  protected void cleanup(final ServletRequest request, final ServletResponse response, Exception failure)
      throws ServletException, IOException
  {
    // Use pattern matching to handle different exception types
    if (failure instanceof ServletException se && se.getCause() != null) {
      failure = (Exception) se.getCause();
    }

    // Special handling for authz failures due to permissive
    if (failure instanceof AuthorizationException) {
      // clear the failure
      failure = null;

      Subject subject = getSubject(request, response);
      boolean authenticated = subject.getPrincipal() != null && subject.isAuthenticated();

      if (authenticated) {
        // authenticated subject -> 403 forbidden
        WebUtils.toHttp(response).sendError(HttpServletResponse.SC_FORBIDDEN);
        log.debug(STR."Access denied for authenticated user: \{subject.getPrincipal()}");
      }
      else {
        // unauthenticated subject -> 401 inform to authenticate
        try {
          // TODO: Should we build in browser detecting to avoid sending 401, should that be its own filter?
          log.debug("Requesting authentication for unauthenticated access attempt");
          onAccessDenied(request, response);
        }
        catch (Exception e) {
          failure = e;
          log.warn(STR."Error during authentication request: \{e.getMessage()}", e);
        }
      }
    }

    super.cleanup(request, response, failure);
  }

  /**
   * Optimized for Virtual Threads performance.
   */
  @Override
  protected boolean onLoginSuccess(AuthenticationToken token,
                                   Subject subject,
                                   ServletRequest request,
                                   ServletResponse response)
      throws Exception
  {
    return virtualThreadExecutor.submit(() -> {
      try {
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
          
          log.debug(STR."Login success for user: \{userId}");
        }
        return super.onLoginSuccess(token, subject, request, response);
      } catch (Exception e) {
        // Re-throw the exception to be handled by the caller
        if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        } else {
          throw new RuntimeException(e);
        }
      }
    }).get();
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