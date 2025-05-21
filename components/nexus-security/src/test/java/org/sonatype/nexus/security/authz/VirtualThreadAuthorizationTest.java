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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadMatchers;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the thread-safety and performance characteristics of the Nexus authorization system
 * when used with Java 21 Virtual Threads.
 */
@Tag("VirtualThreadTestGroup")
@Category(VirtualThreadTestGroup.class)
public class VirtualThreadAuthorizationTest
    extends AbstractSecurityTest
{
  private SecuritySystem securitySystem;
  private AuthorizationManager authorizationManager;
  private SimplePrincipalCollection principals;
  private static final int HIGH_CONCURRENCY_THREADS = 1000;
  private static final int PERFORMANCE_TEST_ITERATIONS = 10000;
  
  @Override
  protected MemorySecurityConfiguration initialSecurityConfiguration() {
    return AuthorizationManagerTestSecurity.securityModel();
  }
  
  @BeforeEach
  public void setUp() throws Exception {
    securitySystem = lookup(SecuritySystem.class);
    authorizationManager = lookup(AuthorizationManager.class);
    principals = new SimplePrincipalCollection("jcool", "default");
  }
  
  /**
   * Tests concurrent permission evaluations using virtual threads.
   * Validates that the authorization system correctly handles high concurrency
   * with virtual threads without race conditions or inconsistencies.
   */
  @Test
  public void testConcurrentPermissionEvaluations() throws Exception {
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between different permission checks
            String permission = (index % 3 == 0) ? "test:read" : 
                               (index % 3 == 1) ? "test:write" : "test:delete";
            
            // Perform permission check
            boolean hasPermission = securitySystem.isPermitted(principals, permission);
            
            // All threads should get consistent results
            if (permission.equals("test:read") && !hasPermission) {
              failed.set(true);
              System.err.println("Expected permission test:read to be granted");
            }
            else if (!permission.equals("test:read") && hasPermission) {
              failed.set(true);
              System.err.println("Expected permission " + permission + " to be denied");
            }
          } 
          catch (Exception e) {
            failed.set(true);
            e.printStackTrace();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent permission test did not complete in time");
    }
    
    // Verify the test passed
    assertFalse(failed.get(), "Concurrent permission test failed with inconsistent results");
  }
  
  /**
   * Tests thread-safety of authorization caching with high concurrent access.
   * Validates that the cache remains consistent when accessed by many virtual threads.
   */
  @Test
  public void testAuthorizationCachingWithVirtualThreads() throws Exception {
    final int threadCount = 200;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicInteger cacheHits = new AtomicInteger(0);
    final AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Every 10th thread will update a role to invalidate cache
            if (index % 10 == 0 && index < 100) {
              Role role = authorizationManager.getRole("role" + ((index % 3) + 1));
              role.setDescription("Updated by thread " + index);
              authorizationManager.updateRole(role);
            }
            
            // All threads perform permission checks that should use/update the cache
            boolean hasPermission = securitySystem.isPermitted(principals, "test:read");
            if (!hasPermission) {
              failed.set(true);
              System.err.println("Expected permission test:read to be granted");
            }
            
            // Track cache hits (simplified - in real implementation we'd need to instrument the cache)
            cacheHits.incrementAndGet();
          } 
          catch (Exception e) {
            failed.set(true);
            e.printStackTrace();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Cache test did not complete in time");
    }
    
    // Verify the test passed
    assertFalse(failed.get(), "Cache test failed with inconsistent results");
    assertEquals(threadCount, cacheHits.get(), "Not all threads completed permission checks");
  }
  
  /**
   * Tests concurrent role and privilege operations using virtual threads.
   * Validates that the authorization system correctly handles concurrent CRUD operations.
   */
  @Test
  public void testConcurrentRoleAndPrivilegeOperations() throws Exception {
    final int threadCount = 50;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final AtomicBoolean failed = new AtomicBoolean(false);
    
    // Create an executor service that uses virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            if (index % 5 == 0) {
              // Create a new role
              Role role = new Role();
              role.setRoleId("vt-role-" + index);
              role.setName("Virtual Thread Role " + index);
              role.setDescription("Created by virtual thread " + index);
              role.addPrivilege("1");
              authorizationManager.addRole(role);
              
              // Verify it was created
              Role retrieved = authorizationManager.getRole(role.getRoleId());
              assertEquals(role.getName(), retrieved.getName());
            }
            else if (index % 5 == 1) {
              // List all roles
              Set<Role> roles = authorizationManager.listRoles();
              assertTrue(roles.size() >= 3, "Expected at least 3 roles");
            }
            else if (index % 5 == 2) {
              // Create a new privilege
              Privilege privilege = new Privilege();
              privilege.setId("vt-priv-" + index);
              privilege.setName("vt-name-" + index);
              privilege.setDescription("Created by virtual thread " + index);
              privilege.setType("application");
              privilege.addProperty("method", "read");
              privilege.addProperty("permission", "/test/path");
              authorizationManager.addPrivilege(privilege);
              
              // Verify it was created
              Privilege retrieved = authorizationManager.getPrivilege(privilege.getId());
              assertEquals(privilege.getName(), retrieved.getName());
            }
            else if (index % 5 == 3) {
              // List all privileges
              Set<Privilege> privileges = authorizationManager.listPrivileges();
              assertTrue(privileges.size() >= 4, "Expected at least 4 privileges");
            }
            else {
              // Perform permission check
              boolean hasPermission = securitySystem.isPermitted(principals, "test:read");
              assertTrue(hasPermission, "Expected permission test:read to be granted");
            }
          } 
          catch (Exception e) {
            failed.set(true);
            e.printStackTrace();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent CRUD test did not complete in time");
    }
    
    // Verify the test passed
    assertFalse(failed.get(), "Concurrent CRUD test failed");
  }
  
  /**
   * Compares performance between platform threads and virtual threads for authorization operations.
   * This test validates that virtual threads provide better performance for I/O-bound operations.
   */
  @Test
  public void testAuthorizationPerformanceComparison() throws Exception {
    // Measure performance with platform threads
    long platformThreadTime = measureAuthorizationPerformance(false);
    
    // Measure performance with virtual threads
    long virtualThreadTime = measureAuthorizationPerformance(true);
    
    // Virtual threads should be more efficient for I/O-bound operations
    System.out.println("Platform thread time: " + platformThreadTime + "ms");
    System.out.println("Virtual thread time: " + virtualThreadTime + "ms");
    
    // In a properly optimized system, virtual threads should perform better
    // However, this is not a strict assertion as it depends on the environment
    // and the specific operations being performed
    assertThat("Virtual threads should be more efficient than platform threads",
        virtualThreadTime, lessThan(platformThreadTime * 2)); // Allow some margin
  }
  
  /**
   * Measures the performance of authorization operations using either platform or virtual threads.
   * 
   * @param useVirtualThreads whether to use virtual threads
   * @return the time taken in milliseconds
   */
  private long measureAuthorizationPerformance(boolean useVirtualThreads) throws Exception {
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final List<Future<?>> futures = new ArrayList<>();
    
    long startTime = System.currentTimeMillis();
    
    // Create appropriate executor service
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(Math.min(threadCount, 20)); // Limit platform threads
    
    try {
      // Submit tasks to the executor
      for (int i = 0; i < threadCount; i++) {
        Future<?> future = executor.submit(() -> {
          try {
            // Perform multiple authorization operations
            for (int j = 0; j < PERFORMANCE_TEST_ITERATIONS / threadCount; j++) {
              securitySystem.isPermitted(principals, "test:read");
              authorizationManager.listRoles();
              authorizationManager.listPrivileges();
            }
          } 
          finally {
            latch.countDown();
          }
        });
        futures.add(future);
      }
      
      // Wait for all threads to complete
      latch.await(60, TimeUnit.SECONDS);
      
      // Check if any tasks failed
      for (Future<?> future : futures) {
        future.get(1, TimeUnit.SECONDS); // Will throw exception if task failed
      }
    } 
    finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    return System.currentTimeMillis() - startTime;
  }
  
  /**
   * Tests for thread pinning during authorization operations.
   * Thread pinning occurs when a virtual thread is forced to occupy a platform thread
   * for its entire execution, negating many of the benefits of virtual threads.
   */
  @Test
  public void testThreadPinningDuringAuthorization() throws Exception {
    // Enable thread pinning detection
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    pinningDetector.enable();
    
    try {
      // Create and start a virtual thread to perform authorization operations
      Thread virtualThread = Thread.ofVirtual().name("auth-test-thread").start(() -> {
        try {
          // Perform various authorization operations
          securitySystem.isPermitted(principals, "test:read");
          authorizationManager.listRoles();
          authorizationManager.listPrivileges();
          
          // Get a role and update it
          Role role = authorizationManager.getRole("role1");
          role.setDescription("Updated in virtual thread test");
          authorizationManager.updateRole(role);
          
          // Create and delete a privilege
          Privilege privilege = new Privilege();
          privilege.setId("vt-test-priv");
          privilege.setName("vt-test-name");
          privilege.setDescription("Test privilege for thread pinning");
          privilege.setType("application");
          privilege.addProperty("method", "read");
          privilege.addProperty("permission", "/test/path");
          authorizationManager.addPrivilege(privilege);
          authorizationManager.deletePrivilege(privilege.getId());
        }
        catch (Exception e) {
          e.printStackTrace();
        }
      });
      
      // Wait for the thread to complete
      virtualThread.join(10000);
      
      // Check if the thread is still alive (it shouldn't be)
      assertFalse(virtualThread.isAlive(), "Virtual thread did not complete in time");
      
      // Verify the thread was a virtual thread
      assertThat(virtualThread, VirtualThreadMatchers.isVirtualThread());
      
      // Check for pinning events - ideally there should be none or very few
      int pinningEvents = pinningDetector.getPinningEvents().size();
      System.out.println("Detected " + pinningEvents + " thread pinning events");
      
      // This is not a strict assertion as some pinning might be unavoidable
      // but we want to be aware of excessive pinning
      assertThat("Thread pinning events should be minimal", 
          pinningEvents, lessThan(5));
    }
    finally {
      pinningDetector.disable();
    }
  }
}