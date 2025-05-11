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
package org.sonatype.nexus.repository.httpbridge.internal;

import java.util.Enumeration;

import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.repository.view.Headers;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * HTTP {@link Headers} adapter.
 *
 * @since 3.0
 */
class HttpHeadersAdapter
    extends Headers
{
  /**
   * Creates a new Headers instance from an HttpServletRequest.
   * 
   * @param request The HTTP servlet request containing headers to adapt
   * @throws NullPointerException if request is null
   */
  public HttpHeadersAdapter(final HttpServletRequest request) {
    checkNotNull(request);
    
    // Get all header names from the request
    Enumeration<String> names = request.getHeaderNames();
    
    // Process each header name
    while (names.hasMoreElements()) {
      String name = names.nextElement();
      
      // For each header name, get all its values
      Enumeration<String> values = request.getHeaders(name);
      
      // Process each header value using pattern matching
      processHeaderValues(name, values);
    }
  }
  
  /**
   * Processes header values using pattern matching.
   * 
   * @param name The header name
   * @param values The enumeration of header values
   */
  private void processHeaderValues(String name, Enumeration<String> values) {
    while (values.hasMoreElements()) {
      Object value = values.nextElement();
      
      // Use pattern matching to handle different value types
      // This demonstrates Java 21's pattern matching capabilities
      // In this case, we're only expecting String values from the servlet API,
      // but this pattern allows for future extensibility
      switch (value) {
        case String s -> set(name, s);
        case null -> { /* Skip null values */ }
        default -> set(name, value.toString());
      }
    }
  }
}