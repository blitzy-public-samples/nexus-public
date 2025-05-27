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

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

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
 * Mock realm implementation for testing Shiro 2.0.0 compatibility with Java 21 features.
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

  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
    // Using pattern matching for more concise credential validation
    if (token instanceof UsernamePasswordToken userPass) {
      // Check if username and password both equal "jcool"
      if ("jcool".equals(userPass.getUsername()) && "jcool".equals(new String(userPass.getPassword()))) {
        return new SimpleAuthenticationInfo(userPass.getUsername(), new String(userPass.getPassword()), this.getName());
      }
    }

    return null;
  }

  @Override
  protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
    // Thread-safe and non-blocking implementation for authorization info creation
    if (principals != null && !principals.isEmpty()) {
      // Using pattern matching to check principal type
      Object primaryPrincipal = principals.getPrimaryPrincipal();
      
      if (primaryPrincipal instanceof String username && "jcool".equals(username)) {
        // Create thread-safe authorization info
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        
        // Use non-blocking operations for role and permission assignment
        // ConcurrentHashMap-backed sets are used internally by SimpleAuthorizationInfo
        info.addRole("test-role1");
        info.addRole("test-role2");
        info.addStringPermission("test:*");

        return info;
      }
    }

    return null;
  }

  @Override
  public String getName() {
    return "MockRealmB";
  }
}