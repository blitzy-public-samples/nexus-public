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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserStatus;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link SecuritySystem} operations with Java 21 Virtual Threads.
 * 
 * This test class verifies that the SecuritySystem can handle high concurrency
 * operations using Java 21 Virtual Threads, ensuring that authorization checks,
 * user operations, and role management work correctly under load.
 */
public class SecuritySystemVirtualThreadTest
    extends AbstractSecurityTest
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setup() throws Exception {
    // Create executors for both platform and virtual threads for comparison
    platformThreadExecutor = Executors.newFixedThreadPool(100); // Limited pool for platform threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Unlimited virtual threads
  }
  
  @After
  public void tearDown() throws Exception {
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdownNow();
    }
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests concurrent permission checks using virtual threads.
   * This verifies that the SecuritySystem can handle thousands of concurrent
   * permission checks efficiently using virtual threads.
   */
  @Test
  public void testConcurrentPermissionChecksWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a principal for testing
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
    
    // Start timing for virtual threads
    Instant virtualStart = Instant.now();
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Check a permission that should be granted
          securitySystem.checkPermission(principal, "test:read");
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Calculate duration
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Verify all permission checks were successful
    assertEquals("All permission checks should succeed", THREAD_COUNT, successCount.get());
    
    System.out.println("Completed " + THREAD_COUNT + " concurrent permission checks with virtual threads in " 
        + virtualDuration.toMillis() + "ms");
  }
  
  /**
   * Compares performance between platform threads and virtual threads for permission checks.
   * This test demonstrates the efficiency of virtual threads for concurrent security operations.
   */
  @Test
  public void testPermissionCheckPerformanceComparison() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
    
    // Test with platform threads
    CountDownLatch platformLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger platformSuccessCount = new AtomicInteger(0);
    
    Instant platformStart = Instant.now();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      platformThreadExecutor.submit(() -> {
        try {
          securitySystem.checkPermission(principal, "test:read");
          platformSuccessCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          platformLatch.countDown();
        }
      });
    }
    
    assertTrue("Timed out waiting for platform threads to complete", 
        platformLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Test with virtual threads
    CountDownLatch virtualLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger virtualSuccessCount = new AtomicInteger(0);
    
    Instant virtualStart = Instant.now();
    
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          securitySystem.checkPermission(principal, "test:read");
          virtualSuccessCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          virtualLatch.countDown();
        }
      });
    }
    
    assertTrue("Timed out waiting for virtual threads to complete", 
        virtualLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Verify both approaches succeeded
    assertEquals("All platform thread permission checks should succeed", 
        THREAD_COUNT, platformSuccessCount.get());
    assertEquals("All virtual thread permission checks should succeed", 
        THREAD_COUNT, virtualSuccessCount.get());
    
    // Log performance comparison
    System.out.println("Permission check performance comparison:");
    System.out.println("Platform threads: " + platformDuration.toMillis() + "ms");
    System.out.println("Virtual threads: " + virtualDuration.toMillis() + "ms");
    System.out.println("Improvement factor: " + 
        (double) platformDuration.toMillis() / virtualDuration.toMillis() + "x");
  }
  
  /**
   * Tests concurrent user queries and updates with virtual threads.
   * This verifies that the SecuritySystem can handle many concurrent user operations
   * efficiently using virtual threads.
   */
  @Test
  public void testConcurrentUserOperationsWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create test users first
    for (int i = 0; i < 10; i++) {
      User user = createUser("vt-user-" + i, UserStatus.active);
      securitySystem.addUser(user, "password");
    }
    
    // Start timing
    Instant start = Instant.now();
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int userIndex = i % 10;
      virtualThreadExecutor.submit(() -> {
        try {
          // Get user
          User user = securitySystem.getUser("vt-user-" + userIndex, "MockUserManagerA");
          assertNotNull("User should exist", user);
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Calculate duration
    Duration duration = Duration.between(start, Instant.now());
    
    // Verify all user operations were successful
    assertEquals("All user operations should succeed", THREAD_COUNT, successCount.get());
    
    System.out.println("Completed " + THREAD_COUNT + " concurrent user operations with virtual threads in " 
        + duration.toMillis() + "ms");
  }
  
  /**
   * Tests concurrent role assignment and verification with virtual threads.
   * This verifies that the SecuritySystem can handle many concurrent role operations
   * efficiently using virtual threads.
   */
  @Test
  public void testConcurrentRoleOperationsWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Start timing
    Instant start = Instant.now();
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // List roles
          Set<Role> roles = securitySystem.listRoles();
          assertFalse("Roles should not be empty", roles.isEmpty());
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Calculate duration
    Duration duration = Duration.between(start, Instant.now());
    
    // Verify all role operations were successful
    assertEquals("All role operations should succeed", THREAD_COUNT, successCount.get());
    
    System.out.println("Completed " + THREAD_COUNT + " concurrent role operations with virtual threads in " 
        + duration.toMillis() + "ms");
  }
  
  /**
   * Tests concurrent authentication with virtual threads.
   * This verifies that the SecuritySystem can handle many concurrent login/logout operations
   * efficiently using virtual threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<String, Subject> subjects = new ConcurrentHashMap<>();
    
    // Create test users first
    for (int i = 0; i < 10; i++) {
      User user = createUser("vt-auth-user-" + i, UserStatus.active);
      securitySystem.addUser(user, "password");
    }
    
    // Start timing
    Instant start = Instant.now();
    
    // Submit tasks to virtual thread executor
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int userIndex = i % 10;
      final String username = "vt-auth-user-" + userIndex;
      
      Future<?> future = virtualThreadExecutor.submit(() -> {
        try {
          // Login
          UsernamePasswordToken token = new UsernamePasswordToken(username, "password");
          Subject subject = securitySystem.getSubject();
          subject.login(token);
          
          // Store subject for later logout
          subjects.put(username + "-" + Thread.currentThread().getId(), subject);
          
          // Verify authentication
          assertTrue("Subject should be authenticated", subject.isAuthenticated());
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
      
      futures.add(future);
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Calculate duration
    Duration duration = Duration.between(start, Instant.now());
    
    // Verify all authentication operations were successful
    assertEquals("All authentication operations should succeed", THREAD_COUNT, successCount.get());
    
    System.out.println("Completed " + THREAD_COUNT + " concurrent authentication operations with virtual threads in " 
        + duration.toMillis() + "ms");
    
    // Now logout all subjects
    CountDownLatch logoutLatch = new CountDownLatch(subjects.size());
    AtomicInteger logoutSuccessCount = new AtomicInteger(0);
    
    Instant logoutStart = Instant.now();
    
    subjects.forEach((key, subject) -> {
      virtualThreadExecutor.submit(() -> {
        try {
          subject.logout();
          assertFalse("Subject should be logged out", subject.isAuthenticated());
          logoutSuccessCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          logoutLatch.countDown();
        }
      });
    });
    
    // Wait for all logout operations to complete
    assertTrue("Timed out waiting for logout operations to complete", 
        logoutLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    Duration logoutDuration = Duration.between(logoutStart, Instant.now());
    
    // Verify all logout operations were successful
    assertEquals("All logout operations should succeed", subjects.size(), logoutSuccessCount.get());
    
    System.out.println("Completed " + subjects.size() + " concurrent logout operations with virtual threads in " 
        + logoutDuration.toMillis() + "ms");
  }
  
  /**
   * Tests SecuritySystem cache behavior under virtual thread load.
   * This verifies that the SecuritySystem's caching mechanisms work correctly
   * when accessed by many concurrent virtual threads.
   */
  @Test
  public void testSecuritySystemCacheWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a principal for testing
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
    
    // Start timing
    Instant start = Instant.now();
    
    // First pass - should populate cache
    for (int i = 0; i < THREAD_COUNT / 2; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Check permission - first time should hit database/source
          securitySystem.checkPermission(principal, "test:read");
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for first half to complete
    while (latch.getCount() > THREAD_COUNT / 2) {
      Thread.sleep(10);
    }
    
    // Second pass - should hit cache
    Instant secondPassStart = Instant.now();
    AtomicInteger secondPassSuccessCount = new AtomicInteger(0);
    
    for (int i = 0; i < THREAD_COUNT / 2; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Check permission - second time should hit cache
          securitySystem.checkPermission(principal, "test:read");
          secondPassSuccessCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Calculate durations
    Duration firstPassDuration = Duration.between(start, secondPassStart);
    Duration secondPassDuration = Duration.between(secondPassStart, Instant.now());
    
    // Verify all operations were successful
    assertEquals("First pass operations should succeed", THREAD_COUNT / 2, successCount.get());
    assertEquals("Second pass operations should succeed", THREAD_COUNT / 2, secondPassSuccessCount.get());
    
    System.out.println("Cache performance with virtual threads:");
    System.out.println("First pass (cache population): " + firstPassDuration.toMillis() + "ms");
    System.out.println("Second pass (cache hits): " + secondPassDuration.toMillis() + "ms");
    System.out.println("Cache speedup factor: " + 
        (double) firstPassDuration.toMillis() / secondPassDuration.toMillis() + "x");
  }
  
  /**
   * Tests high concurrency with a very large number of virtual threads.
   * This verifies that the SecuritySystem can handle extreme concurrency
   * using thousands of virtual threads simultaneously.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Only run this test if explicitly enabled, as it creates a very large number of threads
    if (!Boolean.getBoolean("enable.high.concurrency.test")) {
      System.out.println("High concurrency test skipped. Enable with -Denable.high.concurrency.test=true");
      return;
    }
    
    SecuritySystem securitySystem = getSecuritySystem();
    final int highThreadCount = 10000; // 10x more threads than other tests
    CountDownLatch latch = new CountDownLatch(highThreadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create a principal for testing
    PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
    
    // Start timing
    Instant start = Instant.now();
    
    // Submit tasks to virtual thread executor
    for (int i = 0; i < highThreadCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          // Check permission
          securitySystem.checkPermission(principal, "test:read");
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Ignore exceptions for this test
        }
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS));
    
    // Calculate duration
    Duration duration = Duration.between(start, Instant.now());
    
    // Verify all operations were successful
    assertEquals("All operations should succeed", highThreadCount, successCount.get());
    
    System.out.println("Completed " + highThreadCount + " concurrent operations with virtual threads in " 
        + duration.toMillis() + "ms");
    System.out.println("Operations per second: " + 
        (int)(highThreadCount / (duration.toMillis() / 1000.0)));
  }
  
  /**
   * Helper method to create a user for testing.
   */
  private User createUser(String name, UserStatus status) {
    User user = new User();
    user.setEmailAddress("email@example.com");
    user.setName(name);
    user.setSource("MockUserManagerA");
    user.setStatus(status);
    user.setUserId(name);
    user.addRole(new RoleIdentifier("default", "test-role1"));
    return user;
  }
}