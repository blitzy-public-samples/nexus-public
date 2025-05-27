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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.realm.MockRealmB;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.user.User;

import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for authorization system behavior with Java 21 Virtual Threads.
 * <p>
 * This test class validates that the Nexus authorization system maintains thread-safety
 * and performance characteristics when used with Java 21 Virtual Threads. It tests
 * concurrent permission evaluations, role lookups, and privilege operations to ensure
 * proper authorization behavior under high concurrency.
 */
@ExtendWith(MockitoExtension.class)
@Tag("Java21TestGroup")
@Tag("VirtualThreadTestGroup")
public class VirtualThreadAuthorizationTest
    extends AbstractSecurityTest
{
  private static final int THREAD_COUNT = 100;
  private static final int ITERATIONS = 10;
  private static final String TEST_PERMISSION = "test:heHasIt";
  private static final String TEST_USER = "jcool";
  
  /**
   * Tests that permission evaluation works correctly with many concurrent virtual threads.
   * <p>
   * This test creates multiple virtual threads that all perform permission checks
   * simultaneously, verifying that the authorization system correctly handles
   * concurrent access without errors or inconsistent results.
   */
  @Test
  void concurrentPermissionEvaluationWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = lookup(SecuritySystem.class);
    MockRealmB mockRealmB = (MockRealmB) lookup(Realm.class, "MockRealmB");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicBoolean inconsistentResult = new AtomicBoolean(false);
      
      // Submit multiple concurrent permission checks using virtual threads
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(executor.submit(() -> {
          try {
            SimplePrincipalCollection principals = new SimplePrincipalCollection(TEST_USER, mockRealmB.getName());
            boolean result = securitySystem.isPermitted(principals, TEST_PERMISSION);
            
            // All permission checks should return the same result
            if (!result) {
              inconsistentResult.set(true);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all tasks to complete
      Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), 
          "Errors occurred during virtual thread permission checks");
      
      // Verify all permission checks returned consistent results
      Assertions.assertFalse(inconsistentResult.get(), 
          "Inconsistent permission check results detected");
      
      // Verify all futures completed successfully
      for (Future<?> future : futures) {
        future.get(1, TimeUnit.SECONDS); // This will throw if any task failed
      }
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that authorization caching works correctly under high concurrent access with virtual threads.
   * <p>
   * This test verifies that the authorization cache maintains consistency when accessed
   * and modified by many virtual threads simultaneously.
   */
  @Test
  void authorizationCachingWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = lookup(SecuritySystem.class);
    MockRealmB mockRealmB = (MockRealmB) lookup(Realm.class, "MockRealmB");
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // First populate the cache with a permission check
      SimplePrincipalCollection principals = new SimplePrincipalCollection(TEST_USER, mockRealmB.getName());
      securitySystem.isPermitted(principals, TEST_PERMISSION);
      
      // Verify cache is populated
      Assertions.assertFalse(mockRealmB.getAuthorizationCache().keys().isEmpty());
      
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks that mix cache reads and invalidations
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            if (index % 2 == 0) {
              // Even threads do permission checks (read from cache)
              securitySystem.isPermitted(principals, TEST_PERMISSION);
            } 
            else {
              // Odd threads update a user (invalidates cache)
              User user = securitySystem.getUser("bburton", "MockUserManagerB");
              securitySystem.updateUser(user);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), 
          "Errors occurred during virtual thread cache operations");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests role operations with virtual threads to ensure thread-safety.
   * <p>
   * This test performs concurrent role lookups and verifies that the results
   * are consistent across all virtual threads.
   */
  @Test
  void roleOperationsWithVirtualThreads() throws Exception {
    AuthorizationManager authzManager = getAuthorizationManager();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicBoolean inconsistentResult = new AtomicBoolean(false);
      
      // Submit multiple concurrent role lookups using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            Role role = authzManager.getRole("role1");
            
            // Verify role properties are consistent
            if (!"role1".equals(role.getRoleId()) ||
                !"RoleOne".equals(role.getName()) ||
                !"Role One".equals(role.getDescription()) ||
                !role.getPrivileges().contains("1") ||
                !role.getPrivileges().contains("2") ||
                role.getPrivileges().size() != 2) {
              inconsistentResult.set(true);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), 
          "Errors occurred during virtual thread role operations");
      
      // Verify all role lookups returned consistent results
      Assertions.assertFalse(inconsistentResult.get(), 
          "Inconsistent role lookup results detected");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests privilege operations with virtual threads to ensure thread-safety.
   * <p>
   * This test performs concurrent privilege lookups and verifies that the results
   * are consistent across all virtual threads.
   */
  @Test
  void privilegeOperationsWithVirtualThreads() throws Exception {
    AuthorizationManager authzManager = getAuthorizationManager();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicBoolean inconsistentResult = new AtomicBoolean(false);
      
      // Submit multiple concurrent privilege lookups using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            Privilege privilege = authzManager.getPrivilege("3");
            
            // Verify privilege properties are consistent
            if (!"3".equals(privilege.getId()) ||
                !"3-name".equals(privilege.getName()) ||
                !"Privilege Three".equals(privilege.getDescription()) ||
                !"method".equals(privilege.getType()) ||
                !"read".equals(privilege.getPrivilegeProperty("method")) ||
                !"/some/path/".equals(privilege.getPrivilegeProperty("permission"))) {
              inconsistentResult.set(true);
            }
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), 
          "Errors occurred during virtual thread privilege operations");
      
      // Verify all privilege lookups returned consistent results
      Assertions.assertFalse(inconsistentResult.get(), 
          "Inconsistent privilege lookup results detected");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for authorization operations.
   * <p>
   * This test measures and compares the execution time of permission checks using both
   * platform threads and virtual threads under high concurrency.
   */
  @Test
  void comparePerformanceBetweenPlatformAndVirtualThreads() throws Exception {
    SecuritySystem securitySystem = lookup(SecuritySystem.class);
    MockRealmB mockRealmB = (MockRealmB) lookup(Realm.class, "MockRealmB");
    SimplePrincipalCollection principals = new SimplePrincipalCollection(TEST_USER, mockRealmB.getName());
    
    // Run performance test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
      try {
        runConcurrentPermissionChecks(executor, securitySystem, principals, THREAD_COUNT * ITERATIONS);
      } 
      finally {
        executor.shutdown();
      }
    });
    
    // Run performance test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
      try {
        runConcurrentPermissionChecks(executor, securitySystem, principals, THREAD_COUNT * ITERATIONS);
      } 
      finally {
        executor.shutdown();
      }
    });
    
    // Log the performance comparison
    System.out.println("Platform thread execution time: " + platformThreadTime + "ms");
    System.out.println("Virtual thread execution time: " + virtualThreadTime + "ms");
    System.out.println("Performance improvement: " + 
        String.format("%.2f", (double) platformThreadTime / virtualThreadTime) + "x");
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }
  
  /**
   * Tests for thread pinning issues during authorization operations with virtual threads.
   * <p>
   * This test detects potential thread pinning by running many concurrent virtual threads
   * and measuring if they complete in a reasonable time. Thread pinning would cause
   * significant delays as virtual threads would be forced to execute sequentially.
   */
  @Test
  void detectThreadPinningDuringAuthorizationOperations() throws Exception {
    SecuritySystem securitySystem = lookup(SecuritySystem.class);
    MockRealmB mockRealmB = (MockRealmB) lookup(Realm.class, "MockRealmB");
    SimplePrincipalCollection principals = new SimplePrincipalCollection(TEST_USER, mockRealmB.getName());
    
    // Create a large number of virtual threads to detect pinning
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      // Use a high thread count to increase chances of detecting pinning
      int highThreadCount = THREAD_COUNT * 10;
      CountDownLatch latch = new CountDownLatch(highThreadCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Start time measurement
      long startTime = System.currentTimeMillis();
      
      // Submit many concurrent permission checks using virtual threads
      for (int i = 0; i < highThreadCount; i++) {
        executor.submit(() -> {
          try {
            // Perform multiple operations that could cause pinning
            securitySystem.isPermitted(principals, TEST_PERMISSION);
            securitySystem.isPermitted(principals, "test:someOtherPermission");
            securitySystem.isPermitted(principals, "test:yetAnotherPermission");
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete with a reasonable timeout
      boolean completed = latch.await(10, TimeUnit.SECONDS);
      long executionTime = System.currentTimeMillis() - startTime;
      
      // Verify all tasks completed within the timeout
      Assertions.assertTrue(completed, 
          "Virtual threads did not complete in time, possible thread pinning detected");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), 
          "Errors occurred during virtual thread operations");
      
      // Log the execution time for analysis
      System.out.println("Thread pinning test execution time: " + executionTime + "ms");
      System.out.println("Average time per thread: " + (executionTime / (double) highThreadCount) + "ms");
      
      // If thread pinning occurs, execution time would be much higher than expected
      // This is a heuristic check - the actual threshold depends on the environment
      Assertions.assertTrue(executionTime < 5000, 
          "Execution time suggests possible thread pinning: " + executionTime + "ms");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Tests that concurrent role and privilege modifications are thread-safe with virtual threads.
   * <p>
   * This test performs concurrent additions, updates, and deletions of roles and privileges
   * using virtual threads to verify that the authorization system maintains consistency.
   */
  @Test
  void concurrentRoleAndPrivilegeModificationsWithVirtualThreads() throws Exception {
    AuthorizationManager authzManager = getAuthorizationManager();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    try {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent role and privilege modifications using virtual threads
      for (int i = 0; i < THREAD_COUNT; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            String suffix = String.valueOf(index);
            
            // Create a new role
            Role role = new Role();
            role.setRoleId("vt-role-" + suffix);
            role.setName("VT Role " + suffix);
            role.setDescription("Virtual Thread Test Role " + suffix);
            role.addPrivilege("1");
            
            // Add the role
            authzManager.addRole(role);
            
            // Create a new privilege
            Privilege privilege = new Privilege();
            privilege.setId("vt-priv-" + suffix);
            privilege.setName("vt-priv-name-" + suffix);
            privilege.setDescription("Virtual Thread Test Privilege " + suffix);
            privilege.setType("method");
            privilege.addProperty("method", "read");
            privilege.addProperty("permission", "/vt/test/" + suffix);
            
            // Add the privilege
            authzManager.addPrivilege(privilege);
            
            // Update the role to include the new privilege
            role.addPrivilege(privilege.getId());
            authzManager.updateRole(role);
            
            // Verify the role was updated correctly
            Role updatedRole = authzManager.getRole(role.getRoleId());
            if (!updatedRole.getPrivileges().contains(privilege.getId())) {
              throw new AssertionError("Role was not updated correctly");
            }
            
            // Delete the role and privilege
            authzManager.deleteRole(role.getRoleId());
            authzManager.deletePrivilege(privilege.getId());
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS), 
          "Timed out waiting for virtual threads to complete");
      
      // Verify no errors occurred
      Assertions.assertEquals(0, errorCount.get(), 
          "Errors occurred during concurrent role and privilege modifications");
    } 
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Helper method to run concurrent permission checks using the provided executor.
   */
  private void runConcurrentPermissionChecks(
      ExecutorService executor, 
      SecuritySystem securitySystem,
      SimplePrincipalCollection principals,
      int checkCount) throws Exception 
  {
    CountDownLatch latch = new CountDownLatch(checkCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    for (int i = 0; i < checkCount; i++) {
      executor.submit(() -> {
        try {
          securitySystem.isPermitted(principals, TEST_PERMISSION);
        } 
        catch (Exception e) {
          errorCount.incrementAndGet();
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    
    // Verify all tasks completed and no errors occurred
    if (!completed) {
      throw new AssertionError("Timed out waiting for permission checks to complete");
    }
    
    if (errorCount.get() > 0) {
      throw new AssertionError("Errors occurred during permission checks: " + errorCount.get());
    }
  }
  
  /**
   * Helper method to measure execution time of a runnable in milliseconds.
   */
  private long measureExecutionTime(Runnable runnable) {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Helper method to get the authorization manager.
   */
  private AuthorizationManager getAuthorizationManager() throws Exception {
    return lookup(AuthorizationManager.class);
  }
}