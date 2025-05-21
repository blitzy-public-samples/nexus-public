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

import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.security.jwt.SecretStore;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.Claim;
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

@ExtendWith(MockitoExtension.class)
public class JwtHelperTest
    extends TestSupport
{
  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  @Mock
  private SecretStore secretStore;

  @Mock
  private Provider<SecretStore> storeProvider;

  private JwtHelper underTest;

  @BeforeEach
  public void setup() throws Exception {
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    underTest = new JwtHelper(300, "/", storeProvider);
    underTest.doStart();
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(Collections.singleton("NexusAuthorizingRealm"));
  }

  @Test
  public void testCreateJwtCookie() {
    Cookie jwtCookie = underTest.createJwtCookie(subject, false);
    assertNotNull(jwtCookie);
    String jwt = jwtCookie.getValue();

    assertJwt(jwt);
    assertCookie(jwtCookie);
  }

  @Test
  public void testCreateJwtCookie_withSecure() {
    Cookie jwtCookie = underTest.createJwtCookie(subject, true);
    assertNotNull(jwtCookie);
    String jwt = jwtCookie.getValue();

    assertJwt(jwt);
    assertCookie(jwtCookie, true);
  }

  @Test
  public void testVerifyJwt_success() throws Exception {
    String jwt = makeValidJwt();
    DecodedJWT decodedJWT = underTest.verifyJwt(jwt);

    assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
  }

  @Test
  public void testVerifyJwt_tokenExpired() {
    String jwt = makeInvalidJwt();
    assertThrows(JwtVerificationException.class, () -> underTest.verifyJwt(jwt));
  }

  @Test
  public void testVerifyAndRefresh_success() throws Exception {
    String jwt = makeValidJwt();
    DecodedJWT decodedJWT = decodeJwt(jwt);

    Cookie refreshed = underTest.verifyAndRefreshJwtCookie(jwt, false);
    assertCookie(refreshed);

    DecodedJWT refreshedJwt = decodeJwt(refreshed.getValue());

    Claim userSessionId = decodedJWT.getClaim(USER_SESSION_ID);
    assertEquals(userSessionId.asString(), refreshedJwt.getClaim(USER_SESSION_ID).asString());
    assertJwt(refreshed.getValue());
  }

  @Test
  public void testVerifyAndRefresh_secureRequest_success() throws Exception {
    String jwt = makeValidJwt();
    DecodedJWT decodedJWT = decodeJwt(jwt);

    Cookie refreshed = underTest.verifyAndRefreshJwtCookie(jwt, true);
    assertCookie(refreshed, true);

    DecodedJWT refreshedJwt = decodeJwt(refreshed.getValue());

    Claim userSessionId = decodedJWT.getClaim(USER_SESSION_ID);
    assertEquals(userSessionId.asString(), refreshedJwt.getClaim(USER_SESSION_ID).asString());
    assertJwt(refreshed.getValue());
  }

  /**
   * Verify behavior when nexus.jwt.cookieSecure is set to false and the request occurs in an HTTPS environment.
   * This combination should result in the JWT returning false for {@link Cookie#getSecure()}, as the feature flag
   * takes precedence.
   * @throws Exception
   */
  @Test
  public void testVerifyAndRefresh_secureRequest_cookieSecure_false() throws Exception {
    String jwt = makeValidJwt();
    DecodedJWT decodedJWT = decodeJwt(jwt);

    JwtHelper cookieSecureFalse = new JwtHelper(300, "/", storeProvider, false);
    cookieSecureFalse.doStart();

    Cookie refreshed = cookieSecureFalse.verifyAndRefreshJwtCookie(jwt, true);
    assertCookie(refreshed, false);

    DecodedJWT refreshedJwt = decodeJwt(refreshed.getValue());

    Claim userSessionId = decodedJWT.getClaim(USER_SESSION_ID);
    assertEquals(userSessionId.asString(), refreshedJwt.getClaim(USER_SESSION_ID).asString());
    assertJwt(refreshed.getValue());
  }

  @Test
  public void testVerifyAndRefresh_invalidJwt() {
    String jwt = makeInvalidJwt();
    assertThrows(JwtVerificationException.class, () -> underTest.verifyAndRefreshJwtCookie(jwt, false));
  }

  @Test
  public void testJwtWithVirtualThread() throws Exception {
    // Test JWT creation and verification in a virtual thread
    CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Create and verify JWT in virtual thread
        String jwt = makeValidJwt();
        DecodedJWT decodedJWT = underTest.verifyJwt(jwt);
        assertEquals(ISSUER, decodedJWT.getClaim("iss").asString());
        return jwt;
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS, Thread.ofVirtual().factory()));
    
    String jwt = future.get(); // Wait for the virtual thread to complete
    assertNotNull(jwt);
    assertJwt(jwt);
  }

  @Test
  public void testJwtRefreshWithVirtualThread() throws Exception {
    // Test JWT refresh in a virtual thread
    String jwt = makeValidJwt();
    
    CompletableFuture<Cookie> future = CompletableFuture.supplyAsync(() -> {
      try {
        // Verify we're running in a virtual thread
        Thread currentThread = Thread.currentThread();
        assertTrue(currentThread.isVirtual(), "Test should run in a virtual thread");
        
        // Refresh JWT in virtual thread
        return underTest.verifyAndRefreshJwtCookie(jwt, false);
      } 
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    }, CompletableFuture.delayedExecutor(0, TimeUnit.MILLISECONDS, Thread.ofVirtual().factory()));
    
    Cookie refreshed = future.get(); // Wait for the virtual thread to complete
    assertNotNull(refreshed);
    assertCookie(refreshed);
    assertJwt(refreshed.getValue());
  }

  private String makeValidJwt() {
    Date now = new Date();
    Date expiresAt = new Date(now.getTime() + 100000);
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

  private String makeInvalidJwt() {
    Date now = new Date();
    Date expiresAt = new Date(now.getTime() - 100000); // Expired token
    return JWT.create()
        .withIssuer(ISSUER)
        .withIssuedAt(now)
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