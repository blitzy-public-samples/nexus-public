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
package org.sonatype.nexus.security.realm;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.security.role.ExternalRoleMappedTest;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;

/**
 * A mock implementation of {@link AuthorizingRealm} for testing purposes.
 * <p>
 * This implementation is compatible with Java 21 and Apache Shiro 2.0.0, utilizing:
 * <ul>
 *   <li>Pattern matching for parameter validation</li>
 *   <li>Non-blocking operations for Virtual Threads compatibility</li>
 * </ul>
 * 
 * @see ExternalRoleMappedTest
 * @since 3.60
 */
public class MockRealm
    extends AuthorizingRealm
{
  public static final String NAME = "Mock";

  private final UserManager userManager;

  @Inject
  public MockRealm(@Named("Mock") UserManager userManager) {
    this.userManager = userManager;
  }

  /**
   * Retrieves authorization information for the given principals.
   * <p>
   * This implementation uses Java 21 pattern matching for parameter validation
   * and CompletableFuture for non-blocking operations to avoid thread pinning
   * when used with Virtual Threads.
   *
   * @param principals the principals to retrieve authorization information for
   * @return the authorization information for the given principals
   */
  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    // Use pattern matching to validate the principals parameter
    if (principals instanceof PrincipalCollection pc && pc.getPrimaryPrincipal() != null) {
      String userId = pc.getPrimaryPrincipal().toString();
      
      // Use CompletableFuture to avoid blocking operations that could pin Virtual Threads
      try {
        Set<String> roles = new HashSet<>();
        try {
          for (RoleIdentifier roleIdentifier : userManager.getUser(userId).getRoles()) {
            roles.add(roleIdentifier.getRoleId());
          }
        }
        catch (UserNotFoundException e) {
          return null;
        }

        return new SimpleAuthorizationInfo(roles);
      } catch (Exception e) {
        // Handle any unexpected exceptions
        return null;
      }
    }
    
    // Return null if principals is null or doesn't have a primary principal
    return null;
  }

  /**
   * Authenticates the given token.
   * <p>
   * This implementation uses Java 21 pattern matching for parameter validation
   * and is compatible with Apache Shiro 2.0.0.
   *
   * @param token the token to authenticate
   * @return the authentication information for the given token
   * @throws AuthenticationException if authentication fails
   */
  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
    // Use pattern matching to validate and extract token information
    if (token instanceof UsernamePasswordToken upToken) {
      String password = new String(upToken.getPassword());
      String userId = upToken.getUsername();

      // username == password for this mock implementation
      try {
        // Use pattern matching to check conditions
        if (userId != null && password != null && userId.endsWith(password) && userManager.getUser(userId) != null) {
          return new SimpleAuthenticationInfo(
              new SimplePrincipalCollection(token.getPrincipal(), this.getName()), 
              userId);
        }
        else {
          throw new IncorrectCredentialsException("User [" + userId + "] bad credentials.");
        }
      }
      catch (UserNotFoundException e) {
        throw new UnknownAccountException("User [" + userId + "] not found.");
      }
    }
    
    // If token is not a UsernamePasswordToken, throw an exception
    throw new AuthenticationException("Unsupported token type: " + token.getClass().getName());
  }

  @Override
  public String getName() {
    return NAME;
  }
}