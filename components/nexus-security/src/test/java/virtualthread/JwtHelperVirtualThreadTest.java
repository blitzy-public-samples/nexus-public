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

import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.inject.Provider;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Tests for {@link JwtHelper} using Java 21 Virtual Threads to validate concurrent token operations.
 * 
 * @since 3.63
 */
public class JwtHelperVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_COUNT = 1000;
  private static final int TIMEOUT_SECONDS = 30;

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
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    underTest = new JwtHelper(300, "/", storeProvider);
    underTest.doStart();
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
  }

  /**
   * Tests concurrent JWT token creation using virtual threads.
   * Validates that multiple threads can create tokens simultaneously without issues.
   */
  @Test
  public void testConcurrentJwtCreation() throws Exception {
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            assertNotNull(jwtCookie);
            String jwt = jwtCookie.getValue();
            assertJwt(jwt);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Error creating JWT token", e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue("Timed out waiting for virtual threads to complete", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All token creations should succeed", THREAD_COUNT, successCount.get());
      assertEquals("No token creations should fail", 0, failureCount.get());
    }
  }

  /**
   * Tests concurrent JWT token verification using virtual threads.
   * Validates that multiple threads can verify tokens simultaneously without issues.
   */
  @Test
  public void testConcurrentJwtVerification() throws Exception {
    String validJwt = makeValidJwt();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            DecodedJWT decodedJWT = underTest.verifyJwt(validJwt);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Error verifying JWT token", e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue("Timed out waiting for virtual threads to complete", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All token verifications should succeed", THREAD_COUNT, successCount.get());
      assertEquals("No token verifications should fail", 0, failureCount.get());
    }
  }

  /**
   * Tests concurrent JWT token refresh using virtual threads.
   * Validates that multiple threads can refresh tokens simultaneously without issues.
   */
  @Test
  public void testConcurrentJwtRefresh() throws Exception {
    String validJwt = makeValidJwt();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            Cookie refreshed = underTest.verifyAndRefreshJwtCookie(validJwt, false);
            assertCookie(refreshed);
            assertJwt(refreshed.getValue());
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            failureCount.incrementAndGet();
            log.error("Error refreshing JWT token", e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue("Timed out waiting for virtual threads to complete", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All token refreshes should succeed", THREAD_COUNT, successCount.get());
      assertEquals("No token refreshes should fail", 0, failureCount.get());
    }
  }

  /**
   * Tests expired token verification with virtual threads.
   * Validates that multiple threads correctly handle expired tokens.
   */
  @Test
  public void testConcurrentExpiredTokenVerification() throws Exception {
    String expiredJwt = makeInvalidJwt();
    CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
    AtomicInteger expectedExceptionCount = new AtomicInteger(0);
    AtomicInteger unexpectedExceptionCount = new AtomicInteger(0);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < THREAD_COUNT; i++) {
        executor.submit(() -> {
          try {
            underTest.verifyJwt(expiredJwt);
            fail("Expected JwtVerificationException was not thrown");
          }
          catch (JwtVerificationException e) {
            // This is the expected exception
            expectedExceptionCount.incrementAndGet();
          }
          catch (Exception e) {
            unexpectedExceptionCount.incrementAndGet();
            log.error("Unexpected error verifying expired JWT token", e);
          }
          finally {
            latch.countDown();
          }
        });
      }

      assertTrue("Timed out waiting for virtual threads to complete", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All verifications should throw expected exception", THREAD_COUNT, expectedExceptionCount.get());
      assertEquals("No unexpected exceptions should occur", 0, unexpectedExceptionCount.get());
    }
  }

  /**
   * Compares performance between platform threads and virtual threads for JWT operations.
   * This test demonstrates the scalability benefits of virtual threads.
   */
  @Test
  public void testPlatformVsVirtualThreadPerformance() throws Exception {
    // First test with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      try {
        runWithThreadFactory(Thread.ofPlatform().factory());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Then test with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      try {
        runWithThreadFactory(Thread.ofVirtual().factory());
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    log.info("Platform thread execution time: {} ms", platformThreadTime);
    log.info("Virtual thread execution time: {} ms", virtualThreadTime);
    log.info("Performance ratio (platform/virtual): {}", (double) platformThreadTime / virtualThreadTime);
    
    // We don't assert on the actual times as they can vary by environment,
    // but we log them for informational purposes
  }

  /**
   * Helper method to run JWT operations with a specific thread factory.
   */
  private void runWithThreadFactory(ThreadFactory threadFactory) throws Exception {
    int threadCount = 100; // Using fewer threads for this test to avoid overwhelming platform threads
    CountDownLatch latch = new CountDownLatch(threadCount);
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Create a token
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            String jwt = jwtCookie.getValue();
            
            // Verify the token
            DecodedJWT decodedJWT = underTest.verifyJwt(jwt);
            
            // Refresh the token
            underTest.verifyAndRefreshJwtCookie(jwt, false);
          }
          catch (Exception e) {
            log.error("Error in JWT operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
  }

  /**
   * Helper method to measure execution time of a runnable.
   */
  private long measureExecutionTime(Runnable runnable) {
    long startTime = System.currentTimeMillis();
    runnable.run();
    return System.currentTimeMillis() - startTime;
  }

  /**
   * Tests for thread pinning detection using the jdk.tracePinnedThreads diagnostic flag.
   * This test demonstrates how to detect when virtual threads are pinned to carrier threads.
   * 
   * Note: This test is informational and doesn't assert on results, as pinning behavior
   * depends on JVM implementation details and the diagnostic flag configuration.
   */
  @Test
  public void testThreadPinningDetection() throws Exception {
    log.info("Thread pinning detection test - check logs for diagnostic output");
    log.info("To enable pinning detection, run with: -Djdk.tracePinnedThreads=full");
    
    // Create a virtual thread that performs operations that might cause pinning
    Thread virtualThread = Thread.ofVirtual().start(() -> {
      synchronized (this) {
        try {
          // Perform a blocking operation while holding a lock
          // This would typically cause pinning and would be detected by jdk.tracePinnedThreads
          Thread.sleep(100);
          
          // Check if we're running in a virtual thread
          log.info("Running in virtual thread: {}", Thread.currentThread().isVirtual());
        }
        catch (InterruptedException e) {
          log.error("Thread interrupted", e);
        }
      }
    });
    
    virtualThread.join();
  }

  private String makeValidJwt() {
    Date expiresAt = new Date(new Date().getTime() + 100000);
    String userSessionId = UUID.randomUUID().toString();
    return JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(expiresAt)
        .withClaim(USER_SESSION_ID, userSessionId)
        .withClaim(USER, "admin")
        .withClaim(REALM, "NexusAuthorizingRealm")
        .sign(Algorithm.HMAC256("secret"));
  }

  private String makeInvalidJwt() {
    Date expiresAt = new Date(new Date().getTime() - 100000);
    return JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(expiresAt)
        .sign(Algorithm.HMAC256("secret"));
  }

  private void assertCookie(final Cookie jwtCookie) {
    assertCookie(jwtCookie, false);
  }

  private void assertCookie(final Cookie jwtCookie, final boolean secure) {
    assertEquals(JwtHelper.JWT_COOKIE_NAME, jwtCookie.getName());
    assertNotNull(jwtCookie.getValue());
    assertEquals(300, jwtCookie.getMaxAge());
    assertEquals("/", jwtCookie.getPath());
    assertTrue(jwtCookie.isHttpOnly());
    assertEquals(secure, jwtCookie.getSecure());
  }

  private void assertJwt(final String jwt) {
    DecodedJWT decode = decodeJwt(jwt);

    Claim user = decode.getClaim(USER);
    Claim userId = decode.getClaim(USER_SESSION_ID);
    Claim issuer = decode.getClaim("iss");
    Claim realm = decode.getClaim(REALM);

    assertEquals("admin", user.asString());
    assertNotNull(userId.asString());
    assertEquals(ISSUER, issuer.asString());
    assertEquals("NexusAuthorizingRealm", realm.asString());
  }

  private DecodedJWT decodeJwt(final String jwt) {
    try {
      return JWT.decode(jwt);
    }
    catch (Exception e) {
      throw new IllegalArgumentException("Invalid token");
    }
  }
}