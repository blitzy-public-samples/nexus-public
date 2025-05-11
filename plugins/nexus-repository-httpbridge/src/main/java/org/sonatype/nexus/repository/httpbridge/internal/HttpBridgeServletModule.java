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

import java.util.concurrent.Executor;

import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.servlet.ServletModule;

import static java.lang.Thread.ofVirtual;
import static org.sonatype.nexus.repository.httpbridge.internal.HttpBridgeModule.MOUNT_POINT;

/**
 * Servlet module for Repository HTTP bridge.
 * 
 * Configured to use Java 21 Virtual Threads for improved scalability and performance
 * with I/O-bound operations like HTTP requests.
 *
 * @since 3.38
 */
public abstract class HttpBridgeServletModule
    extends ServletModule
{
  /**
   * Provides a Virtual Thread-based executor for servlet processing.
   * This enables high-throughput handling of concurrent HTTP requests with minimal resource usage.
   * 
   * @return An executor that creates a new virtual thread for each task
   */
  @Provides
  @Singleton
  Executor provideVirtualThreadExecutor() {
    return task -> ofVirtual().name("http-bridge-").start(task);
  }

  @Override
  protected void configureServlets() {
    // Bind the ViewServlet as a singleton
    bind(ViewServlet.class).in(Singleton.class);
    
    // Configure the servlet mapping with virtual thread support
    serve(MOUNT_POINT + "/*").with(ViewServlet.class);
    
    // Bind filters in the correct order
    bindViewFiltersFor(MOUNT_POINT + "/*");
  }

  /**
   * Helper to make sure view-related filters are bound in the correct order by servlet filter.
   * 
   * @param urlPattern The primary URL pattern to match
   * @param morePatterns Additional URL patterns to match
   */
  private void bindViewFiltersFor(final String urlPattern, final String... morePatterns) {
    bindViewFilters(filter(urlPattern, morePatterns));
  }

  /**
   * Configures the filter chain for HTTP requests.
   * All filters are executed using virtual threads for improved scalability.
   * 
   * @param filter The filter binding builder to configure
   */
  private void bindViewFilters(FilterKeyBindingBuilder filter) {
    // Ensure ExhaustRequestFilter is bound as a singleton for thread safety
    bind(ExhaustRequestFilter.class).in(Singleton.class);
    
    // Add the request exhaustion filter first in the chain
    filter.through(ExhaustRequestFilter.class);
    
    // Add security filters (implemented by subclasses)
    bindSecurityFilter(filter);
  }

  /**
   * Abstract method to be implemented by subclasses to configure security filters.
   * Security filters should be configured to work with virtual threads.
   * 
   * @param filter The filter binding builder to configure with security filters
   */
  protected abstract void bindSecurityFilter(final FilterKeyBindingBuilder filter);
}