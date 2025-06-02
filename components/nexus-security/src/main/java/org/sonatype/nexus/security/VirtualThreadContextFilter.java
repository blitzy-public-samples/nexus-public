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

import java.io.IOException;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static java.lang.StringTemplate.STR;

/**
 * Filter that ensures proper context propagation across Virtual Threads.
 * 
 * This filter captures the current security context, MDC context, and other thread-local state
 * and ensures it's properly propagated when request processing is handed off to Virtual Threads.
 *
 * @since 3.60
 */
@Named(VirtualThreadContextFilter.NAME)
@Singleton
public class VirtualThreadContextFilter
    implements Filter
{
  public static final String NAME = "virtualThreadContext";

  private static final Logger log = LoggerFactory.getLogger(VirtualThreadContextFilter.class);

  @Override
  public void init(final FilterConfig config) {
    log.debug("Initialized Virtual Thread context propagation filter");
  }

  @Override
  public void destroy() {
    log.debug("Destroyed Virtual Thread context propagation filter");
  }

  @Override
  public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain)
      throws IOException, ServletException
  {
    // Only process HTTP requests
    if (request instanceof HttpServletRequest && response instanceof HttpServletResponse) {
      HttpServletRequest httpRequest = (HttpServletRequest) request;
      HttpServletResponse httpResponse = (HttpServletResponse) response;
      
      // Capture the current security subject
      Subject currentSubject = SecurityUtils.getSubject();
      
      // Capture the current MDC context
      Map<String, String> mdcContext = MDC.getCopyOfContextMap();
      
      // Capture any other thread-local state that needs to be propagated
      ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
      
      try {
        // Set request attributes to propagate context to Virtual Threads
        request.setAttribute("virtualthread.subject", currentSubject);
        request.setAttribute("virtualthread.mdc", mdcContext);
        request.setAttribute("virtualthread.classloader", contextClassLoader);
        
        if (log.isTraceEnabled()) {
          log.trace(STR."Propagating context for request: {httpRequest.getRequestURI()}");
        }
        
        // Continue the filter chain with context attributes set
        chain.doFilter(request, response);
      }
      finally {
        // Clean up request attributes
        request.removeAttribute("virtualthread.subject");
        request.removeAttribute("virtualthread.mdc");
        request.removeAttribute("virtualthread.classloader");
      }
    }
    else {
      // For non-HTTP requests, just continue the chain
      chain.doFilter(request, response);
    }
  }
}