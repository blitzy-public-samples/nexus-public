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
package virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizingRealmImpl;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.internal.SecurityConfigurationManagerImpl;
import org.sonatype.nexus.security.privilege.WildcardPrivilegeDescriptor;
import org.sonatype.nexus.security.user.UserStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests concurrent permission evaluation and authorization operations using Java 21's Virtual Threads.
 * Validates that the Nexus authorization framework maintains correctness and efficiency when handling
 * thousands of simultaneous permission checks.
 */
public class AuthorizationConcurrencyTest
    extends AbstractSecurityTest
{
  private static final int THREAD_COUNT = 10_000;
  private static final int PERMISSION_CHECK_COUNT = 100;
  private static final String TEST_USER = "virtualThreadTestUser";
  private static final String TEST_ROLE = "virtualThreadTestRole";
  private static final String TEST_PERMISSION = "app:test:read";
  
  private SecurityConfigurationManagerImpl configurationManager;
  private SecuritySystem securitySystem;
  private ExecutorService platformExecutor;
  private ExecutorService virtualExecutor;

  @BeforeEach
  @Override
  public void setUp() throws Exception {
    super.setUp();
    
    configurationManager = lookup(SecurityConfigurationManagerImpl.class);
    securitySystem = lookup(SecuritySystem.class);
    
    // Create test user with specific permissions
    setupTestUserAndPermissions();
    
    // Create executors for platform and virtual threads
    platformExecutor = Executors.newFixedThreadPool(100); // Limited pool for platform threads
    virtualExecutor = Executors.newVirtualThreadPerTaskExecutor(); // Unlimited virtual threads
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (platformExecutor != null) {
      platformExecutor.shutdownNow();
    }
    if (virtualExecutor != null) {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Sets up a test user with specific permissions for testing.
   */
  private void setupTestUserAndPermissions() throws Exception {
    // Create test permission
    CPrivilege priv = WildcardPrivilegeDescriptor.privilege(TEST_PERMISSION);
    configurationManager.createPrivilege(priv);
    
    // Create test role with the permission
    CRole role = configurationManager.newRole();
    role.setId(TEST_ROLE);
    role.setName("Test Role for Virtual Thread Testing");
    role.setDescription("Role used for testing virtual thread concurrency");
    role.addPrivilege(priv.getId());
    configurationManager.createRole(role);
    
    // Create test user with the role
    CUser user = new MemoryCUser();
    user.setEmail("virtualthread@example.com");
    user.setFirstName("Virtual");
    user.setLastName("Thread");
    user.setStatus(UserStatus.active.toString());
    user.setId(TEST_USER);
    user.setPassword("password");
    
    Set<String> roles = new HashSet<>();
    roles.add(role.getId());
    
    configurationManager.createUser(user, roles);
  }
  
  /**
   * Tests that permission evaluation works correctly with a large number of concurrent
   * virtual threads all checking permissions simultaneously.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testConcurrentPermissionEvaluationWithVirtualThreads() throws Exception {
    Permission permission = new WildcardPermission(TEST_PERMISSION);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create many virtual threads that will all evaluate permissions concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      virtualExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Check the permission multiple times to test caching behavior
          for (int j = 0; j < PERMISSION_CHECK_COUNT; j++) {
            boolean hasPermission = securitySystem.hasPermission(TEST_USER, permission);
            if (hasPermission) {
              successCount.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(20, TimeUnit.SECONDS);
    assertTrue(completed, "Not all threads completed in time");
    
    // Verify all permission checks succeeded
    assertEquals(THREAD_COUNT * PERMISSION_CHECK_COUNT, successCount.get(), 
        "Some permission checks failed or were not executed");
  }
  
  /**
   * Compares the performance of permission checking between platform threads and virtual threads.
   * This test demonstrates the scalability advantages of virtual threads for concurrent permission checks.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testPermissionCheckPerformanceComparison() throws Exception {
    Permission permission = new WildcardPermission(TEST_PERMISSION);
    int threadCount = 5_000; // Use a smaller count for platform threads to avoid resource exhaustion
    
    // Test with platform threads
    long platformTime = measurePermissionCheckTime(platformExecutor, permission, threadCount);
    
    // Test with virtual threads
    long virtualTime = measurePermissionCheckTime(virtualExecutor, permission, threadCount);
    
    System.out.println("Platform threads time: " + platformTime + "ms");
    System.out.println("Virtual threads time: " + virtualTime + "ms");
    
    // We don't assert on exact times as they can vary by environment,
    // but we log them for manual verification
  }
  
  /**
   * Measures the time taken to perform permission checks using the given executor.
   */
  private long measurePermissionCheckTime(ExecutorService executor, Permission permission, int threadCount) 
      throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    // Create threads that will all evaluate permissions concurrently
    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Check the permission
          securitySystem.hasPermission(TEST_USER, permission);
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start timing
    long startTime = System.currentTimeMillis();
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    completionLatch.await();
    
    // End timing
    long endTime = System.currentTimeMillis();
    
    return endTime - startTime;
  }
  
  /**
   * Tests that permission caching works correctly under high concurrency with virtual threads.
   * This verifies that the cache doesn't have thread-safety issues when accessed by many virtual threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testPermissionCachingWithVirtualThreads() throws Exception {
    Permission permission = new WildcardPermission(TEST_PERMISSION);
    Permission invalidPermission = new WildcardPermission("app:test:invalid");
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger validPermissionCount = new AtomicInteger(0);
    AtomicInteger invalidPermissionCount = new AtomicInteger(0);
    
    // Create many virtual threads that will all evaluate permissions concurrently
    for (int i = 0; i < THREAD_COUNT; i++) {
      final int index = i;
      virtualExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Alternate between checking valid and invalid permissions
          if (index % 2 == 0) {
            boolean hasPermission = securitySystem.hasPermission(TEST_USER, permission);
            if (hasPermission) {
              validPermissionCount.incrementAndGet();
            }
          } else {
            boolean hasPermission = securitySystem.hasPermission(TEST_USER, invalidPermission);
            if (!hasPermission) {
              invalidPermissionCount.incrementAndGet();
            }
          }
        }
        catch (Exception e) {
          e.printStackTrace();
        }
        finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all threads to complete
    boolean completed = completionLatch.await(20, TimeUnit.SECONDS);
    assertTrue(completed, "Not all threads completed in time");
    
    // Verify all permission checks returned the expected results
    assertEquals(THREAD_COUNT / 2, validPermissionCount.get(), 
        "Valid permission checks returned unexpected results");
    assertEquals(THREAD_COUNT / 2, invalidPermissionCount.get(), 
        "Invalid permission checks returned unexpected results");
  }
  
  /**
   * Tests for thread pinning during permission evaluation with virtual threads.
   * Thread pinning occurs when a virtual thread gets stuck on a blocking operation
   * that doesn't properly yield, preventing the carrier thread from executing other virtual threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testNoThreadPinningDuringPermissionEvaluation() throws Exception {
    int threadCount = 1000;
    Permission permission = new WildcardPermission(TEST_PERMISSION);
    CountDownLatch startLatch = new CountDownLatch(1);
    List<CountDownLatch> threadLatches = new ArrayList<>(threadCount);
    
    // Create latches for each thread to signal completion
    for (int i = 0; i < threadCount; i++) {
      threadLatches.add(new CountDownLatch(1));
    }
    
    // Create virtual threads that will all evaluate permissions concurrently
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      virtualExecutor.submit(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Check permission
          securitySystem.hasPermission(TEST_USER, permission);
          
          // Signal completion
          threadLatches.get(index).countDown();
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Check if any threads are pinned by monitoring completion
    // If thread pinning occurs, some threads will complete much later than others
    long startTime = System.currentTimeMillis();
    
    // Wait for first thread to complete
    boolean firstCompleted = threadLatches.get(0).await(5, TimeUnit.SECONDS);
    assertTrue(firstCompleted, "First thread did not complete in time");
    
    // Wait a short time for remaining threads
    Thread.sleep(500);
    
    // Count how many threads have completed
    int completedCount = 0;
    for (CountDownLatch latch : threadLatches) {
      if (latch.getCount() == 0) {
        completedCount++;
      }
    }
    
    // If thread pinning is not occurring, most threads should complete around the same time
    // We expect at least 90% of threads to complete within 500ms of the first thread
    int expectedCompletions = (int)(threadCount * 0.9);
    assertTrue(completedCount >= expectedCompletions, 
        "Only " + completedCount + " of " + threadCount + " threads completed within the expected timeframe. " +
        "This suggests thread pinning may be occurring during permission evaluation.");
    
    // Wait for all remaining threads to complete (for cleanup)
    for (CountDownLatch latch : threadLatches) {
      latch.await(5, TimeUnit.SECONDS);
    }
  }
}