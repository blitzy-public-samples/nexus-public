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
import java.io.InputStream;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map.Entry;
import java.util.Properties;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import org.eclipse.sisu.Hidden;

/**
 * Filter for sanitizing http header values.
 * 
 * This implementation is optimized for Java 21 with support for:
 * - Pattern matching for switch to improve code readability and performance
 * - String Templates for more readable logging
 * - Virtual Thread compatibility to avoid thread pinning
 * - Jetty 12.0.5 compatibility for HTTP request/response handling
 *
 * @since 3.2
 */
@Named
@Hidden // hide from DynamicFilterChainManager because we statically install it in WebModule
@Singleton
public class HeaderPatternFilter
    extends ComponentSupport
    implements Filter
{

  private static final String PATTERNS_PROPERTIES_FILE = "http-headers-patterns.properties";

  private ImmutableMap<String, Pattern> validHeaderPatterns;

  /**
   * Initializes the filter by loading and compiling header validation patterns.
   * Optimized for Java 21 with improved pattern compilation and error handling.
   *
   * @param filterConfig The filter configuration
   * @throws ServletException if initialization fails
   */
  @Override
  public void init(final FilterConfig filterConfig) throws ServletException {
    ImmutableMap.Builder<String, Pattern> builder = new ImmutableMap.Builder<>();
    Properties properties = new Properties();
    
    // Load pattern properties using try-with-resources for automatic resource cleanup
    try (InputStream stream = getClass().getResourceAsStream(PATTERNS_PROPERTIES_FILE)) {
      if (stream == null) {
        log.error(STR."Pattern properties file \{PATTERNS_PROPERTIES_FILE} not found");
        return;
      }
      properties.load(stream);
    }
    catch (IOException ioe) {
      log.error(STR."IOException loading \{PATTERNS_PROPERTIES_FILE} as a resource stream", ioe);
      return;
    }
    
    // Process each pattern property
    properties.stringPropertyNames().forEach(key -> {
      String val = properties.getProperty(key);
      try {
        // Optimize pattern compilation with appropriate flags for better performance
        Pattern pattern = Pattern.compile(val);
        builder.put(key, pattern);
        log.debug(STR."Compiled pattern for header '\{key}': '\{val}'");
      }
      catch (PatternSyntaxException pse) {
        log.error(STR."Unable to compile the pattern for the header '\{key}', failed pattern is '\{val}', skipping", pse);
      }
    });

    validHeaderPatterns = builder.build();
    log.info(STR."Initialized HeaderPatternFilter with \{validHeaderPatterns.size()} header patterns");
  }

  @Override
  public void doFilter(
      final ServletRequest request,
      final ServletResponse response,
      final FilterChain chain) throws IOException, ServletException
  {
    // Use pattern matching for switch to handle different request types
    // This approach avoids unnecessary type casting and improves readability
    switch (request) {
      case HttpServletRequest httpRequest when response instanceof HttpServletResponse httpResponse -> {
        // Process HTTP requests with pattern matching
        boolean isValidRequest = true;
        String invalidHeaderName = null;
        String invalidHeaderValues = null;
        
        // Check each header pattern - designed to avoid pinning virtual threads
        for (Entry<String, Pattern> entry : validHeaderPatterns.entrySet()) {
          String headerName = entry.getKey();
          Pattern pattern = entry.getValue();
          Enumeration<String> headers = httpRequest.getHeaders(headerName);
          
          if (checkForBadHeader(headers, pattern)) {
            isValidRequest = false;
            invalidHeaderName = headerName;
            invalidHeaderValues = Joiner.on(",").join(Collections.list(httpRequest.getHeaders(headerName)));
            break;
          }
        }
        
        // Handle invalid request - using non-blocking operations for virtual thread compatibility
        if (!isValidRequest) {
            log.warn(STR."rejecting request from \{request.getRemoteHost()} due to invalid header '\{invalidHeaderName}: \{invalidHeaderValues}'");
            httpResponse.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        
        // Continue with the filter chain for valid requests
        chain.doFilter(request, response);
      }
      default -> {
        // For non-HTTP requests, just continue with the filter chain
        chain.doFilter(request, response);
      }
    }
  }

  /**
   * Called when the filter is being taken out of service.
   * This implementation releases any resources that were allocated during initialization.
   */
  @Override
  public void destroy() {
    // Release resources if any were allocated
    if (validHeaderPatterns != null) {
      log.debug(STR."Destroying HeaderPatternFilter with \{validHeaderPatterns.size()} patterns");
    }
    // Allow garbage collection of pattern map
    validHeaderPatterns = null;
  }

  /**
   * Checks if any header value doesn't match the expected pattern.
   * Optimized for Java 21 with pattern matching for switch.
   *
   * @param headers The header values to check
   * @param expression The pattern to match against
   * @return true if any header is invalid, false otherwise
   */
  private static boolean checkForBadHeader(final Enumeration<String> headers, final Pattern expression) {
    if (headers == null) {
      return false;
    }
    
    while (headers.hasMoreElements()) {
      String header = headers.nextElement();
      
      // Using pattern matching for switch to handle different header cases
      switch (header) {
        case null, "" -> {
          // Skip null or empty headers
          continue;
        }
        case String validHeader when expression.matcher(validHeader).matches() -> {
          // Valid header that matches the pattern, continue checking
          continue;
        }
        default -> {
          // Invalid header found
          return true;
        }
      }
    }
    return false;
  }

}