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
package org.sonatype.nexus.security.role;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.internal.AuthenticatingRealmImpl;
import org.sonatype.nexus.security.internal.AuthorizingRealmImpl;
import org.sonatype.nexus.security.internal.SecurityConfigurationManagerImpl;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserSearchCriteria;
import org.sonatype.nexus.security.user.UserStatus;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests adding, updating, searching, authc, and authz a user that has an empty role (a role that does not contain any
 * other role or permission).
 */
public class EmptyRoleTest
    extends AbstractSecurityTest
{
  @Test
  void createEmptyRoleTest() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

    // create an empty role
    Role emptyRole = this.buildEmptyRole();

    // this should work fine
    assertThat(authManager.addRole(emptyRole), notNullValue());

    // now create a user and add it to the user
    User user = this.buildTestUser();
    user.setRoles(Collections.singleton(new RoleIdentifier(emptyRole.getSource(), emptyRole.getRoleId())));

    // create the user, this user only has an empty role
    assertThat(securitySystem.addUser(user, "test123"), notNullValue());

    Set<RoleIdentifier> emptyRoleSet = Collections.emptySet();
    user.setRoles(emptyRoleSet);
    assertThat(securitySystem.updateUser(user), notNullValue());

    // delete the empty role
    authManager.deleteRole(emptyRole.getRoleId());
  }

  /**
   * Note: this test is kinda useless, as Security system (as underlying Shiro) is not "reloadable": once created,
   * you need to toss it away and ask another instance from Guice, we cannot reload security currently.
   */
  @Test
  void reloadSecurityWithEmptyRoleTest() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

    Role emptyRole = this.buildEmptyRole();

    // this should work fine
    authManager.addRole(emptyRole);

    // make sure the role is still there
    assertNotNull(authManager.getRole(emptyRole.getRoleId()));
  }

  @Test
  void authorizeUserWithEmptyRoleTest() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);

    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of(AuthenticatingRealmImpl.NAME, AuthorizingRealmImpl.NAME));

    AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

    // create an empty role
    Role emptyRole = this.buildEmptyRole();

    // this should work fine
    authManager.addRole(emptyRole);

    Role normalRole = new Role("normalRole-" + Math.random(), "NormalRole", "Normal Role", "default", false,
            new HashSet<String>(), new HashSet<String>());

    normalRole.addPrivilege(this.createTestPriv());
    authManager.addRole(normalRole);

    // now create a user and add it to the user
    User user = this.buildTestUser();
    user.addRole(new RoleIdentifier(emptyRole.getSource(), emptyRole.getRoleId()));
    user.addRole(new RoleIdentifier(normalRole.getSource(), normalRole.getRoleId()));

    // create the user, this user only has an empty role
    securitySystem.addUser(user, "password");

    // now authorize the user
    Subject subject = securitySystem.getSubject();
    subject.login(new UsernamePasswordToken(user.getUserId(), "password"));
    // check if the user is able to be authenticated if he has an empty role
    subject.checkPermission("app:config:read");
  }

  @Test
  void searchForUserWithEmptyRoleTest() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

    // create an empty role
    Role emptyRole = this.buildEmptyRole();

    // this should work fine
    authManager.addRole(emptyRole);

    // now create a user and add it to the user
    User user = this.buildTestUser();
    user.setRoles(Collections.singleton(new RoleIdentifier(emptyRole.getSource(), emptyRole.getRoleId())));

    // create the user, this user only has an empty role
    securitySystem.addUser(user, "test123");

    Set<User> userSearchResult = securitySystem.searchUsers(
        new UserSearchCriteria(null, Collections.singleton(emptyRole.getRoleId()), null));
    // this should contain a single result
    assertEquals(1, userSearchResult.size());
    assertEquals(user.getUserId(), userSearchResult.iterator().next().getUserId());
  }

  @Test
  void concurrentRoleOperationsWithVirtualThreadsTest() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 100;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique role for each thread
            Role role = new Role(
                "role-" + index, 
                "Role " + index, 
                "Test Role " + index, 
                "default", 
                false,
                new HashSet<>(), 
                new HashSet<>());
            
            // Add the role
            authManager.addRole(role);
            
            // Verify the role was added correctly
            Role retrievedRole = authManager.getRole(role.getRoleId());
            if (retrievedRole == null || !retrievedRole.getRoleId().equals(role.getRoleId())) {
              errorCount.incrementAndGet();
            }
            
            // Delete the role
            authManager.deleteRole(role.getRoleId());
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some concurrent role operations failed");
    } finally {
      executor.shutdown();
    }
  }

  @Test
  void concurrentUserRoleAssignmentWithVirtualThreadsTest() throws Exception {
    SecuritySystem securitySystem = this.lookup(SecuritySystem.class);
    AuthorizationManager authManager = securitySystem.getAuthorizationManager("default");

    // Create a shared role
    Role sharedRole = this.buildEmptyRole();
    authManager.addRole(sharedRole);

    // Create a shared user
    User sharedUser = this.buildTestUser();
    securitySystem.addUser(sharedUser, "test123");

    // Create a virtual thread executor
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    
    int taskCount = 50;
    CountDownLatch latch = new CountDownLatch(taskCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit multiple concurrent tasks using virtual threads
      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Create a unique role for this thread
            Role threadRole = new Role(
                "thread-role-" + index, 
                "Thread Role " + index, 
                "Thread Test Role " + index, 
                "default", 
                false,
                new HashSet<>(), 
                new HashSet<>());
            
            // Add the role
            authManager.addRole(threadRole);
            
            // Add the role to the shared user
            User user = securitySystem.getUser(sharedUser.getUserId());
            user.addRole(new RoleIdentifier(threadRole.getSource(), threadRole.getRoleId()));
            securitySystem.updateUser(user);
            
            // Verify the role was added to the user
            User updatedUser = securitySystem.getUser(sharedUser.getUserId());
            boolean roleFound = false;
            for (RoleIdentifier role : updatedUser.getRoles()) {
              if (role.getRoleId().equals(threadRole.getRoleId())) {
                roleFound = true;
                break;
              }
            }
            
            if (!roleFound) {
              errorCount.incrementAndGet();
            }
            
            // Clean up - remove the role from the user
            user = securitySystem.getUser(sharedUser.getUserId());
            Set<RoleIdentifier> roles = new HashSet<>(user.getRoles());
            roles.removeIf(r -> r.getRoleId().equals(threadRole.getRoleId()));
            user.setRoles(roles);
            securitySystem.updateUser(user);
            
            // Delete the role
            authManager.deleteRole(threadRole.getRoleId());
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      assertEquals(0, errorCount.get(), "Some concurrent user role operations failed");
      
      // Clean up the shared role and user
      securitySystem.deleteUser(sharedUser.getUserId());
      authManager.deleteRole(sharedRole.getRoleId());
    } finally {
      executor.shutdown();
    }
  }

  private User buildTestUser() {
    User user = new User();
    user.setUserId("test-user-" + Math.random());
    user.setEmailAddress("test@foo.com");
    user.setFirstName("test");
    user.setLastName("user");
    user.setSource("default");
    user.setStatus(UserStatus.active);

    return user;
  }

  private String createTestPriv() throws Exception {
    CPrivilege priv = WildcardPrivilegeDescriptor.privilege("app:config:read");
    this.lookup(SecurityConfigurationManagerImpl.class).createPrivilege(priv);

    return priv.getId();
  }

  private Role buildEmptyRole() {
    Role emptyRole = new Role();
    emptyRole.setName("Empty Role");
    emptyRole.setDescription("Empty Role");
    emptyRole.setRoleId("emptyRole-" + Math.random());
    // no contained roles or privileges

    return emptyRole;
  }
}