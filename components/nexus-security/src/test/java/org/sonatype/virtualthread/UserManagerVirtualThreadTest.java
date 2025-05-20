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
package org.sonatype.virtualthread;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserManager;
import org.sonatype.nexus.security.user.UserNotFoundException;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authc.credential.PasswordService;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * Tests UserManager operations with Java 21 Virtual Threads, focusing on concurrent user creation, deletion, updates,
 * and role mapping operations. This test ensures that user management remains consistent and thread-safe when thousands
 * of concurrent operations are performed using virtual threads, maintaining data integrity under high load.
 */
public class UserManagerVirtualThreadTest
    extends AbstractSecurityTest
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  // Thread pinning detection flag - can be enabled to detect thread pinning issues
  static {
    // Uncomment to enable thread pinning detection
    // System.setProperty("jdk.tracePinnedThreads", "full");
  }
  
  private PasswordService passwordService;

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return UserManagerTestSecurity.securityModel();
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    passwordService = lookup(PasswordService.class, "default");
  }

  public SecurityConfigurationManager getConfigurationManager() throws Exception {
    return lookup(SecurityConfigurationManager.class);
  }

  /**
   * Tests concurrent user creation using virtual threads.
   * Verifies that multiple users can be created simultaneously without conflicts.
   */
  @Test
  public void testConcurrentUserCreation() throws Exception {
    UserManager userManager = getUserManager();
    int userCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    Set<String> createdUserIds = ConcurrentHashMap.newKeySet();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit user creation tasks
      for (int i = 0; i < userCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String userId = "vt-user-" + index + "-" + UUID.randomUUID().toString().substring(0, 8);
            User user = createTestUser(userId);
            userManager.addUser(user, "password-" + index);
            createdUserIds.add(userId);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            System.err.println("Error creating user: " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all user creation tasks completed in time", completed);
      
      // Verify all users were created successfully
      Assert.assertEquals("All users should be created successfully", userCount, successCount.get());
      
      // Verify each user exists and can be retrieved
      for (String userId : createdUserIds) {
        User user = userManager.getUser(userId);
        Assert.assertNotNull("User should exist: " + userId, user);
        Assert.assertEquals("User ID should match", userId, user.getUserId());
      }
    }
  }

  /**
   * Tests concurrent user deletion using virtual threads.
   * Creates a set of users and then deletes them concurrently.
   */
  @Test
  public void testConcurrentUserDeletion() throws Exception {
    UserManager userManager = getUserManager();
    int userCount = CONCURRENT_THREADS / 10; // Create fewer users for deletion test
    List<String> userIds = new ArrayList<>();
    
    // Create users sequentially first
    for (int i = 0; i < userCount; i++) {
      String userId = "vt-del-user-" + i;
      User user = createTestUser(userId);
      userManager.addUser(user, "password-" + i);
      userIds.add(userId);
    }
    
    // Now delete them concurrently with virtual threads
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String userId : userIds) {
        executor.submit(() -> {
          try {
            userManager.deleteUser(userId);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            System.err.println("Error deleting user " + userId + ": " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all user deletion tasks completed in time", completed);
      Assert.assertEquals("All users should be deleted successfully", userCount, successCount.get());
      
      // Verify all users were deleted
      for (String userId : userIds) {
        try {
          userManager.getUser(userId);
          Assert.fail("User should have been deleted: " + userId);
        } 
        catch (UserNotFoundException e) {
          // Expected - user should be deleted
        }
      }
    }
  }

  /**
   * Tests concurrent user updates using virtual threads.
   * Creates users and then updates their properties concurrently.
   */
  @Test
  public void testConcurrentUserUpdates() throws Exception {
    UserManager userManager = getUserManager();
    int userCount = CONCURRENT_THREADS / 10;
    List<String> userIds = new ArrayList<>();
    
    // Create users sequentially first
    for (int i = 0; i < userCount; i++) {
      String userId = "vt-upd-user-" + i;
      User user = createTestUser(userId);
      userManager.addUser(user, "password-" + i);
      userIds.add(userId);
    }
    
    // Now update them concurrently with virtual threads
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < userCount; i++) {
        final String userId = userIds.get(i);
        final int index = i;
        
        executor.submit(() -> {
          try {
            User user = userManager.getUser(userId);
            user.setEmailAddress("updated-" + index + "@example.com");
            user.setFirstName("Updated");
            user.setLastName("User-" + index);
            userManager.updateUser(user);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            System.err.println("Error updating user " + userId + ": " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all user update tasks completed in time", completed);
      Assert.assertEquals("All users should be updated successfully", userCount, successCount.get());
      
      // Verify all users were updated correctly
      for (int i = 0; i < userCount; i++) {
        String userId = userIds.get(i);
        User user = userManager.getUser(userId);
        Assert.assertEquals("Email should be updated", "updated-" + i + "@example.com", user.getEmailAddress());
        Assert.assertEquals("First name should be updated", "Updated", user.getFirstName());
        Assert.assertEquals("Last name should be updated", "User-" + i, user.getLastName());
      }
    }
  }

  /**
   * Tests concurrent role mapping operations using virtual threads.
   * Updates user roles concurrently and verifies the changes.
   */
  @Test
  public void testConcurrentRoleMapping() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    int userCount = CONCURRENT_THREADS / 10;
    List<String> userIds = new ArrayList<>();
    UserManager userManager = getUserManager();
    
    // Create users sequentially first
    for (int i = 0; i < userCount; i++) {
      String userId = "vt-role-user-" + i;
      User user = createTestUser(userId);
      userManager.addUser(user, "password-" + i);
      userIds.add(userId);
    }
    
    // Now update their roles concurrently with virtual threads
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < userCount; i++) {
        final String userId = userIds.get(i);
        final int index = i;
        
        executor.submit(() -> {
          try {
            Set<RoleIdentifier> roleIdentifiers = new HashSet<>();
            // Alternate between role1 and role2 based on index
            String roleId = (index % 2 == 0) ? "role1" : "role2";
            roleIdentifiers.add(new RoleIdentifier("default", roleId));
            
            securitySystem.setUsersRoles(userId, "default", roleIdentifiers);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            System.err.println("Error setting roles for user " + userId + ": " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all role mapping tasks completed in time", completed);
      Assert.assertEquals("All role mappings should be updated successfully", userCount, successCount.get());
      
      // Verify all role mappings were updated correctly
      SecurityConfigurationManager config = getConfigurationManager();
      for (int i = 0; i < userCount; i++) {
        String userId = userIds.get(i);
        String expectedRoleId = (i % 2 == 0) ? "role1" : "role2";
        
        CUserRoleMapping roleMapping = config.readUserRoleMapping(userId, "default");
        Assert.assertNotNull("Role mapping should exist", roleMapping);
        Assert.assertEquals("Should have exactly one role", 1, roleMapping.getRoles().size());
        Assert.assertTrue("Should have the expected role", roleMapping.getRoles().contains(expectedRoleId));
      }
    }
  }

  /**
   * Tests concurrent password changes using virtual threads.
   * Changes passwords for multiple users concurrently and verifies the changes.
   */
  @Test
  public void testConcurrentPasswordChanges() throws Exception {
    UserManager userManager = getUserManager();
    int userCount = CONCURRENT_THREADS / 10;
    List<String> userIds = new ArrayList<>();
    List<String> newPasswords = new ArrayList<>();
    
    // Create users sequentially first
    for (int i = 0; i < userCount; i++) {
      String userId = "vt-pwd-user-" + i;
      User user = createTestUser(userId);
      userManager.addUser(user, "initial-password-" + i);
      userIds.add(userId);
      newPasswords.add("new-password-" + i + "-" + UUID.randomUUID().toString().substring(0, 8));
    }
    
    // Now change passwords concurrently with virtual threads
    CountDownLatch latch = new CountDownLatch(userCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < userCount; i++) {
        final String userId = userIds.get(i);
        final String newPassword = newPasswords.get(i);
        
        executor.submit(() -> {
          try {
            userManager.changePassword(userId, newPassword);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            System.err.println("Error changing password for user " + userId + ": " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all password change tasks completed in time", completed);
      Assert.assertEquals("All passwords should be changed successfully", userCount, successCount.get());
      
      // Verify all passwords were changed correctly
      SecurityConfigurationManager config = getConfigurationManager();
      for (int i = 0; i < userCount; i++) {
        String userId = userIds.get(i);
        String newPassword = newPasswords.get(i);
        
        CUser user = config.readUser(userId);
        Assert.assertNotNull("User should exist", user);
        assertThat("Password should be updated", 
            passwordService.passwordsMatch(newPassword, user.getPassword()), is(true));
      }
    }
  }

  /**
   * Tests concurrent user search operations using virtual threads.
   * Performs many concurrent getUser operations and verifies they all succeed.
   */
  @Test
  public void testConcurrentUserSearch() throws Exception {
    UserManager userManager = getUserManager();
    
    // Create a test user first
    String userId = "vt-search-user";
    User user = createTestUser(userId);
    userManager.addUser(user, "search-password");
    
    // Now search for the user concurrently with virtual threads
    int searchCount = CONCURRENT_THREADS;
    CountDownLatch latch = new CountDownLatch(searchCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < searchCount; i++) {
        executor.submit(() -> {
          try {
            User foundUser = userManager.getUser(userId);
            if (foundUser != null && userId.equals(foundUser.getUserId())) {
              successCount.incrementAndGet();
            }
          } 
          catch (Exception e) {
            System.err.println("Error searching for user: " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all user search tasks completed in time", completed);
      Assert.assertEquals("All user searches should succeed", searchCount, successCount.get());
    }
  }
  
  /**
   * Tests event handling for user changes under virtual thread execution.
   * This test verifies that user events are properly handled when operations
   * are performed concurrently using virtual threads.
   */
  @Test
  public void testUserEventHandling() throws Exception {
    UserManager userManager = getUserManager();
    int userCount = CONCURRENT_THREADS / 10;
    
    // Create a set of users for testing events
    List<String> userIds = new ArrayList<>();
    for (int i = 0; i < userCount; i++) {
      String userId = "vt-event-user-" + i;
      User user = createTestUser(userId);
      userManager.addUser(user, "event-password-" + i);
      userIds.add(userId);
    }
    
    // Track events with a thread-safe set
    Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    CountDownLatch latch = new CountDownLatch(userCount * 2); // Update and delete events
    
    // Perform mixed operations concurrently with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // First update all users
      for (int i = 0; i < userCount; i++) {
        final String userId = userIds.get(i);
        final int index = i;
        
        executor.submit(() -> {
          try {
            User user = userManager.getUser(userId);
            user.setEmailAddress("event-updated-" + index + "@example.com");
            userManager.updateUser(user);
            processedEvents.add("update-" + userId);
          } 
          catch (Exception e) {
            System.err.println("Error updating user for event test: " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Then delete all users
      for (int i = 0; i < userCount; i++) {
        final String userId = userIds.get(i);
        
        executor.submit(() -> {
          try {
            // Small delay to ensure update completes first
            Thread.sleep(10);
            userManager.deleteUser(userId);
            processedEvents.add("delete-" + userId);
          } 
          catch (Exception e) {
            System.err.println("Error deleting user for event test: " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      Assert.assertTrue("Not all event test operations completed in time", completed);
      
      // Verify all expected events were processed
      Assert.assertEquals("All events should be processed", userCount * 2, processedEvents.size());
      
      // Verify all users were properly deleted
      for (String userId : userIds) {
        try {
          userManager.getUser(userId);
          Assert.fail("User should have been deleted: " + userId);
        } 
        catch (UserNotFoundException e) {
          // Expected - user should be deleted
        }
      }
    }
  }

  /**
   * Creates a test user with the given ID and standard test values.
   */
  /**
   * Creates a test user with the given ID and standard test values.
   */
  private User createTestUser(String userId) {
    User user = new User();
    user.setUserId(userId);
    user.setFirstName("Virtual");
    user.setLastName("Thread");
    user.setEmailAddress(userId + "@example.com");
    user.setStatus(UserStatus.active);
    user.setSource("default");
    
    // Add default roles
    user.addRole(new RoleIdentifier("default", "role1"));
    user.addRole(new RoleIdentifier("default", "role2"));
    
    return user;
  }
  
  /**
   * Executes a task concurrently using virtual threads.
   * This helper method simplifies running concurrent operations with proper error handling.
   *
   * @param count Number of concurrent operations to perform
   * @param task The task to execute for each operation
   * @param timeoutSeconds Maximum time to wait for all operations to complete
   * @return Number of successful operations
   * @throws InterruptedException if the wait is interrupted
   */
  private int runConcurrentVirtualThreads(int count, Consumer<Integer> task, int timeoutSeconds) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(count);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < count; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            task.accept(index);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            System.err.println("Error in virtual thread task: " + e.getMessage());
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
      Assert.assertTrue("Not all tasks completed in time", completed);
    }
    
    return successCount.get();
  }
}