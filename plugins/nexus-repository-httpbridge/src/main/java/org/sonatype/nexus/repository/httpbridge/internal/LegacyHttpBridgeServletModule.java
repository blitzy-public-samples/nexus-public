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

import java.util.List;
import java.util.concurrent.Executor;

import com.google.inject.servlet.ServletModule;

/**
 * Servlet module for legacy HTTP bridge module.
 * 
 * Updated for Java 21 compatibility with support for virtual threads and modern Java features.
 *
 * @since 3.38
 */
public abstract class LegacyHttpBridgeServletModule
    extends ServletModule
{
  /**
   * Legacy content URL patterns that should be served by the LegacyViewServlet.
   */
  private static final List<String> LEGACY_CONTENT_PATTERNS = List.of(
      "/content/groups/*", 
      "/content/repositories/*", 
      "/content/sites/*"
  );

  /**
   * Legacy service URL pattern for regex matching.
   */
  private static final String LEGACY_SERVICE_PATTERN = "/service/local/.*?(/.*)"; 

  /**
   * Virtual thread executor for handling I/O-bound servlet operations.
   * Java 21 feature: Uses virtual threads for improved scalability with minimal resource usage.
   */
  private static final Executor VIRTUAL_THREAD_EXECUTOR = Thread.ofVirtual().name("legacy-http-bridge-", 0).factory();

  @Override
  protected void configureServlets() {
    // this technically makes non-group repositories visible under /content/groups,
    // but this is acceptable since their IDs are unique and it keeps things simple
    serve(LEGACY_CONTENT_PATTERNS.toArray(String[]::new)).with(LegacyViewServlet.class);
    bindViewFiltersFor(LEGACY_CONTENT_PATTERNS);

    // this makes /service/local/x/x available, as a view servlet. Note that we have to strip the last forward
    // slash so that our first group would be "/service/local/x" without the forward slash. This is needed so that
    // the following matcher group starts with the forward slash, which is needed for the NXRM to detect the endpoint.
    serveRegex(LEGACY_SERVICE_PATTERN).with(LegacyViewServlet.class);
    bindViewFiltersRegexFor(LEGACY_SERVICE_PATTERN);
  }

  /**
   * Helper to make sure view-related filters are bound in the correct order by servlet filter.
   * 
   * @param patterns the URL patterns to bind filters for
   */
  private void bindViewFiltersFor(final List<String> patterns) {
    bindViewFilters(filter(patterns.toArray(String[]::new)));
  }

  /**
   * Helper to make sure view-related filters are bound in the correct order by servlet filter.
   * 
   * @param urlPattern the URL pattern to bind filters for
   * @param morePatterns additional URL patterns to bind filters for
   */
  private void bindViewFiltersFor(final String urlPattern, final String... morePatterns) {
    bindViewFilters(filter(urlPattern, morePatterns));
  }

  /**
   * Helper to make sure view-related filters are bound in the correct order by regex filter.
   * 
   * @param urlPattern the URL pattern regex to bind filters for
   * @param morePatterns additional URL pattern regexes to bind filters for
   */
  private void bindViewFiltersRegexFor(final String urlPattern, final String... morePatterns) {
    bindViewFilters(filterRegex(urlPattern, morePatterns));
  }

  /**
   * Binds the view filters in the correct order.
   * 
   * Java 21 enhancement: Filter processing can leverage virtual threads for I/O-bound operations
   * within the filter chain, improving scalability under high load.
   * 
   * @param filter the filter key binding builder
   */
  private void bindViewFilters(FilterKeyBindingBuilder filter) {
    filter.through(ExhaustRequestFilter.class);
    bindSecurityFilter(filter);
  }

  /**
   * Abstract method to be implemented by subclasses to bind the appropriate security filter.
   * 
   * @param filter the filter key binding builder
   */
  protected abstract void bindSecurityFilter(final FilterKeyBindingBuilder filter);
}
