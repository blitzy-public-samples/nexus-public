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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.eclipse.sisu.Description;

/**
 * Mock realm implementation for testing purposes.
 * Updated for Java 21 and Shiro 2.0.0 compatibility with virtual thread support.
 */
@Singleton
@Named("MockRealmB")
@Description("MockRealmB")
public class MockRealmB
    extends AuthorizingRealm
{
  public MockRealmB() {
    this.setAuthenticationTokenClass(UsernamePasswordToken.class);
  }

  /**
   * Authentication implementation that supports virtual threads by avoiding blocking operations.
   * Only allows jcool/jcool credentials for testing purposes.
   */
  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
    // Using Java 21 pattern matching for more concise credential validation
    if (token instanceof UsernamePasswordToken userpass) {
      if ("jcool".equals(userpass.getUsername()) && "jcool".equals(new String(userpass.getPassword()))) {
        return new SimpleAuthenticationInfo(userpass.getUsername(), new String(userpass.getPassword()), this.getName());
      }
    }

    return null;
  }

  /**
   * Authorization implementation that supports virtual threads by avoiding blocking operations.
   * Assigns test roles and permissions for the jcool user.
   */
  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    // Thread-safe check for the principal
    if (principals != null && !principals.isEmpty()) {
      String username = principals.getPrimaryPrincipal().toString();
      if ("jcool".equals(username)) {
        // Create authorization info in a thread-safe manner
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();

        // Add roles in a non-blocking manner
        info.addRole("test-role1");
        info.addRole("test-role2");

        // Add permissions in a non-blocking manner
        info.addStringPermission("test:*");

        return info;
      }
    }

    return null;
  }

  /**
   * Non-blocking asynchronous authorization check for virtual thread compatibility.
   * This method can be used when performing authorization checks in a virtual thread context.
   *
   * @param principals the principals to check
   * @return a CompletionStage with the AuthorizationInfo
   */
  public CompletionStage<AuthorizationInfo> getAuthorizationInfoAsync(PrincipalCollection principals) {
    return CompletableFuture.supplyAsync(() -> doGetAuthorizationInfo(principals));
  }

  /**
   * Non-blocking asynchronous authentication check for virtual thread compatibility.
   * This method can be used when performing authentication in a virtual thread context.
   *
   * @param token the authentication token
   * @return a CompletionStage with the AuthenticationInfo
   */
  public CompletionStage<AuthenticationInfo> getAuthenticationInfoAsync(AuthenticationToken token) {
    return CompletableFuture.supplyAsync(() -> {
      try {
        return doGetAuthenticationInfo(token);
      } catch (AuthenticationException e) {
        throw new RuntimeException(e);
      }
    });
  }

  @Override
  public String getName() {
    return "MockRealmB";
  }
}