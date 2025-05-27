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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationException;
import org.sonatype.nexus.security.config.CPrivilege;
import org.sonatype.nexus.security.config.CRole;
import org.sonatype.nexus.security.config.CUser;
import org.sonatype.nexus.security.config.SecurityConfigurationManager;
import org.sonatype.nexus.security.config.memory.MemoryCPrivilege;
import org.sonatype.nexus.security.config.memory.MemoryCUser;
import org.sonatype.nexus.security.internal.AuthenticatingRealmImpl;

import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.PasswordService;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tests authentication operations (login/logout flows) with Java 21 Virtual Threads to ensure that
 * authentication realms, credential verification, and security context management work properly in a
 * highly concurrent environment.
 */
public class AuthenticationVirtualThreadTest
    extends AbstractSecurityTest
{
  private static final int CONCURRENT_USERS = 1000;
  private static final int ITERATIONS = 5;
  
  private SecurityConfigurationManager configurationManager;
  private PasswordService passwordService;
  private AuthenticatingRealmImpl realm;
  private List<String> usernames;
  private List<String> passwords;

  @BeforeEach
  @Override
  public void setUp() throws Exception {
    super.setUp();
    
    configurationManager = lookup(SecurityConfigurationManager.class);
    passwordService = lookup(PasswordService.class, "default");
    realm = (AuthenticatingRealmImpl) lookup(Realm.class, AuthenticatingRealmImpl.NAME);
    
    // Create test users and credentials
    usernames = new ArrayList<>();
    passwords = new ArrayList<>();
    
    // Create test privilege and role
    createTestPrivilegeAndRole();
    
    // Create test users
    for (int i = 0; i < CONCURRENT_USERS; i++) {
      String username = "vt-user-" + i;
      String password = "password-" + i;
      createTestUser(username, password, CUser.STATUS_ACTIVE);
      usernames.add(username);
      passwords.add(password);
    }
  }

  @AfterEach
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
  }

  /**
   * Tests that authentication works correctly with a large number of concurrent virtual threads.
   * This verifies that the security framework can handle high concurrency with virtual threads
   * and that authentication operations maintain proper isolation between threads.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    // Verify we're running on Java 21 or later with virtual thread support
    assertTrue(Runtime.version().feature() >= 21, "This test requires Java 21 or later");
    
    // Use a CountDownLatch to coordinate thread start
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Track successful authentications
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Track any authentication failures
    ConcurrentHashMap<String, Throwable> failures = new ConcurrentHashMap<>();
    
    // Use structured concurrency to manage virtual threads
    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
      // Fork a subtask for each user authentication
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int index = i;
        scope.fork(() -> {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          // Verify we're running on a virtual thread
          assertTrue(Thread.currentThread().isVirtual(), 
              "Test should be running on a virtual thread but is running on: " + Thread.currentThread());
          
          try {
            // Perform authentication
            String username = usernames.get(index);
            String password = passwords.get(index);
            
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            AuthenticationInfo info = realm.getAuthenticationInfo(token);
            
            // Verify authentication succeeded
            assertNotNull(info);
            assertEquals(username, info.getPrincipals().getPrimaryPrincipal());
            
            // Verify password matches
            String storedPassword = new String((char[]) info.getCredentials());
            assertTrue(passwordService.passwordsMatch(password, storedPassword));
            
            successCount.incrementAndGet();
            return true;
          } 
          catch (Throwable t) {
            failures.put("Thread-" + index, t);
            throw t;
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for all tasks to complete or fail
      scope.join();
      
      // Check for any failures
      if (!failures.isEmpty()) {
        fail("Authentication failures occurred: " + failures);
      }
      
      // Verify all authentications succeeded
      assertEquals(CONCURRENT_USERS, successCount.get(), "Not all authentications succeeded");
    }
  }

  /**
   * Tests that login and logout operations work correctly with virtual threads.
   * This verifies that the security context is properly maintained and isolated between threads.
   */
  @Test
  public void testLoginLogoutWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      // Track successful logins/logouts
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Perform login/logout operations concurrently
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          // Verify we're running on a virtual thread
          assertTrue(Thread.currentThread().isVirtual(), 
              "Test should be running on a virtual thread but is running on: " + Thread.currentThread());
          
          SecuritySystem securitySystem = getSecuritySystem();
          String username = usernames.get(index);
          String password = passwords.get(index);
          
          try {
            // Login
            Subject subject = securitySystem.login(new UsernamePasswordToken(username, password));
            
            // Verify subject is authenticated
            assertTrue(subject.isAuthenticated());
            assertEquals(username, subject.getPrincipal());
            
            // Verify subject has expected roles
            assertTrue(subject.hasRole("role"));
            
            // Verify subject has expected permissions
            assertTrue(subject.isPermitted("somevalue:read"));
            
            // Logout
            subject.logout();
            
            // Verify subject is no longer authenticated
            assertFalse(subject.isAuthenticated());
            
            successCount.incrementAndGet();
          }
          catch (AuthenticationException e) {
            fail("Authentication failed for user " + username + ": " + e.getMessage());
          }
          
          return null;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
      
      // Verify all login/logout operations succeeded
      assertEquals(CONCURRENT_USERS, successCount.get(), "Not all login/logout operations succeeded");
    }
  }

  /**
   * Tests authentication caching under virtual thread access patterns.
   * This verifies that the authentication cache works correctly when accessed from virtual threads.
   */
  @Test
  public void testAuthenticationCachingWithVirtualThreads() throws Exception {
    // Get the security manager to access cache statistics
    RealmSecurityManager securityManager = (RealmSecurityManager) getSecuritySystem().getSecurityManager();
    
    // Clear any existing cache entries
    securityManager.getCacheManager().getCache(AuthenticatingRealmImpl.NAME).clear();
    
    // Use virtual threads to perform repeated authentications
    try (var scope = new StructuredTaskScope<Void>()) {
      // For each user, authenticate multiple times to test caching
      for (int i = 0; i < CONCURRENT_USERS; i += 10) { // Use subset of users for this test
        final int index = i;
        
        scope.fork(() -> {
          String username = usernames.get(index);
          String password = passwords.get(index);
          
          // First authentication should not be from cache
          Subject subject = getSecuritySystem().login(new UsernamePasswordToken(username, password));
          subject.logout();
          
          // Subsequent authentications should use cache
          for (int j = 0; j < ITERATIONS; j++) {
            subject = getSecuritySystem().login(new UsernamePasswordToken(username, password));
            assertTrue(subject.isAuthenticated());
            subject.logout();
          }
          
          return null;
        });
      }
      
      // Wait for all tasks to complete
      scope.join();
    }
    
    // Verify cache has entries
    assertTrue(securityManager.getCacheManager().getCache(AuthenticatingRealmImpl.NAME).size() > 0,
        "Authentication cache should have entries");
  }

  /**
   * Tests for potential carrier thread pinning during authentication operations.
   * This verifies that authentication operations don't cause thread pinning issues.
   */
  @Test
  public void testThreadPinningDuringAuthentication() throws Exception {
    // Create a large number of virtual threads with a small number of carrier threads
    // This will help detect pinning issues as carrier threads will be limited
    System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
    System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "4");
    
    try {
      // Use a countdown latch to coordinate thread start
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_USERS);
      
      // Start many virtual threads that will compete for the limited carrier threads
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int index = i;
        Thread.startVirtualThread(() -> {
          try {
            startLatch.await(); // Wait for signal to start
            
            // Perform authentication
            String username = usernames.get(index % usernames.size());
            String password = passwords.get(index % passwords.size());
            
            Subject subject = getSecuritySystem().login(new UsernamePasswordToken(username, password));
            assertTrue(subject.isAuthenticated());
            subject.logout();
            
            completionLatch.countDown();
          }
          catch (Exception e) {
            fail("Authentication failed: " + e.getMessage());
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // If threads are being pinned, this will time out as carrier threads will be exhausted
      boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Authentication operations timed out, possible thread pinning detected");
    }
    finally {
      // Reset system properties
      System.clearProperty("jdk.virtualThreadScheduler.parallelism");
      System.clearProperty("jdk.virtualThreadScheduler.maxPoolSize");
    }
  }

  /**
   * Tests that authentication operations maintain security isolation between virtual threads.
   * This verifies that security contexts don't leak between threads.
   */
  @Test
  public void testSecurityIsolationBetweenVirtualThreads() throws Exception {
    // Use a concurrent map to track subject principals by thread
    ConcurrentHashMap<Long, String> threadToPrincipal = new ConcurrentHashMap<>();
    
    // Create virtual threads that authenticate as different users
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int index = i;
        futures.add(executor.submit(() -> {
          SecuritySystem securitySystem = getSecuritySystem();
          String username = usernames.get(index);
          String password = passwords.get(index);
          
          // Login as this user
          Subject subject = securitySystem.login(new UsernamePasswordToken(username, password));
          
          // Store the principal for this thread
          threadToPrincipal.put(Thread.currentThread().threadId(), (String) subject.getPrincipal());
          
          // Sleep briefly to allow thread interleaving
          try {
            Thread.sleep(Duration.ofMillis(10));
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          
          // Verify the principal hasn't changed
          assertEquals(username, subject.getPrincipal(), 
              "Security context changed unexpectedly between operations");
          
          // Logout
          subject.logout();
          
          return null;
        }));
      }
      
      // Wait for all tasks to complete
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }
    
    // Verify we had the expected number of distinct threads
    assertEquals(CONCURRENT_USERS, threadToPrincipal.size(), 
        "Expected one security context per virtual thread");
  }

  private void createTestPrivilegeAndRole() throws Exception {
    // Create test privilege
    CPrivilege priv = new MemoryCPrivilege();
    priv.setId("priv");
    priv.setName("name");
    priv.setDescription("desc");
    priv.setType("method");
    priv.setProperty("method", "read");
    priv.setProperty("permission", "somevalue");
    configurationManager.createPrivilege(priv);

    // Create test role
    CRole role = configurationManager.newRole();
    role.setName("name");
    role.setId("role");
    role.setDescription("desc");
    role.addPrivilege("priv");
    configurationManager.createRole(role);
  }

  private void createTestUser(String username, String password, String status) throws Exception {
    // Create user
    CUser user = new MemoryCUser();
    user.setEmail(username + "@example.com");
    user.setFirstName("Test");
    user.setLastName("User");
    user.setStatus(status);
    user.setId(username);
    user.setPassword(passwordService.encryptPassword(password));

    // Assign role
    Set<String> roles = new HashSet<>();
    roles.add("role");

    configurationManager.createUser(user, roles);
  }
}