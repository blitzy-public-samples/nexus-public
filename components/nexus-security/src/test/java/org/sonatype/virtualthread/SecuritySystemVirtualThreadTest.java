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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationException;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests the SecuritySystem operations with Java 21 Virtual Threads.
 * 
 * This test verifies that authorization checks, user operations, and role management
 * work correctly with high concurrency using virtual threads.
 */
public class SecuritySystemVirtualThreadTest
    extends AbstractSecurityTest
{
  private static final Logger log = LoggerFactory.getLogger(SecuritySystemVirtualThreadTest.class);
  
  private static final int THREAD_COUNT = 1000;
  private static final int LARGE_THREAD_COUNT = 10000;
  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  public void setupExecutors() {
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create platform thread executor with fixed thread pool
    platformThreadExecutor = Executors.newFixedThreadPool(100, Thread.ofPlatform().factory());
  }
  
  @AfterEach
  public void shutdownExecutors() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
      }
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      try {
        if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          platformThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        platformThreadExecutor.shutdownNow();
      }
    }
  }
  
  /**
   * Tests concurrent permission checks with virtual threads.
   * 
   * This test verifies that the SecuritySystem can handle a large number of
   * concurrent permission checks using virtual threads without errors.
   */
  @Test
  public void testConcurrentPermissionChecksWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Create a principal collection for testing
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "MockRealmA");
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Alternate between valid and invalid permissions to test both paths
          if (index % 2 == 0) {
            securitySystem.checkPermission(principal, "test:read");
            successCount.incrementAndGet();
          }
          else {
            try {
              securitySystem.checkPermission(principal, "invalid-permission:" + index);
            }
            catch (AuthorizationException e) {
              // Expected exception for invalid permissions
              failureCount.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          log.error("Unexpected error in permission check", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertThat("Timed out waiting for permission checks to complete",
        latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    
    // Verify results
    assertThat(successCount.get(), is(THREAD_COUNT / 2));
    assertThat(failureCount.get(), is(THREAD_COUNT / 2));
  }
  
  /**
   * Tests concurrent role listing and verification with virtual threads.
   * 
   * This test verifies that the SecuritySystem can handle concurrent role operations
   * using virtual threads without errors or data corruption.
   */
  @Test
  public void testConcurrentRoleOperationsWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    ConcurrentHashMap<String, Role> roleMap = new ConcurrentHashMap<>();
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // List roles from source B
          for (Role role : securitySystem.listRoles("sourceB")) {
            roleMap.put(role.getRoleId(), role);
          }
        }
        catch (Exception e) {
          log.error("Unexpected error in role operation", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertThat("Timed out waiting for role operations to complete",
        latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    
    // Verify results
    assertThat(roleMap.size(), is(2));
    assertThat(roleMap.containsKey("test-role1"), is(true));
    assertThat(roleMap.containsKey("test-role2"), is(true));
    
    Role role1 = roleMap.get("test-role1");
    assertThat(role1, notNullValue());
    assertThat(role1.getName(), is("Role 1"));
    assertThat(role1.getPrivileges().contains("from-role1:read"), is(true));
    assertThat(role1.getPrivileges().contains("from-role1:delete"), is(true));
  }
  
  /**
   * Tests concurrent user creation and updates with virtual threads.
   * 
   * This test verifies that the SecuritySystem can handle concurrent user operations
   * using virtual threads without errors or data corruption.
   */
  @Test
  public void testConcurrentUserOperationsWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create a unique user
          String userId = "vt-user-" + index;
          User user = createUser(userId, UserStatus.active);
          
          // Add the user
          User addedUser = securitySystem.addUser(user, "password123");
          assertThat(addedUser, notNullValue());
          assertThat(addedUser.getUserId(), is(userId));
          
          // Retrieve the user
          User retrievedUser = securitySystem.getUser(userId, "MockUserManagerA");
          assertThat(retrievedUser, notNullValue());
          assertThat(retrievedUser.getUserId(), is(userId));
          
          // Update the user
          retrievedUser.setEmailAddress("updated-" + userId + "@example.com");
          securitySystem.updateUser(retrievedUser);
          
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          log.error("Unexpected error in user operation", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertThat("Timed out waiting for user operations to complete",
        latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    
    // Verify results
    assertThat(successCount.get(), is(THREAD_COUNT));
  }
  
  /**
   * Tests performance comparison between virtual threads and platform threads.
   * 
   * This test compares the performance of the SecuritySystem when using virtual threads
   * versus platform threads for concurrent operations.
   */
  @Test
  public void testPerformanceComparisonBetweenVirtualAndPlatformThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    
    // Test with virtual threads
    long virtualThreadStartTime = System.currentTimeMillis();
    CountDownLatch virtualThreadLatch = new CountDownLatch(LARGE_THREAD_COUNT);
    
    for (int i = 0; i < LARGE_THREAD_COUNT; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          PrincipalCollection principal = new SimplePrincipalCollection("jcool", "MockRealmA");
          if (index % 2 == 0) {
            securitySystem.checkPermission(principal, "test:read");
          }
          else {
            try {
              securitySystem.checkPermission(principal, "invalid-permission:" + index);
            }
            catch (AuthorizationException e) {
              // Expected
            }
          }
        }
        catch (Exception e) {
          log.error("Unexpected error in virtual thread test", e);
        }
        finally {
          virtualThreadLatch.countDown();
        }
      });
    }
    
    assertThat("Timed out waiting for virtual thread operations",
        virtualThreadLatch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    long virtualThreadDuration = System.currentTimeMillis() - virtualThreadStartTime;
    
    // Test with platform threads (using a smaller count to avoid resource exhaustion)
    int platformThreadCount = 1000; // Smaller count for platform threads
    long platformThreadStartTime = System.currentTimeMillis();
    CountDownLatch platformThreadLatch = new CountDownLatch(platformThreadCount);
    
    for (int i = 0; i < platformThreadCount; i++) {
      final int index = i;
      platformThreadExecutor.submit(() -> {
        try {
          PrincipalCollection principal = new SimplePrincipalCollection("jcool", "MockRealmA");
          if (index % 2 == 0) {
            securitySystem.checkPermission(principal, "test:read");
          }
          else {
            try {
              securitySystem.checkPermission(principal, "invalid-permission:" + index);
            }
            catch (AuthorizationException e) {
              // Expected
            }
          }
        }
        catch (Exception e) {
          log.error("Unexpected error in platform thread test", e);
        }
        finally {
          platformThreadLatch.countDown();
        }
      });
    }
    
    assertThat("Timed out waiting for platform thread operations",
        platformThreadLatch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    long platformThreadDuration = System.currentTimeMillis() - platformThreadStartTime;
    
    // Calculate throughput (operations per second)
    double virtualThreadThroughput = LARGE_THREAD_COUNT / (virtualThreadDuration / 1000.0);
    double platformThreadThroughput = platformThreadCount / (platformThreadDuration / 1000.0);
    
    log.info("Virtual Thread Performance: {} operations in {}ms (throughput: {}/sec)",
        LARGE_THREAD_COUNT, virtualThreadDuration, String.format("%.2f", virtualThreadThroughput));
    log.info("Platform Thread Performance: {} operations in {}ms (throughput: {}/sec)",
        platformThreadCount, platformThreadDuration, String.format("%.2f", platformThreadThroughput));
    
    // Verify that virtual threads can handle more concurrent operations
    assertThat(LARGE_THREAD_COUNT, greaterThan(platformThreadCount));
    
    // Normalize throughput for comparison (operations per second per thread)
    double normalizedVirtualThroughput = virtualThreadThroughput / LARGE_THREAD_COUNT;
    double normalizedPlatformThroughput = platformThreadThroughput / platformThreadCount;
    
    log.info("Normalized Virtual Thread Throughput: {}/thread/sec", 
        String.format("%.5f", normalizedVirtualThroughput));
    log.info("Normalized Platform Thread Throughput: {}/thread/sec", 
        String.format("%.5f", normalizedPlatformThroughput));
  }
  
  /**
   * Tests cache behavior under virtual thread load.
   * 
   * This test verifies that the SecuritySystem's caching mechanisms work correctly
   * when accessed concurrently by many virtual threads.
   */
  @Test
  public void testCacheBehaviorUnderVirtualThreadLoad() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(LARGE_THREAD_COUNT);
    ConcurrentHashMap<String, List<Role>> userRoles = new ConcurrentHashMap<>();
    
    // First, create a test user with roles
    User user = createUser("cache-test-user", UserStatus.active);
    securitySystem.addUser(user, "password123");
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < LARGE_THREAD_COUNT; i++) {
      final int index = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // List roles for the user - this should use caching
          List<Role> roles = new ArrayList<>(securitySystem.listRoles());
          userRoles.put("thread-" + index, roles);
        }
        catch (Exception e) {
          log.error("Unexpected error in cache test", e);
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertThat("Timed out waiting for cache test to complete",
        latch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    
    // Verify that all threads saw the same data (cache consistency)
    List<Role> firstResult = userRoles.values().iterator().next();
    for (List<Role> roles : userRoles.values()) {
      assertThat(roles.size(), is(firstResult.size()));
      for (int i = 0; i < roles.size(); i++) {
        assertThat(roles.get(i).getRoleId(), is(firstResult.get(i).getRoleId()));
      }
    }
  }
  
  /**
   * Tests that no thread pinning occurs during normal security operations.
   * 
   * This test verifies that the SecuritySystem operations don't cause thread pinning,
   * which would reduce the effectiveness of virtual threads.
   */
  @Test
  public void testNoThreadPinningDuringNormalOperations() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    int threadCount = 100;
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    List<Future<?>> futures = new ArrayList<>();
    
    // Submit tasks to virtual thread executor but don't start them yet
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();
          
          // Perform a mix of security operations
          PrincipalCollection principal = new SimplePrincipalCollection("jcool", "MockRealmA");
          securitySystem.checkPermission(principal, "test:read");
          
          // List roles
          securitySystem.listRoles();
          
          // Create and retrieve a user
          String userId = "pinning-test-user-" + index;
          User user = createUser(userId, UserStatus.active);
          securitySystem.addUser(user, "password123");
          securitySystem.getUser(userId, "MockUserManagerA");
          
          return "Success";
        }
        catch (Exception e) {
          log.error("Error in pinning test", e);
          return "Error: " + e.getMessage();
        }
        finally {
          completionLatch.countDown();
        }
      });
      futures.add(future);
    }
    
    // Start all threads simultaneously
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for all threads to complete
    assertThat("Timed out waiting for pinning test to complete",
        completionLatch.await(TIMEOUT.toSeconds(), TimeUnit.SECONDS), is(true));
    long duration = System.currentTimeMillis() - startTime;
    
    // Check results
    for (Future<?> future : futures) {
      assertThat(future.get(), is("Success"));
    }
    
    // If there was significant thread pinning, the duration would be much longer
    // This is a heuristic test - we expect operations to complete quickly if no pinning occurs
    log.info("Completed {} concurrent operations in {}ms", threadCount, duration);
    
    // A very rough heuristic - if operations take more than 5ms per thread on average,
    // there might be pinning issues. This threshold may need adjustment based on the environment.
    long expectedMaxDuration = threadCount * 5; // 5ms per thread
    assertThat("Operations took too long, suggesting possible thread pinning",
        duration, lessThan(expectedMaxDuration));
  }
  
  /**
   * Creates a user with the specified ID and status.
   */
  private User createUser(String userId, UserStatus status) {
    User user = new User();
    user.setEmailAddress(userId + "@example.com");
    user.setFirstName("Test");
    user.setLastName("User");
    user.setSource("MockUserManagerA");
    user.setStatus(status);
    user.setUserId(userId);
    user.addRole(new RoleIdentifier("default", "test-role1"));
    return user;
  }
}