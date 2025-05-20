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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationException;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.role.RoleIdentifier;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserStatus;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the {@link SecuritySystem} functionality under Java 21's Virtual Thread execution model.
 * 
 * This test suite validates that security operations maintain correctness and performance
 * when handling many concurrent security requests using Virtual Threads.
 */
public class SecuritySystemVirtualThreadTest
    extends AbstractSecurityTest
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TIMEOUT_SECONDS = 30;
  
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @Before
  public void setup() throws Exception {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a comparable platform thread executor
    platformThreadExecutor = Executors.newFixedThreadPool(20, new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger();
      
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName("platform-thread-" + counter.incrementAndGet());
        t.setDaemon(true);
        return t;
      }
    });
  }
  
  @After
  public void cleanup() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests concurrent user authentication with virtual threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    
    // Create a list to hold all the futures
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    // Start the timer for virtual threads
    long startTime = System.nanoTime();
    
    // Submit concurrent authentication tasks
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Get a new subject for this thread
          Subject subject = securitySystem.getSubject();
          assertNotNull("Subject should not be null", subject);
          
          // Login with valid credentials
          UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
          subject.login(token);
          
          // Verify the subject is authenticated
          assertTrue("Subject should be authenticated", subject.isAuthenticated());
          
          // Logout
          subject.logout();
          assertFalse("Subject should be logged out", subject.isAuthenticated());
          
          return true;
        } 
        catch (Exception e) {
          e.printStackTrace();
          return false;
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all authentication operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join();
    
    // Calculate elapsed time
    long virtualThreadTime = System.nanoTime() - startTime;
    
    // Verify all operations succeeded
    for (CompletableFuture<Boolean> future : futures) {
      assertTrue("Authentication operation should succeed", future.get());
    }
    
    System.out.printf("Completed %d concurrent authentication operations with virtual threads in %d ms%n", 
        CONCURRENT_OPERATIONS, TimeUnit.NANOSECONDS.toMillis(virtualThreadTime));
  }
  
  /**
   * Tests concurrent authorization checks with virtual threads.
   */
  @Test
  public void testConcurrentAuthorizationWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    
    // Create a list to hold all the futures
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    // Start the timer for virtual threads
    long startTime = System.nanoTime();
    
    // Submit concurrent authorization tasks
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      final int index = i;
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Create a principal collection for the user
          PrincipalCollection principal = new SimplePrincipalCollection("jcool", "ANYTHING");
          
          // Check a valid permission
          securitySystem.checkPermission(principal, "test:read");
          
          // Try an invalid permission (should throw exception)
          if (index % 10 == 0) { // Only do this for some threads to avoid too many exceptions
            try {
              securitySystem.checkPermission(principal, "INVALID-ROLE:*");
              fail("Expected AuthorizationException for invalid permission");
            }
            catch (AuthorizationException e) {
              // Expected exception
            }
          }
          
          return true;
        } 
        catch (Exception e) {
          if (!(e instanceof AuthorizationException)) {
            e.printStackTrace();
          }
          return false;
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all authorization operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join();
    
    // Calculate elapsed time
    long virtualThreadTime = System.nanoTime() - startTime;
    
    // Verify all operations succeeded
    for (CompletableFuture<Boolean> future : futures) {
      assertTrue("Authorization operation should succeed", future.get());
    }
    
    System.out.printf("Completed %d concurrent authorization operations with virtual threads in %d ms%n", 
        CONCURRENT_OPERATIONS, TimeUnit.NANOSECONDS.toMillis(virtualThreadTime));
  }
  
  /**
   * Tests concurrent user creation and management with virtual threads.
   */
  @Test
  public void testConcurrentUserManagementWithVirtualThreads() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    
    // Create a map to track created user IDs
    ConcurrentHashMap<String, String> createdUsers = new ConcurrentHashMap<>();
    
    // Create a list to hold all the futures
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    // Start the timer for virtual threads
    long startTime = System.nanoTime();
    
    // Submit concurrent user creation tasks
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Generate a unique user ID
          String userId = "vt-user-" + UUID.randomUUID().toString().substring(0, 8);
          
          // Create a new user
          User user = new User();
          user.setEmailAddress(userId + "@example.com");
          user.setName("Virtual Thread Test User");
          user.setSource("MockUserManagerA");
          user.setStatus(UserStatus.active);
          user.setUserId(userId);
          user.addRole(new RoleIdentifier("default", "test-role1"));
          
          // Add the user
          User createdUser = securitySystem.addUser(user, "password123");
          assertNotNull("Created user should not be null", createdUser);
          assertEquals("User ID should match", userId, createdUser.getUserId());
          
          // Store the created user ID
          createdUsers.put(userId, userId);
          
          // Read the user back
          User retrievedUser = securitySystem.getUser(userId, "MockUserManagerA");
          assertNotNull("Retrieved user should not be null", retrievedUser);
          assertEquals("Retrieved user ID should match", userId, retrievedUser.getUserId());
          
          // Update the user
          retrievedUser.setName("Updated Name");
          retrievedUser.setEmailAddress("updated-" + userId + "@example.com");
          securitySystem.updateUser(retrievedUser);
          
          // Read the user again to verify update
          User updatedUser = securitySystem.getUser(userId, "MockUserManagerA");
          assertNotNull("Updated user should not be null", updatedUser);
          assertEquals("Updated name should match", "Updated Name", updatedUser.getName());
          
          return true;
        } 
        catch (Exception e) {
          e.printStackTrace();
          return false;
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all user management operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join();
    
    // Calculate elapsed time
    long virtualThreadTime = System.nanoTime() - startTime;
    
    // Verify all operations succeeded
    for (CompletableFuture<Boolean> future : futures) {
      assertTrue("User management operation should succeed", future.get());
    }
    
    // Verify the number of created users
    assertEquals("Number of created users should match", CONCURRENT_OPERATIONS, createdUsers.size());
    
    System.out.printf("Completed %d concurrent user management operations with virtual threads in %d ms%n", 
        CONCURRENT_OPERATIONS, TimeUnit.NANOSECONDS.toMillis(virtualThreadTime));
  }
  
  /**
   * Compares performance between virtual threads and platform threads for authentication operations.
   */
  @Test
  public void testAuthenticationPerformanceComparison() throws Exception {
    SecuritySystem securitySystem = getSecuritySystem();
    
    // Run authentication benchmark with platform threads
    long platformThreadTime = runAuthenticationBenchmark(securitySystem, platformThreadExecutor);
    
    // Run authentication benchmark with virtual threads
    long virtualThreadTime = runAuthenticationBenchmark(securitySystem, virtualThreadExecutor);
    
    // Log the results
    System.out.printf("Authentication Performance Comparison:%n");
    System.out.printf("  Platform Threads: %d ms%n", TimeUnit.NANOSECONDS.toMillis(platformThreadTime));
    System.out.printf("  Virtual Threads:  %d ms%n", TimeUnit.NANOSECONDS.toMillis(virtualThreadTime));
    System.out.printf("  Improvement:      %.2f%%%n", 
        (platformThreadTime - virtualThreadTime) * 100.0 / platformThreadTime);
    
    // We expect virtual threads to be at least as fast as platform threads
    // This is a loose assertion since actual performance depends on the environment
    assertThat("Virtual threads should not be significantly slower than platform threads",
        Duration.ofNanos(virtualThreadTime).compareTo(Duration.ofNanos(platformThreadTime) 
            .multipliedBy(12).dividedBy(10)) <= 0);
  }
  
  /**
   * Runs an authentication benchmark with the specified executor.
   * 
   * @param securitySystem the security system to use
   * @param executor the executor to use (virtual or platform)
   * @return the elapsed time in nanoseconds
   */
  private long runAuthenticationBenchmark(SecuritySystem securitySystem, ExecutorService executor) throws Exception {
    // Create a list to hold all the futures
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();
    
    // Start the timer
    long startTime = System.nanoTime();
    
    // Submit concurrent authentication tasks
    for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
      CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Get a new subject for this thread
          Subject subject = securitySystem.getSubject();
          assertThat(subject, is(notNullValue()));
          
          // Login with valid credentials
          UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
          subject.login(token);
          
          // Verify the subject is authenticated
          assertTrue(subject.isAuthenticated());
          
          // Logout
          subject.logout();
          assertFalse(subject.isAuthenticated());
          
          return true;
        } 
        catch (Exception e) {
          e.printStackTrace();
          return false;
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for all authentication operations to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
        .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .join();
    
    // Calculate and return elapsed time
    return System.nanoTime() - startTime;
  }
}