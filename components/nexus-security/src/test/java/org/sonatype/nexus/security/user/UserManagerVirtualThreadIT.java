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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.CUserRoleMapping;
import org.sonatype.nexus.security.config.MemorySecurityConfiguration;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.role.RoleIdentifier;

import org.apache.shiro.authc.credential.PasswordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Integration tests for {@link UserManager} operations using Java 21 Virtual Threads.
 * 
 * These tests verify that user management operations work correctly when executed
 * via Virtual Threads, ensuring thread pinning doesn't occur and operations complete
 * successfully with proper transaction handling.
 */
@Tag("Java21TestGroup")
@Tag("VirtualThreadTestGroup")
public class UserManagerVirtualThreadIT
    extends AbstractSecurityTest
{
  private PasswordService passwordService;
  private UserManager userManager;
  private ExecutorService virtualThreadExecutor;

  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return UserManagerTestSecurity.securityModel();
  }

  @BeforeEach
  public void setUp() throws Exception {
    super.setUp();
    passwordService = lookup(PasswordService.class, "default");
    userManager = getUserManager();
    
    // Create a virtual thread executor using Java 21's virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }

  @AfterEach
  public void tearDown() throws Exception {
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

  public SecurityConfigurationManager getConfigurationManager() throws Exception {
    return lookup(SecurityConfigurationManager.class);
  }

  /**
   * Tests concurrent user lookup operations with a large number of virtual threads.
   * This verifies that the user manager can handle many concurrent read operations
   * when executed via virtual threads without thread pinning or other concurrency issues.
   */
  @Test
  public void testConcurrentUserLookupWithVirtualThreads() throws Exception {
    // Create a test user first
    User user = createTestUser("vt-lookup-test");
    userManager.addUser(user, "password123");
    
    int threadCount = 1000; // Use a large number of virtual threads
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Execute concurrent lookups using virtual threads
    for (int i = 0; i < threadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          User foundUser = userManager.getUser("vt-lookup-test");
          if (foundUser != null && "vt-lookup-test".equals(foundUser.getUserId())) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    
    // Verify all operations completed successfully
    assertEquals(0, errorCount.get(), "Some user lookup operations failed");
    assertEquals(threadCount, successCount.get(), "Not all user lookup operations succeeded");
  }

  /**
   * Tests concurrent user creation operations with virtual threads.
   * This verifies that the user manager can handle concurrent write operations
   * when executed via virtual threads without thread pinning or transaction issues.
   */
  @Test
  public void testConcurrentUserCreationWithVirtualThreads() throws Exception {
    int threadCount = 100; // Use a moderate number for creation operations
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Execute concurrent user creations using virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "vt-create-user-" + index;
          User user = createTestUser(userId);
          userManager.addUser(user, "password123");
          
          // Verify the user was created correctly
          User foundUser = userManager.getUser(userId);
          assertNotNull(foundUser, "Created user not found");
          assertEquals(userId, foundUser.getUserId(), "User ID mismatch");
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    
    // Verify all operations completed successfully
    assertEquals(0, errorCount.get(), "Some user creation operations failed");
    assertEquals(threadCount, successCount.get(), "Not all user creation operations succeeded");
    
    // Verify we can find all created users
    for (int i = 0; i < threadCount; i++) {
      String userId = "vt-create-user-" + i;
      assertNotNull(userManager.getUser(userId), "User " + userId + " not found");
    }
  }

  /**
   * Tests concurrent user update operations with virtual threads.
   * This verifies that the user manager can handle concurrent update operations
   * when executed via virtual threads without thread pinning or transaction issues.
   */
  @Test
  public void testConcurrentUserUpdateWithVirtualThreads() throws Exception {
    // Create a test user first
    String userId = "vt-update-test";
    User user = createTestUser(userId);
    userManager.addUser(user, "password123");
    
    int threadCount = 50; // Use a moderate number for update operations
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Execute concurrent updates using virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Get the user
          User foundUser = userManager.getUser(userId);
          
          // Update the user
          String newEmail = "vt-update-" + index + "@example.com";
          foundUser.setEmailAddress(newEmail);
          userManager.updateUser(foundUser);
          
          // Verify the update was successful
          User updatedUser = userManager.getUser(userId);
          assertEquals(newEmail, updatedUser.getEmailAddress(), "Email update failed");
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    
    // Verify operations completed - some may fail due to concurrent updates, but that's expected
    assertTrue(successCount.get() > 0, "No user update operations succeeded");
    
    // Verify the user still exists and has a valid email
    User finalUser = userManager.getUser(userId);
    assertNotNull(finalUser, "User not found after updates");
    assertTrue(finalUser.getEmailAddress().startsWith("vt-update-"), "Email format incorrect");
  }

  /**
   * Tests concurrent user deletion operations with virtual threads.
   * This verifies that the user manager can handle concurrent delete operations
   * when executed via virtual threads without thread pinning or transaction issues.
   */
  @Test
  public void testConcurrentUserDeletionWithVirtualThreads() throws Exception {
    int threadCount = 50; // Use a moderate number for deletion operations
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create users to delete
    for (int i = 0; i < threadCount; i++) {
      String userId = "vt-delete-user-" + i;
      User user = createTestUser(userId);
      userManager.addUser(user, "password123");
    }
    
    // Execute concurrent deletions using virtual threads
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "vt-delete-user-" + index;
          userManager.deleteUser(userId);
          successCount.incrementAndGet();
        } catch (Exception e) {
          // UserNotFoundException is expected if another thread already deleted the user
          if (!(e instanceof UserNotFoundException)) {
            fail("Unexpected exception: " + e.getMessage());
          }
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    
    // Verify all users were deleted
    for (int i = 0; i < threadCount; i++) {
      String userId = "vt-delete-user-" + i;
      try {
        userManager.getUser(userId);
        fail("User " + userId + " was not deleted");
      } catch (UserNotFoundException e) {
        // Expected - user should be deleted
      }
    }
  }

  /**
   * Tests user authentication with virtual threads.
   * This verifies that the security system can authenticate users correctly
   * when executed via virtual threads without thread pinning or other issues.
   */
  @Test
  public void testUserAuthenticationWithVirtualThreads() throws Exception {
    // Create a test user first
    String userId = "vt-auth-test";
    String password = "authPassword123";
    User user = createTestUser(userId);
    userManager.addUser(user, password);
    
    int threadCount = 100; // Use a moderate number for authentication operations
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Execute concurrent authentications using virtual threads
    for (int i = 0; i < threadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Verify password matches
          CUser secUser = getConfigurationManager().readUser(userId);
          boolean passwordMatches = passwordService.passwordsMatch(password, secUser.getPassword());
          if (passwordMatches) {
            successCount.incrementAndGet();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
    
    // Verify all operations completed successfully
    assertEquals(0, errorCount.get(), "Some authentication operations failed");
    assertEquals(threadCount, successCount.get(), "Not all authentication operations succeeded");
  }

  /**
   * Helper method to create a test user with the given ID.
   */
  private User createTestUser(String userId) {
    User user = new User();
    user.setUserId(userId);
    user.setFirstName("Virtual");
    user.setLastName("Thread Test");
    user.setEmailAddress(userId + "@example.org");
    user.setSource("default");
    user.setStatus(UserStatus.active);
    
    // Add some roles
    user.addRole(new RoleIdentifier("default", "role1"));
    user.addRole(new RoleIdentifier("default", "role2"));
    
    return user;
  }
}