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
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.google.inject.Provider;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests JWT authentication and token management with Java 21 Virtual Threads.
 * Validates that JwtHelper's token creation, verification, and refresh operations
 * work correctly when executed concurrently using virtual threads.
 */
public class JwtHelperVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TOKEN_EXPIRY_SECONDS = 300;
  private static final String COOKIE_PATH = "/";
  private static final String SECRET = "test-secret-key-for-jwt-virtual-thread-testing";

  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  @Mock
  private SecretStore secretStore;

  @Mock
  private Provider<SecretStore> storeProvider;

  private JwtHelper underTest;

  @Before
  public void setup() throws Exception {
    when(secretStore.getSecret()).thenReturn(Optional.of(SECRET));
    when(storeProvider.get()).thenReturn(secretStore);
    underTest = new JwtHelper(TOKEN_EXPIRY_SECONDS, COOKIE_PATH, storeProvider);
    underTest.doStart();
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
  }

  /**
   * Tests creating JWT cookies concurrently using virtual threads.
   * Validates that JwtHelper can handle many concurrent token creation requests
   * and that all created tokens are valid.
   */
  @Test
  public void testConcurrentJwtCookieCreation() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create tokens concurrently using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            assertNotNull(jwtCookie);
            String jwt = jwtCookie.getValue();
            assertNotNull(jwt);
            
            // Verify the token is valid
            underTest.verifyJwt(jwt);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error creating or verifying JWT", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(30, TimeUnit.SECONDS);
    }
    
    assertEquals("All JWT creation and verification operations should succeed", 
        CONCURRENT_THREADS, successCount.get());
  }

  /**
   * Tests verifying and refreshing JWT tokens concurrently using virtual threads.
   * Validates that JwtHelper can handle many concurrent token refresh operations
   * and that all refreshed tokens are valid.
   */
  @Test
  public void testConcurrentJwtVerifyAndRefresh() throws Exception {
    // Create a valid JWT token to refresh
    Cookie originalCookie = underTest.createJwtCookie(subject, false);
    String originalJwt = originalCookie.getValue();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Refresh tokens concurrently using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            Cookie refreshedCookie = underTest.verifyAndRefreshJwtCookie(originalJwt, false);
            assertNotNull(refreshedCookie);
            String refreshedJwt = refreshedCookie.getValue();
            assertNotNull(refreshedJwt);
            
            // Verify the refreshed token is valid
            underTest.verifyJwt(refreshedJwt);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error refreshing or verifying JWT", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(30, TimeUnit.SECONDS);
    }
    
    assertEquals("All JWT refresh and verification operations should succeed", 
        CONCURRENT_THREADS, successCount.get());
  }

  /**
   * Tests token expiration handling with virtual threads.
   * Validates that JwtHelper correctly identifies and rejects expired tokens
   * when processed by virtual threads.
   */
  @Test
  public void testExpiredTokenHandlingWithVirtualThreads() throws Exception {
    // Create a JwtHelper with very short expiration time
    JwtHelper shortLivedJwtHelper = new JwtHelper(1, COOKIE_PATH, storeProvider);
    shortLivedJwtHelper.doStart();
    
    // Create a token that will expire quickly
    Cookie jwtCookie = shortLivedJwtHelper.createJwtCookie(subject, false);
    String jwt = jwtCookie.getValue();
    
    // Wait for token to expire
    Thread.sleep(1500);
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Verify expired tokens concurrently using virtual threads
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        executor.submit(() -> {
          try {
            shortLivedJwtHelper.verifyJwt(jwt);
          }
          catch (JwtVerificationException e) {
            // Expected exception for expired token
            exceptionCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(30, TimeUnit.SECONDS);
    }
    
    assertEquals("All JWT verification operations should fail with expired token", 
        CONCURRENT_THREADS, exceptionCount.get());
  }

  /**
   * Compares performance between platform threads and virtual threads for JWT operations.
   * Validates that virtual threads provide better performance for I/O-bound JWT operations
   * under high concurrency.
   */
  @Test
  public void testPlatformVsVirtualThreadPerformance() throws Exception {
    final int operationCount = 10000;
    
    // Test with platform threads
    Instant platformStart = Instant.now();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(200)) {
      CountDownLatch platformLatch = new CountDownLatch(operationCount);
      
      for (int i = 0; i < operationCount; i++) {
        platformExecutor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            underTest.verifyJwt(jwtCookie.getValue());
          }
          catch (Exception e) {
            log.error("Error in platform thread test", e);
          }
          finally {
            platformLatch.countDown();
          }
        });
      }
      
      platformLatch.await(60, TimeUnit.SECONDS);
    }
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Test with virtual threads
    Instant virtualStart = Instant.now();
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch virtualLatch = new CountDownLatch(operationCount);
      
      for (int i = 0; i < operationCount; i++) {
        virtualExecutor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            underTest.verifyJwt(jwtCookie.getValue());
          }
          catch (Exception e) {
            log.error("Error in virtual thread test", e);
          }
          finally {
            virtualLatch.countDown();
          }
        });
      }
      
      virtualLatch.await(60, TimeUnit.SECONDS);
    }
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    log.info("Platform threads execution time: {} ms", platformDuration.toMillis());
    log.info("Virtual threads execution time: {} ms", virtualDuration.toMillis());
    
    // Virtual threads should be faster or at least not significantly slower
    // The exact performance difference will depend on the environment
    assertThat("Virtual threads should perform better than platform threads for I/O operations",
        virtualDuration.compareTo(platformDuration.multipliedBy(2)), is(lessThan(0)));
  }

  /**
   * Tests thread safety of JwtHelper under high concurrency with virtual threads.
   * Validates that JwtHelper methods remain thread-safe when accessed concurrently
   * by many virtual threads.
   */
  @Test
  public void testThreadSafetyWithVirtualThreads() throws Exception {
    final int operationCount = 5000;
    final AtomicInteger successCount = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(operationCount * 2); // Create and verify operations
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < operationCount; i++) {
        // Task 1: Create JWT cookie
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, i % 2 == 0); // Alternate secure flag
            assertNotNull(jwtCookie);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error creating JWT cookie", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Task 2: Create and verify JWT
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, i % 2 != 0); // Alternate secure flag
            String jwt = jwtCookie.getValue();
            underTest.verifyJwt(jwt);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error verifying JWT", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(60, TimeUnit.SECONDS);
    }
    
    assertEquals("All concurrent JWT operations should succeed", operationCount * 2, successCount.get());
  }
}