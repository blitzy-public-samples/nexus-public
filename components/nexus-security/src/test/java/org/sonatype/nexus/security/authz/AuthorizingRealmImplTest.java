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
package org.sonatype.nexus.security.authz;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.internal.AuthorizingRealmImpl;
import org.sonatype.nexus.security.internal.SecurityConfigurationManagerImpl;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authz.permission.RolePermissionResolver;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AuthorizingRealmImpl}.
 */
@ExtendWith(MockitoExtension.class)
public class AuthorizingRealmImplTest
    extends AbstractSecurityTest
{
  private AuthorizingRealmImpl realm;

  private SecurityConfigurationManagerImpl configurationManager;

  @BeforeEach
  protected void setUp() throws Exception {
    super.setUp();

    realm = (AuthorizingRealmImpl) lookup(Realm.class, AuthorizingRealmImpl.NAME);
    realm.setRolePermissionResolver(this.lookup(RolePermissionResolver.class));

    configurationManager = lookup(SecurityConfigurationManagerImpl.class);
  }

  @Test
  public void shouldVerifyPermissionsCorrectly() throws Exception {
    buildTestAuthorizationConfig();

    // Fails because the configuration requirement in nexus authorizing realm isn't initialized
    // thus NPE
    SimplePrincipalCollection principal = new SimplePrincipalCollection("username", realm.getName());

    assertTrue(realm.hasRole(principal, "role"));

    // Verify the permission
    assertTrue(realm.isPermitted(principal, new WildcardPermission("app:config:read")));
    // Verify other method not allowed
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:config:create")));
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:config:update")));
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:config:delete")));

    // Verify other permission not allowed
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:ui:read")));
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:ui:create")));
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:ui:update")));
    assertFalse(realm.isPermitted(principal, new WildcardPermission("app:ui:delete")));
  }
  
  @Test
  public void shouldHandleMultiThreadedAccess() throws Exception {
    // Create test users with different permissions
    buildTestAuthorizationConfig("user1");
    buildTestAuthorizationConfig("user2");
    
    final int threadCount = 10;
    final CountDownLatch startLatch = new CountDownLatch(1);
    final AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create an executor service using virtual threads when available (Java 21+)
    ExecutorService executor = createVirtualThreadExecutor();
    
    try {
      // Create multiple threads that will check permissions concurrently
      for (int i = 0; i < threadCount; i++) {
        final String userId = "user" + (i % 2 + 1); // Alternate between user1 and user2
        
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Create principal for this thread's user
            SimplePrincipalCollection principal = new SimplePrincipalCollection(userId, realm.getName());
            
            // Verify permissions multiple times to increase chance of concurrency issues
            for (int j = 0; j < 100; j++) {
              assertTrue(realm.hasRole(principal, "role"), "User should have role");
              assertTrue(realm.isPermitted(principal, new WildcardPermission("app:config:read")), 
                  "User should have read permission");
              assertFalse(realm.isPermitted(principal, new WildcardPermission("app:config:delete")), 
                  "User should not have delete permission");
            }
          } 
          catch (Exception e) {
            failed.set(true);
            throw new RuntimeException("Error in thread for user " + userId, e);
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all threads to complete
      executor.shutdown();
      while (!executor.isTerminated()) {
        Thread.sleep(100);
      }
      
      assertFalse(failed.get(), "Multi-threaded authorization test failed");
      
    } finally {
      executor.shutdownNow();
    }
  }

  private void buildTestAuthorizationConfig() throws Exception {
    buildTestAuthorizationConfig("username");
  }

  private void buildTestAuthorizationConfig(String userId) throws Exception {
    CPrivilege priv = WildcardPrivilegeDescriptor.privilege("app:config:read");
    configurationManager.createPrivilege(priv);

    CRole role = configurationManager.newRole();
    role.setId("role");
    role.setName("somerole");
    role.setDescription("somedescription");
    role.addPrivilege(priv.getId());

    configurationManager.createRole(role);

    CUser user = new MemoryCUser();
    user.setEmail("dummyemail@foo");
    user.setFirstName("dummyFirstName");
    user.setLastName("dummyLastName");
    user.setStatus(UserStatus.active.toString());
    user.setId(userId);
    user.setPassword("password");

    Set<String> roles = new HashSet<>();
    roles.add(role.getId());

    configurationManager.createUser(user, roles);
  }
}
