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

import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A chain of {@link RequestMatcher} instances, matching if and only if all matchers in the chain match.
 * <p>
 * This implementation is optimized for Java 21, leveraging pattern matching and functional programming
 * for improved readability and performance. It is compatible with Virtual Threads for high-throughput
 * request matching in test environments.
 * </p>
 * <p>
 * This class is designed to work with JUnit Jupiter 5.10.1 and Mockito 4.11.0 for testing HTTP request
 * matching scenarios in a fluent, chainable API style.
 * </p>
 *
 * @since 3.60
 */
public class ChainedRequestMatcher
    implements RequestMatcher
{
  private final List<RequestMatcher> requestMatchers = new ArrayList<>();

  /**
   * Creates a new chained matcher that matches requests for the specified operation.
   *
   * @param operation the operation name to match at the end of request paths
   * @return a new chained matcher configured to match the specified operation
   */
  public static ChainedRequestMatcher forOperation(final String operation) {
    return new ChainedRequestMatcher().operation(operation);
  }

  /**
   * Adds a matcher that checks if the request path ends with the specified operation.
   *
   * @param operation the operation name to match at the end of request paths
   * @return this matcher for method chaining
   */
  public ChainedRequestMatcher operation(final String operation) {
    requestMatchers.add(new PathEndsWith(operation));
    return this;
  }

  /**
   * Adds a matcher that checks if the request has a parameter with the specified name.
   *
   * @param paramName the name of the parameter to match
   * @return this matcher for method chaining
   */
  public ChainedRequestMatcher hasParam(final String paramName) {
    requestMatchers.add(new QueryParamMatcher(paramName));
    return this;
  }

  /**
   * Adds a matcher that checks if the request has a parameter with the specified name and value.
   *
   * @param paramName the name of the parameter to match
   * @param value the value of the parameter to match
   * @return this matcher for method chaining
   */
  public ChainedRequestMatcher hasParam(final String paramName, final String value) {
    requestMatchers.add(new QueryParamMatcher(paramName, value));
    return this;
  }

  /**
   * Determines if the request matches all matchers in this chain.
   * <p>
   * Uses Java 21 pattern matching for improved null-safety and readability.
   * </p>
   *
   * @param request the HTTP request to match against
   * @return true if all matchers in the chain match the request, false otherwise
   * @throws Exception if an error occurs during matching
   */
  @Override
  public boolean matches(final HttpServletRequest request) throws Exception {
    // Using Java 21 pattern matching with switch expression for null safety
    return switch (request) {
      case null -> false;
      default -> requestMatchers.stream().allMatch(matcher -> {
        try {
          return matcher.matches(request);
        }
        catch (Exception e) {
          // Convert checked exceptions to runtime exceptions to work with streams
          throw new RuntimeException("Error matching request", e);
        }
      });
    };
  }
}