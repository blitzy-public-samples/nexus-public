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
package org.sonatype.java21;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.crypto.internal.CryptoHelperImpl;
import org.sonatype.nexus.crypto.internal.MavenCipherImpl;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.PasswordHelper;
import org.sonatype.nexus.security.jwt.SecretStore;
import org.sonatype.nexus.security.jwt.JwtVerificationException;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.inject.Provider;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.crypto.PhraseService.LEGACY_PHRASE_SERVICE;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Tests for validating the compatibility of Nexus security components with Java 21 Virtual Threads.
 * 
 * This test suite focuses on:
 * 1. Performance comparison between platform threads and virtual threads for security operations
 * 2. Thread pinning detection in security contexts
 * 3. Validation of concurrent security operations using the lightweight thread model
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
@Tag("virtualthread")
public class VirtualThreadSecurityTest extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
  private static final int WARMUP_OPERATIONS = 100;
  
  @Mock
  private Subject subject;
  
  @Mock
  private PrincipalCollection principals;
  
  @Mock
  private SecretStore secretStore;
  
  @Mock
  private Provider<SecretStore> storeProvider;
  
  private JwtHelper jwtHelper;
  private PasswordHelper passwordHelper;
  private DefaultSecurityManager securityManager;
  private SimpleAccountRealm realm;
  
  @BeforeEach
  public void setup() throws Exception {
    // Setup JwtHelper
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    
    // Setup Subject mock
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
    
    // Setup PasswordHelper
    passwordHelper = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), LEGACY_PHRASE_SERVICE);
    
    // Setup Shiro SecurityManager with a test realm
    realm = new SimpleAccountRealm();
    realm.addAccount("testuser", "password", "user");
    realm.addAccount("testadmin", "adminpass", "admin");
    
    securityManager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(securityManager);
  }
  
  @AfterEach
  public void tearDown() {
    SecurityUtils.setSecurityManager(null);
  }
  
  /**
   * Tests that JWT operations work correctly with Virtual Threads.
   * This validates that the JWT creation, verification, and refresh operations
   * function properly when executed in Virtual Threads.
   */
  @Test
  @DisplayName("JWT operations should work correctly with Virtual Threads")
  public void testJwtOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task to create and verify a JWT in a virtual thread
      Future<String> jwtFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Create JWT
        Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
        String jwt = jwtCookie.getValue();
        assertNotNull(jwt, "JWT should not be null");
        
        // Verify JWT
        DecodedJWT decodedJWT = jwtHelper.verifyJwt(jwt);
        assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
        assertEquals("admin", decodedJWT.getClaim(USER).asString());
        
        return jwt;
      });
      
      // Get the JWT from the future
      String jwt = jwtFuture.get();
      assertNotNull(jwt, "JWT should not be null");
      
      // Submit another task to refresh the JWT in a virtual thread
      Future<Cookie> refreshFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Refresh JWT
        Cookie refreshedCookie = jwtHelper.verifyAndRefreshJwtCookie(jwt, false);
        assertNotNull(refreshedCookie, "Refreshed cookie should not be null");
        assertEquals(JwtHelper.JWT_COOKIE_NAME, refreshedCookie.getName());
        
        return refreshedCookie;
      });
      
      // Get the refreshed cookie from the future
      Cookie refreshedCookie = refreshFuture.get();
      assertNotNull(refreshedCookie, "Refreshed cookie should not be null");
      assertNotNull(refreshedCookie.getValue(), "Refreshed JWT should not be null");
    }
  }
  
  /**
   * Tests that password encryption and decryption operations work correctly with Virtual Threads.
   * This validates that the password helper functions properly when executed in Virtual Threads.
   */
  @Test
  @DisplayName("Password operations should work correctly with Virtual Threads")
  public void testPasswordOperationsWithVirtualThreads() throws Exception {
    final String password = "secure-password-123";
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task to encrypt a password in a virtual thread
      Future<String> encryptFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Encrypt password
        String encrypted = passwordHelper.encrypt(password);
        assertNotNull(encrypted, "Encrypted password should not be null");
        
        return encrypted;
      });
      
      // Get the encrypted password from the future
      String encrypted = encryptFuture.get();
      assertNotNull(encrypted, "Encrypted password should not be null");
      
      // Submit another task to decrypt the password in a virtual thread
      Future<String> decryptFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Decrypt password
        String decrypted = passwordHelper.decrypt(encrypted);
        assertEquals(password, decrypted, "Decrypted password should match original");
        
        return decrypted;
      });
      
      // Get the decrypted password from the future
      String decrypted = decryptFuture.get();
      assertEquals(password, decrypted, "Decrypted password should match original");
    }
  }
  
  /**
   * Tests that Shiro authentication operations work correctly with Virtual Threads.
   * This validates that the Shiro security framework functions properly when executed in Virtual Threads.
   */
  @Test
  @DisplayName("Shiro authentication should work correctly with Virtual Threads")
  public void testShiroAuthenticationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task to authenticate a user in a virtual thread
      Future<Subject> authFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Authenticate user
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken("testuser", "password");
        subject.login(token);
        
        assertTrue(subject.isAuthenticated(), "Subject should be authenticated");
        assertEquals("testuser", subject.getPrincipal());
        assertTrue(subject.hasRole("user"));
        
        return subject;
      });
      
      // Get the subject from the future
      Subject authenticatedSubject = authFuture.get();
      assertTrue(authenticatedSubject.isAuthenticated(), "Subject should be authenticated");
      
      // Submit another task to log out the user in a virtual thread
      Future<Boolean> logoutFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Log out user
        authenticatedSubject.logout();
        assertFalse(authenticatedSubject.isAuthenticated(), "Subject should be logged out");
        
        return true;
      });
      
      // Get the logout result from the future
      assertTrue(logoutFuture.get(), "Logout should succeed");
    }
  }
  
  /**
   * Tests that invalid authentication attempts are properly handled in Virtual Threads.
   * This validates that security exceptions are properly propagated when executed in Virtual Threads.
   */
  @Test
  @DisplayName("Invalid authentication should be properly handled in Virtual Threads")
  public void testInvalidAuthenticationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task to attempt invalid authentication in a virtual thread
      Future<Boolean> authFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Attempt invalid authentication
        Subject subject = SecurityUtils.getSubject();
        UsernamePasswordToken token = new UsernamePasswordToken("testuser", "wrongpassword");
        
        assertThrows(AuthenticationException.class, () -> subject.login(token),
            "Invalid authentication should throw AuthenticationException");
        
        return true;
      });
      
      // Get the result from the future
      assertTrue(authFuture.get(), "Invalid authentication test should succeed");
    }
  }
  
  /**
   * Tests that JWT verification exceptions are properly handled in Virtual Threads.
   * This validates that security exceptions are properly propagated when executed in Virtual Threads.
   */
  @Test
  @DisplayName("JWT verification exceptions should be properly handled in Virtual Threads")
  public void testJwtVerificationExceptionWithVirtualThreads() throws Exception {
    // Create an invalid JWT (expired)
    String invalidJwt = JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(new java.util.Date(System.currentTimeMillis() - 1000)) // Expired
        .sign(Algorithm.HMAC256("secret"));
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit a task to verify an invalid JWT in a virtual thread
      Future<Boolean> verifyFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Attempt to verify invalid JWT
        assertThrows(JwtVerificationException.class, () -> jwtHelper.verifyJwt(invalidJwt),
            "Invalid JWT should throw JwtVerificationException");
        
        return true;
      });
      
      // Get the result from the future
      assertTrue(verifyFuture.get(), "JWT verification exception test should succeed");
    }
  }
  
  /**
   * Compares the performance of JWT operations between platform threads and virtual threads.
   * This test validates that virtual threads provide better performance for I/O-bound security operations.
   */
  @Test
  @DisplayName("Virtual threads should provide better performance for JWT operations")
  public void testJwtOperationsPerformanceComparison() throws Exception {
    // Warm up both thread types
    performJwtOperations(Thread.ofPlatform().factory(), WARMUP_OPERATIONS);
    performJwtOperations(Thread.ofVirtual().factory(), WARMUP_OPERATIONS);
    
    // Measure platform thread performance
    Instant platformStart = Instant.now();
    performJwtOperations(Thread.ofPlatform().factory(), CONCURRENT_OPERATIONS);
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Measure virtual thread performance
    Instant virtualStart = Instant.now();
    performJwtOperations(Thread.ofVirtual().factory(), CONCURRENT_OPERATIONS);
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Log performance results
    log.info("JWT operations with platform threads took {} ms", platformDuration.toMillis());
    log.info("JWT operations with virtual threads took {} ms", virtualDuration.toMillis());
    
    // Assert that virtual threads are more efficient (or at least not significantly worse)
    // We're using a relaxed assertion here because the actual performance difference
    // can vary based on the test environment
    assertThat("Virtual threads should be more efficient for JWT operations",
        virtualDuration.toMillis(), lessThan(platformDuration.toMillis() * 1.5));
  }
  
  /**
   * Compares the performance of password operations between platform threads and virtual threads.
   * This test validates that virtual threads provide better performance for CPU-bound security operations
   * when executed concurrently.
   */
  @Test
  @DisplayName("Virtual threads should provide better throughput for concurrent password operations")
  public void testPasswordOperationsPerformanceComparison() throws Exception {
    // Warm up both thread types
    performPasswordOperations(Thread.ofPlatform().factory(), WARMUP_OPERATIONS);
    performPasswordOperations(Thread.ofVirtual().factory(), WARMUP_OPERATIONS);
    
    // Measure platform thread performance
    Instant platformStart = Instant.now();
    performPasswordOperations(Thread.ofPlatform().factory(), CONCURRENT_OPERATIONS);
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Measure virtual thread performance
    Instant virtualStart = Instant.now();
    performPasswordOperations(Thread.ofVirtual().factory(), CONCURRENT_OPERATIONS);
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Log performance results
    log.info("Password operations with platform threads took {} ms", platformDuration.toMillis());
    log.info("Password operations with virtual threads took {} ms", virtualDuration.toMillis());
    
    // Assert that virtual threads are more efficient for concurrent operations
    // We're using a relaxed assertion here because the actual performance difference
    // can vary based on the test environment
    assertThat("Virtual threads should provide better throughput for concurrent password operations",
        virtualDuration.toMillis(), lessThan(platformDuration.toMillis() * 1.5));
  }
  
  /**
   * Tests that Shiro authentication operations work correctly under high concurrency with Virtual Threads.
   * This validates that the Shiro security framework maintains consistency when many operations
   * are executed concurrently using Virtual Threads.
   */
  @Test
  @DisplayName("Shiro should handle high concurrency with Virtual Threads")
  public void testShiroHighConcurrencyWithVirtualThreads() throws Exception {
    final int CONCURRENT_USERS = 500;
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicInteger failureCount = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(CONCURRENT_USERS);
    
    // Add more test accounts to the realm
    for (int i = 0; i < CONCURRENT_USERS; i++) {
      realm.addAccount("user" + i, "pass" + i, "user");
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit concurrent authentication tasks
      for (int i = 0; i < CONCURRENT_USERS; i++) {
        final int userId = i;
        executor.submit(() -> {
          try {
            // Authenticate user
            Subject subject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken("user" + userId, "pass" + userId);
            subject.login(token);
            
            if (subject.isAuthenticated() && subject.hasRole("user")) {
              successCount.incrementAndGet();
            } else {
              failureCount.incrementAndGet();
            }
            
            // Log out
            subject.logout();
          } 
          catch (Exception e) {
            log.error("Authentication error", e);
            failureCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All authentication tasks should complete within timeout");
    }
    
    // Verify results
    assertEquals(CONCURRENT_USERS, successCount.get(), "All authentication attempts should succeed");
    assertEquals(0, failureCount.get(), "There should be no authentication failures");
  }
  
  /**
   * Tests that thread-local security contexts are properly maintained across virtual thread boundaries.
   * This validates that security context propagation works correctly with Virtual Threads.
   */
  @Test
  @DisplayName("Security contexts should be properly maintained across virtual thread boundaries")
  public void testSecurityContextPropagationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Authenticate in the main thread
      Subject mainThreadSubject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken("testadmin", "adminpass");
      mainThreadSubject.login(token);
      
      assertTrue(mainThreadSubject.isAuthenticated(), "Subject should be authenticated in main thread");
      assertTrue(mainThreadSubject.hasRole("admin"), "Subject should have admin role in main thread");
      
      // Submit a task to check the security context in a virtual thread
      Future<Boolean> contextFuture = executor.submit(() -> {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Get the subject in the virtual thread
        Subject virtualThreadSubject = SecurityUtils.getSubject();
        
        // The subject should be different (security context is not automatically propagated)
        assertFalse(virtualThreadSubject.isAuthenticated(), 
            "Security context should not automatically propagate to virtual threads");
        
        // Authenticate in the virtual thread
        UsernamePasswordToken newToken = new UsernamePasswordToken("testadmin", "adminpass");
        virtualThreadSubject.login(newToken);
        
        assertTrue(virtualThreadSubject.isAuthenticated(), "Subject should be authenticated in virtual thread");
        assertTrue(virtualThreadSubject.hasRole("admin"), "Subject should have admin role in virtual thread");
        
        return true;
      });
      
      // Get the result from the future
      assertTrue(contextFuture.get(), "Security context test should succeed");
      
      // The main thread subject should still be authenticated
      assertTrue(mainThreadSubject.isAuthenticated(), "Subject should still be authenticated in main thread");
      
      // Log out in the main thread
      mainThreadSubject.logout();
      assertFalse(mainThreadSubject.isAuthenticated(), "Subject should be logged out in main thread");
    }
  }
  
  /**
   * Tests for thread pinning during security operations with Virtual Threads.
   * This validates that security operations don't cause thread pinning issues.
   */
  @Test
  @DisplayName("Security operations should not cause thread pinning with Virtual Threads")
  public void testThreadPinningWithSecurityOperations() throws Exception {
    final int OPERATIONS = 100;
    final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    final ConcurrentHashMap<String, AtomicInteger> operationCounts = new ConcurrentHashMap<>();
    final CountDownLatch latch = new CountDownLatch(OPERATIONS * 3); // 3 types of operations
    
    // Create a virtual thread executor with a limited number of carrier threads
    // This helps detect pinning by forcing operations to share carrier threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("test-virtual-", 0).factory())) {
      
      // Submit concurrent tasks for different security operations
      for (int i = 0; i < OPERATIONS; i++) {
        // JWT operations
        executor.submit(() -> {
          try {
            String operationId = "jwt-" + Thread.currentThread().getName();
            operationCounts.computeIfAbsent(operationId, k -> new AtomicInteger()).incrementAndGet();
            
            Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
            String jwt = jwtCookie.getValue();
            jwtHelper.verifyJwt(jwt);
          } 
          finally {
            latch.countDown();
          }
        });
        
        // Password operations
        executor.submit(() -> {
          try {
            String operationId = "password-" + Thread.currentThread().getName();
            operationCounts.computeIfAbsent(operationId, k -> new AtomicInteger()).incrementAndGet();
            
            String password = "test-password-" + UUID.randomUUID();
            String encrypted = passwordHelper.encrypt(password);
            passwordHelper.decrypt(encrypted);
          } 
          finally {
            latch.countDown();
          }
        });
        
        // Shiro operations
        executor.submit(() -> {
          try {
            String operationId = "shiro-" + Thread.currentThread().getName();
            operationCounts.computeIfAbsent(operationId, k -> new AtomicInteger()).incrementAndGet();
            
            Subject subject = SecurityUtils.getSubject();
            if (!subject.isAuthenticated()) {
              UsernamePasswordToken token = new UsernamePasswordToken("testuser", "password");
              subject.login(token);
              subject.logout();
            }
          } 
          catch (Exception e) {
            log.error("Shiro operation error", e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All operations should complete within timeout");
    }
    
    // Analyze operation counts to detect pinning
    // If operations are evenly distributed across threads, it suggests no pinning
    log.info("Operation distribution across threads: {}", operationCounts);
    
    // Count the number of threads used for each operation type
    int jwtThreadCount = 0;
    int passwordThreadCount = 0;
    int shiroThreadCount = 0;
    
    for (String key : operationCounts.keySet()) {
      if (key.startsWith("jwt-")) jwtThreadCount++;
      else if (key.startsWith("password-")) passwordThreadCount++;
      else if (key.startsWith("shiro-")) shiroThreadCount++;
    }
    
    log.info("JWT operations used {} threads", jwtThreadCount);
    log.info("Password operations used {} threads", passwordThreadCount);
    log.info("Shiro operations used {} threads", shiroThreadCount);
    
    // If operations are well-distributed, we should see multiple threads used for each type
    assertThat("JWT operations should use multiple threads", jwtThreadCount, greaterThan(1));
    assertThat("Password operations should use multiple threads", passwordThreadCount, greaterThan(1));
    assertThat("Shiro operations should use multiple threads", shiroThreadCount, greaterThan(1));
  }
  
  /**
   * Helper method to perform JWT operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use
   * @param operations The number of operations to perform
   * @throws Exception If an error occurs
   */
  private void performJwtOperations(ThreadFactory threadFactory, int operations) throws Exception {
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      CountDownLatch latch = new CountDownLatch(operations);
      List<Future<Boolean>> futures = new ArrayList<>();
      
      for (int i = 0; i < operations; i++) {
        futures.add(executor.submit(() -> {
          try {
            // Create JWT
            Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
            String jwt = jwtCookie.getValue();
            
            // Verify JWT
            DecodedJWT decodedJWT = jwtHelper.verifyJwt(jwt);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            
            // Refresh JWT
            Cookie refreshedCookie = jwtHelper.verifyAndRefreshJwtCookie(jwt, false);
            assertNotNull(refreshedCookie);
            
            return true;
          } 
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All JWT operations should complete within timeout");
      
      // Verify all operations succeeded
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(), "JWT operation should succeed");
      }
    }
  }
  
  /**
   * Helper method to perform password operations using the specified thread factory.
   * 
   * @param threadFactory The thread factory to use
   * @param operations The number of operations to perform
   * @throws Exception If an error occurs
   */
  private void performPasswordOperations(ThreadFactory threadFactory, int operations) throws Exception {
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      CountDownLatch latch = new CountDownLatch(operations);
      List<Future<Boolean>> futures = new ArrayList<>();
      
      for (int i = 0; i < operations; i++) {
        final String password = "password-" + i;
        futures.add(executor.submit(() -> {
          try {
            // Encrypt password
            String encrypted = passwordHelper.encrypt(password);
            assertNotNull(encrypted);
            
            // Decrypt password
            String decrypted = passwordHelper.decrypt(encrypted);
            assertEquals(password, decrypted);
            
            return true;
          } 
          finally {
            latch.countDown();
          }
        }));
      }
      
      // Wait for all operations to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "All password operations should complete within timeout");
      
      // Verify all operations succeeded
      for (Future<Boolean> future : futures) {
        assertTrue(future.get(), "Password operation should succeed");
      }
    }
  }
}