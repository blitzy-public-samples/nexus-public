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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.realm.RealmManager;
import org.sonatype.nexus.security.role.Role;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestGroup;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for concurrent user operations using Java 21 Virtual Threads.
 * <p>
 * These tests verify that the Nexus security framework correctly handles high-concurrency
 * scenarios by performing thousands of concurrent user lookups, authentications, and role
 * evaluations using virtual threads.
 * </p>
 * <p>
 * The tests also compare performance between virtual threads and platform threads to
 * demonstrate the scalability benefits of virtual threads for I/O-bound operations.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@VirtualThreadTestGroup
public class ConcurrentUserOperationsIT extends AbstractSecurityTest
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int USER_COUNT = 100;
  private static final int ROLES_PER_USER = 5;
  private static final int PRIVILEGES_PER_ROLE = 3;
  
  private SecuritySystem securitySystem;
  private ConcurrentUserManager userManager;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  /**
   * Custom user manager for concurrent testing that uses thread-safe collections.
   */
  @Singleton
  static class ConcurrentUserManager extends MockUserManagerSupport
  {
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final String source;
    
    public ConcurrentUserManager(String source) {
      this.source = source;
    }
    
    @Override
    public String getSource() {
      return source;
    }
    
    @Override
    public String getAuthenticationRealmName() {
      return source;
    }
    
    @Override
    protected Map<String, User> getUsers() {
      return users;
    }
    
    @Override
    public boolean isConfigured() {
      return true;
    }
  }
  
  @Override
  protected void customizeModules(List<Module> modules) {
    super.customizeModules(modules);
    modules.add(new AbstractModule() {
      @Override
      protected void configure() {
        // Bind our concurrent user manager for testing
        userManager = new ConcurrentUserManager("ConcurrentRealm");
        bind(UserManager.class)
            .annotatedWith(Names.named("Concurrent"))
            .toInstance(userManager);
      }
    });
  }
  
  @BeforeEach
  protected void setUp() throws Exception {
    super.setUp();
    
    securitySystem = getSecuritySystem();
    
    // Configure the realm manager to use our test realm
    RealmManager realmManager = lookup(RealmManager.class);
    realmManager.setConfiguredRealmIds(ImmutableList.of("ConcurrentRealm"));
    
    // Create executors for virtual and platform threads
    virtualThreadExecutor = createVirtualThreadExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(100); // Match typical server thread pool size
    
    // Create test users and roles
    createTestUsers();
  }
  
  @AfterEach
  protected void tearDown() throws Exception {
    try {
      if (virtualThreadExecutor != null) {
        virtualThreadExecutor.shutdown();
        virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
      }
      
      if (platformThreadExecutor != null) {
        platformThreadExecutor.shutdown();
        platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
      }
    } finally {
      super.tearDown();
    }
  }
  
  /**
   * Creates test users with roles and privileges for concurrent testing.
   */
  private void createTestUsers() throws Exception {
    // Create roles with privileges
    AuthorizationManager authzManager = securitySystem.getAuthorizationManager("ConcurrentRealm");
    
    for (int i = 0; i < ROLES_PER_USER; i++) {
      Role role = new Role();
      String roleId = "role-" + i;
      role.setRoleId(roleId);
      role.setName("Role " + i);
      role.setSource("ConcurrentRealm");
      
      // Add privileges to role
      List<String> privilegeIds = new ArrayList<>();
      for (int j = 0; j < PRIVILEGES_PER_ROLE; j++) {
        String privilegeId = "priv-" + i + "-" + j;
        Privilege privilege = new Privilege();
        privilege.setId(privilegeId);
        privilege.setName("Privilege " + i + "-" + j);
        privilege.setType("application");
        privilege.setReadOnly(false);
        
        authzManager.addPrivilege(privilege);
        privilegeIds.add(privilegeId);
      }
      
      role.setPrivileges(privilegeIds);
      authzManager.addRole(role);
    }
    
    // Create users with roles
    for (int i = 0; i < USER_COUNT; i++) {
      User user = new User();
      String userId = "user-" + i;
      user.setUserId(userId);
      user.setFirstName("Test");
      user.setLastName("User " + i);
      user.setEmailAddress("user" + i + "@example.com");
      user.setSource("ConcurrentRealm");
      user.setStatus(UserStatus.active);
      
      // Assign roles to user
      for (int j = 0; j < ROLES_PER_USER; j++) {
        user.addRole(new RoleIdentifier("ConcurrentRealm", "role-" + j));
      }
      
      userManager.addUser(user, "password");
    }
  }
  
  /**
   * Tests concurrent user lookups using virtual threads.
   * <p>
   * This test verifies that the security system can handle a large number of concurrent
   * user lookups without errors or race conditions.
   * </p>
   */
  @Test
  @DisplayName("Test concurrent user lookups with virtual threads")
  public void testConcurrentUserLookups() throws Exception {
    // Create a set to track unique users found
    Set<String> foundUsers = ConcurrentHashMap.newKeySet();
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Run concurrent lookups
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i % USER_COUNT;
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "user-" + index;
          User user = securitySystem.getUser(userId);
          assertNotNull(user, "User should not be null");
          assertEquals(userId, user.getUserId(), "User ID should match");
          foundUsers.add(userId);
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent operations");
    
    // Verify results
    assertEquals(0, errorCount.get(), "There should be no errors during concurrent lookups");
    assertEquals(USER_COUNT, foundUsers.size(), "All users should be found");
  }
  
  /**
   * Tests concurrent user authentications using virtual threads.
   * <p>
   * This test verifies that the security system can handle a large number of concurrent
   * authentication requests without errors or race conditions.
   * </p>
   */
  @Test
  @DisplayName("Test concurrent user authentications with virtual threads")
  public void testConcurrentAuthentications() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Run concurrent authentications
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i % USER_COUNT;
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "user-" + index;
          securitySystem.authenticate(new UsernamePasswordToken(userId, "password"));
          successCount.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent operations");
    
    // Verify results
    assertEquals(0, errorCount.get(), "There should be no errors during concurrent authentications");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "All authentications should succeed");
  }
  
  /**
   * Tests concurrent role evaluations using virtual threads.
   * <p>
   * This test verifies that the security system can handle a large number of concurrent
   * role and permission evaluations without errors or race conditions.
   * </p>
   */
  @Test
  @DisplayName("Test concurrent role evaluations with virtual threads")
  public void testConcurrentRoleEvaluations() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Run concurrent role evaluations
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int userIndex = i % USER_COUNT;
      final int roleIndex = i % ROLES_PER_USER;
      
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "user-" + userIndex;
          String roleId = "role-" + roleIndex;
          
          // Get user and check if they have the role
          User user = securitySystem.getUser(userId);
          boolean hasRole = user.getRoles().stream()
              .anyMatch(role -> role.getRoleId().equals(roleId));
          
          assertTrue(hasRole, "User should have the role");
          successCount.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent operations");
    
    // Verify results
    assertEquals(0, errorCount.get(), "There should be no errors during concurrent role evaluations");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "All role evaluations should succeed");
  }
  
  /**
   * Tests concurrent permission checks using virtual threads.
   * <p>
   * This test verifies that the security system can handle a large number of concurrent
   * permission checks without errors or race conditions.
   * </p>
   */
  @Test
  @DisplayName("Test concurrent permission checks with virtual threads")
  public void testConcurrentPermissionChecks() throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    
    // Run concurrent permission checks
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int userIndex = i % USER_COUNT;
      final int roleIndex = i % ROLES_PER_USER;
      final int privIndex = i % PRIVILEGES_PER_ROLE;
      
      virtualThreadExecutor.submit(() -> {
        try {
          String userId = "user-" + userIndex;
          String privilegeId = "priv-" + roleIndex + "-" + privIndex;
          
          // Authenticate as user
          securitySystem.authenticate(new UsernamePasswordToken(userId, "password"));
          
          // Check if user has the privilege
          boolean hasPermission = securitySystem.isPermitted(privilegeId);
          assertTrue(hasPermission, "User should have the permission");
          
          successCount.incrementAndGet();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for concurrent operations");
    
    // Verify results
    assertEquals(0, errorCount.get(), "There should be no errors during concurrent permission checks");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "All permission checks should succeed");
  }
  
  /**
   * Compares performance between virtual threads and platform threads for user lookups.
   * <p>
   * This test demonstrates the scalability benefits of virtual threads for I/O-bound operations
   * by comparing throughput between virtual threads and platform threads.
   * </p>
   */
  @Test
  @DisplayName("Compare performance between virtual threads and platform threads")
  public void testVirtualThreadPerformance() throws Exception {
    // Run performance test with virtual threads
    PerformanceResult virtualThreadResult = runPerformanceTest(
        "Virtual Threads",
        virtualThreadExecutor,
        CONCURRENT_OPERATIONS);
    
    // Run performance test with platform threads
    PerformanceResult platformThreadResult = runPerformanceTest(
        "Platform Threads",
        platformThreadExecutor,
        CONCURRENT_OPERATIONS);
    
    // Log results
    System.out.println("\nPerformance Comparison:");
    System.out.println("- Virtual Threads: " + virtualThreadResult);
    System.out.println("- Platform Threads: " + platformThreadResult);
    System.out.println("- Throughput Ratio (Virtual/Platform): " + 
        String.format("%.2f", virtualThreadResult.getOperationsPerSecond() / platformThreadResult.getOperationsPerSecond()));
    
    // Verify that both completed successfully
    assertEquals(0, virtualThreadResult.getErrorCount(), "Virtual thread test should have no errors");
    assertEquals(0, platformThreadResult.getErrorCount(), "Platform thread test should have no errors");
    
    // Note: We don't assert that virtual threads are faster, as that depends on the environment
    // and the nature of the operations. In some cases, the difference might be minimal.
  }
  
  /**
   * Runs a performance test with the specified executor and number of operations.
   *
   * @param testName the name of the test for reporting
   * @param executor the executor to use for the test
   * @param operations the number of operations to perform
   * @return the performance result
   */
  private PerformanceResult runPerformanceTest(String testName, ExecutorService executor, int operations) 
      throws Exception {
    System.out.println("\nRunning performance test: " + testName);
    
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    CountDownLatch latch = new CountDownLatch(operations);
    
    // Start timing
    long startTime = System.nanoTime();
    
    // Submit tasks
    for (int i = 0; i < operations; i++) {
      final int index = i % USER_COUNT;
      executor.submit(() -> {
        try {
          String userId = "user-" + index;
          User user = securitySystem.getUser(userId);
          if (user != null && userId.equals(user.getUserId())) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for completion
    latch.await(30, TimeUnit.SECONDS);
    
    // Calculate duration
    long duration = System.nanoTime() - startTime;
    double durationSeconds = duration / 1_000_000_000.0;
    double opsPerSecond = operations / durationSeconds;
    
    return new PerformanceResult(testName, operations, successCount.get(), errorCount.get(), 
        durationSeconds, opsPerSecond);
  }
  
  /**
   * Value class to hold performance test results.
   */
  private static class PerformanceResult {
    private final String testName;
    private final int totalOperations;
    private final int successCount;
    private final int errorCount;
    private final double durationSeconds;
    private final double operationsPerSecond;
    
    public PerformanceResult(String testName, int totalOperations, int successCount, int errorCount, 
                            double durationSeconds, double operationsPerSecond) {
      this.testName = testName;
      this.totalOperations = totalOperations;
      this.successCount = successCount;
      this.errorCount = errorCount;
      this.durationSeconds = durationSeconds;
      this.operationsPerSecond = operationsPerSecond;
    }
    
    public int getErrorCount() {
      return errorCount;
    }
    
    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }
    
    @Override
    public String toString() {
      return String.format("%d operations in %.2f seconds (%.2f ops/sec, %d errors)",
          totalOperations, durationSeconds, operationsPerSecond, errorCount);
    }
  }
}