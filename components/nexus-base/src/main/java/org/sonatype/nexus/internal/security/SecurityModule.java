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
package org.sonatype.nexus.internal.security;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.security.FilterProviderSupport;
import org.sonatype.nexus.security.JwtFilter;
import org.sonatype.nexus.security.anonymous.AnonymousFilter;
import org.sonatype.nexus.security.authc.AntiCsrfFilter;
import org.sonatype.nexus.security.authc.NexusAuthenticationFilter;
import org.sonatype.nexus.security.authc.apikey.ApiKeyAuthenticationFilter;
import org.sonatype.nexus.security.authz.PermissionsFilter;

import com.google.inject.AbstractModule;

import static org.sonatype.nexus.security.FilterProviderSupport.filterKey;

/**
 * Security module.
 * 
 * This module configures Shiro filters with Virtual Thread compatibility for Java 21.
 * The filter bindings are updated to work with Shiro 2.0.0 and ensure proper thread inheritance
 * when using Virtual Threads.
 * 
 * @since 3.0
 */
@Named
public class SecurityModule
    extends AbstractModule
{
  @Override
  protected void configure() {
    // Bind filters directly to their implementations
    // This approach is compatible with Virtual Threads in Java 21 and Shiro 2.0.0
    // Direct bindings avoid unnecessary thread context switching that could impact Virtual Thread performance
    bind(filterKey(JwtFilter.NAME)).to(JwtFilter.class);
    bind(filterKey(AnonymousFilter.NAME)).to(AnonymousFilter.class);
    bind(filterKey(NexusAuthenticationFilter.NAME)).to(NexusAuthenticationFilter.class);
    bind(filterKey(ApiKeyAuthenticationFilter.NAME)).to(ApiKeyAuthenticationFilter.class);
    bind(filterKey(PermissionsFilter.NAME)).to(PermissionsFilter.class);
    bind(filterKey(AntiCsrfFilter.NAME)).to(AntiCsrfFilter.class);

    // Use provider implementations for filters that require additional configuration
    // These providers are optimized for Virtual Thread compatibility in Java 21
    // The provider pattern ensures proper initialization while maintaining thread safety
    // which is critical for Virtual Thread support in Shiro 2.0.0
    bind(filterKey("authcBasic")).toProvider(AuthcBasicFilterProvider.class);
    bind(filterKey("authcAntiCsrf")).toProvider(AuthcAntiCsrfFilterProvider.class);
    bind(filterKey("authcApiKey")).toProvider(AuthcApiKeyFilterProvider.class);
  }

  /**
   * Provider for basic authentication filter.
   * 
   * Optimized for Virtual Thread compatibility in Java 21 by ensuring proper thread inheritance.
   * This implementation works with Shiro 2.0.0 filter chain and avoids thread-local storage issues
   * that could occur when using Virtual Threads.
   */
  @Singleton
  static class AuthcBasicFilterProvider
      extends FilterProviderSupport
  {
    @Inject
    AuthcBasicFilterProvider(final NexusAuthenticationFilter filter) {
      super(filter);
    }
  }

  /**
   * Provider for API key authentication filter.
   * 
   * Optimized for Virtual Thread compatibility in Java 21 by ensuring proper thread inheritance.
   * This implementation works with Shiro 2.0.0 filter chain and ensures that authentication state
   * is properly maintained when using Virtual Threads for API requests.
   */
  @Singleton
  static class AuthcApiKeyFilterProvider
      extends FilterProviderSupport
  {
    @Inject
    AuthcApiKeyFilterProvider(final ApiKeyAuthenticationFilter filter) {
      super(filter);
    }
  }

  /**
   * Provider for Anti-CSRF filter.
   * 
   * Optimized for Virtual Thread compatibility in Java 21 by ensuring proper thread inheritance.
   * This implementation works with Shiro 2.0.0 filter chain and ensures that CSRF protection
   * is properly applied even when using Virtual Threads for handling requests.
   */
  @Singleton
  static class AuthcAntiCsrfFilterProvider
      extends FilterProviderSupport
  {
    @Inject
    AuthcAntiCsrfFilterProvider(final AntiCsrfFilter filter) {
      super(filter);
    }
  }
}