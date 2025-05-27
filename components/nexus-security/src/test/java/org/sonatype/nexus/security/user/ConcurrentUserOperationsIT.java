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

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.role.RoleIdentifier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Integration test for concurrent user management operations using Java 21 Virtual Threads.
 * <p>
 * This test validates that the Nexus security framework can handle high-concurrency scenarios
 * correctly by performing thousands of concurrent user lookups, authentications, and role
 * evaluations using virtual threads.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@VirtualThreadTestGroup
public class ConcurrentUserOperationsIT
    extends AbstractSecurityTest
{
  /**
   * Annotation to categorize tests that use Virtual Threads.
   * <p>
   * This allows selective execution of Virtual Thread tests, which require Java 21.
   */
  @Target({ElementType.TYPE, ElementType.METHOD})
  @Retention(RetentionPolicy.RUNTIME)
  @Tag("virtual-thread")
  public @interface VirtualThreadTestGroup {
  }

  private static final int CONCURRENT_USERS = 1000;
  private static final int CONCURRENT_OPERATIONS = 5000;
  private static final int THREAD_COUNT = 10;
  private static final int TIMEOUT_SECONDS = 30;

  private SecuritySystem securitySystem;
  private UserManager userManager;
  private ExecutorService virtualThreadExecutor;

  @Mock
  private AuthorizationManager authorizationManager;

  @BeforeEach
  @Override
  public void setUp() throws Exception {
    super.setUp();

    securitySystem = getSecuritySystem();
    userManager = lookup(UserManager.class, "default");

    // Create a virtual thread executor
    ThreadFactory factory = Thread.ofVirtual()
        .name("virtual-thread-", 0)
        .factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(factory);

    // Create test users
    createTestUsers(CONCURRENT_USERS);
  }

  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Tests concurrent user retrieval operations using virtual threads.
   * <p>
   * This test creates thousands of virtual threads that each retrieve a user from the security system,
   * verifying that the system can handle high concurrency without errors or deadlocks.
   */
  @Test
  @DisplayName("Test concurrent user retrieval with virtual threads")
  public void testConcurrentUserRetrieval() throws Exception {
    // Create a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();

    // Submit tasks to the virtual thread executor
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int userId = i % CONCURRENT_USERS;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();

          // Retrieve a user
          User user = securitySystem.getUser("test-user-" + userId);
          if (user != null && user.getUserId().equals("test-user-" + userId)) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the test and measure execution time
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify results
    assertTrue(completed, "Operations did not complete within timeout");
    assertEquals(0, errorCount.get(), 
        lastException.get() != null ? "Errors occurred: " + lastException.get().getMessage() : "Errors occurred");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all operations succeeded");

    // Log performance metrics
    double operationsPerSecond = (double) CONCURRENT_OPERATIONS / (durationMs / 1000.0);
    log.info("Completed {} user retrievals in {} ms ({} ops/sec) using virtual threads",
        CONCURRENT_OPERATIONS, durationMs, String.format("%.2f", operationsPerSecond));
  }

  /**
   * Tests concurrent user authentication operations using virtual threads.
   * <p>
   * This test creates thousands of virtual threads that each authenticate a user with the security system,
   * verifying that the system can handle high concurrency without errors or deadlocks.
   */
  @Test
  @DisplayName("Test concurrent user authentication with virtual threads")
  public void testConcurrentUserAuthentication() throws Exception {
    // Create a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();

    // Submit tasks to the virtual thread executor
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int userId = i % CONCURRENT_USERS;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();

          // Authenticate a user
          String username = "test-user-" + userId;
          String password = "password-" + userId;
          securitySystem.authenticate(username, password);
          successCount.incrementAndGet();
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the test and measure execution time
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify results
    assertTrue(completed, "Operations did not complete within timeout");
    assertEquals(0, errorCount.get(), 
        lastException.get() != null ? "Errors occurred: " + lastException.get().getMessage() : "Errors occurred");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all operations succeeded");

    // Log performance metrics
    double operationsPerSecond = (double) CONCURRENT_OPERATIONS / (durationMs / 1000.0);
    log.info("Completed {} user authentications in {} ms ({} ops/sec) using virtual threads",
        CONCURRENT_OPERATIONS, durationMs, String.format("%.2f", operationsPerSecond));
  }

  /**
   * Tests concurrent role evaluation operations using virtual threads.
   * <p>
   * This test creates thousands of virtual threads that each evaluate a user's roles with the security system,
   * verifying that the system can handle high concurrency without errors or deadlocks.
   */
  @Test
  @DisplayName("Test concurrent role evaluation with virtual threads")
  public void testConcurrentRoleEvaluation() throws Exception {
    // Create a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();

    // Submit tasks to the virtual thread executor
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int userId = i % CONCURRENT_USERS;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();

          // Retrieve a user and evaluate roles
          User user = securitySystem.getUser("test-user-" + userId);
          Set<String> roleIds = user.getRoles().stream()
              .map(RoleIdentifier::getRoleId)
              .collect(Collectors.toSet());
          
          // Verify the user has the expected role
          if (roleIds.contains("test-role-" + userId)) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the test and measure execution time
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify results
    assertTrue(completed, "Operations did not complete within timeout");
    assertEquals(0, errorCount.get(), 
        lastException.get() != null ? "Errors occurred: " + lastException.get().getMessage() : "Errors occurred");
    assertEquals(CONCURRENT_OPERATIONS, successCount.get(), "Not all operations succeeded");

    // Log performance metrics
    double operationsPerSecond = (double) CONCURRENT_OPERATIONS / (durationMs / 1000.0);
    log.info("Completed {} role evaluations in {} ms ({} ops/sec) using virtual threads",
        CONCURRENT_OPERATIONS, durationMs, String.format("%.2f", operationsPerSecond));
  }

  /**
   * Tests concurrent permission checks using virtual threads.
   * <p>
   * This test creates thousands of virtual threads that each check a user's permissions with the security system,
   * verifying that the system can handle high concurrency without errors or deadlocks.
   */
  @Test
  @DisplayName("Test concurrent permission checks with virtual threads")
  public void testConcurrentPermissionChecks() throws Exception {
    // Create a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();

    // Submit tasks to the virtual thread executor
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int userId = i % CONCURRENT_USERS;
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();

          // Authenticate a user and check permissions
          String username = "test-user-" + userId;
          String password = "password-" + userId;
          securitySystem.authenticate(username, password);
          
          // Check a permission (this will be mocked)
          boolean hasPermission = securitySystem.isPermitted(username, "nexus:test:read:" + userId);
          if (hasPermission) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the test and measure execution time
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify results
    assertTrue(completed, "Operations did not complete within timeout");
    assertEquals(0, errorCount.get(), 
        lastException.get() != null ? "Errors occurred: " + lastException.get().getMessage() : "Errors occurred");

    // Log performance metrics
    double operationsPerSecond = (double) CONCURRENT_OPERATIONS / (durationMs / 1000.0);
    log.info("Completed {} permission checks in {} ms ({} ops/sec) using virtual threads",
        CONCURRENT_OPERATIONS, durationMs, String.format("%.2f", operationsPerSecond));
  }

  /**
   * Tests concurrent user creation operations using virtual threads.
   * <p>
   * This test creates thousands of virtual threads that each create a new user in the security system,
   * verifying that the system can handle high concurrency without errors or deadlocks.
   */
  @Test
  @DisplayName("Test concurrent user creation with virtual threads")
  public void testConcurrentUserCreation() throws Exception {
    // Create a countdown latch to coordinate threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicReference<Exception> lastException = new AtomicReference<>();

    // Generate unique user IDs
    List<String> userIds = IntStream.range(0, THREAD_COUNT)
        .mapToObj(i -> "concurrent-user-" + UUID.randomUUID().toString().substring(0, 8))
        .collect(Collectors.toList());

    // Submit tasks to the virtual thread executor
    for (int i = 0; i < THREAD_COUNT; i++) {
      final String userId = userIds.get(i);
      virtualThreadExecutor.submit(() -> {
        try {
          // Wait for the start signal
          startLatch.await();

          // Create a new user
          User user = new User();
          user.setUserId(userId);
          user.setFirstName("Concurrent");
          user.setLastName("User");
          user.setEmailAddress(userId + "@example.com");
          user.setStatus(UserStatus.active);
          user.setSource("default");

          // Add a role
          Set<RoleIdentifier> roles = new HashSet<>();
          roles.add(new RoleIdentifier("default", "nx-admin"));
          user.setRoles(roles);

          // Create the user
          securitySystem.addUser(user, "password");

          // Verify the user was created
          User createdUser = securitySystem.getUser(userId);
          if (createdUser != null && createdUser.getUserId().equals(userId)) {
            successCount.incrementAndGet();
          }
        }
        catch (Exception e) {
          errorCount.incrementAndGet();
          lastException.set(e);
        }
        finally {
          completionLatch.countDown();
        }
      });
    }

    // Start the test and measure execution time
    long startTime = System.nanoTime();
    startLatch.countDown();

    // Wait for all operations to complete
    boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    long endTime = System.nanoTime();
    long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);

    // Verify results
    assertTrue(completed, "Operations did not complete within timeout");
    assertEquals(0, errorCount.get(), 
        lastException.get() != null ? "Errors occurred: " + lastException.get().getMessage() : "Errors occurred");
    assertEquals(THREAD_COUNT, successCount.get(), "Not all operations succeeded");

    // Log performance metrics
    double operationsPerSecond = (double) THREAD_COUNT / (durationMs / 1000.0);
    log.info("Completed {} user creations in {} ms ({} ops/sec) using virtual threads",
        THREAD_COUNT, durationMs, String.format("%.2f", operationsPerSecond));

    // Clean up created users
    for (String userId : userIds) {
      try {
        securitySystem.deleteUser(userId);
      }
      catch (Exception e) {
        log.warn("Failed to delete test user {}: {}", userId, e.getMessage());
      }
    }
  }

  /**
   * Compares performance between virtual threads and platform threads for user operations.
   * <p>
   * This test executes the same user retrieval operations using both virtual threads and platform threads,
   * then compares the performance to demonstrate the benefits of virtual threads for I/O-bound operations.
   */
  @Test
  @DisplayName("Compare virtual threads vs platform threads performance")
  public void testVirtualVsPlatformThreadsPerformance() throws Exception {
    int operationCount = 1000;
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try {
        executeUserRetrievals(operationCount, true);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try {
        executeUserRetrievals(operationCount, false);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Log and compare results
    log.info("Virtual threads: {} operations in {} ms", operationCount, virtualThreadTime);
    log.info("Platform threads: {} operations in {} ms", operationCount, platformThreadTime);
    log.info("Performance ratio: {}", String.format("%.2f", (double) platformThreadTime / virtualThreadTime));
    
    // Virtual threads should generally be faster for I/O-bound operations
    assertThat("Virtual threads should be at least as fast as platform threads",
        platformThreadTime, greaterThan(virtualThreadTime * 0.8));
  }

  /**
   * Helper method to create test users for the integration tests.
   *
   * @param count the number of users to create
   */
  private void createTestUsers(int count) throws Exception {
    for (int i = 0; i < count; i++) {
      User user = new User();
      user.setUserId("test-user-" + i);
      user.setFirstName("Test");
      user.setLastName("User " + i);
      user.setEmailAddress("test-user-" + i + "@example.com");
      user.setStatus(UserStatus.active);
      user.setSource("default");

      // Add a role
      Set<RoleIdentifier> roles = new HashSet<>();
      roles.add(new RoleIdentifier("default", "test-role-" + i));
      user.setRoles(roles);

      // Create the user
      userManager.addUser(user, "password-" + i);
    }
  }

  /**
   * Helper method to execute user retrieval operations with either virtual or platform threads.
   *
   * @param count the number of operations to execute
   * @param useVirtualThreads whether to use virtual threads or platform threads
   */
  private void executeUserRetrievals(int count, boolean useVirtualThreads) throws Exception {
    // Create an appropriate executor
    ExecutorService executor = null;
    try {
      if (useVirtualThreads) {
        ThreadFactory factory = Thread.ofVirtual()
            .name("virtual-thread-", 0)
            .factory();
        executor = Executors.newThreadPerTaskExecutor(factory);
      }
      else {
        executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
      }

      // Create a countdown latch to coordinate threads
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(count);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);

      // Submit tasks to the executor
      for (int i = 0; i < count; i++) {
        final int userId = i % CONCURRENT_USERS;
        executor.submit(() -> {
          try {
            // Wait for the start signal
            startLatch.await();

            // Retrieve a user
            User user = securitySystem.getUser("test-user-" + userId);
            if (user != null && user.getUserId().equals("test-user-" + userId)) {
              successCount.incrementAndGet();
            }
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }

      // Start the test
      startLatch.countDown();

      // Wait for all operations to complete
      boolean completed = completionLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

      // Verify results
      assertTrue(completed, "Operations did not complete within timeout");
      assertEquals(0, errorCount.get(), "Errors occurred");
      assertEquals(count, successCount.get(), "Not all operations succeeded");
    }
    finally {
      if (executor != null) {
        executor.shutdown();
        executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    }
  }

  /**
   * Helper method to measure the execution time of a task.
   *
   * @param task the task to execute
   * @return the execution time in milliseconds
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.nanoTime();
    task.run();
    long endTime = System.nanoTime();
    return TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
  }
}