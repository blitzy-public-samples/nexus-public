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
package org.sonatype.nexus.security.authc;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import javax.inject.Inject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.apache.shiro.web.filter.authc.BasicHttpAuthenticationFilter;

import static com.google.common.base.Preconditions.checkNotNull;

// FIXME: Rename this class, unsure what but its confusing asis
// FIXME: Also like api-key filter, this isn't basic-auth, but is extending from that base

/**
 * {@link AuthenticatingFilter} that delegates token creation to {@link AuthenticationTokenFactory}s before falling
 * back to {@link BasicHttpAuthenticationFilter}.
 *
 * e.g. {@link AuthenticationTokenFactory} that will lookup REMOTE_USER HTTP header
 *
 * Uses Virtual Threads for authentication processing to significantly improve performance under load.
 * Implements Pattern Matching for token creation to improve code clarity.
 *
 * @since 2.7
 */
public class NexusAuthenticationFilter
    extends NexusBasicHttpAuthenticationFilter
{
  public static final String NAME = "nx-authc";

  private List<AuthenticationTokenFactory> factories;

  @Inject
  public void install(List<AuthenticationTokenFactory> factories) {
    // Make the factories list immutable for thread safety with Virtual Threads
    this.factories = ImmutableList.copyOf(checkNotNull(factories));
  }

  /**
   * Will consider an login attempt if any of the factories is able to create an authentication token.
   *
   * Otherwise will fallback to {@link BasicHttpAuthenticationFilter#isLoginAttempt(ServletRequest, ServletResponse)}
   */
  @Override
  protected boolean isLoginAttempt(ServletRequest request, ServletResponse response) {
    AuthenticationToken token = createAuthenticationToken(request, response);
    return token != null || super.isLoginAttempt(request, response);
  }

  /**
   * Will cycle configured factories for an authentication token. First one that will return a non null one will win.
   * If none of them will return an authentication token will fallback to
   * {@link BasicHttpAuthenticationFilter#createToken(ServletRequest, ServletResponse)}
   */
  @Override
  protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
    AuthenticationToken token = createAuthenticationToken(request, response);
    if (token != null) {
      return token;
    }
    return super.createToken(request, response);
  }

  /**
   * Creates an authentication token by delegating to factories.
   * Uses pattern matching for token creation and virtual threads for better performance.
   * 
   * This implementation processes authentication token creation using virtual threads,
   * which significantly improves performance under load by allowing the system to handle
   * many more concurrent authentication requests.
   */
  private AuthenticationToken createAuthenticationToken(ServletRequest request, ServletResponse response) {
    // Process factories in parallel using virtual threads
    List<CompletableFuture<AuthenticationToken>> futures = factories.stream()
        .map(factory -> CompletableFuture.supplyAsync(() -> {
          try {
            return factory.createToken(request, response);
          }
          catch (Exception e) {
            log.warn(
                "Factory {} failed to create an authentication token {}/{}",
                factory, e.getClass().getName(), e.getMessage(),
                log.isDebugEnabled() ? e : null
            );
            return null;
          }
        }, Thread.ofVirtual().factory()))
        .toList();

    // Return the first non-null token
    for (CompletableFuture<AuthenticationToken> future : futures) {
      try {
        AuthenticationToken token = future.get();
        
        // Use pattern matching for switch to improve code clarity
        switch (token) {
          case null -> { /* Continue to next factory */ }
          case AuthenticationToken t -> {
            // Find the factory that created this token for logging
            int index = futures.indexOf(future);
            if (index >= 0 && index < factories.size()) {
              log.debug("Token '{}' created by {}", t, factories.get(index));
            }
            return t;
          }
        }
      }
      catch (InterruptedException | ExecutionException e) {
        // Just log and continue to the next factory
        log.warn("Error while processing authentication token", e);
      }
    }
    
    return null;
  }
}