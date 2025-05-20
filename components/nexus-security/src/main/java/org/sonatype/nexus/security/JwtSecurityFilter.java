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
package org.sonatype.nexus.security;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;

import org.sonatype.nexus.common.text.Strings2;
import org.sonatype.nexus.security.jwt.JwtVerificationException;

import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.apache.shiro.session.mgt.SimpleSession;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.web.filter.mgt.FilterChainResolver;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.apache.shiro.web.subject.WebSubject;
import org.apache.shiro.web.subject.support.WebDelegatingSubject;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Arrays.stream;
import static org.sonatype.nexus.security.JwtHelper.JWT_COOKIE_NAME;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;

/**
 * JWT security filter.
 *
 * @since 3.38
 */
@Singleton
public class JwtSecurityFilter
    extends SecurityFilter
{
  private final JwtHelper jwtHelper;
  private final ExecutorService executor;

  private static final Logger log = LoggerFactory.getLogger(JwtSecurityFilter.class);

  @Inject
  public JwtSecurityFilter(
      final WebSecurityManager webSecurityManager,
      final FilterChainResolver filterChainResolver,
      final JwtHelper jwtHelper)
  {
    super(webSecurityManager, filterChainResolver);
    this.jwtHelper = checkNotNull(jwtHelper);
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
  }

  @Override
  protected WebSubject createSubject(final ServletRequest request, final ServletResponse response) {
    Cookie[] cookies = ((HttpServletRequest) request).getCookies();

    if (cookies != null) {
      Optional<Cookie> jwtCookie = stream(cookies)
          .filter(cookie -> cookie.getName().equals(JWT_COOKIE_NAME))
          .findFirst();

      if (jwtCookie.isPresent()) {
        Cookie cookie = jwtCookie.get();
        String jwt = cookie.getValue();
        
        if (!Strings2.isEmpty(jwt)) {
          try {
            // Use virtual thread for JWT verification which is I/O bound
            DecodedJWT decodedJwt = executor.submit(() -> jwtHelper.verifyJwt(jwt)).join();
            
            // Create session with Java 21 compatibility
            SimpleSession session = new SimpleSession(request.getRemoteHost());
            session.setTimeout(TimeUnit.SECONDS.toMillis(jwtHelper.getExpirySeconds()));
            session.setAttribute(JWT_COOKIE_NAME, jwt);
            
            // Use pattern matching for claim extraction
            return switch (decodedJwt) {
              case DecodedJWT jwt when jwt.getClaim(USER) != null && jwt.getClaim(REALM) != null -> {
                Claim user = jwt.getClaim(USER);
                Claim realm = jwt.getClaim(REALM);
                
                PrincipalCollection principals = new SimplePrincipalCollection(
                    user.asString(),
                    realm.asString()
                );
                
                yield new WebDelegatingSubject(
                    principals,
                    true,
                    request.getRemoteHost(),
                    session,
                    true,
                    request,
                    response,
                    getSecurityManager());
              }
              default -> {
                log.debug(STR."Invalid JWT token: missing required claims \{USER} or \{REALM}");
                cookie.setValue("");
                cookie.setMaxAge(0);
                WebUtils.toHttp(response).addCookie(cookie);
                yield super.createSubject(request, response);
              }
            };
          }
          catch (JwtVerificationException e) {
            log.debug(STR."Expire and reset the JWT cookie due to the error: \{e.getMessage()}");
            cookie.setValue("");
            cookie.setMaxAge(0);
            WebUtils.toHttp(response).addCookie(cookie);
          }
        }
      }
    }
    return super.createSubject(request, response);
  }
}