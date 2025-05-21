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
package org.sonatype.nexus.internal.security.jaas;

import java.io.IOException;
import java.security.Principal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.security.Roles;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;

import org.apache.karaf.jaas.boot.principal.RolePrincipal;
import org.apache.karaf.jaas.boot.principal.UserPrincipal;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.support.SubjectThreadState;
import org.apache.shiro.util.ThreadState;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * JAAS {@link LoginModule} that delegates to Shiro for authentication.
 * Updated for Java 21 Virtual Threads compatibility.
 *
 * @since 3.0
 */
public class ShiroLoginModule
    extends ComponentSupport
    implements LoginModule
{
  private final Set<Principal> principals = new HashSet<>();

  private Subject jaasSubject;

  private CallbackHandler callbackHandler;

  private SecurityHelper securityHelper;

  private SecuritySystem securitySystem;

  private User user;

  @Override
  public void initialize(
      final Subject jaasSubject,
      final CallbackHandler callbackHandler,
      final Map<String, ?> sharedState,
      final Map<String, ?> options)
  {
    this.jaasSubject = checkNotNull(jaasSubject);
    this.callbackHandler = checkNotNull(callbackHandler);

    securityHelper = (SecurityHelper) checkNotNull(options.get(SecurityHelper.class.getName()));
    securitySystem = (SecuritySystem) checkNotNull(options.get(SecuritySystem.class.getName()));
  }

  @Override
  public boolean login() throws LoginException {
    // Create callbacks for username and password
    Callback[] callbacks = new Callback[2];
    callbacks[0] = new NameCallback("Username: ");
    callbacks[1] = new PasswordCallback("Password: ", false);

    try {
      // Handle callbacks to get credentials
      callbackHandler.handle(callbacks);
    }
    catch (IOException | UnsupportedCallbackException e) {
      log.debug("Missing credentials", e);
      throw new LoginException(e.getMessage());
    }

    // Get the current Shiro subject
    org.apache.shiro.subject.Subject shiroSubject = securityHelper.subject();
    checkState(shiroSubject != null);

    // Create a thread state to ensure proper context propagation with virtual threads
    final ThreadState threadState = new SubjectThreadState(shiroSubject);
    
    try {
      // Execute login within a properly bound thread context to support virtual threads
      return executeWithThreadState(threadState, () -> {
        try {
          // Attempt to login with the provided credentials
          shiroSubject.login(
              new UsernamePasswordToken(
                  ((NameCallback) callbacks[0]).getName(),
                  ((PasswordCallback) callbacks[1]).getPassword()));

          // Check if the user has a non-anonymous role
          if (!shiroSubject.hasRole(Roles.ANONYMOUS_ROLE_ID)) {
            user = securitySystem.getUser(shiroSubject.getPrincipal().toString());
            return true;
          }
          else {
            throw new LoginException("Invalid username or password");
          }
        }
        catch (AuthenticationException | UserNotFoundException e) {
          log.debug("Authentication failed", e);
          throw new LoginException("Invalid username or password");
        }
        finally {
          if (user == null) {
            shiroSubject.logout();
          }
        }
      });
    }
    catch (LoginException e) {
      throw e;
    }
    catch (Exception e) {
      log.error("Unexpected error during login", e);
      throw new LoginException("Authentication failed: " + e.getMessage());
    }
  }

  /**
   * Executes the given callable with the thread state properly bound and restored.
   * This ensures proper thread context handling for both platform and virtual threads.
   *
   * @param threadState the thread state to bind
   * @param callable the callable to execute
   * @return the result of the callable
   * @throws Exception if the callable throws an exception
   */
  private <V> V executeWithThreadState(ThreadState threadState, Callable<V> callable) throws Exception {
    threadState.bind();
    try {
      return callable.call();
    }
    finally {
      threadState.restore();
    }
  }

  @Override
  public boolean commit() throws LoginException {
    if (user != null) {
      principals.add(new UserPrincipal(user.getUserId()));
      for (RoleIdentifier role : user.getRoles()) {
        String roleId = role.getRoleId();
        if (Roles.ADMIN_ROLE_ID.equals(roleId)) {
          // Karaf default roles implied by nx-admin
          principals.add(new RolePrincipal("admin"));
          principals.add(new RolePrincipal("manager"));
          principals.add(new RolePrincipal("viewer"));
        }
        else if (roleId.startsWith("karaf-")) {
          // flatten Karaf name-spaced roles by removing prefix
          principals.add(new RolePrincipal(roleId.substring(6)));
        }
        // ignore non-admin/non-karaf roles...
      }
      jaasSubject.getPrincipals().addAll(principals);
      return true;
    }
    else {
      return clearState();
    }
  }

  @Override
  public boolean abort() throws LoginException {
    return clearState();
  }

  @Override
  public boolean logout() throws LoginException {
    return clearState();
  }

  /**
   * Clears cached user state; returns {@code true} if user was authenticated, otherwise {@code false}.
   * Updated to properly handle thread context with virtual threads.
   */
  private boolean clearState() {
    if (user != null) {
      // Clear JAAS subject principals
      jaasSubject.getPrincipals().removeAll(principals);
      principals.clear();
      user = null;

      // Get the current Shiro subject
      org.apache.shiro.subject.Subject shiroSubject = securityHelper.subject();
      if (shiroSubject != null) {
        // Create a thread state to ensure proper context propagation with virtual threads
        final ThreadState threadState = new SubjectThreadState(shiroSubject);
        try {
          // Execute logout within a properly bound thread context
          executeWithThreadState(threadState, () -> {
            shiroSubject.logout();
            return null;
          });
        }
        catch (Exception e) {
          log.error("Error during logout", e);
        }
      }
      return true;
    }
    else {
      return false;
    }
  }
}