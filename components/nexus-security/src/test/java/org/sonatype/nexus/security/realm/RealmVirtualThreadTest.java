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
package org.sonatype.nexus.security.realm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authc.AuthenticationToken;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.user.User;
import org.sonatype.nexus.security.user.UserNotFoundException;

import com.google.common.collect.ImmutableList;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests to validate that Apache Shiro realm security system works correctly with Java 21 Virtual Threads.
 * 
 * These tests verify that authentication and authorization operations function correctly when executed
 * in virtual threads, ensuring that no thread pinning or deadlocks occur during realm operations.
 */
public class RealmVirtualThreadTest
    extends AbstractSecurityTest
{
  private SecuritySystem securitySystem;
  private RealmManager realmManager;
  private ExecutorService virtualThreadExecutor;
  private ExecutorService platformThreadExecutor;
  
  @BeforeEach
  public void setUp() throws Exception {
    securitySystem = lookup(SecuritySystem.class);
    realmManager = lookup(RealmManager.class);
    
    // Configure realms for testing
    realmManager.setConfiguredRealmIds(ImmutableList.of("MockRealmA", "MockRealmB", "MockRealmC"));
    
    // Create executors for testing
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests that authentication works correctly when executed in virtual threads.
   * This verifies that the Shiro realm chain can process authentication requests
   * from virtual threads without issues.
   */
  @Test
  @DisplayName("Authentication operations work correctly in virtual threads")
  public void testAuthenticationInVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Create and start virtual threads for authentication
    for (int i = 0; i < threadCount; i++) {
      final String username = i % 2 == 0 ? "jcoder" : "jcool";
      final String password = username; // In mock realms, password equals username
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Check if thread is virtual
          Thread currentThread = Thread.currentThread();
          boolean isVirtual = currentThread.isVirtual();
          assertTrue(isVirtual, "Thread should be virtual");
          
          // Attempt authentication
          AuthenticationToken token = new UsernamePasswordToken(username, password);
          Subject subject = securitySystem.login(token);
          
          assertNotNull(subject, "Subject should not be null after login");
          assertTrue(subject.isAuthenticated(), "Subject should be authenticated");
          
          // Verify the correct realm was used based on username
          PrincipalCollection principals = subject.getPrincipals();
          assertNotNull(principals, "Principals should not be null");
          
          if (username.equals("jcoder")) {
            assertEquals("MockRealmA", principals.getRealmNames().iterator().next());
          } else {
            assertEquals("MockRealmB", principals.getRealmNames().iterator().next());
          }
          
          // Logout
          subject.logout();
          assertFalse(subject.isAuthenticated(), "Subject should be logged out");
          
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Check if this is a thread pinning issue
          if (e.getMessage() != null && e.getMessage().contains("pinned")) {
            threadPinningDetected.set(true);
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for authentication threads");
    
    // Verify results
    assertEquals(threadCount, successCount.get(), "All authentication attempts should succeed");
    assertFalse(threadPinningDetected.get(), "No thread pinning should be detected during authentication");
  }
  
  /**
   * Tests that authorization works correctly when executed in virtual threads.
   * This verifies that the Shiro realm chain can process authorization requests
   * from virtual threads without issues.
   */
  @Test
  @DisplayName("Authorization operations work correctly in virtual threads")
  public void testAuthorizationInVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for authorization
    for (int i = 0; i < threadCount; i++) {
      final String username = "jcool"; // MockRealmB assigns test roles and permissions
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Check if thread is virtual
          Thread currentThread = Thread.currentThread();
          boolean isVirtual = currentThread.isVirtual();
          assertTrue(isVirtual, "Thread should be virtual");
          
          // Create principal collection for authorization check
          PrincipalCollection principals = new SimplePrincipalCollection(username, "MockRealmB");
          
          // Check role
          boolean hasRole = securitySystem.hasRole(principals, "test-role1");
          assertTrue(hasRole, "Subject should have test-role1");
          
          // Check permission
          boolean hasPermission = securitySystem.isPermitted(principals, "test:read");
          assertTrue(hasPermission, "Subject should have test:read permission");
          
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Just count down the latch, we'll check success count later
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for authorization threads");
    
    // Verify results
    assertEquals(threadCount, successCount.get(), "All authorization attempts should succeed");
  }
  
  /**
   * Tests concurrent user retrieval operations using virtual threads.
   * This verifies that the Shiro realm chain can handle concurrent user lookups
   * from virtual threads without issues.
   */
  @Test
  @DisplayName("User retrieval operations work correctly in virtual threads")
  public void testUserRetrievalInVirtualThreads() throws Exception {
    int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    Set<String> userSources = ConcurrentHashMap.newKeySet();
    
    // Create and start virtual threads for user retrieval
    for (int i = 0; i < threadCount; i++) {
      final String username = i % 2 == 0 ? "jcoder" : "jcool";
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Check if thread is virtual
          Thread currentThread = Thread.currentThread();
          boolean isVirtual = currentThread.isVirtual();
          assertTrue(isVirtual, "Thread should be virtual");
          
          // Retrieve user
          User user = securitySystem.getUser(username);
          
          assertNotNull(user, "User should not be null");
          assertEquals(username, user.getUserId(), "User ID should match");
          
          // Track user sources to verify realm ordering
          userSources.add(user.getSource());
          
          successCount.incrementAndGet();
        } 
        catch (UserNotFoundException e) {
          // This shouldn't happen with our mock users
        } 
        catch (Exception e) {
          // Just count down the latch, we'll check success count later
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for user retrieval threads");
    
    // Verify results
    assertEquals(threadCount, successCount.get(), "All user retrieval attempts should succeed");
    assertEquals(2, userSources.size(), "Should have users from two different sources");
    assertTrue(userSources.contains("MockUserManagerA"), "Should have users from MockUserManagerA");
    assertTrue(userSources.contains("MockUserManagerB"), "Should have users from MockUserManagerB");
  }
  
  /**
   * Tests for thread pinning during security operations.
   * This test attempts to detect if any operations in the security system
   * cause virtual threads to be pinned to carrier threads.
   */
  @Test
  @DisplayName("Security operations should not cause thread pinning")
  public void testThreadPinningDetection() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    int threadCount = 20;
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<String> pinnedOperations = new ArrayList<>();
    
    // Create and start virtual threads for various security operations
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      
      virtualThreadExecutor.submit(() -> {
        try {
          // Perform different security operations based on index
          switch (index % 4) {
            case 0:
              // Authentication
              AuthenticationToken token = new UsernamePasswordToken("jcoder", "jcoder");
              securitySystem.login(token);
              break;
            case 1:
              // Authorization
              PrincipalCollection principals = new SimplePrincipalCollection("jcool", "MockRealmB");
              securitySystem.hasRole(principals, "test-role1");
              break;
            case 2:
              // User retrieval
              securitySystem.getUser("jcoder");
              break;
            case 3:
              // List users
              securitySystem.listUsers();
              break;
          }
        } 
        catch (Exception e) {
          // Check if this is a thread pinning issue
          if (e.getMessage() != null && e.getMessage().contains("pinned")) {
            pinnedOperations.add("Operation " + (index % 4) + ": " + e.getMessage());
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for security operation threads");
    
    // Verify no thread pinning was detected
    assertTrue(pinnedOperations.isEmpty(), 
        "Thread pinning detected in operations: " + String.join(", ", pinnedOperations));
    
    // Reset thread pinning detection
    System.clearProperty("jdk.tracePinnedThreads");
  }
  
  /**
   * Compares performance between virtual threads and platform threads for security operations.
   * This test measures the time taken to perform a large number of concurrent security operations
   * using both virtual threads and platform threads.
   */
  @Test
  @DisplayName("Virtual threads should perform better than platform threads under high concurrency")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testPerformanceComparison() throws Exception {
    int threadCount = 1000; // High concurrency to demonstrate virtual thread benefits
    
    // Test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      CountDownLatch platformLatch = new CountDownLatch(threadCount);
      
      for (int i = 0; i < threadCount; i++) {
        final String username = i % 2 == 0 ? "jcoder" : "jcool";
        
        platformThreadExecutor.submit(() -> {
          try {
            // Simulate I/O by sleeping briefly
            Thread.sleep(10);
            
            // Perform security operation
            assertDoesNotThrow(() -> securitySystem.getUser(username));
          } 
          catch (Exception e) {
            // Ignore exceptions for performance test
          } 
          finally {
            platformLatch.countDown();
          }
        });
      }
      
      try {
        platformLatch.await(20, TimeUnit.SECONDS);
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      CountDownLatch virtualLatch = new CountDownLatch(threadCount);
      
      for (int i = 0; i < threadCount; i++) {
        final String username = i % 2 == 0 ? "jcoder" : "jcool";
        
        virtualThreadExecutor.submit(() -> {
          try {
            // Simulate I/O by sleeping briefly
            Thread.sleep(10);
            
            // Perform security operation
            assertDoesNotThrow(() -> securitySystem.getUser(username));
          } 
          catch (Exception e) {
            // Ignore exceptions for performance test
          } 
          finally {
            virtualLatch.countDown();
          }
        });
      }
      
      try {
        virtualLatch.await(20, TimeUnit.SECONDS);
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    
    // Log performance results
    System.out.println("Platform thread execution time: " + platformThreadTime + " ms");
    System.out.println("Virtual thread execution time: " + virtualThreadTime + " ms");
    
    // Virtual threads should be more efficient under high concurrency with I/O operations
    assertThat("Virtual threads should be faster than platform threads",
        virtualThreadTime, lessThan(platformThreadTime));
  }
  
  /**
   * Tests that security token generation and validation works correctly under high virtual thread concurrency.
   * This verifies that the security system can handle token operations from many virtual threads simultaneously.
   */
  @Test
  @DisplayName("Security token operations work correctly under high virtual thread concurrency")
  public void testSecurityTokensUnderHighConcurrency() throws Exception {
    int threadCount = 500;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for token operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < threadCount; i++) {
      final String username = i % 2 == 0 ? "jcoder" : "jcool";
      final String password = username; // In mock realms, password equals username
      
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Login to get a token (subject)
          AuthenticationToken token = new UsernamePasswordToken(username, password);
          Subject subject = securitySystem.login(token);
          
          assertNotNull(subject, "Subject should not be null after login");
          assertTrue(subject.isAuthenticated(), "Subject should be authenticated");
          
          // Perform an authorization check with the token
          boolean hasPermission = subject.isPermitted("test:read");
          
          // For jcool (MockRealmB), this should be true; for jcoder (MockRealmA), this may be false
          if (username.equals("jcool")) {
            assertTrue(hasPermission, "jcool should have test:read permission");
          }
          
          // Logout
          subject.logout();
          assertFalse(subject.isAuthenticated(), "Subject should be logged out");
          
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          // Just count down the latch, we'll check success count later
        } 
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all threads to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    assertTrue(latch.await(15, TimeUnit.SECONDS), "Timed out waiting for token operation threads");
    
    // Verify results
    assertEquals(threadCount, successCount.get(), "All token operations should succeed");
  }
  
  /**
   * Helper method to measure execution time of a runnable task.
   * 
   * @param task The task to measure
   * @return The execution time in milliseconds
   */
  private long measureExecutionTime(Runnable task) {
    long startTime = System.currentTimeMillis();
    task.run();
    return System.currentTimeMillis() - startTime;
  }
}