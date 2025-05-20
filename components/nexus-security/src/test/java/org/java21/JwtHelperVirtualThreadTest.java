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
package org.java21;

import java.util.Collections;
import java.util.Date;
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
import org.sonatype.nexus.virtualthread.VirtualThreadTestGroup;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.inject.Provider;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
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
 * Tests JWT token creation, validation, and refresh operations using virtual threads
 * to verify that Java-JWT 4.4.0 functions correctly in Java 21's virtual threading model.
 * 
 * @since 3.60.0
 */
@Category(VirtualThreadTestGroup.class)
public class JwtHelperVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 1000;
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
   * Verifies that multiple tokens can be created simultaneously without thread safety issues.
   */
  @Test
  public void testConcurrentJwtCreation() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            assertNotNull(jwtCookie);
            String jwt = jwtCookie.getValue();
            assertJwt(jwt);
            assertCookie(jwtCookie);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread JWT creation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("Timed out waiting for JWT creation tasks", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All JWT creation operations should succeed", 
          CONCURRENT_OPERATIONS, successCount.get());
    }
  }

  /**
   * Tests concurrent JWT token validation using virtual threads.
   * Verifies that multiple tokens can be validated simultaneously without thread safety issues.
   */
  @Test
  public void testConcurrentJwtValidation() throws Exception {
    // Create a valid JWT token to test with
    String validJwt = makeValidJwt();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            DecodedJWT decodedJWT = underTest.verifyJwt(validJwt);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread JWT validation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("Timed out waiting for JWT validation tasks", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All JWT validation operations should succeed", 
          CONCURRENT_OPERATIONS, successCount.get());
    }
  }

  /**
   * Tests concurrent JWT token refresh operations using virtual threads.
   * Verifies that multiple tokens can be refreshed simultaneously without thread safety issues.
   */
  @Test
  public void testConcurrentJwtRefresh() throws Exception {
    // Create a valid JWT token to test with
    String validJwt = makeValidJwt();
    DecodedJWT originalJwt = decodeJwt(validJwt);
    String originalSessionId = originalJwt.getClaim(USER_SESSION_ID).asString();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final boolean secure = i % 2 == 0; // Test both secure and non-secure refreshes
        
        executor.submit(() -> {
          try {
            Cookie refreshed = underTest.verifyAndRefreshJwtCookie(validJwt, secure);
            assertCookie(refreshed, secure);
            
            DecodedJWT refreshedJwt = decodeJwt(refreshed.getValue());
            assertEquals(originalSessionId, refreshedJwt.getClaim(USER_SESSION_ID).asString());
            assertJwt(refreshed.getValue());
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread JWT refresh", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("Timed out waiting for JWT refresh tasks", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All JWT refresh operations should succeed", 
          CONCURRENT_OPERATIONS, successCount.get());
    }
  }

  /**
   * Tests concurrent JWT token validation with invalid tokens using virtual threads.
   * Verifies that invalid tokens are properly rejected even under high concurrency.
   */
  @Test
  public void testConcurrentInvalidJwtValidation() throws Exception {
    // Create an invalid JWT token to test with
    String invalidJwt = makeInvalidJwt();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            underTest.verifyJwt(invalidJwt);
            fail("Should have thrown JwtVerificationException");
          }
          catch (JwtVerificationException e) {
            // Expected exception
            exceptionCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Unexpected error in virtual thread JWT validation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("Timed out waiting for invalid JWT validation tasks", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All invalid JWT validations should throw JwtVerificationException", 
          CONCURRENT_OPERATIONS, exceptionCount.get());
    }
  }

  /**
   * Tests mixed JWT operations (create, validate, refresh) using virtual threads.
   * Verifies that different operations can be performed concurrently without thread safety issues.
   */
  @Test
  public void testMixedJwtOperations() throws Exception {
    String validJwt = makeValidJwt();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS * 3); // 3 operations per iteration
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        
        // Create operation
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, index % 2 == 0);
            assertNotNull(jwtCookie);
            assertJwt(jwtCookie.getValue());
            assertCookie(jwtCookie, index % 2 == 0);
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread JWT creation", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Validate operation
        executor.submit(() -> {
          try {
            DecodedJWT decodedJWT = underTest.verifyJwt(validJwt);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread JWT validation", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Refresh operation
        executor.submit(() -> {
          try {
            Cookie refreshed = underTest.verifyAndRefreshJwtCookie(validJwt, index % 2 == 0);
            assertCookie(refreshed, index % 2 == 0);
            assertJwt(refreshed.getValue());
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error in virtual thread JWT refresh", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      assertTrue("Timed out waiting for mixed JWT operation tasks", 
          latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      assertEquals("All JWT operations should succeed", 
          CONCURRENT_OPERATIONS * 3, successCount.get());
    }
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
      throw new IllegalArgumentException("Invalid token", e);
    }
  }
}