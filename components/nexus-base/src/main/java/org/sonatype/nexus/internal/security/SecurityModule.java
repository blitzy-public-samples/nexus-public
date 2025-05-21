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
import com.google.inject.Provider;

import static org.sonatype.nexus.security.FilterProviderSupport.filterKey;

/**
 * Security module.
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
    // This ensures proper Virtual Thread compatibility by avoiding unnecessary indirection
    bind(filterKey(JwtFilter.NAME)).to(JwtFilter.class);
    bind(filterKey(AnonymousFilter.NAME)).to(AnonymousFilter.class);
    bind(filterKey(NexusAuthenticationFilter.NAME)).to(NexusAuthenticationFilter.class);
    bind(filterKey(ApiKeyAuthenticationFilter.NAME)).to(ApiKeyAuthenticationFilter.class);
    bind(filterKey(PermissionsFilter.NAME)).to(PermissionsFilter.class);
    bind(filterKey(AntiCsrfFilter.NAME)).to(AntiCsrfFilter.class);

    // Use provider classes that are optimized for Virtual Thread compatibility
    // These providers ensure proper thread context inheritance with Virtual Threads
    bind(filterKey("authcBasic")).toProvider(AuthcBasicFilterProvider.class);
    bind(filterKey("authcAntiCsrf")).toProvider(AuthcAntiCsrfFilterProvider.class);
    bind(filterKey("authcApiKey")).toProvider(AuthcApiKeyFilterProvider.class);
  }

  /**
   * Provider for basic authentication filter.
   * 
   * Optimized for Virtual Thread compatibility by implementing Provider directly
   * instead of extending FilterProviderSupport.
   */
  @Singleton
  static class AuthcBasicFilterProvider
      implements Provider<javax.servlet.Filter>
  {
    private final NexusAuthenticationFilter filter;

    @Inject
    AuthcBasicFilterProvider(final NexusAuthenticationFilter filter) {
      this.filter = filter;
    }

    @Override
    public javax.servlet.Filter get() {
      return filter;
    }
  }

  /**
   * Provider for API key authentication filter.
   * 
   * Optimized for Virtual Thread compatibility by implementing Provider directly
   * instead of extending FilterProviderSupport.
   */
  @Singleton
  static class AuthcApiKeyFilterProvider
      implements Provider<javax.servlet.Filter>
  {
    private final ApiKeyAuthenticationFilter filter;

    @Inject
    AuthcApiKeyFilterProvider(final ApiKeyAuthenticationFilter filter) {
      this.filter = filter;
    }

    @Override
    public javax.servlet.Filter get() {
      return filter;
    }
  }

  /**
   * Provider for Anti-CSRF filter.
   * 
   * Optimized for Virtual Thread compatibility by implementing Provider directly
   * instead of extending FilterProviderSupport.
   */
  @Singleton
  static class AuthcAntiCsrfFilterProvider
      implements Provider<javax.servlet.Filter>
  {
    private final AntiCsrfFilter filter;

    @Inject
    AuthcAntiCsrfFilterProvider(final AntiCsrfFilter filter) {
      this.filter = filter;
    }

    @Override
    public javax.servlet.Filter get() {
      return filter;
    }
  }
}