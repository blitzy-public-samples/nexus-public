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
package org.apache.shiro.java21;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.java21.Java21TestSupport;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.session.Session;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Apache Shiro compatibility with Java 21 Virtual Threads.
 * <p>
 * This test class validates that Shiro's security operations (authentication, authorization,
 * and session management) work correctly when executed concurrently using virtual threads.
 * It verifies that thread-local security contexts are properly maintained across virtual thread
 * boundaries and that no thread pinning occurs during security operations.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
@Tag("virtualthread")
public class VirtualThreadShiroIntegrationTest extends Java21TestSupport
{
  private static final int THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 50;
  
  private DefaultSecurityManager securityManager;
  private SimpleAccountRealm realm;
  
  /**
   * Setup method that runs before each test.
   * Initializes the Shiro security manager with a simple realm for testing.
   */
  @BeforeEach
  public void setupShiroEnvironment() {
    // Setup Shiro security manager with a simple realm
    realm = new SimpleAccountRealm();
    realm.addAccount("admin", "admin_password", "admin");
    realm.addAccount("user", "user_password", "user");
    realm.addAccount("guest", "guest_password", "guest");
    
    // Add permissions
    realm.setPermissionResolver(permission -> new WildcardPermission(permission));
    realm.addRole("admin", Collections.singleton(new WildcardPermission("*")));
    realm.addRole("user", Collections.singleton(new WildcardPermission("repository:read:*")));
    realm.addRole("guest", Collections.singleton(new WildcardPermission("repository:read:public")));
    
    securityManager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(securityManager);
  }
  
  /**
   * Cleanup method that runs after each test.
   * Resets the Shiro security manager.
   */
  @AfterEach
  public void tearDownShiroEnvironment() {
    SecurityUtils.setSecurityManager(null);
  }
  
