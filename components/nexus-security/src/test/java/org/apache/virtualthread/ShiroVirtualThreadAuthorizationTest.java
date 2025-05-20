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
package org.apache.virtualthread;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.internal.AuthorizingRealmImpl;
import org.sonatype.nexus.security.internal.SecurityConfigurationManagerImpl;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.RolePermissionResolver;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests Apache Shiro's authorization mechanisms using Java 21 virtual threads.
 * 
 * This test verifies that permission checks and role-based access control work correctly
 * when executed concurrently on virtual threads. It validates that the AuthorizingRealmImpl
 * properly handles permission checks without thread interference.
 */
public class ShiroVirtualThreadAuthorizationTest
    extends AbstractSecurityTest
{
  private static final int THREAD_COUNT = 100;
  private static final int ITERATIONS = 10;
  private static final String BASE_USERNAME = "vt-user-";
  
  private AuthorizingRealmImpl realm;
  private SecurityConfigurationManagerImpl configurationManager;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  @Override
  protected void setUp() throws Exception {
    super.setUp();

    realm = (AuthorizingRealmImpl) lookup(Realm.class, AuthorizingRealmImpl.NAME);
    realm.setRolePermissionResolver(this.lookup(RolePermissionResolver.class));

    configurationManager = lookup(SecurityConfigurationManagerImpl.class);
    
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Set up test users, roles and permissions
    setupTestAuthorizationConfig();
  }

  @AfterEach
  @Override
  protected void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      } catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }
    super.tearDown();
  }

  /**
   * Tests basic authorization functionality with a single virtual thread.
   * This verifies that the basic permission checks work correctly on a virtual thread.
   */
  @Test
  public void testBasicAuthorizationOnVirtualThread() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicBoolean hasRole = new AtomicBoolean(false);
    AtomicBoolean canRead = new AtomicBoolean(false);
    AtomicBoolean canCreate = new AtomicBoolean(false);
    
    virtualThreadExecutor.submit(() -> {
      try {
        SimplePrincipalCollection principal = new SimplePrincipalCollection(BASE_USERNAME + "0", realm.getName());
        
        // Test role check
        hasRole.set(realm.hasRole(principal, "role-0"));
        
        // Test permission checks
        canRead.set(realm.isPermitted(principal, new WildcardPermission("app:config:read")));
        canCreate.set(realm.isPermitted(principal, new WildcardPermission("app:config:create")));
      } finally {
        latch.countDown();
      }
    });
    
    assertTrue(latch.await(5, TimeUnit.SECONDS), "Test did not complete in time");
    assertTrue(hasRole.get(), "User should have role");
    assertTrue(canRead.get(), "User should have read permission");
    assertFalse(canCreate.get(), "User should not have create permission");
  }

  /**
   * Tests concurrent authorization checks with multiple virtual threads.
   * This verifies that multiple virtual threads can perform permission checks
   * concurrently without interference.
   */
  @Test
  public void testConcurrentAuthorizationChecks() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    ConcurrentHashMap<String, Boolean> results = new ConcurrentHashMap<>();
    
    // Submit tasks to virtual threads
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int userId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String username = BASE_USERNAME + userId;
          SimplePrincipalCollection principal = new SimplePrincipalCollection(username, realm.getName());
          
          // Each user should have their own role
          boolean hasRole = realm.hasRole(principal, "role-" + userId);
          results.put(username + "-role", hasRole);
          
          // Each user should have read permission
          boolean canRead = realm.isPermitted(principal, new WildcardPermission("app:config:read"));
          results.put(username + "-read", canRead);
        } finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent tests did not complete in time");
    
    // Verify all results
    for (int i = 0; i < THREAD_COUNT; i++) {
      String username = BASE_USERNAME + i;
      assertTrue(results.get(username + "-role"), "User " + username + " should have role");
      assertTrue(results.get(username + "-read"), "User " + username + " should have read permission");
    }
  }

  /**
   * Tests repeated authorization checks with the same virtual thread.
   * This verifies that authorization caching works correctly with virtual threads.
   */
  @Test
  public void testRepeatedAuthorizationChecks() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int userId = i % 10; // Reuse users to test caching
      virtualThreadExecutor.submit(() -> {
        try {
          String username = BASE_USERNAME + userId;
          SimplePrincipalCollection principal = new SimplePrincipalCollection(username, realm.getName());
          
          // Perform multiple permission checks to test caching
          boolean allChecksSucceeded = true;
          for (int j = 0; j < ITERATIONS; j++) {
            boolean hasRole = realm.hasRole(principal, "role-" + userId);
            boolean canRead = realm.isPermitted(principal, new WildcardPermission("app:config:read"));
            
            if (!hasRole || !canRead) {
              allChecksSucceeded = false;
              break;
            }
            
            // Small delay to simulate work
            Thread.sleep(10);
          }
          
          if (allChecksSucceeded) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          fail("Exception during repeated authorization checks: " + e.getMessage());
        } finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Repeated tests did not complete in time");
    assertEquals(THREAD_COUNT, successCount.get(), "All authorization checks should succeed");
  }

  /**
   * Tests concurrent permission resolution with multiple virtual threads.
   * This verifies that permission resolution works correctly when multiple
   * virtual threads are resolving permissions concurrently.
   */
  @Test
  public void testConcurrentPermissionResolution() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    ConcurrentHashMap<Integer, Set<String>> permissionResults = new ConcurrentHashMap<>();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int userId = i % 10; // Reuse users to test caching
      virtualThreadExecutor.submit(() -> {
        try {
          String username = BASE_USERNAME + userId;
          SimplePrincipalCollection principal = new SimplePrincipalCollection(username, realm.getName());
          
          // Test different permission patterns
          List<Permission> permissions = List.of(
              new WildcardPermission("app:config:read"),
              new WildcardPermission("app:config:*"),
              new WildcardPermission("app:ui:read"),
              new WildcardPermission("app:*:read")
          );
          
          Set<String> grantedPermissions = new HashSet<>();
          for (Permission permission : permissions) {
            if (realm.isPermitted(principal, permission)) {
              grantedPermissions.add(permission.toString());
            }
          }
          
          permissionResults.put(userId, grantedPermissions);
        } finally {
          latch.countDown();
        }
      });
    }
    
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Permission resolution tests did not complete in time");
    
    // Verify results - each user should have consistent permissions
    for (int i = 0; i < 10; i++) {
      Set<String> permissions = permissionResults.get(i);
      assertTrue(permissions.contains("app:config:read"), "User should have app:config:read permission");
      assertTrue(permissions.contains("app:config:*"), "User should have app:config:* permission");
      assertFalse(permissions.contains("app:ui:read"), "User should not have app:ui:read permission");
      assertFalse(permissions.contains("app:*:read"), "User should not have app:*:read permission");
    }
  }

  /**
   * Sets up test users, roles, and permissions for authorization testing.
   * Creates multiple users with different roles and permissions to test
   * various authorization scenarios.
   */
  private void setupTestAuthorizationConfig() throws Exception {
    // Create a read privilege that all users will have
    CPrivilege readPrivilege = WildcardPrivilegeDescriptor.privilege("app:config:read");
    configurationManager.createPrivilege(readPrivilege);
    
    // Create roles and users for testing
    for (int i = 0; i < 10; i++) {
      CRole role = configurationManager.newRole();
      role.setId("role-" + i);
      role.setName("Role " + i);
      role.setDescription("Test role " + i);
      role.addPrivilege(readPrivilege.getId());
      
      configurationManager.createRole(role);
      
      CUser user = new MemoryCUser();
      user.setEmail("vt-user-" + i + "@example.com");
      user.setFirstName("VT");
      user.setLastName("User " + i);
      user.setStatus(UserStatus.active.toString());
      user.setId(BASE_USERNAME + i);
      user.setPassword("password");
      
      Set<String> roles = new HashSet<>();
      roles.add(role.getId());
      
      configurationManager.createUser(user, roles);
    }
  }
}