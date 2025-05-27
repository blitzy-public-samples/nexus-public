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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.VirtualThreadTestGroup;
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
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Tests JWT token creation, validation, and refresh operations using virtual threads.
 * Verifies that Java-JWT 4.4.0 functions correctly in Java 21's virtual threading model.
 * 
 * @since 3.60
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
   * Tests concurrent JWT cookie creation using virtual threads.
   * Verifies that the JwtHelper can handle high-volume concurrent token creation
   * operations without errors or thread safety issues.
   */
  @Test
  public void testConcurrentJwtCookieCreation() throws Exception {
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<String, String> userSessionIds = new ConcurrentHashMap<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to create JWT cookies concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, false);
            assertNotNull(jwtCookie);
            String jwt = jwtCookie.getValue();
            
            DecodedJWT decode = JWT.decode(jwt);
            String userId = decode.getClaim(USER_SESSION_ID).asString();
            
            // Verify each token has a unique session ID
            assertNotNull(userId);
            userSessionIds.put(userId, userId);
            
            // Verify cookie properties
            assertEquals(JwtHelper.JWT_COOKIE_NAME, jwtCookie.getName());
            assertEquals(300, jwtCookie.getMaxAge());
            assertEquals("/", jwtCookie.getPath());
            assertTrue(jwtCookie.isHttpOnly());
            
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations succeeded
    assertEquals(CONCURRENT_OPERATIONS, successCount.get());
    
    // Verify each token had a unique session ID
    assertEquals(CONCURRENT_OPERATIONS, userSessionIds.size());
  }

  /**
   * Tests concurrent JWT verification using virtual threads.
   * Verifies that the JwtHelper can handle high-volume concurrent token verification
   * operations without errors or thread safety issues.
   */
  @Test
  public void testConcurrentJwtVerification() throws Exception {
    // Create a valid JWT token to verify
    String validJwt = makeValidJwt();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to verify the JWT concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        executor.submit(() -> {
          try {
            DecodedJWT decodedJWT = underTest.verifyJwt(validJwt);
            
            // Verify token claims
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            assertEquals("admin", decodedJWT.getClaim(USER).asString());
            assertEquals("NexusAuthorizingRealm", decodedJWT.getClaim(REALM).asString());
            assertNotNull(decodedJWT.getClaim(USER_SESSION_ID).asString());
            
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations succeeded
    assertEquals(CONCURRENT_OPERATIONS, successCount.get());
  }

  /**
   * Tests concurrent JWT refresh operations using virtual threads.
   * Verifies that the JwtHelper can handle high-volume concurrent token refresh
   * operations without errors or thread safety issues.
   */
  @Test
  public void testConcurrentJwtRefresh() throws Exception {
    // Create a valid JWT token to refresh
    String validJwt = makeValidJwt();
    DecodedJWT originalJwt = JWT.decode(validJwt);
    String originalSessionId = originalJwt.getClaim(USER_SESSION_ID).asString();
    
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS);
    AtomicInteger successCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to refresh the JWT concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final boolean secure = i % 2 == 0; // Test both secure and non-secure requests
        
        executor.submit(() -> {
          try {
            Cookie refreshed = underTest.verifyAndRefreshJwtCookie(validJwt, secure);
            assertNotNull(refreshed);
            
            // Verify cookie properties
            assertEquals(JwtHelper.JWT_COOKIE_NAME, refreshed.getName());
            assertEquals(300, refreshed.getMaxAge());
            assertEquals("/", refreshed.getPath());
            assertTrue(refreshed.isHttpOnly());
            assertEquals(secure, refreshed.getSecure());
            
            // Verify refreshed token
            DecodedJWT refreshedJwt = JWT.decode(refreshed.getValue());
            assertEquals(originalSessionId, refreshedJwt.getClaim(USER_SESSION_ID).asString());
            assertEquals("admin", refreshedJwt.getClaim(USER).asString());
            assertEquals("NexusAuthorizingRealm", refreshedJwt.getClaim(REALM).asString());
            
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in virtual thread", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations succeeded
    assertEquals(CONCURRENT_OPERATIONS, successCount.get());
  }

  /**
   * Tests mixed JWT operations (create, verify, refresh) using virtual threads.
   * Verifies that the JwtHelper can handle different types of operations concurrently
   * without errors or thread safety issues.
   */
  @Test
  public void testMixedJwtOperations() throws Exception {
    String validJwt = makeValidJwt();
    CountDownLatch latch = new CountDownLatch(CONCURRENT_OPERATIONS * 3); // 3 operations per iteration
    AtomicInteger successCount = new AtomicInteger(0);
    ConcurrentHashMap<String, String> userSessionIds = new ConcurrentHashMap<>();
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Launch multiple virtual threads to perform mixed JWT operations concurrently
      for (int i = 0; i < CONCURRENT_OPERATIONS; i++) {
        final int index = i;
        
        // Create operation
        executor.submit(() -> {
          try {
            Cookie jwtCookie = underTest.createJwtCookie(subject, index % 2 == 0);
            assertNotNull(jwtCookie);
            
            DecodedJWT decode = JWT.decode(jwtCookie.getValue());
            String userId = decode.getClaim(USER_SESSION_ID).asString();
            userSessionIds.put(userId, userId);
            
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in create operation", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Verify operation
        executor.submit(() -> {
          try {
            DecodedJWT decodedJWT = underTest.verifyJwt(validJwt);
            assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
            
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in verify operation", e);
          }
          finally {
            latch.countDown();
          }
        });
        
        // Refresh operation
        executor.submit(() -> {
          try {
            Cookie refreshed = underTest.verifyAndRefreshJwtCookie(validJwt, index % 2 == 0);
            assertNotNull(refreshed);
            
            successCount.incrementAndGet();
          } 
          catch (Exception e) {
            log.error("Error in refresh operation", e);
          }
          finally {
            latch.countDown();
          }
        });
      }
    }
    
    // Wait for all threads to complete
    assertTrue("Timed out waiting for virtual threads to complete", 
        latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    
    // Verify all operations succeeded
    assertEquals(CONCURRENT_OPERATIONS * 3, successCount.get());
    
    // Verify each created token had a unique session ID
    assertEquals(CONCURRENT_OPERATIONS, userSessionIds.size());
  }

  /**
   * Creates a valid JWT token for testing.
   */
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
}