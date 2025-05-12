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
package org.sonatype.nexus.testsuite.testsupport.dispatch;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import javax.servlet.http.HttpServletRequest;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

/**
 * Matches requests as long as they have a particular query string parameter.
 * <p>
 * This implementation is optimized for Java 21, using modern language features
 * such as streams and functional programming. It demonstrates a more concise
 * and readable approach to query parameter matching using Java 21 capabilities.
 */
public class QueryParamMatcher
    implements RequestMatcher
{
  private final String paramName;

  private final String value;

  /**
   * Constructs a matcher that checks for the presence of a parameter with the given name.
   *
   * @param paramName the name of the parameter to match
   */
  public QueryParamMatcher(final String paramName) {
    this(paramName, null);
  }

  /**
   * Constructs a matcher that checks for the presence of a parameter with the given name and value.
   *
   * @param paramName the name of the parameter to match
   * @param value the value of the parameter to match, or null to match any value
   */
  public QueryParamMatcher(final String paramName, final String value) {
    this.paramName = paramName;
    this.value = value;
  }

  @Override
  public boolean matches(final HttpServletRequest request) throws Exception {
    if (request.getQueryString() == null) {
      return false;
    }
    
    final URI uri = new URI("http://placeholder?" + request.getQueryString());
    List<NameValuePair> params = URLEncodedUtils.parse(uri, StandardCharsets.UTF_8);

    // Using Java 21 features with streams to find matching parameter
    return params.stream()
        // Pattern matching would be ideal here if NameValuePair were a record
        // For now, we use functional style with streams
        .filter(param -> param.getName().equals(paramName))
        .findFirst()
        .map(param -> value == null || value.equals(param.getValue()))
        .orElse(false);
  }
}
