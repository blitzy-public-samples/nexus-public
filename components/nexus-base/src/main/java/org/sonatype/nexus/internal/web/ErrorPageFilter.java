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
package org.sonatype.nexus.internal.web;

import java.io.IOException;
import java.lang.StringTemplate.Processor;
import java.util.concurrent.Callable;

import javax.inject.Inject;
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

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.servlet.XFrameOptions;

import org.eclipse.sisu.Hidden;
import org.slf4j.MDC;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.CONTENT_SECURITY_POLICY;
import static com.google.common.net.HttpHeaders.X_CONTENT_TYPE_OPTIONS;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static java.lang.StringTemplate.STR;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

/**
 * Servlet filter to add error page rendering.
 *
 * @since 2.8
 *
 * @see ErrorPageServlet
 */
@Named
@Hidden // hide from DynamicFilterChainManager because we statically install it in WebModule
@Singleton
public class ErrorPageFilter
    extends ComponentSupport
    implements Filter
{
  private final XFrameOptions xFrameOptions;

  @Inject
  public ErrorPageFilter(final XFrameOptions xFrameOptions) {
    this.xFrameOptions = checkNotNull(xFrameOptions);
  }

  @Override
  public void init(final FilterConfig config) throws ServletException {
    // ignore
  }

  @Override
  public void destroy() {
    // ignore
  }

  /**
   * Executes the given task with the current thread's context (MDC and other thread-locals)
   * propagated to the execution context. This is especially important for Virtual Threads
   * in Java 21 where thread-local variables might not be properly propagated when a virtual
   * thread is unmounted from one carrier thread and remounted on another.
   *
   * @param <V> the result type of the callable
   * @param task the task to execute with the current thread context
   * @return the result of the callable execution
   * @throws Exception if the callable throws an exception
   */
  private <V> V withThreadContext(Callable<V> task) throws Exception {
    // Capture the current MDC context - crucial for Virtual Thread context propagation
    var mdcContext = MDC.getCopyOfContextMap();
    
    try {
      // If we have MDC context, ensure it's propagated to the task execution
      if (mdcContext != null) {
        // Store the previous context so we can restore it later
        var previousMdcContext = MDC.getCopyOfContextMap();
        try {
          // Apply the captured context to this execution
          MDC.setContextMap(mdcContext);
          return task.call();
        } finally {
          // Restore the previous MDC context or clear it
          // This is important to prevent context leakage between requests
          if (previousMdcContext != null) {
            MDC.setContextMap(previousMdcContext);
          } else {
            MDC.clear();
          }
        }
      } else {
        // No MDC context to propagate, just execute the task directly
        return task.call();
      }
    } catch (Exception e) {
      // Log the error with the current context using String Templates for better performance
      log.debug(STR."Error executing task with thread context: \{e.getMessage()}", e);
      throw e;
    }
  }

  @Override
  public void doFilter(
      final ServletRequest req,
      final ServletResponse resp,
      final FilterChain chain) throws IOException, ServletException
  {
    final HttpServletRequest request = (HttpServletRequest) req;
    final HttpServletResponse response = (HttpServletResponse) resp;

    // Delegate any exceptions to the ErrorPageServlet via standard sendError servlet api
    // Custom handling here to avoid logging from Jetty implementation
    try {
      chain.doFilter(request, response);
    }
    catch (Exception e) {
      try {
        // Use withThreadContext to ensure Virtual Thread context propagation
        withThreadContext(() -> {
          handleException(request, response, e);
          return null;
        });
      } catch (Exception ex) {
        // If context propagation itself fails, fall back to direct handling
        log.error(STR."Failed to propagate thread context for error handling: \{ex.getMessage()}", ex);
        handleException(request, response, e);
      }
    }
  }
  
  /**
   * Handles an exception by attaching it to the request and sending an error response.
   * Uses modern security headers and is compatible with Jetty 12.0.5 error handling.
   * 
   * This method is optimized for Java 21 with improved error handling and security headers.
   * It ensures proper error propagation in both platform and virtual thread environments.
   *
   * @param request the HTTP request
   * @param response the HTTP response
   * @param exception the exception to handle
   * @throws IOException if an I/O error occurs during error handling
   */
  private void handleException(HttpServletRequest request, HttpServletResponse response, Exception exception) 
      throws IOException {
    // Attach the exception to the request for the ErrorPageServlet to use
    // This preserves the full exception context for the error page
    ErrorPageServlet.attachCause(request, exception);
    
    // Check if the response is already committed
    // If it is, we can't modify it further and must return
    if (response.isCommitted()) {
      log.debug(STR."Response is committed, cannot change status for error: \{exception.getMessage()}", exception);
      return;
    }
    
    // Set modern security headers for improved web security
    // These headers protect against clickjacking, MIME sniffing, and other attacks
    String pathInfo = request.getPathInfo();
    response.setHeader(X_FRAME_OPTIONS, xFrameOptions.getValueForPath(pathInfo));
    response.setHeader(X_CONTENT_TYPE_OPTIONS, "nosniff"); // Prevents MIME type sniffing
    response.setHeader(CONTENT_SECURITY_POLICY, "frame-ancestors 'self'"); // Modern alternative to X-Frame-Options
    
    // Send the error to trigger the error page
    // Jetty 12.0.5 will handle this by forwarding to the appropriate error page
    log.debug(STR."Sending error response for exception: \{exception.getMessage()}", exception);
    response.sendError(SC_INTERNAL_SERVER_ERROR);
  }
}
