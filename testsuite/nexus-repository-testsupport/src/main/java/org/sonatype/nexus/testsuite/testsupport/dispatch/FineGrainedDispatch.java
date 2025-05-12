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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.sonatype.goodies.httpfixture.server.api.Behaviour;
import org.sonatype.goodies.httpfixture.server.fluent.Server;

/**
 * Dispatches calls with more flexibility than {@link Server#serve} patterns.
 * <p>
 * Updated for Java 21 compatibility with Jakarta Servlet API and modern language features.
 */
public class FineGrainedDispatch
    extends HttpServlet
{
  private final transient Server proxyServer;

  private Map<RequestMatcher, String> routes = new LinkedHashMap<>();

  private int pathNumber = 0;

  /**
   * Creates a new dispatcher for the given server and root path.
   *
   * @param proxyServer the server to dispatch for
   * @param root the root path to serve from
   */
  public FineGrainedDispatch(final Server proxyServer, final String root) {
    this.proxyServer = proxyServer;
    proxyServer.serve(root).withServlet(this);
  }

  /**
   * Adds a new route with the given matcher and behaviors.
   *
   * @param matcher the request matcher to use
   * @param behaviours the behaviors to apply when the matcher matches
   * @return this dispatcher for method chaining
   */
  public FineGrainedDispatch serve(final RequestMatcher matcher, final Behaviour... behaviours) {
    final String dispatchPath = STR."/internalDispatch\{pathNumber++}";

    routes.put(matcher, dispatchPath);
    proxyServer.serve(dispatchPath).withBehaviours(behaviours);
    return this;
  }

  @Override
  protected void service(final HttpServletRequest req, final HttpServletResponse resp)
      throws ServletException, IOException
  {
    try {
      final String matchingRoute = findMatchingRoute(req);
      ServletContext context = getServletContext();
      RequestDispatcher rd = context.getRequestDispatcher(matchingRoute);
      rd.forward(req, resp);
    }
    catch (ServletException | IOException e) {
      throw e; // Rethrow these exceptions directly
    }
    catch (Exception e) {
      // Wrap other exceptions
      if (e instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new ServletException(STR."Unexpected error during dispatch: \{e.getMessage()}", e);
    }
  }

  /**
   * Finds the first matching route for the given request.
   * Uses Java 21 pattern matching for enhanced readability.
   *
   * @param req the HTTP request to match
   * @return the matching route path
   * @throws Exception if no matching route is found or an error occurs
   */
  private String findMatchingRoute(final HttpServletRequest req) throws Exception {
    // Traverse the routes in reverse order so the most recently defined route wins
    final List<Entry<RequestMatcher, String>> reverseInsertionOrderedRoutes = new ArrayList<>(routes.entrySet());
    Collections.reverse(reverseInsertionOrderedRoutes);

    for (Entry<RequestMatcher, String> dispatch : reverseInsertionOrderedRoutes) {
      if (dispatch.getKey().matches(req)) {
        return dispatch.getValue();
      }
    }
    throw new RuntimeException("No matching route found");
  }
}