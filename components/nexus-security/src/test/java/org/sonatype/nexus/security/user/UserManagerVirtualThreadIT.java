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
package org.sonatype.nexus.security.user;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.role.RoleIdentifier;

import org.apache.shiro.authc.credential.PasswordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests that verify Nexus security user management operations work correctly with Java 21 Virtual Threads.
 * Tests various user operations (lookup, creation, updates, deletion) executed via Virtual Threads to ensure thread
 * pinning doesn't occur and operations complete successfully with proper transaction handling.
 */
@ExtendWith(MockitoExtension.class)
@org.junit.jupiter.api.Tag("Java21TestGroup")
@org.junit.jupiter.api.Tag("VirtualThreadTestGroup")
public class UserManagerVirtualThreadIT
    extends AbstractSecurityTest
{
  private static final int CONCURRENT_THREADS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  
  private PasswordService passwordService;
  private ExecutorService virtualThreadExecutor;

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return UserManagerTestSecurity.securityModel();
  }

  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    passwordService = lookup(PasswordService.class, "default");
    
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    super.tearDown();
  }

  /**
   * Test concurrent user lookups using virtual threads.
   * This verifies that the UserManager can handle multiple concurrent lookups
   * without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentUserLookupWithVirtualThreads() throws Exception {
    UserManager userManager = this.getUserManager();
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    // Submit concurrent user lookup tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          User user = userManager.getUser("test-user");
          
          // Verify user properties
          assertNotNull(user);
          assertEquals("test-user", user.getUserId());
          assertEquals("test-user@example.org", user.getEmailAddress());
          assertEquals("Test User", user.getName());
          assertEquals(UserStatus.active.name(), user.getStatus().name());
          
          // Verify roles
          List<String> roleIds = getRoleIds(user);
          assertTrue(roleIds.contains("role1"));
          assertTrue(roleIds.contains("role2"));
          assertEquals(2, roleIds.size());
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all threads completed within the timeout period");
    if (errorCount.get() > 0) {
      fail("Encountered " + errorCount.get() + " errors during concurrent user lookups. Last error: " + 
          lastException.get().getMessage());
    }
  }

  /**
   * Test concurrent user creation using virtual threads.
   * This verifies that the UserManager can handle multiple concurrent user creations
   * without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentUserCreationWithVirtualThreads() throws Exception {
    UserManager userManager = this.getUserManager();
    SecurityConfigurationManager configManager = this.getConfigurationManager();
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    // Submit concurrent user creation tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "vt-user-" + index;
          
          User user = new User();
          user.setUserId(userId);
          user.setName(userId + "-name");
          user.setSource("default");
          user.setEmailAddress(userId + "@example.org");
          user.setStatus(UserStatus.active);
          user.addRole(new RoleIdentifier("default", "role1"));
          
          userManager.addUser(user, "password-" + index);
          
          // Verify user was created correctly
          CUser secUser = configManager.readUser(userId);
          assertNotNull(secUser);
          assertEquals(userId, secUser.getId());
          assertEquals(user.getEmailAddress(), secUser.getEmail());
          assertTrue(passwordService.passwordsMatch("password-" + index, secUser.getPassword()));
          
          // Verify role mapping
          CUserRoleMapping roleMapping = configManager.readUserRoleMapping(userId, "default");
          assertNotNull(roleMapping);
          assertTrue(roleMapping.getRoles().contains("role1"));
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all threads completed within the timeout period");
    if (errorCount.get() > 0) {
      fail("Encountered " + errorCount.get() + " errors during concurrent user creation. Last error: " + 
          lastException.get().getMessage());
    }
    
    // Cleanup - delete created users
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      try {
        userManager.deleteUser("vt-user-" + i);
      } 
      catch (UserNotFoundException e) {
        // Ignore if user wasn't created
      }
    }
  }

  /**
   * Test concurrent user updates using virtual threads.
   * This verifies that the UserManager can handle multiple concurrent user updates
   * without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentUserUpdateWithVirtualThreads() throws Exception {
    UserManager userManager = this.getUserManager();
    SecurityConfigurationManager configManager = this.getConfigurationManager();
    
    // First create a test user
    String userId = "vt-update-user";
    User user = new User();
    user.setUserId(userId);
    user.setName(userId + "-name");
    user.setSource("default");
    user.setEmailAddress(userId + "@example.org");
    user.setStatus(UserStatus.active);
    user.addRole(new RoleIdentifier("default", "role1"));
    userManager.addUser(user, "initial-password");
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    // Submit concurrent user update tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Get the user
          User updateUser = userManager.getUser(userId);
          
          // Update user properties
          updateUser.setName(userId + "-updated-" + index);
          updateUser.setEmailAddress(userId + "-updated-" + index + "@example.org");
          
          // Update roles - alternate between role1 and role2
          Set<RoleIdentifier> roles = new HashSet<>();
          roles.add(new RoleIdentifier("default", (index % 2 == 0) ? "role1" : "role2"));
          updateUser.setRoles(roles);
          
          // Perform the update
          userManager.updateUser(updateUser);
          
          // Change password
          userManager.changePassword(userId, "updated-password-" + index);
          
          // Verify user was updated
          CUser secUser = configManager.readUser(userId);
          assertNotNull(secUser);
          assertEquals(userId, secUser.getId());
          assertEquals(updateUser.getEmailAddress(), secUser.getEmail());
          assertTrue(passwordService.passwordsMatch("updated-password-" + index, secUser.getPassword()));
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all threads completed within the timeout period");
    if (errorCount.get() > 0) {
      fail("Encountered " + errorCount.get() + " errors during concurrent user updates. Last error: " + 
          lastException.get().getMessage());
    }
    
    // Cleanup - delete the test user
    userManager.deleteUser(userId);
  }

  /**
   * Test concurrent user deletion using virtual threads.
   * This verifies that the UserManager can handle multiple concurrent user deletions
   * without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentUserDeletionWithVirtualThreads() throws Exception {
    UserManager userManager = this.getUserManager();
    
    // First create test users
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      String userId = "vt-delete-user-" + i;
      User user = new User();
      user.setUserId(userId);
      user.setName(userId + "-name");
      user.setSource("default");
      user.setEmailAddress(userId + "@example.org");
      user.setStatus(UserStatus.active);
      user.addRole(new RoleIdentifier("default", "role1"));
      userManager.addUser(user, "delete-password-" + i);
    }
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    // Submit concurrent user deletion tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "vt-delete-user-" + index;
          userManager.deleteUser(userId);
          
          // Verify user was deleted
          try {
            userManager.getUser(userId);
            fail("User " + userId + " was not deleted");
          } 
          catch (UserNotFoundException e) {
            // Expected - user should be deleted
          }
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all threads completed within the timeout period");
    if (errorCount.get() > 0) {
      fail("Encountered " + errorCount.get() + " errors during concurrent user deletions. Last error: " + 
          lastException.get().getMessage());
    }
  }

  /**
   * Test setting user roles concurrently using virtual threads.
   * This verifies that the SecuritySystem can handle multiple concurrent role assignments
   * without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentSetUserRolesWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = this.getSecuritySystem();
    
    // First create a test user
    String userId = "vt-roles-user";
    UserManager userManager = this.getUserManager();
    User user = new User();
    user.setUserId(userId);
    user.setName(userId + "-name");
    user.setSource("default");
    user.setEmailAddress(userId + "@example.org");
    user.setStatus(UserStatus.active);
    userManager.addUser(user, "roles-password");
    
    // Create a countdown latch to wait for all threads to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();
    
    // Submit concurrent role assignment tasks
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Alternate between role1, role2, and role3
          Set<RoleIdentifier> roleIdentifiers = new HashSet<>();
          String roleId = "role" + ((index % 3) + 1);
          roleIdentifiers.add(new RoleIdentifier("default", roleId));
          
          // Set user roles
          securitySystem.setUsersRoles(userId, "default", roleIdentifiers);
          
          // Verify roles were set correctly
          User updatedUser = userManager.getUser(userId);
          List<String> roleIds = getRoleIds(updatedUser);
          assertThat(roleIds, contains(roleId));
          assertEquals(1, roleIds.size());
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    
    // Verify all threads completed successfully
    assertTrue(completed, "Not all threads completed within the timeout period");
    if (errorCount.get() > 0) {
      fail("Encountered " + errorCount.get() + " errors during concurrent role assignments. Last error: " + 
          lastException.get().getMessage());
    }
    
    // Cleanup - delete the test user
    userManager.deleteUser(userId);
  }

  /**
   * Helper method to get the SecurityConfigurationManager.
   */
  private SecurityConfigurationManager getConfigurationManager() throws Exception {
    return lookup(SecurityConfigurationManager.class);
  }

  /**
   * Helper method to extract role IDs from a user.
   */
  private List<String> getRoleIds(User user) {
    List<String> roleIds = new ArrayList<>();
    for (RoleIdentifier role : user.getRoles()) {
      roleIds.add(role.getRoleId());
    }
    return roleIds;
  }
}