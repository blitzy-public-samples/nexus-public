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

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.sonatype.goodies.common.Time;
import org.sonatype.nexus.cache.CacheHelper;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.security.UserIdMdcHelper;
import org.sonatype.nexus.security.authc.AuthenticationEvent;
import org.sonatype.nexus.security.authc.AuthenticationFailureReason;
import org.sonatype.nexus.security.authc.LoginEvent;
import org.sonatype.nexus.security.authc.NexusAuthenticationException;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.session.mgt.eis.CachingSessionDAO;
import org.apache.shiro.session.mgt.eis.SessionDAO;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.mgt.DefaultWebSecurityManager;
import org.apache.shiro.web.mgt.WebSecurityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Collections.emptySet;
import static org.sonatype.nexus.common.app.ManagedLifecycleManager.isShuttingDown;

/**
 * Custom {@link WebSecurityManager} with Java 21 enhancements.
 *
 * @since 2.7.2
 */
public class NexusWebSecurityManager
    extends DefaultWebSecurityManager
{
  private static final Logger log = LoggerFactory.getLogger(NexusWebSecurityManager.class);
  
  private final Provider<EventManager> eventManager;
  
  // Virtual thread executor for authentication processing
  private final Executor virtualThreadExecutor;

  @Inject
  public NexusWebSecurityManager(final Provider<EventManager> eventManager,
                                 final Provider<CacheHelper> cacheHelper,
                                 @Named("${nexus.shiro.cache.defaultTimeToLive:-2m}") final Provider<Time> defaultTimeToLive)
  {
    this.eventManager = checkNotNull(eventManager);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Configure cache manager with Shiro 2.0.0 compatible adapter
    setCacheManager(new ShiroJCacheManagerAdapter(cacheHelper, defaultTimeToLive));
    
    // Explicitly disable rememberMe
    this.setRememberMeManager(null);
    
    log.debug(STR."Initialized \{getClass().getSimpleName()} with virtual thread support");
  }

  /**
   * Post {@link AuthenticationEvent} using virtual threads for improved concurrency.
   */
  private void post(
      final AuthenticationToken token,
      final boolean successful,
      final Set<AuthenticationFailureReason> authenticationFailureReasons)
  {
    // Use virtual threads for event posting to improve concurrency
    virtualThreadExecutor.execute(() -> {
      String principal = token.getPrincipal().toString();
      log.debug(STR."Posting authentication event for principal: \{principal}, successful: \{successful}");
      eventManager.get()
          .post(new AuthenticationEvent(principal, successful, authenticationFailureReasons));
    });
  }

  /**
   * After login set the userId MDC attribute.
   * Enhanced with virtual threads and Java 21 String Templates for logging.
   */
  @Override
  public Subject login(Subject subject, final AuthenticationToken token) {
    // Anonymous user isn't allowed to authenticate
    if ("anonymous".equals(token.getPrincipal())) {
      log.debug(STR."Rejecting authentication attempt for anonymous user");
      throw new AuthenticationException("Cannot login with anonymous user");
    }
    
    try {
      // Perform authentication
      subject = super.login(subject, token);
      
      // Set MDC context
      UserIdMdcHelper.set(subject);
      
      // Post authentication event
      post(token, true, emptySet());
      
      // Handle SAML realm login events
      String principal = subject.getPrincipal().toString();
      Optional<String> realmName = subject.getPrincipals().getRealmNames().stream()
          .filter(realm -> realm.equals("SamlRealm"))
          .findFirst();
      
      // Post login event for SAML realm using virtual threads
      realmName.ifPresent(realm -> 
          virtualThreadExecutor.execute(() -> 
              eventManager.get().post(new LoginEvent(principal, realm))
          )
      );

      log.debug(STR."Successfully authenticated principal: \{principal}");
      return subject;
    }
    catch (NexusAuthenticationException e) {
      log.debug(STR."Authentication failed for token: \{token.getPrincipal()}, reason: \{e.getMessage()}");
      post(token, false, e.getAuthenticationFailureReasons());
      throw e;
    }
    catch (AuthenticationException e) {
      log.debug(STR."Authentication failed for token: \{token.getPrincipal()}, reason: \{e.getMessage()}");
      post(token, false, emptySet());
      throw e;
    }
  }

  /**
   * After logout unset the userId MDC attribute.
   * Enhanced with Java 21 String Templates for logging.
   */
  @Override
  public void logout(final Subject subject) {
    if (subject != null && subject.getPrincipal() != null) {
      log.debug(STR."Logging out principal: \{subject.getPrincipal()}");
    }
    super.logout(subject);
    UserIdMdcHelper.unset();
  }

  @Override
  public void destroy() {
    // Underlying manager cannot be restarted, so avoid shutting it down when bouncing the service
    if (isShuttingDown()) {
      log.debug(STR."Shutting down \{getClass().getSimpleName()}");
      super.destroy();
    }
    else {
      // Null out the session cache to force it to be recreated on the next request after bouncing
      SessionDAO sessionDAO = ((NexusWebSessionManager) getSessionManager()).getSessionDAO();
      if (sessionDAO instanceof CachingSessionDAO cachingSessionDAO) {
        log.debug(STR."Clearing session cache for \{sessionDAO.getClass().getSimpleName()}");
        cachingSessionDAO.setActiveSessionsCache(null);
      }
    }
  }
}