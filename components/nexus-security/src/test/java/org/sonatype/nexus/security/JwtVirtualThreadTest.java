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
package org.sonatype.nexus.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.inject.Provider;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Tests JWT token generation, validation, and refresh operations using Java 21 Virtual Threads.
 * Verifies that JWT operations execute correctly when performed concurrently by many Virtual Threads,
 * ensuring that the JWT implementation is thread-safe and compatible with Java 21's Virtual Thread model.
 */
@ExtendWith(MockitoExtension.class)
public class JwtVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TOKEN_EXPIRY_MILLIS = 10000; // 10 seconds
  private static final int TOKEN_EXPIRED_MILLIS = -10000; // 10 seconds in the past
  
  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  @Mock
  private SecretStore secretStore;

  @Mock
  private Provider<SecretStore> storeProvider;

  private JwtHelper jwtHelper;

  @BeforeEach
  public void setup() throws Exception {
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
  }

  /**
   * Tests concurrent JWT token generation using Virtual Threads.
   * Verifies that multiple Virtual Threads can simultaneously generate valid JWT tokens
   * without interference or errors.
   */
  @Test
  public void testConcurrentTokenGeneration() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      ConcurrentHashMap<String, String> tokens = new ConcurrentHashMap<>();
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Launch multiple virtual threads to generate tokens concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Generate a JWT token
            Cookie jwtCookie = jwtHelper.createJwtCookie(subject, false);
            String token = jwtCookie.getValue();
            
            // Verify the token is valid
            DecodedJWT decodedJWT = jwtHelper.verifyJwt(token);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            assertEquals("admin", decodedJWT.getClaim(USER).asString());
            
            // Store the token with the thread ID
            tokens.put("thread-" + threadId, token);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all tokens were generated successfully
      assertEquals(CONCURRENT_THREADS, successCount.get(), "Not all tokens were generated successfully");
      assertEquals(CONCURRENT_THREADS, tokens.size(), "Expected one token per thread");
      
      // Verify each token is unique (by checking the USER_SESSION_ID claim)
      ConcurrentHashMap<String, Boolean> sessionIds = new ConcurrentHashMap<>();
      tokens.forEach((threadId, token) -> {
        DecodedJWT jwt = JWT.decode(token);
        String sessionId = jwt.getClaim(USER_SESSION_ID).asString();
        assertNotNull(sessionId, "Session ID should not be null");
        sessionIds.put(sessionId, true);
      });
      
      assertEquals(CONCURRENT_THREADS, sessionIds.size(), "Each token should have a unique session ID");
    }
  }

  /**
   * Tests concurrent JWT token validation using Virtual Threads.
   * Verifies that multiple Virtual Threads can simultaneously validate JWT tokens
   * without interference or errors.
   */
  @Test
  public void testConcurrentTokenValidation() throws Exception {
    // Generate a set of valid tokens first
    String[] tokens = new String[CONCURRENT_THREADS];
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      tokens[i] = createValidToken();
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Launch multiple virtual threads to validate tokens concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Validate the token
            DecodedJWT decodedJWT = jwtHelper.verifyJwt(tokens[threadId]);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all tokens were validated successfully
      assertEquals(CONCURRENT_THREADS, successCount.get(), "Not all tokens were validated successfully");
    }
  }

  /**
   * Tests concurrent JWT token refresh operations using Virtual Threads.
   * Verifies that multiple Virtual Threads can simultaneously refresh JWT tokens
   * without interference or errors.
   */
  @Test
  public void testConcurrentTokenRefresh() throws Exception {
    // Generate a set of valid tokens first
    String[] tokens = new String[CONCURRENT_THREADS];
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      tokens[i] = createValidToken();
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      ConcurrentHashMap<Integer, String> refreshedTokens = new ConcurrentHashMap<>();
      AtomicInteger successCount = new AtomicInteger(0);
      
      // Launch multiple virtual threads to refresh tokens concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // Refresh the token
            Cookie refreshedCookie = jwtHelper.verifyAndRefreshJwtCookie(tokens[threadId], false);
            String refreshedToken = refreshedCookie.getValue();
            
            // Verify the refreshed token
            DecodedJWT originalJwt = JWT.decode(tokens[threadId]);
            DecodedJWT refreshedJwt = JWT.decode(refreshedToken);
            
            // The session ID should be preserved during refresh
            assertEquals(
                originalJwt.getClaim(USER_SESSION_ID).asString(),
                refreshedJwt.getClaim(USER_SESSION_ID).asString(),
                "Session ID should be preserved during refresh"
            );
            
            // Store the refreshed token
            refreshedTokens.put(threadId, refreshedToken);
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread {}: {}", threadId, e.getMessage(), e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all tokens were refreshed successfully
      assertEquals(CONCURRENT_THREADS, successCount.get(), "Not all tokens were refreshed successfully");
      assertEquals(CONCURRENT_THREADS, refreshedTokens.size(), "Expected one refreshed token per thread");
    }
  }

  /**
   * Tests concurrent JWT token expiration handling using Virtual Threads.
   * Verifies that multiple Virtual Threads can simultaneously detect expired JWT tokens
   * without interference or errors.
   */
  @Test
  public void testConcurrentTokenExpirationHandling() throws Exception {
    // Generate a set of expired tokens
    String[] expiredTokens = new String[CONCURRENT_THREADS];
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      expiredTokens[i] = createExpiredToken();
    }
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
      AtomicInteger exceptionCount = new AtomicInteger(0);
      
      // Launch multiple virtual threads to validate expired tokens concurrently
      for (int i = 0; i < CONCURRENT_THREADS; i++) {
        final int threadId = i;
        executor.submit(() -> {
          try {
            // This should throw a JwtVerificationException
            jwtHelper.verifyJwt(expiredTokens[threadId]);
          } 
          catch (JwtVerificationException e) {
            // Expected exception for expired tokens
            exceptionCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Unexpected error in virtual thread {}: {}", threadId, e.getMessage(), e);
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertTrue(latch.await(30, TimeUnit.SECONDS), "Timed out waiting for virtual threads to complete");
      
      // Verify all tokens were correctly identified as expired
      assertEquals(CONCURRENT_THREADS, exceptionCount.get(), "Not all expired tokens were correctly identified");
    }
  }

  /**
   * Compares the performance of JWT operations between platform threads and Virtual Threads.
   * This test helps evaluate the performance benefits of using Virtual Threads for JWT operations
   * under high concurrency scenarios.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    final int operationsPerThread = 10;
    final int totalThreads = 1000;
    
    // Test with platform threads
    Instant platformStart = Instant.now();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(100)) { // Limited pool size for platform threads
      CountDownLatch platformLatch = new CountDownLatch(totalThreads);
      
      for (int i = 0; i < totalThreads; i++) {
        platformExecutor.submit(() -> {
          try {
            for (int j = 0; j < operationsPerThread; j++) {
              // Generate and verify a token
              String token = createValidToken();
              jwtHelper.verifyJwt(token);
            }
          } 
          catch (Exception e) {
            log.error("Error in platform thread: {}", e.getMessage(), e);
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
      CountDownLatch virtualLatch = new CountDownLatch(totalThreads);
      
      for (int i = 0; i < totalThreads; i++) {
        virtualExecutor.submit(() -> {
          try {
            for (int j = 0; j < operationsPerThread; j++) {
              // Generate and verify a token
              String token = createValidToken();
              jwtHelper.verifyJwt(token);
            }
          } 
          catch (Exception e) {
            log.error("Error in virtual thread: {}", e.getMessage(), e);
          } 
          finally {
            virtualLatch.countDown();
          }
        });
      }
      
      virtualLatch.await(60, TimeUnit.SECONDS);
    }
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Log the performance comparison
    log.info("Performance comparison for {} threads with {} operations each:", totalThreads, operationsPerThread);
    log.info("Platform threads: {} ms", platformDuration.toMillis());
    log.info("Virtual threads: {} ms", virtualDuration.toMillis());
    log.info("Improvement ratio: {}", (double) platformDuration.toMillis() / virtualDuration.toMillis());
    
    // We don't assert on the actual performance as it can vary by environment,
    // but we log the results for analysis
  }

  /**
   * Tests the behavior of a large number of concurrent JWT operations using Virtual Threads.
   * This test verifies that the JWT implementation can handle a high volume of concurrent operations
   * without errors or performance degradation.
   */
  @Test
  public void testMassiveConcurrentJwtOperations() throws Exception {
    final int numOperations = 10000; // 10,000 concurrent operations
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Create futures for all operations
      CompletableFuture<?>[] futures = IntStream.range(0, numOperations)
          .mapToObj(i -> CompletableFuture.runAsync(() -> {
            try {
              // Perform a random JWT operation based on the thread ID
              int operation = i % 3;
              switch (operation) {
                case 0: // Generate token
                  Cookie cookie = jwtHelper.createJwtCookie(subject, false);
                  assertNotNull(cookie.getValue());
                  break;
                  
                case 1: // Validate token
                  String validToken = createValidToken();
                  DecodedJWT jwt = jwtHelper.verifyJwt(validToken);
                  assertEquals(ISSUER, jwt.getClaim("iss").asString());
                  break;
                  
                case 2: // Refresh token
                  String tokenToRefresh = createValidToken();
                  Cookie refreshed = jwtHelper.verifyAndRefreshJwtCookie(tokenToRefresh, false);
                  assertNotNull(refreshed.getValue());
                  break;
              }
            } 
            catch (Exception e) {
              throw new RuntimeException("Error in operation " + i, e);
            }
          }, executor))
          .toArray(CompletableFuture[]::new);
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures).join();
    }
    
    // If we reach here without exceptions, the test passed
    log.info("Successfully completed {} concurrent JWT operations using Virtual Threads", numOperations);
  }

  /**
   * Creates a valid JWT token for testing.
   */
  private String createValidToken() {
    Date now = new Date();
    Date expiresAt = new Date(now.getTime() + TOKEN_EXPIRY_MILLIS);
    String userSessionId = UUID.randomUUID().toString();
    
    return JWT.create()
        .withIssuer(ISSUER)
        .withIssuedAt(now)
        .withExpiresAt(expiresAt)
        .withClaim(USER_SESSION_ID, userSessionId)
        .withClaim(USER, "admin")
        .withClaim(REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256("secret"));
  }

  /**
   * Creates an expired JWT token for testing.
   */
  private String createExpiredToken() {
    Date now = new Date();
    Date expiresAt = new Date(now.getTime() + TOKEN_EXPIRED_MILLIS); // Expired
    String userSessionId = UUID.randomUUID().toString();
    
    return JWT.create()
        .withIssuer(ISSUER)
        .withIssuedAt(now)
        .withExpiresAt(expiresAt)
        .withClaim(USER_SESSION_ID, userSessionId)
        .withClaim(USER, "admin")
        .withClaim(REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256("secret"));
  }
}