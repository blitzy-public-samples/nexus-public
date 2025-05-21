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

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.sonatype.nexus.security.user.UserManager;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.eclipse.sisu.Description;

/**
 * Mock realm implementation for testing authentication with Shiro 2.0.0 and Java 21 virtual threads.
 * This realm is optimized for concurrent execution in virtual thread environments.
 */
@Singleton
@Named("MockRealmA")
@Description("MockRealmA")
public class MockRealmA
    extends AuthenticatingRealm
{
  @Inject
  public MockRealmA(@Named("MockUserManagerA") UserManager userManager) {
    this.setAuthenticationTokenClass(UsernamePasswordToken.class);
  }

  /**
   * Performs authentication for the given token.
   * This implementation is optimized for virtual threads by avoiding synchronization
   * and thread-local variables that could cause pinning.
   *
   * @param token the authentication token containing the user's principal and credentials
   * @return an {@code AuthenticationInfo} object containing account data resulting from the
   *         authentication if successful, or null if not
   * @throws AuthenticationException if there is an error acquiring data or performing
   *         realm-specific authentication logic for the specified token
   */
  @Override
  protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token)
      throws AuthenticationException
  {
    // Virtual thread-friendly implementation - no synchronization or thread locals used
    if (!(token instanceof UsernamePasswordToken userpass)) {
      return null;
    }
    
    // Using local variables to avoid shared state issues in concurrent environments
    String username = userpass.getUsername();
    char[] password = userpass.getPassword();
    
    // Perform authentication check without blocking or pinning virtual threads
    if (username != null && password != null && 
        "jcoder".equals(username) && "jcoder".equals(new String(password))) {
      // Create immutable authentication info object for thread safety
      return new SimpleAuthenticationInfo(username, new String(password), getName());
    }

    return null;
  }

  @Override
  public String getName() {
    return "MockRealmA";
  }
}