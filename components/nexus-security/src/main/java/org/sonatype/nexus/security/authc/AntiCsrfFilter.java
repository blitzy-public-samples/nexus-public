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
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.web.filter.authc.AuthenticationFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an anti cross-site request forgery (CSRF / XSRF) protection using a cookie-to-header token approach.
 * Leverages Java 21 Virtual Threads for improved concurrency and performance during token validation.
 *
 * @since 3.13
 */
@Named
@Singleton
public class AntiCsrfFilter
    extends AuthenticationFilter
{
  public static final String NAME = "nx-anticsrf-authc";

  private static final Logger log = LoggerFactory.getLogger(AntiCsrfFilter.class);
  
  private final AntiCsrfHelper csrfHelper;
  
  // Executor for virtual threads
  private final Executor virtualThreadExecutor;

  @Inject
  public AntiCsrfFilter(final AntiCsrfHelper csrfHelper)
  {
    this.csrfHelper = csrfHelper;
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  public boolean isEnabled() {
    return csrfHelper.isEnabled();
  }

  @Override
  protected boolean isAccessAllowed(final ServletRequest request, final ServletResponse response, final Object mappedValue) {
    // Use a virtual thread for token validation to improve concurrency
    try {
      // Create a thread-safe wrapper for the request to ensure thread safety
      final HttpServletRequest httpRequest = (HttpServletRequest) request;
      
      // Use a virtual thread to perform the validation
      Future<Boolean> validationFuture = virtualThreadExecutor.submit(() -> csrfHelper.isAccessAllowed(httpRequest));
      
      // Wait for the result - this won't block OS threads as it's using virtual threads
      return validationFuture.get();
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt(); // Restore the interrupted status
      log.error(STR."CSRF validation interrupted: \{e.getMessage()}");
      return false;
    }
    catch (ExecutionException e) {
      log.error(STR."Error during CSRF token validation: \{e.getCause() != null ? e.getCause().getMessage() : e.getMessage()}");
      return false;
    }
    catch (Exception e) {
      log.error(STR."Unexpected error during CSRF token validation: \{e.getMessage()}");
      return false;
    }
  }

  @Override
  protected boolean onAccessDenied(final ServletRequest request, final ServletResponse response) throws IOException
  {
    String remoteAddr = request.getRemoteAddr();
    log.debug(STR."Rejecting request from \{remoteAddr} due to invalid cross-site request forgery token");

    HttpServletResponse httpResponse = (HttpServletResponse) response;
    httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    httpResponse.setContentType("text/plain");
    httpResponse.getWriter().print(AntiCsrfHelper.ERROR_MESSAGE_TOKEN_MISMATCH);

    return false;
  }
}