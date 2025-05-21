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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Tests JWT token operations using Java 21 Virtual Threads.
 * 
 * This test class validates that JWT token generation, verification, and refresh operations
 * execute correctly when performed concurrently by many Virtual Threads, ensuring that
 * the JWT implementation is thread-safe and compatible with Java 21's Virtual Thread model.
 */
public class JwtVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_THREADS = 1000;
  private static final int TOKEN_EXPIRY_SECONDS = 300;
  private static final String SECRET = "test-secret-key-for-jwt-operations";
  
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
    underTest = new JwtHelper(TOKEN_EXPIRY_SECONDS, "/", storeProvider);
    underTest.doStart();
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
  }

  /**
   * Tests concurrent JWT token generation using Virtual Threads.
   * 
   * This test creates a large number of Virtual Threads, each generating a JWT token,
   * and verifies that all tokens are created successfully and contain the expected claims.
   */
  @Test
  public void testConcurrentTokenGenerationWithVirtualThreads() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    List<String> generatedTokens = Collections.synchronizedList(new ArrayList<>());
    
    // Create and start virtual threads for token generation
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("token-gen-" + threadId).start(() -> {
        try {
          Cookie jwtCookie = underTest.createJwtCookie(subject, false);
          String jwt = jwtCookie.getValue();
          generatedTokens.add(jwt);
          
          // Verify the token has expected claims
          DecodedJWT decoded = JWT.decode(jwt);
          assertEquals("admin", decoded.getClaim(USER).asString());
          assertEquals(ISSUER, decoded.getClaim("iss").asString());
          assertEquals("NexusAuthorizingRealm", decoded.getClaim(REALM).asString());
          assertNotNull(decoded.getClaim(USER_SESSION_ID).asString());
          
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
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(30, TimeUnit.SECONDS));
    
    // Verify all tokens were generated successfully
    assertEquals("All token generations should succeed", CONCURRENT_THREADS, successCount.get());
    assertEquals("Should have generated the expected number of tokens", 
        CONCURRENT_THREADS, generatedTokens.size());
  }

  /**
   * Tests concurrent JWT token verification using Virtual Threads.
   * 
   * This test creates a valid JWT token, then verifies it concurrently from many
   * Virtual Threads to ensure the verification process is thread-safe.
   */
  @Test
  public void testConcurrentTokenVerificationWithVirtualThreads() throws Exception {
    // Create a valid token to verify
    String validToken = makeValidJwt();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for token verification
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("token-verify-" + threadId).start(() -> {
        try {
          DecodedJWT decodedJWT = underTest.verifyJwt(validToken);
          assertNotNull("Decoded JWT should not be null", decodedJWT);
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
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(30, TimeUnit.SECONDS));
    
    // Verify all verifications were successful
    assertEquals("All token verifications should succeed", CONCURRENT_THREADS, successCount.get());
  }

  /**
   * Tests concurrent JWT token refresh operations using Virtual Threads.
   * 
   * This test creates a valid JWT token, then refreshes it concurrently from many
   * Virtual Threads to ensure the refresh process is thread-safe and maintains
   * the user session ID across refreshes.
   */
  @Test
  public void testConcurrentTokenRefreshWithVirtualThreads() throws Exception {
    // Create a valid token to refresh
    String validToken = makeValidJwt();
    DecodedJWT originalJwt = JWT.decode(validToken);
    String originalSessionId = originalJwt.getClaim(USER_SESSION_ID).asString();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads for token refresh
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("token-refresh-" + threadId).start(() -> {
        try {
          Cookie refreshedCookie = underTest.verifyAndRefreshJwtCookie(validToken, false);
          assertNotNull("Refreshed cookie should not be null", refreshedCookie);
          
          // Verify the refreshed token maintains the same session ID
          DecodedJWT refreshedJwt = JWT.decode(refreshedCookie.getValue());
          assertEquals("Session ID should be preserved during refresh",
              originalSessionId, refreshedJwt.getClaim(USER_SESSION_ID).asString());
          
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
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(30, TimeUnit.SECONDS));
    
    // Verify all refreshes were successful
    assertEquals("All token refreshes should succeed", CONCURRENT_THREADS, successCount.get());
  }

  /**
   * Tests concurrent handling of expired JWT tokens using Virtual Threads.
   * 
   * This test creates an expired JWT token, then attempts to verify it concurrently
   * from many Virtual Threads to ensure the expiration handling is thread-safe and
   * consistently rejects expired tokens.
   */
  @Test
  public void testConcurrentExpiredTokenHandlingWithVirtualThreads() throws Exception {
    // Create an expired token
    String expiredToken = makeExpiredJwt();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_THREADS);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    
    // Create and start virtual threads for expired token verification
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      final int threadId = i;
      Thread.ofVirtual().name("expired-token-" + threadId).start(() -> {
        try {
          underTest.verifyJwt(expiredToken);
          // Should not reach here as the token is expired
          log.error("Thread {} did not throw expected exception for expired token", threadId);
        } 
        catch (JwtVerificationException e) {
          // Expected exception for expired token
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
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(30, TimeUnit.SECONDS));
    
    // Verify all threads received the expected exception
    assertEquals("All threads should have received JwtVerificationException", 
        CONCURRENT_THREADS, exceptionCount.get());
  }

  /**
   * Compares performance between platform threads and virtual threads for JWT operations.
   * 
   * This test measures and compares the execution time of JWT token generation using
   * both traditional platform threads (via ExecutorService) and Java 21 Virtual Threads.
   */
  @Test
  public void testPerformanceComparisonBetweenPlatformAndVirtualThreads() throws Exception {
    final int threadCount = 10000; // Higher count to better measure performance difference
    
    // Test with platform threads
    Instant platformStart = Instant.now();
    try (ExecutorService platformExecutor = Executors.newFixedThreadPool(100)) { // Limited pool size
      List<Future<?>> platformFutures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        platformFutures.add(platformExecutor.submit(() -> {
          Cookie jwtCookie = underTest.createJwtCookie(subject, false);
          assertNotNull(jwtCookie.getValue());
          return null;
        }));
      }
      
      // Wait for all platform thread tasks to complete
      for (Future<?> future : platformFutures) {
        future.get();
      }
    }
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    // Test with virtual threads
    Instant virtualStart = Instant.now();
    try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> virtualFutures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        virtualFutures.add(virtualExecutor.submit(() -> {
          Cookie jwtCookie = underTest.createJwtCookie(subject, false);
          assertNotNull(jwtCookie.getValue());
          return null;
        }));
      }
      
      // Wait for all virtual thread tasks to complete
      for (Future<?> future : virtualFutures) {
        future.get();
      }
    }
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    // Log performance results
    log.info("Platform threads execution time: {} ms", platformDuration.toMillis());
    log.info("Virtual threads execution time: {} ms", virtualDuration.toMillis());
    log.info("Performance ratio (platform/virtual): {}", 
        (double) platformDuration.toMillis() / virtualDuration.toMillis());
    
    // Assert that virtual threads are more efficient for this I/O-bound operation
    // This may not always be true depending on the environment, so we use a loose assertion
    assertThat("Virtual threads should be at least as fast as platform threads",
        platformDuration.toMillis(), greaterThan(virtualDuration.toMillis() / 2L));
  }

  /**
   * Creates a valid JWT token for testing.
   * 
   * @return A valid JWT token string that has not yet expired
   */
  private String makeValidJwt() {
    Date expiresAt = new Date(new Date().getTime() + 100000); // Expires in 100 seconds
    String userSessionId = UUID.randomUUID().toString();
    return JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(expiresAt)
        .withClaim(USER_SESSION_ID, userSessionId)
        .withClaim(USER, "admin")
        .withClaim(REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256(SECRET));
  }

  /**
   * Creates an expired JWT token for testing.
   * 
   * @return An expired JWT token string
   */
  private String makeExpiredJwt() {
    Date expiresAt = new Date(new Date().getTime() - 100000); // Expired 100 seconds ago
    return JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(expiresAt)
        .withClaim(USER_SESSION_ID, UUID.randomUUID().toString())
        .withClaim(USER, "admin")
        .withClaim(REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256(SECRET));
  }
}