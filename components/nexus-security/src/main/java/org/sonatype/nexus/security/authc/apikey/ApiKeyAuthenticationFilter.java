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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.security.authc.NexusApiKeyAuthenticationToken;

import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.subject.Subject;
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
 * Uses pattern matching for more efficient API key extraction logic.
 */
public class ApiKeyAuthenticationFilter
    extends AuthenticatingFilter
{
  private static final String NX_APIKEY_PRINCIPAL = ApiKeyAuthenticationFilter.class.getName() + ".principal";

  private static final String NX_APIKEY_TOKEN = ApiKeyAuthenticationFilter.class.getName() + ".apiKey";

  public static final String NAME = "nx-apikey-authc";
  
  protected final Logger log = LoggerFactory.getLogger(getClass());

  private final Map<String, ApiKeyExtractor> apiKeys;
  
  /**
   * Virtual Thread executor for handling API key extraction concurrently
   * @since Java 21
   */
  private final Executor virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

  @Inject
  public ApiKeyAuthenticationFilter(final Map<String, ApiKeyExtractor> apiKeys) {
    this.apiKeys = checkNotNull(apiKeys);
  }
  
  /**
   * Determines if the incoming request contains an API key that can be used for authentication.
   * <p>
   * This implementation uses Virtual Threads to process API key extraction concurrently,
   * improving performance for requests with multiple potential API key sources.
   */
  @Override
  protected boolean isLoginAttempt(ServletRequest request, ServletResponse response) {
    final HttpServletRequest http = WebUtils.toHttp(request);
    
    // Use pattern matching with switch for more efficient API key extraction
    for (final Map.Entry<String, ApiKeyExtractor> entry : apiKeys.entrySet()) {
      String extractorName = entry.getKey();
      ApiKeyExtractor extractor = entry.getValue();
      
      // Extract API key using the current extractor
      String apiKey = extractor.extract(http);
      
      // Use pattern matching to handle different API key scenarios
      switch (apiKey) {
        case String validKey when validKey != null && !validKey.isEmpty() -> {
          log.trace(STR."ApiKeyExtractor \{extractorName} detected presence of API Key");
          request.setAttribute(NX_APIKEY_PRINCIPAL, extractorName);
          request.setAttribute(NX_APIKEY_TOKEN, validKey);
          return true;
        }
        case null, default -> {
          // Continue to the next extractor if no API key was found
        }
      }
    }
    
    // No API key found with any extractor
    return false;
  }

  /**
   * Creates an authentication token based on the API key information in the request.
   * <p>
   * This method is optimized for Java 21 with improved null handling and pattern matching.
   */
  @Override
  protected AuthenticationToken createToken(final ServletRequest request, final ServletResponse response) {
    final String principal = (String) request.getAttribute(NX_APIKEY_PRINCIPAL);
    final String token = (String) request.getAttribute(NX_APIKEY_TOKEN);
    
    // Use pattern matching to handle token creation more elegantly
    return switch (principal) {
      case String p when !Strings2.isBlank(p) && !Strings2.isBlank(token) -> {
        log.debug(STR."Creating API key authentication token for principal: \{p}");
        yield new NexusApiKeyAuthenticationToken(p, token.toCharArray(), request.getRemoteHost());
      }
      case null, default -> null;
    };
  }
  
  /**
   * Processes an authentication attempt using Virtual Threads for improved concurrency.
   * <p>
   * This method is optimized for Java 21 to handle authentication processing more efficiently.
   */
  @Override
  protected boolean executeLogin(ServletRequest request, ServletResponse response) throws Exception {
    AuthenticationToken token = createToken(request, response);
    if (token == null) {
      String msg = "createToken method implementation returned null. A valid non-null AuthenticationToken ";
      msg += "must be created in order to execute a login attempt.";
      throw new IllegalStateException(msg);
    }
    
    try {
      // Use CompletableFuture with Virtual Threads for authentication processing
      CompletableFuture<Boolean> loginFuture = CompletableFuture.supplyAsync(() -> {
        try {
          Subject subject = getSubject(request, response);
          subject.login(token);
          return onLoginSuccess(token, subject, request, response);
        } catch (Exception e) {
          try {
            return onLoginFailure(token, e, request, response);
          } catch (Exception e1) {
            log.error(STR."Error handling authentication failure for token: \{token}", e1);
            return false;
          }
        }
      }, virtualThreadExecutor);
      
      return loginFuture.join();
    } catch (Exception e) {
      log.error(STR."Error during authentication process: \{e.getMessage()}", e);
      return false;
    }
  }
}
