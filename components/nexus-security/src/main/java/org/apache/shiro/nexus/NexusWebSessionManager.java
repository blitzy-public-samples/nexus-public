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
package org.apache.shiro.nexus;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.nexus.common.app.FeatureFlags;

import org.apache.shiro.session.Session;
import org.apache.shiro.session.mgt.SessionContext;
import org.apache.shiro.session.mgt.SessionValidationScheduler;
import org.apache.shiro.session.mgt.ValidatingSessionManager;
import org.apache.shiro.web.servlet.Cookie;
import org.apache.shiro.web.session.mgt.DefaultWebSessionManager;
import org.apache.shiro.web.session.mgt.WebSessionManager;
import org.apache.shiro.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Custom {@link WebSessionManager} for Nexus Repository Manager.
 *
 * This session manager predates the more recent JWT session management in org.sonatype.nexus.security. It's used
 * in for single node deployments typically, however it is not used for Pro deployments using SAML (HA or not).
 * 
 * Updated for Java 21 and Apache Shiro 2.0.0 compatibility with Virtual Thread support for session validation.
 */
public class NexusWebSessionManager
    extends DefaultWebSessionManager
{
  private static final Logger log = LoggerFactory.getLogger(NexusWebSessionManager.class);

  private static final String DEFAULT_NEXUS_SESSION_COOKIE_NAME = "NXSESSIONID";

  // Using AtomicBoolean for thread-safety in Java 21
  private static final ThreadLocal<AtomicBoolean> requestIsHttps = 
      ThreadLocal.withInitial(() -> new AtomicBoolean(true));

  /**
   * Virtual Thread-based session validation scheduler for Java 21.
   */
  private static class VirtualThreadSessionValidationScheduler implements SessionValidationScheduler {
    private final ValidatingSessionManager sessionManager;
    private final long sessionValidationInterval;
    private ScheduledExecutorService service;
    private boolean enabled = false;

    public VirtualThreadSessionValidationScheduler(ValidatingSessionManager sessionManager, long sessionValidationInterval) {
      this.sessionManager = sessionManager;
      this.sessionValidationInterval = sessionValidationInterval;
    }

    @Override
    public boolean isEnabled() {
      return this.enabled;
    }

    @Override
    public void enableSessionValidation() {
      if (this.enabled) {
        return;
      }

      // Using virtual threads for session validation in Java 21
      this.service = Executors.newScheduledThreadPool(1, r -> {
        Thread t = Thread.ofVirtual().name("shiro-session-validation").unstarted(r);
        return t;
      });

      this.service.scheduleAtFixedRate(
          () -> {
            try {
              sessionManager.validateSessions();
            } catch (Throwable t) {
              log.error("Error validating sessions", t);
            }
          },
          sessionValidationInterval,
          sessionValidationInterval,
          TimeUnit.MILLISECONDS);

      this.enabled = true;
    }

    @Override
    public void disableSessionValidation() {
      if (this.service != null) {
        this.service.shutdownNow();
      }
      this.enabled = false;
    }
  }

  @Inject
  public void configureProperties(
      @Named("${shiro.globalSessionTimeout:-" + DEFAULT_GLOBAL_SESSION_TIMEOUT + "}") final long globalSessionTimeout,
      @Named("${nexus.sessionCookieName:-" + DEFAULT_NEXUS_SESSION_COOKIE_NAME + "}") final String sessionCookieName,
      @Named("${nexus.session.enabled:-true}") final boolean sessionEnabled,
      @Named(FeatureFlags.NXSESSIONID_SECURE_COOKIE_NAMED) final boolean cookieSecure)
  {
    setGlobalSessionTimeout(globalSessionTimeout);
    log.info("Global session timeout: {} ms", getGlobalSessionTimeout());

    setSessionIdCookieEnabled(sessionEnabled);
    Cookie cookie = getSessionIdCookie();
    cookie.setName(sessionCookieName);
    cookie.setSecure(cookieSecure);
    
    // Set HttpOnly flag to true for enhanced security in Java 21/TLS 1.3 environment
    cookie.setHttpOnly(true);
    
    // Set SameSite attribute to Lax for better CSRF protection with modern browsers
    if (cookie instanceof org.apache.shiro.web.servlet.SimpleCookie) {
      ((org.apache.shiro.web.servlet.SimpleCookie) cookie).setSameSite("Lax");
    }
    
    log.info("Session-cookie prototype: name={}, secure={}, httpOnly={}", 
        cookie.getName(), cookie.isSecure(), cookie.isHttpOnly());
    
    // Configure session validation with virtual threads
    setSessionValidationScheduler(new VirtualThreadSessionValidationScheduler(
        this, getSessionValidationInterval()));
    enableSessionValidation();
  }

  /**
   * Overrides the {@link #onStart(Session, SessionContext)} to first check to see if the request is coming
   * on a secure channel.
   *
   * @param session The session being started
   * @param context The session context
   */
  @Override
  protected void onStart(final Session session, final SessionContext context) {
    if (WebUtils.isHttp(context)) {
      // Thread-safe update for Java 21
      requestIsHttps.get().set(WebUtils.getHttpRequest(context).isSecure());
    }
    try {
      super.onStart(session, context);
    } finally {
      if (WebUtils.isHttp(context)) {
        requestIsHttps.remove();
      }
    }
  }

  /**
   * {@link #getSessionIdCookie()} in the parent returns a "template" cookie that is passed in as an argument.
   * It represents our ONLY injection point to set the Secure flag. If we blindly set the secure flag and the
   * request came in on a plain text HTTP only channel, the browser will refuse it. We need a way to know
   * if the cookie will be used on an HTTPS channel or not.
   *
   * @return the cookie template, including a value for {@link Cookie#isSecure()} appropriate for the request.
   */
  @Override
  public Cookie getSessionIdCookie() {
    Cookie cookie = super.getSessionIdCookie();
    boolean templateValue = cookie.isSecure();
    
    // Thread-safe access for Java 21
    boolean requestIsSecure = requestIsHttps.get().get();
    
    log.trace("Setting Secure flag on session cookie: systemValue={}, requestIsSecure={}", 
        templateValue, requestIsSecure);
    
    cookie.setSecure(templateValue && requestIsSecure);
    return cookie;
  }

  /**
   * Ensures session validation is properly enabled with thread-safety for Java 21.
   * See https://issues.sonatype.org/browse/NEXUS-5727, https://issues.apache.org/jira/browse/SHIRO-443
   */
  @Override
  protected synchronized void enableSessionValidation() {
    final SessionValidationScheduler scheduler = getSessionValidationScheduler();
    if (scheduler == null) {
      super.enableSessionValidation();
    } else if (!scheduler.isEnabled()) {
      scheduler.enableSessionValidation();
    }
  }
}