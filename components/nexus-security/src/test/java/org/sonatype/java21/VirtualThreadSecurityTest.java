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
import java.util.concurrent.CountDownLatch;
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

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.base.Throwables;
import com.google.inject.Provider;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.crypto.PhraseService.LEGACY_PHRASE_SERVICE;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Tests to validate compatibility of Nexus security components with Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class VirtualThreadSecurityTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 100;
  private static final int OPERATIONS_PER_THREAD = 50;
  
  private DefaultSecurityManager securityManager;
  private SimpleAccountRealm realm;
  
  private PasswordHelper passwordHelper;
  
  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  @Mock
  private SecretStore secretStore;

  @Mock
  private Provider<SecretStore> storeProvider;

  private JwtHelper jwtHelper;
  
  @Before
  public void setup() throws Exception {
    // Setup Shiro security manager with a simple realm
    realm = new SimpleAccountRealm();
    realm.addAccount("admin", "password", "admin");
    realm.addAccount("user", "password", "user");
    
    securityManager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(securityManager);
    
    // Setup password helper
    passwordHelper = new PasswordHelper(new MavenCipherImpl(new CryptoHelperImpl()), LEGACY_PHRASE_SERVICE);
    
    // Setup JWT helper
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
  }
  
  @After
  public void tearDown() {
    SecurityUtils.setSecurityManager(null);
  }
  
  /**
   * Tests that basic authentication works correctly with virtual threads.
   */
  @Test
  public void testBasicAuthenticationWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("auth-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 2 == 0) ? "admin" : "user";
        final String expectedRole = (i % 2 == 0) ? "admin" : "user";
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, "password");
            threadSubject.login(token);
            
            assertTrue(threadSubject.hasRole(expectedRole));
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
      
      assertTrue("Authentication test timed out", latch.await(10, TimeUnit.SECONDS));
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
    }
  }
  
  /**
   * Tests that password encryption/decryption works correctly with virtual threads.
   */
  @Test
  public void testPasswordHelperWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("password-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String password = "secure-password-" + i;
        
        executor.submit(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              String encrypted = passwordHelper.encrypt(password);
              String decrypted = passwordHelper.decrypt(encrypted);
              assertEquals(password, decrypted);
              
              // Also test char array methods
              String encryptedChars = passwordHelper.encryptChars(password.toCharArray());
              char[] decryptedChars = passwordHelper.decryptChars(encryptedChars);
              assertEquals(password, new String(decryptedChars));
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
      
      assertTrue("Password helper test timed out", latch.await(10, TimeUnit.SECONDS));
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
    }
  }
  
  /**
   * Tests that JWT operations work correctly with virtual threads.
   */
  @Test
  public void testJwtHelperWithVirtualThreads() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("jwt-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            // Create and verify JWT cookie
            Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
            assertNotNull(jwtCookie);
            String jwt = jwtCookie.getValue();
            
            DecodedJWT decodedJWT = jwtHelper.verifyJwt(jwt);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            assertEquals("admin", decodedJWT.getClaim(USER).asString());
            
            // Test refresh
            Cookie refreshed = jwtHelper.verifyAndRefreshJwtCookie(jwt, false);
            assertNotNull(refreshed);
            assertEquals(JwtHelper.JWT_COOKIE_NAME, refreshed.getName());
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("JWT helper test timed out", latch.await(10, TimeUnit.SECONDS));
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
    }
  }
  
  /**
   * Tests for thread pinning issues when using virtual threads with security operations.
   * Thread pinning occurs when a virtual thread is forced to stay on its carrier thread,
   * typically due to synchronized blocks or native methods.
   */
  @Test
  public void testThreadPinningWithSecurityOperations() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // We'll use a small number of carrier threads to make pinning more obvious
      System.setProperty("jdk.virtualThreadScheduler.parallelism", "4");
      
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      AtomicInteger concurrentOperations = new AtomicInteger(0);
      AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
      
      List<Future<?>> futures = new ArrayList<>();
      
      // Start threads that will all try to perform security operations simultaneously
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String password = "secure-password-" + i;
        
        futures.add(executor.submit(() -> {
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
            
            // Perform a mix of security operations
            String encrypted = passwordHelper.encrypt(password);
            Thread.sleep(5); // Small delay to simulate I/O
            String decrypted = passwordHelper.decrypt(encrypted);
            assertEquals(password, decrypted);
            
            // Create and verify JWT
            Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
            Thread.sleep(5); // Small delay to simulate I/O
            jwtHelper.verifyJwt(jwtCookie.getValue());
            
            concurrentOperations.decrementAndGet();
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            completionLatch.countDown();
          }
        }));
      }
      
      // Start all threads simultaneously
      startLatch.countDown();
      
      // Wait for completion
      assertTrue("Thread pinning test timed out", completionLatch.await(10, TimeUnit.SECONDS));
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
      
      // If we're not experiencing severe thread pinning, we should see high concurrency
      // even with a limited number of carrier threads
      log.info("Maximum concurrent operations: {}", maxConcurrentOperations.get());
      assertTrue("Expected higher concurrency with virtual threads", maxConcurrentOperations.get() > 4);
      
      // Reset system property
      System.clearProperty("jdk.virtualThreadScheduler.parallelism");
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads for security operations.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    // Test with platform threads
    Instant platformStart = Instant.now();
    runSecurityOperationsWithThreads(Thread.ofPlatform().name("platform-test-", 0).factory());
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Test with virtual threads
    Instant virtualStart = Instant.now();
    runSecurityOperationsWithThreads(Thread.ofVirtual().name("virtual-test-", 0).factory());
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    log.info("Platform threads duration: {} ms", platformDuration.toMillis());
    log.info("Virtual threads duration: {} ms", virtualDuration.toMillis());
    
    // We don't assert on specific performance improvements as they can vary by environment,
    // but we log the results for analysis
  }
  
  /**
   * Helper method to run security operations with the specified thread factory.
   */
  private void runSecurityOperationsWithThreads(ThreadFactory threadFactory) throws Exception {
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String password = "secure-password-" + i;
        final String username = (i % 2 == 0) ? "admin" : "user";
        
        executor.submit(() -> {
          try {
            // Mix of security operations
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              // Password operations
              String encrypted = passwordHelper.encrypt(password);
              String decrypted = passwordHelper.decrypt(encrypted);
              assertEquals(password, decrypted);
              
              // JWT operations
              Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
              jwtHelper.verifyJwt(jwtCookie.getValue());
              
              // Authentication operations (if j is divisible by 10)
              if (j % 10 == 0) {
                Subject threadSubject = SecurityUtils.getSubject();
                UsernamePasswordToken token = new UsernamePasswordToken(username, "password");
                threadSubject.login(token);
                threadSubject.logout();
              }
              
              // Simulate some I/O delay
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
      
      assertTrue("Performance test timed out", latch.await(30, TimeUnit.SECONDS));
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
    }
  }
  
  /**
   * Tests that security contexts are properly maintained across virtual thread yields.
   * This is important because virtual threads can be unmounted from carrier threads during blocking operations.
   */
  @Test
  public void testSecurityContextAcrossVirtualThreadYields() throws Exception {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("context-test-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
      AtomicReference<Throwable> error = new AtomicReference<>();
      
      for (int i = 0; i < THREAD_COUNT; i++) {
        final String username = (i % 2 == 0) ? "admin" : "user";
        final String expectedRole = (i % 2 == 0) ? "admin" : "user";
        
        executor.submit(() -> {
          try {
            Subject threadSubject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, "password");
            threadSubject.login(token);
            
            // Verify role before yield
            assertTrue(threadSubject.hasRole(expectedRole));
            
            // Perform a blocking operation that will cause the virtual thread to yield
            Thread.sleep(10);
            
            // Verify that the security context is maintained after yield
            assertTrue("Security context lost after virtual thread yield", 
                threadSubject.hasRole(expectedRole));
            assertEquals("Principal changed after virtual thread yield",
                username, threadSubject.getPrincipal());
            
            // Perform another blocking operation
            Thread.sleep(10);
            
            // Verify again
            assertTrue("Security context lost after second virtual thread yield", 
                threadSubject.hasRole(expectedRole));
            
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
      
      assertTrue("Security context test timed out", latch.await(10, TimeUnit.SECONDS));
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
    }
  }
  
  /**
   * Tests high concurrency security operations using virtual threads.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Use a much higher thread count for this test to demonstrate virtual thread scalability
    final int highConcurrencyThreadCount = 1000;
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("high-concurrency-", 0).factory();
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      CountDownLatch latch = new CountDownLatch(highConcurrencyThreadCount);
      AtomicReference<Throwable> error = new AtomicReference<>();
      AtomicBoolean allOperationsSuccessful = new AtomicBoolean(true);
      
      Instant start = Instant.now();
      
      for (int i = 0; i < highConcurrencyThreadCount; i++) {
        final String password = "secure-password-" + UUID.randomUUID();
        
        executor.submit(() -> {
          try {
            // Perform password encryption/decryption
            String encrypted = passwordHelper.encrypt(password);
            String decrypted = passwordHelper.decrypt(encrypted);
            
            if (!password.equals(decrypted)) {
              allOperationsSuccessful.set(false);
            }
            
            // Simulate some I/O delay
            Thread.sleep(5);
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("High concurrency test timed out", latch.await(30, TimeUnit.SECONDS));
      
      Duration duration = Duration.between(start, Instant.now());
      log.info("High concurrency test with {} virtual threads completed in {} ms", 
          highConcurrencyThreadCount, duration.toMillis());
      
      if (error.get() != null) {
        Throwables.throwIfUnchecked(error.get());
        throw new RuntimeException(error.get());
      }
      
      assertTrue("Some security operations failed", allOperationsSuccessful.get());
    }
  }
}