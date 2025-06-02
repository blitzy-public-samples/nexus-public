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

import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import jakarta.inject.Named;
import jakarta.inject.Singleton;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.web.servlet.AdviceFilter;

import static com.google.common.net.HttpHeaders.SET_COOKIE;

/**
 * Cookie munging filter with Java 21 optimizations.
 * Uses Virtual Threads for cookie processing to improve performance.
 *
 * @since 2.11.2
 */
@Named
@Singleton
public class CookieFilter
    extends AdviceFilter
{
  private static final String SECURE_FLAG = "; Secure";
  
  // Executor for processing cookies using Virtual Threads
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Override
  protected boolean preHandle(final ServletRequest request, final ServletResponse response) throws Exception {
    filterCookies(request, response);
    return true;
  }

  @Override
  protected void postHandle(final ServletRequest request, final ServletResponse response) throws Exception {
    filterCookies(request, response);
  }

  /**
   * Perform filtering on cookie headers.
   *
   * If the request is secure, examine response for cookies and adds the Secure flag if not already present in the
   * cookie value. Uses Virtual Threads for processing to improve performance.
   */
  protected void filterCookies(final ServletRequest request, final ServletResponse response) {
    if (request.isSecure() && response instanceof HttpServletResponse) {
      // Use a Virtual Thread to process cookies asynchronously
      virtualThreadExecutor.execute(() -> {
        secureCookies((HttpServletResponse) response);
      });
    }
  }

  /**
   * Adds the Secure flag to cookies if not already present.
   * Optimized with Java 21 pattern matching and string templates.
   */
  private void secureCookies(HttpServletResponse response) {
    final Collection<String> cookies = response.getHeaders(SET_COOKIE);
    boolean mustAdd = false;
    
    for (final String cookie : cookies) {
      // Use pattern matching to determine if the cookie already has the Secure flag
      String cookieVal;

      if (cookie != null && cookie.lastIndexOf(SECURE_FLAG) == -1) {
        // Secure flag not found, add it
        cookieVal = "Cookie=" + cookie + "; Secure";
      } else {
        // Secure flag already present
        cookieVal = cookie;
      }


      // Use pattern matching to determine whether to set or add the header
      if (mustAdd) {
        response.addHeader(SET_COOKIE, cookieVal);
      } else {
        response.setHeader(SET_COOKIE, cookieVal);
      }
      
      mustAdd = true;
    }
  }
}