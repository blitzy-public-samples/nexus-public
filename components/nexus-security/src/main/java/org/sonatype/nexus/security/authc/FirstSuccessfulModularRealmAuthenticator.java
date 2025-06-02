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

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.CredentialsException;
import org.apache.shiro.authc.DisabledAccountException;
import org.apache.shiro.authc.ExpiredCredentialsException;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.pam.ModularRealmAuthenticator;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This Authenticator will try to authenticate with each realm in parallel using Virtual Threads.
 * The first successful {@link AuthenticationInfo} found will be returned
 * (only if an authenticated user have the same realm) and other realms will not be queried.
 *
 * @see ModularRealmAuthenticator
 * @since 3.60
 */
public class FirstSuccessfulModularRealmAuthenticator
    extends ModularRealmAuthenticator
{
  private static final Logger log = LoggerFactory.getLogger(FirstSuccessfulModularRealmAuthenticator.class);

  @Override
  protected AuthenticationInfo doMultiRealmAuthentication(
      final Collection<Realm> realms,
      final AuthenticationToken token)
  {
    log.trace("Iterating through [{}] realms for PAM authentication", realms.size());

    Set<AuthenticationFailureReason> authenticationFailureReasons = ConcurrentHashMap.newKeySet();
    Subject subject = SecurityUtils.getSubject();
    AtomicReference<AuthenticationInfo> successfulAuthInfo = new AtomicReference<>();

    // Create a thread pool using virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit authentication tasks for each realm that supports the token
      Collection<Future<?>> futures = realms.stream()
          .filter(realm -> realm.supports(token))
          .map(realm -> executor.submit(() -> {
            try {
              if (successfulAuthInfo.get() != null) {
                // Another realm already succeeded, no need to continue
                return;
              }
              
              log.trace("Attempting to authenticate token [{}] using realm of type [{}]", token, realm);
              AuthenticationInfo info = realm.getAuthenticationInfo(token);
              
              if (info != null) {
                Set<String> realmNames = info.getPrincipals().getRealmNames();
                if (subject.isAuthenticated() && !subject.getPrincipals().getRealmNames().containsAll(realmNames)) {
                  // authenticated user with the different realm - continue with the others
                  return;
                }
                // Set the successful authentication info if not already set
                successfulAuthInfo.compareAndSet(null, info);
              } else {
                log.trace("Realm [{}] returned null when authenticating token [{}]", realm, token);
              }
            } catch (Exception e) {
              // Use pattern matching to handle different exception types
              switch (e) {
                case DisabledAccountException dae -> {
                  logExceptionForRealm(dae, realm);
                  authenticationFailureReasons.add(AuthenticationFailureReason.DISABLED_ACCOUNT);
                }
                case ExpiredCredentialsException ece -> {
                  logExceptionForRealm(ece, realm);
                  authenticationFailureReasons.add(AuthenticationFailureReason.EXPIRED_CREDENTIALS);
                }
                case IncorrectCredentialsException ice -> {
                  logExceptionForRealm(ice, realm);
                  authenticationFailureReasons.add(AuthenticationFailureReason.INCORRECT_CREDENTIALS);
                }
                case UnknownAccountException uae -> {
                  logExceptionForRealm(uae, realm);
                  authenticationFailureReasons.add(AuthenticationFailureReason.USER_NOT_FOUND);
                }
                case CredentialsException ce -> {
                  logExceptionForRealm(ce, realm);
                  authenticationFailureReasons.add(AuthenticationFailureReason.PASSWORD_EMPTY);
                }
                case AuthenticationException ae -> {
                  logExceptionForRealm(ae, realm);
                  authenticationFailureReasons.add(AuthenticationFailureReason.UNKNOWN);
                }
                default -> logExceptionForRealm(e, realm);
              }
            }
          }))
          .toList();

      // Wait for all authentication attempts to complete
      for (Future<?> future : futures) {
        try {
          future.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          log.warn("Authentication thread was interrupted: {}", e.getMessage());
        } catch (ExecutionException e) {
          log.error("Error during parallel realm authentication: {}", e.getCause().getMessage(), e.getCause());
        }
      }
    }

    // Check if any realm successfully authenticated
    AuthenticationInfo authInfo = successfulAuthInfo.get();
    if (authInfo != null) {
      return authInfo;
    }

    // If we get here, no realm could authenticate the token
    throw new NexusAuthenticationException("Authentication token of type [" + token.getClass() + "] "
        + "could not be authenticated by any configured realms. Please ensure that at least one realm can "
        + "authenticate these tokens.", authenticationFailureReasons);
  }

  private void logExceptionForRealm(Throwable t, Realm realm) {
    log.trace("Realm [{}] threw an exception during a multi-realm authentication attempt", realm, t);
  }
}