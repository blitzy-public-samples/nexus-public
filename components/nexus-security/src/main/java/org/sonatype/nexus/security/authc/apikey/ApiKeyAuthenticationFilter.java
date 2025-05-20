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
package org.sonatype.nexus.security.authc.apikey;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.security.authc.NexusApiKeyAuthenticationToken;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * {@link AuthenticatingFilter} that looks for credentials with help of registered {@link ApiKeyExtractor}s.
 * <p>
 * Optimized for Java 21 with Virtual Threads for improved concurrency and performance.
 */
public class ApiKeyAuthenticationFilter
    extends AuthenticatingFilter
{
  private static final String NX_APIKEY_PRINCIPAL = ApiKeyAuthenticationFilter.class.getName() + ".principal";

  private static final String NX_APIKEY_TOKEN = ApiKeyAuthenticationFilter.class.getName() + ".apiKey";

  public static final String NAME = "nx-apikey-authc";
  
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final Map<String, ApiKeyExtractor> apiKeys;
  
  // Virtual Thread executor for handling authentication processing
  private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public ApiKeyAuthenticationFilter(final Map<String, ApiKeyExtractor> apiKeys) {
    this.apiKeys = checkNotNull(apiKeys);
  }

  @Override
  protected boolean isLoginAttempt(ServletRequest request, ServletResponse response) {
    try {
      return virtualThreadExecutor.submit(() -> {
        final HttpServletRequest http = WebUtils.toHttp(request);
        
        // Use pattern matching to efficiently check for API keys
        for (final Map.Entry<String, ApiKeyExtractor> apiKeyEntry : apiKeys.entrySet()) {
          final String extractorName = apiKeyEntry.getKey();
          final ApiKeyExtractor extractor = apiKeyEntry.getValue();
          
          // Extract API key using the appropriate extractor
          final String apiKey = extractor.extract(http);
          
          // Use pattern matching to check if API key was found
          if (apiKey != null) {
            log.trace(STR."ApiKeyExtractor {extractorName} detected presence of API Key");
            request.setAttribute(NX_APIKEY_PRINCIPAL, extractorName);
            request.setAttribute(NX_APIKEY_TOKEN, apiKey);
            return true;
          }
        }
        
        // No API key found
        return false;
      }).get();
    } catch (Exception e) {
      log.error(STR."Error during API key authentication attempt: {e.getMessage()}", e);
      return false;
    }
  }

  @Override
  protected AuthenticationToken createToken(final ServletRequest request, final ServletResponse response) {
    try {
      return virtualThreadExecutor.submit(() -> {
        final String principal = (String) request.getAttribute(NX_APIKEY_PRINCIPAL);
        final String token = (String) request.getAttribute(NX_APIKEY_TOKEN);
        
        // Use pattern matching to check if both principal and token are present
        return switch (principal) {
          case String p when !Strings2.isBlank(p) && token != null && !Strings2.isBlank(token) ->
            new NexusApiKeyAuthenticationToken(p, token.toCharArray(), request.getRemoteHost());
          default -> null;
        };
      }).get();
    } catch (Exception e) {
      log.error(STR."Error creating authentication token: {e.getMessage()}", e);
      return null;
    }
  }
  
  /**
   * This is called when an authentication request is being submitted. This implementation
   * always returns true as API key authentication is handled in isLoginAttempt and createToken.
   */
  @Override
  protected boolean onAccessDenied(ServletRequest request, ServletResponse response) throws Exception {
    return executeLogin(request, response);
  }
  
  /**
   * Disable session creation for all API key auth requests.
   * Optimized with Virtual Threads for improved performance.
   */
  @Override
  protected boolean isRememberMe(ServletRequest request) {
    return false;
  }
}
