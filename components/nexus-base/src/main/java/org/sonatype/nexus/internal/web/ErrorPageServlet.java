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
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URL;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response.Status;

import org.sonatype.nexus.common.template.TemplateHelper;
import org.sonatype.nexus.common.template.TemplateParameters;
import org.sonatype.nexus.common.template.TemplateThrowableAdapter;
import org.sonatype.nexus.servlet.ServletHelper;
import org.sonatype.nexus.servlet.XFrameOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.StringTemplate.STR;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.getRootCause;
import static com.google.common.net.HttpHeaders.X_FRAME_OPTIONS;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;

/**
 * An {@code error.html} servlet to handle generic servlet error-page dispatched requests.
 *
 * @since 2.8
 *
 * @see ErrorPageFilter
 */
@Named
@Singleton
public class ErrorPageServlet
    extends HttpServlet
{
  private static final Logger log = LoggerFactory.getLogger(ErrorPageServlet.class);

  private static final String TEMPLATE_RESOURCE = "errorPageHtml.vm";

  /**
   * @since 3.0
   */
  private static final String ERROR_SERVLET_NAME = "javax.servlet.error.servlet_name";

  /**
   * @since 3.0
   */
  private static final String ERROR_REQUEST_URI = "javax.servlet.error.request_uri";

  /**
   * @since 3.0
   */
  private static final String ERROR_STATUS_CODE = "javax.servlet.error.status_code";

  /**
   * @since 3.0
   */
  private static final String ERROR_MESSAGE = "javax.servlet.error.message";

  /**
   * @since 3.0
   */
  private static final String ERROR_EXCEPTION_TYPE = "javax.servlet.error.exception_type";

  /**
   * @since 3.0
   */
  private static final String ERROR_EXCEPTION = "javax.servlet.error.exception";

  private final TemplateHelper templateHelper;

  private final XFrameOptions xFrameOptions;

  private final URL template;

  @Inject
  public ErrorPageServlet(final TemplateHelper templateHelper, final XFrameOptions xFrameOptions) {
    this.templateHelper = checkNotNull(templateHelper);
    this.xFrameOptions = checkNotNull(xFrameOptions);
    template = getClass().getResource(TEMPLATE_RESOURCE);
    checkNotNull(template);
  }

  @SuppressWarnings("unused")
  @Override
  protected void service(
      final HttpServletRequest request,
      final HttpServletResponse response) throws ServletException, IOException
  {
    ServletHelper.addNoCacheResponseHeaders(response);

    String servletName = (String) request.getAttribute(ERROR_SERVLET_NAME);
    String requestUri = (String) request.getAttribute(ERROR_REQUEST_URI);
    Integer errorCode = (Integer) request.getAttribute(ERROR_STATUS_CODE);
    String errorMessage = (String) request.getAttribute(ERROR_MESSAGE);
    Class<?> causeType = (Class<?>) request.getAttribute(ERROR_EXCEPTION_TYPE);
    Throwable cause = (Throwable) request.getAttribute(ERROR_EXCEPTION);

    // Using pattern matching for switch to handle different error scenarios
    // This happens if someone browses directly to the error page
    if (errorCode == null) {
      errorCode = SC_NOT_FOUND;
      errorMessage = "Not found";
    }

    // Using pattern matching for switch to handle different error message scenarios
    // maintain custom status message when (re)setting the status code,
    // we can't use sendError because it doesn't allow custom html body
    switch (errorMessage) {
      case null -> response.setStatus(errorCode);
      default -> response.setStatus(errorCode, errorMessage);
    }

    response.setHeader(X_FRAME_OPTIONS, xFrameOptions.getValueForPath(request.getPathInfo()));
    response.setContentType("text/html");

    // ensure sanity of passed in strings which are used to render html content
    String errorDescription = escapeHtml(errorMessage != null ? errorMessage : "Unknown error");

    // Using String Templates for error message formatting
    String statusName = Status.fromStatusCode(errorCode).getReasonPhrase();
    
    // Using String Templates for parameter formatting and improved readability
    // Create template parameters with error information
    TemplateParameters params = templateHelper.parameters();
    params.set("errorCode", errorCode);
    params.set("errorName", statusName);
    params.set("errorDescription", errorDescription);
    
    // Log error details using String Templates for improved readability
    log.debug(STR."Handling error: \{errorCode} (\{statusName}) - \{errorDescription}");
    
    // Using pattern matching for switch to handle debug information
    switch (cause) {
      case Throwable t when ServletHelper.isDebug(request) -> {
        // Add debug information when debug is enabled and there is a cause
        params.set("errorCause", new TemplateThrowableAdapter(t));
        log.debug(STR."Adding debug information for error: \{errorCode} - \{statusName}");
      }
      default -> {
        // No debug information added
        log.debug(STR."Rendering error page without debug info: \{errorCode} - \{statusName}");
      }
    }
    
    // Render template with optimized performance using Java 21 features
    String html = templateHelper.render(template, params);
    try (PrintWriter out = new PrintWriter(new OutputStreamWriter(response.getOutputStream()))) {
      out.println(html);
    }
  }

  /**
   * Attach exception details to request.
   *
   * @since 3.0
   */
  static void attachCause(final HttpServletRequest request, final Throwable cause) {
    // Using pattern matching for switch to handle different error types
    switch (cause) {
      case Throwable t when isJavaLangError(t) -> {
        // Log java.lang.Error exceptions at error level
        log.error(STR."Unexpected exception: \{getRootCause(t).getMessage()}", getRootCause(t));
      }
      case Throwable t -> {
        // Log other exceptions at debug level
        log.debug(STR."Attaching cause: \{t.getMessage()}", t);
      }
    }
    request.setAttribute(ERROR_EXCEPTION_TYPE, cause.getClass());
    request.setAttribute(ERROR_EXCEPTION, cause);
  }

  private static boolean isJavaLangError(final Throwable e) {
    return getRootCause(e) instanceof Error;
  }
  
  /**
   * Cache of HTML escape sequences for common characters.
   * Using ConcurrentHashMap for thread safety in a high-concurrency environment.
   */
  private static final Map<Character, String> HTML_ESCAPE_CHARS = new ConcurrentHashMap<>();
  
  static {
    HTML_ESCAPE_CHARS.put('<', "&lt;");
    HTML_ESCAPE_CHARS.put('>', "&gt;");
    HTML_ESCAPE_CHARS.put('&', "&amp;");
    HTML_ESCAPE_CHARS.put('"', "&quot;");
    HTML_ESCAPE_CHARS.put('\'', "&#39;");
  }
  
  /**
   * Escapes HTML special characters in a string to prevent XSS attacks.
   * Uses Java 21 pattern matching for switch to handle different character types.
   * Optimized for performance with cached escape sequences and StringBuilder.
   *
   * @param input The string to escape
   * @return The escaped string
   */
  private static String escapeHtml(String input) {
    if (input == null) {
      return "";
    }
    
    // Pre-allocate StringBuilder with estimated capacity
    StringBuilder escaped = new StringBuilder(input.length() * 2);
    
    // Process each character using pattern matching for switch
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      
      // Using pattern matching for switch with Java 21 syntax
      switch (c) {
        case Character ch when HTML_ESCAPE_CHARS.containsKey(ch) -> 
          escaped.append(HTML_ESCAPE_CHARS.get(ch));
        default -> escaped.append(c);
      }
    }
    
    return escaped.toString();
  }
}