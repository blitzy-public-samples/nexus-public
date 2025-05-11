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

import org.sonatype.nexus.security.FilterChainModule;
import org.sonatype.nexus.security.SecurityFilter;
import org.sonatype.nexus.security.anonymous.AnonymousFilter;
import org.sonatype.nexus.security.authc.AntiCsrfFilter;
import org.sonatype.nexus.security.authc.NexusAuthenticationFilter;
import org.sonatype.nexus.security.authc.apikey.ApiKeyAuthenticationFilter;

import com.google.inject.AbstractModule;
import com.google.inject.Binder;
import org.apache.shiro.web.filter.mgt.FilterChainResolver;
import org.apache.shiro.web.mgt.WebSecurityManager;

import static org.eclipse.sisu.inject.Sources.prioritize;

/**
 * Repository HTTP bridge module for legacy URLs.
 * Updated for Java 21 compatibility and Apache Shiro 2.0.0.
 *
 * @since 3.7
 */
public class LegacyHttpBridgeModule
    extends AbstractModule
{
  @Override
  protected void configure() {
    // Bind the legacy view servlet and request filter
    bind(LegacyViewServlet.class);
    bind(ExhaustRequestFilter.class);

    // Require core Shiro security components
    // These are compatible with Apache Shiro 2.0.0 and Java 21
    requireBinding(WebSecurityManager.class);
    requireBinding(FilterChainResolver.class);

    // Bind after core-servlets but before error servlet
    // Using Eclipse Sisu 0.10.0 prioritize method for binding order
    Binder highPriorityBinder = binder().withSource(prioritize(0x50000000));
    
    // Install the servlet module with SecurityFilter for Jakarta EE and Java 21 compatibility
    highPriorityBinder.install(new LegacyHttpBridgeServletModule()
    {
      @Override
      protected void bindSecurityFilter(final FilterKeyBindingBuilder filter) {
        // SecurityFilter is compatible with Apache Shiro 2.0.0
        filter.through(SecurityFilter.class);
      }
    });

    // Install the filter chain module with updated security filters for Apache Shiro 2.0.0
    highPriorityBinder.install(new FilterChainModule()
    {
      @Override
      protected void configure() {
        // Configure filter chains for legacy content URLs
        // These filters are compatible with Java 21 and Apache Shiro 2.0.0
        addFilterChain("/content/**",
            NexusAuthenticationFilter.NAME,
            ApiKeyAuthenticationFilter.NAME,
            AnonymousFilter.NAME,
            AntiCsrfFilter.NAME);

        // Configure filter chains for legacy service URLs
        addFilterChain("/service/local/**",
            NexusAuthenticationFilter.NAME,
            ApiKeyAuthenticationFilter.NAME,
            AnonymousFilter.NAME,
            AntiCsrfFilter.NAME);
      }
    });
  }
}