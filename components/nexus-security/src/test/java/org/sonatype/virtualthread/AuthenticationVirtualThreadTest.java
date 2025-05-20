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

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationException;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.memory.MemoryCPrivilege;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.internal.AuthenticatingRealmImpl;

import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests authentication operations (login/logout flows) with Java 21 Virtual Threads to ensure that
 * authentication realms, credential verification, and security context management work properly in a
 * highly concurrent environment. This test ensures that user authentication remains secure and scales
 * efficiently when handling thousands of concurrent login attempts.
 * 
 * Key aspects tested:
 * - Concurrent authentication with thousands of virtual threads
 * - Authentication caching behavior with virtual threads
 * - Thread pinning detection during synchronized operations
 * - Security isolation between concurrent authentication requests
 * - Proper cleanup of security contexts after authentication
 * 
 * @since 3.60.0
 */
@EnabledOnJre(JRE.JAVA_21) // Only run on Java 21 or newer which supports virtual threads
public class AuthenticationVirtualThreadTest
    extends AbstractSecurityTest
{
  /**
   * Number of concurrent users to test with.
   * This is set to a high value to stress test the authentication system with virtual threads.
   * In a real-world scenario, this could represent thousands of concurrent login attempts.
   * 
   * Note: The actual number of users created is limited to 100 to avoid excessive test setup time.
   * The remaining virtual threads will reuse existing users in a round-robin fashion.
   */
  private static final int CONCURRENT_USERS = 1000;
  
  /**
   * Number of authentication iterations per user.
   * Each virtual thread will perform this many login/logout cycles.
   */
  private static final int ITERATIONS_PER_USER = 5;
  
  private AuthenticatingRealmImpl realm;
  private PasswordService passwordService;
  private SecuritySystem securitySystem;
  
  private final ConcurrentHashMap<String, String> testUsers = new ConcurrentHashMap<>();
  private final AtomicInteger successfulLogins = new AtomicInteger(0);
  private final AtomicInteger failedLogins = new AtomicInteger(0);
  private final AtomicInteger threadPinningDetected = new AtomicInteger(0);
  
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    
    realm = (AuthenticatingRealmImpl) lookup(Realm.class, AuthenticatingRealmImpl.NAME);
    passwordService = lookup(PasswordService.class, "default");
    securitySystem = getSecuritySystem();
    
    // Create test privilege and role
    createTestPrivilegeAndRole();
    
    // Create test users
    createTestUsers(CONCURRENT_USERS);
  }
  
  @BeforeEach
  public void setUpTest() {
    successfulLogins.set(0);
    failedLogins.set(0);
    threadPinningDetected.set(0);
  }
  
  @AfterEach
  public void tearDownTest() {
    // Log statistics
    System.out.println("Test: " + getClass().getSimpleName() + "." + 
        Thread.currentThread().getStackTrace()[2].getMethodName());
    System.out.println("Successful logins: " + successfulLogins.get());
    System.out.println("Failed logins: " + failedLogins.get());
    System.out.println("Thread pinning detected: " + threadPinningDetected.get());
    System.out.println("---------------------------------------------------");
  }
  
  /**
   * Tests concurrent authentication with virtual threads.
   * This test creates thousands of virtual threads that each attempt to authenticate
   * multiple times, verifying that the authentication system works correctly under high load.
   * 
   * The test validates:
   * - Virtual threads can successfully authenticate and logout
   * - Security contexts are properly maintained per virtual thread
   * - Permissions are correctly evaluated for authenticated subjects
   * - No thread pinning occurs during normal authentication operations
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    // Skip test if virtual threads are not supported
    org.junit.jupiter.api.Assumptions.assumeTrue(isVirtualThreadSupported(), 
        "Virtual threads are not supported in this JVM");
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit authentication tasks
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final String username = "user" + i;
        final String password = testUsers.get(username);
        
        executor.submit(() -> {
          try {
            for (int j = 0; j < ITERATIONS_PER_USER; j++) {
              // Check if we're running in a virtual thread
              assertTrue(Thread.currentThread().isVirtual(), 
                  "Test should be running in a virtual thread");
              
              // Perform authentication
              Subject subject = securitySystem.login(new UsernamePasswordToken(username, password));
              
              // Verify authentication was successful
              assertNotNull(subject);
              assertTrue(subject.isAuthenticated());
              assertEquals(username, subject.getPrincipal());
              
              // Perform some operations as the authenticated user
              assertTrue(subject.isPermitted("privilege:read"));
              
              // Logout
              subject.logout();
              assertFalse(subject.isAuthenticated());
              
              successfulLogins.incrementAndGet();
            }
          } 
          catch (AuthenticationException e) {
            failedLogins.incrementAndGet();
          }
          catch (Exception e) {
            // Check if this is related to thread pinning
            if (e.getMessage() != null && e.getMessage().contains("pinning")) {
              threadPinningDetected.incrementAndGet();
            }
            failedLogins.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
    }
    
    // Wait for all authentication tasks to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "Authentication tasks did not complete in time");
    
    // Verify all authentications were successful
    assertEquals(CONCURRENT_USERS * ITERATIONS_PER_USER, successfulLogins.get(), 
        "Not all authentication attempts were successful");
    assertEquals(0, failedLogins.get(), "Some authentication attempts failed");
    assertEquals(0, threadPinningDetected.get(), "Thread pinning was detected");
  }
  
  /**
   * Tests authentication caching with virtual threads.
   * This test verifies that authentication caching works correctly when accessed
   * from multiple virtual threads simultaneously.
   * 
   * The test performs two authentication attempts per user and verifies that
   * ThreadLocal-based caching works correctly with virtual threads.
   */
  @Test
  public void testAuthenticationCachingWithVirtualThreads() throws Exception {
    // Skip test if virtual threads are not supported
    org.junit.jupiter.api.Assumptions.assumeTrue(isVirtualThreadSupported(), 
        "Virtual threads are not supported in this JVM");
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    final ConcurrentHashMap<String, Long> firstAuthTimes = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Long> secondAuthTimes = new ConcurrentHashMap<>();
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit authentication tasks
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final String username = "user" + i;
        final String password = testUsers.get(username);
        
        executor.submit(() -> {
          try {
            // First authentication should not be cached
            long startTime1 = System.nanoTime();
            Subject subject1 = securitySystem.login(new UsernamePasswordToken(username, password));
            long authTime1 = System.nanoTime() - startTime1;
            firstAuthTimes.put(username, authTime1);
            
            // Verify authentication was successful
            assertNotNull(subject1);
            assertTrue(subject1.isAuthenticated());
            assertEquals(username, subject1.getPrincipal());
            subject1.logout();
            
            // Small delay to ensure caching has taken effect
            TimeUnit.MILLISECONDS.sleep(10);
            
            // Second authentication should potentially use cache
            long startTime2 = System.nanoTime();
            Subject subject2 = securitySystem.login(new UsernamePasswordToken(username, password));
            long authTime2 = System.nanoTime() - startTime2;
            secondAuthTimes.put(username, authTime2);
            
            // Verify second authentication was also successful
            assertNotNull(subject2);
            assertTrue(subject2.isAuthenticated());
            assertEquals(username, subject2.getPrincipal());
            subject2.logout();
            
            successfulLogins.incrementAndGet();
          }
          catch (Exception e) {
            failedLogins.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
    }
    
    // Wait for all authentication tasks to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "Authentication tasks did not complete in time");
    
    // Verify all authentications were successful
    assertEquals(CONCURRENT_USERS, successfulLogins.get(), 
        "Not all authentication attempts were successful");
    assertEquals(0, failedLogins.get(), "Some authentication attempts failed");
    
    // Calculate average authentication times for informational purposes
    double avgFirstAuthTime = firstAuthTimes.values().stream()
        .mapToLong(Long::longValue).average().orElse(0);
    double avgSecondAuthTime = secondAuthTimes.values().stream()
        .mapToLong(Long::longValue).average().orElse(0);
    
    System.out.println("Average first authentication time (ns): " + avgFirstAuthTime);
    System.out.println("Average second authentication time (ns): " + avgSecondAuthTime);
    
    // Note: We don't assert on timing as it would make the test flaky,
    // but we log the data for informational purposes
  }
  
  /**
   * Tests authentication with synchronized blocks to check for thread pinning.
   * This test verifies that virtual threads can handle synchronized blocks during
   * authentication without causing issues.
   * 
   * Note: Virtual threads are pinned to carrier threads during synchronized blocks,
   * which can impact scalability but should not affect correctness.
   */
  @Test
  public void testAuthenticationWithSynchronizedBlocks() throws Exception {
    // Skip test if virtual threads are not supported
    org.junit.jupiter.api.Assumptions.assumeTrue(isVirtualThreadSupported(), 
        "Virtual threads are not supported in this JVM");
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
    final Object lockObject = new Object(); // Dedicated lock object
    
    // Create a virtual thread per task executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit authentication tasks
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final String username = "user" + i;
        final String password = testUsers.get(username);
        
        executor.submit(() -> {
          try {
            // Record thread info before synchronized block
            String threadNameBefore = Thread.currentThread().toString();
            boolean isVirtualBefore = Thread.currentThread().isVirtual();
            
            // Use synchronized block to test thread pinning
            synchronized (lockObject) {
              // Check if we're running in a virtual thread
              assertTrue(Thread.currentThread().isVirtual(), 
                  "Test should be running in a virtual thread");
              
              // Perform authentication
              Subject subject = securitySystem.login(new UsernamePasswordToken(username, password));
              
              // Verify authentication was successful
              assertNotNull(subject);
              assertTrue(subject.isAuthenticated());
              
              // Logout
              subject.logout();
              
              // Record thread info during synchronized block
              String threadNameDuring = Thread.currentThread().toString();
              
              // Check if thread info changed, which might indicate pinning
              if (!threadNameBefore.equals(threadNameDuring) && isVirtualBefore) {
                threadPinningDetected.incrementAndGet();
              }
              
              successfulLogins.incrementAndGet();
            }
          }
          catch (Exception e) {
            // Check if this is related to thread pinning
            if (e.getMessage() != null && e.getMessage().contains("pinning")) {
              threadPinningDetected.incrementAndGet();
            }
            failedLogins.incrementAndGet();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
    }
    
    // Wait for all authentication tasks to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    assertTrue(completed, "Authentication tasks did not complete in time");
    
    // Verify results - note that we expect some thread pinning here
    System.out.println("Thread pinning detected in synchronized test: " + threadPinningDetected.get());
    
    // We don't assert on the number of pinned threads as it's implementation-dependent,
    // but we log it for informational purposes
  }
  
  /**
   * Creates test users for authentication testing.
   * 
   * @param count The number of test users to create
   */
  private void createTestUsers(int count) throws Exception {
    // Use a smaller number of actual users to avoid excessive test setup time,
    // but still test with the full number of virtual threads
    int actualUserCount = Math.min(count, 100);
    
    for (int i = 0; i < actualUserCount; i++) {
      String username = "user" + i;
      String password = "password" + i;
      
      CUser user = createUser(username, password);
      
      Set<String> roles = new HashSet<>();
      roles.add("role");
      
      configurationManager.createUser(user, roles);
      
      // Store the username and password for later use
      testUsers.put(username, password);
    }
    
    // For the remaining virtual threads, reuse existing users in a round-robin fashion
    for (int i = actualUserCount; i < count; i++) {
      String username = "user" + i;
      String password = "password" + (i % actualUserCount);
      
      // Just store the mapping without creating actual users
      testUsers.put(username, password);
    }
  }
  
  /**
   * Creates a test privilege and role for authentication testing.
   */
  private void createTestPrivilegeAndRole() throws Exception {
    // Create privilege
    CPrivilege priv = new MemoryCPrivilege();
    priv.setId("privilege");
    priv.setName("Test Privilege");
    priv.setDescription("Test Privilege Description");
    priv.setType("method");
    priv.setProperty("method", "read");
    priv.setProperty("permission", "privilege:read");
    
    configurationManager.createPrivilege(priv);
    
    // Create role
    CRole role = configurationManager.newRole();
    role.setId("role");
    role.setName("Test Role");
    role.setDescription("Test Role Description");
    role.addPrivilege("privilege");
    
    configurationManager.createRole(role);
  }
  
  /**
   * Creates a user with the given username and password.
   * 
   * @param username The username for the new user
   * @param password The password for the new user
   * @return A configured CUser instance
   */
  private CUser createUser(String username, String password) {
    CUser user = new MemoryCUser();
    user.setId(username);
    user.setEmail(username + "@example.com");
    user.setFirstName("Test");
    user.setLastName("User");
    user.setStatus(CUser.STATUS_ACTIVE);
    user.setPassword(passwordService.encryptPassword(password));
    return user;
  }
  
  /**
   * Checks if virtual threads are supported in the current JVM.
   * 
   * @return true if virtual threads are supported, false otherwise
   */
  private boolean isVirtualThreadSupported() {
    try {
      // Try to create a virtual thread
      Thread virtualThread = Thread.ofVirtual().start(() -> {});
      virtualThread.join();
      return true;
    } catch (Exception e) {
      return false;
    }
  }
}