  /**
   * Tests that basic authentication works correctly with virtual threads.
   * <p>
   * This test creates multiple virtual threads that perform authentication operations
   * concurrently and verifies that all operations complete successfully.
   */
  @Test
  @DisplayName("Authentication operations should work correctly with virtual threads")
  public void testAuthenticationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("auth-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        final String expectedRole = username;
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            threadSubject.login(token);
            
            assertTrue(threadSubject.isAuthenticated(), "Subject should be authenticated");
            assertTrue(threadSubject.hasRole(expectedRole), "Subject should have the expected role");
            
            // Test that the principal is correct
            assertEquals(username, threadSubject.getPrincipal(), "Principal should match the username");
            
            threadSubject.logout();
            assertFalse(threadSubject.isAuthenticated(), "Subject should be logged out");
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Authentication test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during authentication test", error.get());
      }
    }
  }
  
  /**
   * Tests that authorization operations work correctly with virtual threads.
   * <p>
   * This test creates multiple virtual threads that perform permission checks
   * concurrently and verifies that all operations complete successfully.
   */
  @Test
  @DisplayName("Authorization operations should work correctly with virtual threads")
  public void testAuthorizationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("authz-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            threadSubject.login(token);
            
            // Test permissions based on role
            Permission readPublicPerm = new WildcardPermission("repository:read:public");
            Permission readPrivatePerm = new WildcardPermission("repository:read:private");
            Permission writePerm = new WildcardPermission("repository:write:*");
            
            assertTrue(threadSubject.isPermitted(readPublicPerm), 
                "All users should have permission to read public repositories");
            
            if ("admin".equals(username)) {
              assertTrue(threadSubject.isPermitted(readPrivatePerm), 
                  "Admin should have permission to read private repositories");
              assertTrue(threadSubject.isPermitted(writePerm), 
                  "Admin should have permission to write to repositories");
            }
            else if ("user".equals(username)) {
              assertTrue(threadSubject.isPermitted(readPrivatePerm), 
                  "User should have permission to read private repositories");
              assertFalse(threadSubject.isPermitted(writePerm), 
                  "User should not have permission to write to repositories");
            }
            else { // guest
              assertFalse(threadSubject.isPermitted(readPrivatePerm), 
                  "Guest should not have permission to read private repositories");
              assertFalse(threadSubject.isPermitted(writePerm), 
                  "Guest should not have permission to write to repositories");
            }
            
            threadSubject.logout();
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Authorization test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during authorization test", error.get());
      }
    }
  }
  
  /**
   * Tests that session management works correctly with virtual threads.
   * <p>
   * This test creates multiple virtual threads that perform session operations
   * concurrently and verifies that all operations complete successfully.
   */
  @Test
  @DisplayName("Session management should work correctly with virtual threads")
  public void testSessionManagementWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("session-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        final String attributeKey = "testAttribute-" + i;
        final String attributeValue = "value-" + i;
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            Session session = threadSubject.getSession(true);
            assertNotNull(session, "Session should not be null");
            
            // Test session operations
            session.setAttribute(attributeKey, attributeValue);
            assertEquals(attributeValue, session.getAttribute(attributeKey), 
                "Session attribute should be retrievable");
            
            // Login and verify session is maintained
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            threadSubject.login(token);
            
            // Session should still have our attribute after login
            assertEquals(attributeValue, threadSubject.getSession().getAttribute(attributeKey), 
                "Session attribute should be maintained after login");
            
            // Test timeout operations
            long originalTimeout = session.getTimeout();
            session.setTimeout(3600000); // 1 hour
            assertEquals(3600000, session.getTimeout(), "Session timeout should be updatable");
            
            // Reset timeout
            session.setTimeout(originalTimeout);
            
            threadSubject.logout();
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Session management test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during session management test", error.get());
      }
    }
  }
  
  /**
   * Tests that security contexts are properly maintained across virtual thread yields.
   * <p>
   * This is important because virtual threads can be unmounted from carrier threads during
   * blocking operations. This test verifies that the security context is maintained correctly
   * across these yield points.
   */
  @Test
  @DisplayName("Security contexts should be maintained across virtual thread yields")
  public void testSecurityContextAcrossVirtualThreadYields() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("context-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        final String expectedRole = username;
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            threadSubject.login(token);
            
            // Verify role before yield
            assertTrue(threadSubject.hasRole(expectedRole), 
                "Subject should have the expected role before yield");
            
            // Perform a blocking operation that will cause the virtual thread to yield
            Thread.sleep(10);
            
            // Verify that the security context is maintained after yield
            assertTrue(threadSubject.hasRole(expectedRole), 
                "Security context should be maintained after virtual thread yield");
            assertEquals(username, threadSubject.getPrincipal(), 
                "Principal should be maintained after virtual thread yield");
            
            // Perform another blocking operation with a longer duration
            Thread.sleep(50);
            
            // Verify again after a longer yield
            assertTrue(threadSubject.hasRole(expectedRole), 
                "Security context should be maintained after longer virtual thread yield");
            assertEquals(username, threadSubject.getPrincipal(), 
                "Principal should be maintained after longer virtual thread yield");
            
            threadSubject.logout();
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Security context test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during security context test", error.get());
      }
    }
  }
  
  /**
   * Tests for thread pinning issues when using virtual threads with Shiro operations.
   * <p>
   * Thread pinning occurs when a virtual thread is forced to stay on its carrier thread,
   * typically due to synchronized blocks or native methods. This test verifies that Shiro
   * operations don't cause excessive thread pinning.
   */
  @Test
  @DisplayName("Shiro operations should not cause excessive thread pinning")
  public void testThreadPinningWithShiroOperations() throws Exception {
    // We'll use a small number of carrier threads to make pinning more obvious
    System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
    
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      AtomicInteger concurrentOperations = new AtomicInteger(0);
      AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
      
      // Start threads that will all try to perform Shiro operations simultaneously
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        
        executor.submit(() -> {
          try {
            // Wait for all threads to be ready
            startLatch.await();
            
            // Track concurrent operations
            int current = concurrentOperations.incrementAndGet();
            int max;
            do {
              max = maxConcurrentOperations.get();
              if (current <= max) break;
            } while (!maxConcurrentOperations.compareAndSet(max, current));
            
            // Perform Shiro operations
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            threadSubject.login(token);
            
            // Small delay to simulate I/O
            Thread.sleep(5);
            
            // Perform authorization check
            boolean hasPermission = threadSubject.isPermitted("repository:read:public");
            assertTrue(hasPermission, "All users should have permission to read public repositories");
            
            // Another small delay
            Thread.sleep(5);
            
            // Session operations
            Session session = threadSubject.getSession(true);
            session.setAttribute("testKey", "testValue");
            assertEquals("testValue", session.getAttribute("testKey"));
            
            threadSubject.logout();
            concurrentOperations.decrementAndGet();
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for completion
      assertTrue(completionLatch.await(10, TimeUnit.SECONDS), "Thread pinning test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during thread pinning test", error.get());
      }
      
      // If we're not experiencing severe thread pinning, we should see high concurrency
      // even with a limited number of carrier threads
      log.info("Maximum concurrent operations: {}", maxConcurrentOperations.get());
      assertTrue(maxConcurrentOperations.get() > 4, 
          "Expected higher concurrency with virtual threads, which indicates no severe thread pinning");
      
      // Reset system property
      System.clearProperty("jdk.virtualThreadScheduler.parallelism");
    }
  }
  
  /**
   * Tests high concurrency Shiro operations using virtual threads.
   * <p>
   * This test creates a large number of virtual threads to demonstrate the scalability
   * of Shiro operations when using virtual threads.
   */
  @Test
  @DisplayName("Shiro should handle high concurrency with virtual threads")
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a much higher thread count for this test to demonstrate virtual thread scalability
    final int highConcurrencyThreadCount = 1000;
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("high-concurrency-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(highConcurrencyThreadCount);
      AtomicReference<Throwable> error = new AtomicReference<>();
      AtomicBoolean allOperationsSuccessful = new AtomicBoolean(true);
      
      for (int i = 0; i < highConcurrencyThreadCount; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            threadSubject.login(token);
            
            // Verify authentication was successful
            if (!threadSubject.isAuthenticated()) {
              allOperationsSuccessful.set(false);
            }
            
            // Perform a simple permission check
            boolean hasPermission = threadSubject.isPermitted("repository:read:public");
            if (!hasPermission) {
              allOperationsSuccessful.set(false);
            }
            
            // Simulate some I/O delay
            Thread.sleep(5);
            
            threadSubject.logout();
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue(latch.await(30, TimeUnit.SECONDS), "High concurrency test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during high concurrency test", error.get());
      }
      
      assertTrue(allOperationsSuccessful.get(), "Some Shiro operations failed during high concurrency test");
    }
  }
  
  /**
   * Tests that authentication failures are handled correctly with virtual threads.
   * <p>
   * This test verifies that Shiro correctly handles authentication failures when
   * running on virtual threads.
   */
  @Test
  @DisplayName("Authentication failures should be handled correctly with virtual threads")
  public void testAuthenticationFailureWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("auth-failure-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      List<Future<Boolean>> futures = new ArrayList<>();
      
      for (int i = 0; i < 10; i++) {
        futures.add(executor.submit(() -> {
          Subject threadSubject = SecurityUtils.getSubject();
          UsernamePasswordToken token = new UsernamePasswordToken("admin", "wrong_password");
          
          try {
            threadSubject.login(token);
            return false; // Should not reach here
          }
          catch (AuthenticationException e) {
            // Expected exception
            return true;
          }
        }));
      }
      
      // Verify all authentication attempts failed as expected
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(5, TimeUnit.SECONDS), 
            "Authentication should fail with incorrect credentials");
      }
    }
  }
  
  /**
   * Tests that concurrent logins and logouts work correctly with virtual threads.
   * <p>
   * This test creates multiple virtual threads that perform login and logout operations
   * concurrently and verifies that all operations complete successfully.
   */
  @Test
  @DisplayName("Concurrent logins and logouts should work correctly with virtual threads")
  public void testConcurrentLoginLogoutWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("login-logout-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 3 == 0) ? "admin" : (i % 3 == 1) ? "user" : "guest";
        final String password = username + "_password";
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            
            // Perform multiple login/logout cycles
            for (int j = 0; j < 5; j++) {
              UsernamePasswordToken token = new UsernamePasswordToken(username, password);
              threadSubject.login(token);
              assertTrue(threadSubject.isAuthenticated(), "Subject should be authenticated");
              
              // Small delay to increase chance of thread interactions
              Thread.sleep(1);
              
              threadSubject.logout();
              assertFalse(threadSubject.isAuthenticated(), "Subject should be logged out");
              
              // Another small delay
              Thread.sleep(1);
            }
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Concurrent login/logout test timed out");
      
      if (error.get() != null) {
        throw new AssertionError("Error during concurrent login/logout test", error.get());
      }
    }
  }
  
  /**
   * Tests that the thread pinning detector works correctly by intentionally creating
   * a task that will cause thread pinning.
   * <p>
   * This test is used to validate that our thread pinning detection mechanism is working
   * correctly, which is important for the other tests that check for thread pinning.
   */
  @Test
  @DisplayName("Thread pinning detector should correctly identify pinned threads")
  public void testThreadPinningDetector() {
    // Create a task that will intentionally cause thread pinning
    Runnable pinningTask = createPinningTask();
    
    // Verify that the pinning detector correctly identifies the pinning
    boolean pinningDetected = detectThreadPinning(pinningTask);
    assertTrue(pinningDetected, "Thread pinning detector should identify pinned threads");
    
    // Create a task that should not cause thread pinning
    Runnable nonPinningTask = () -> {
      try {
        // This should not cause pinning as it's just a sleep without synchronized block
        Thread.sleep(100);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    };
    
    // Verify that the pinning detector correctly identifies non-pinning tasks
    boolean nonPinningDetected = detectThreadPinning(nonPinningTask);
    assertFalse(nonPinningDetected, "Thread pinning detector should not identify non-pinned threads");
  }
  
  /**
   * Tests that Shiro's thread-local security manager works correctly with virtual threads.
   * <p>
   * This test verifies that the thread-local security manager is properly accessible from
   * virtual threads and that it behaves correctly.
   */
  @Test
  @DisplayName("Shiro's thread-local security manager should work correctly with virtual threads")
  public void testThreadLocalSecurityManagerWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("security-manager-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      List<CompletableFuture<Boolean>> futures = new ArrayList<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        futures.add(CompletableFuture.supplyAsync(() -> {
          try {
            // Verify that the security manager is accessible from the virtual thread
            assertNotNull(SecurityUtils.getSecurityManager(), 
                "Security manager should be accessible from virtual thread");
            
            // Verify that it's the same instance we set up
            assertEquals(securityManager, SecurityUtils.getSecurityManager(), 
                "Security manager should be the same instance we set up");
            
            return true;
          }
          catch (Throwable t) {
            log.error("Error in thread-local security manager test", t);
            return false;
          }
        }, executor));
      }
      
      // Wait for all futures to complete and verify results
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0]));
      allFutures.get(10, TimeUnit.SECONDS);
      
      // Verify all operations were successful
      for (CompletableFuture<Boolean> future : futures) {
        assertTrue(future.get(), "Thread-local security manager operation failed");
      }
    }
  }
}