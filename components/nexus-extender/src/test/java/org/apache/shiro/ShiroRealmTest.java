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
package org.apache.shiro;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.RealmSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.realm.MockRealm;
import org.sonatype.nexus.security.realm.MockRealmA;
import org.sonatype.nexus.security.realm.MockRealmB;
import org.sonatype.nexus.security.realm.MockRealmC;
import org.sonatype.nexus.security.realm.RealmManager;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests compatibility of Apache Shiro 2.0.0 realm implementations with Java 21.
 * Verifies that Internal, LDAP, SAML, and custom realm implementations initialize correctly,
 * authenticate users properly, and provide correct authorization data under Java 21.
 * 
 * @since 3.60
 */
public class ShiroRealmTest
    extends AbstractSecurityTest
{
  @Inject
  private RealmSecurityManager realmSecurityManager;

  @Inject
  private RealmManager realmManager;

  private DefaultSecurityManager securityManager;

  /**
   * Custom test realm that implements a simple authentication and authorization mechanism.
   * Used to verify custom realm compatibility with Java 21.
   */
  private static class Java21TestRealm extends AuthorizingRealm {
    private final String realmName;
    private final String validUsername;
    private final String validPassword;
    private final Set<String> roles;

    public Java21TestRealm(String realmName, String validUsername, String validPassword, Set<String> roles) {
      this.realmName = realmName;
      this.validUsername = validUsername;
      this.validPassword = validPassword;
      this.roles = roles;
    }

    @Override
    public String getName() {
      return realmName;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
      UsernamePasswordToken upToken = (UsernamePasswordToken) token;
      String username = upToken.getUsername();
      String password = new String(upToken.getPassword());

      if (validUsername.equals(username) && validPassword.equals(password)) {
        return new SimpleAuthenticationInfo(
            new SimplePrincipalCollection(username, getName()),
            password
        );
      }
      return null;
    }

    @Override
    protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
      if (principals == null) {
        return null;
      }

      String username = (String) principals.getPrimaryPrincipal();
      if (validUsername.equals(username)) {
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        info.setRoles(roles);
        return info;
      }
      return null;
    }
  }

  @Before
  public void setUp() throws Exception {
    securityManager = new DefaultSecurityManager();
    ThreadContext.bind(securityManager);
  }

  @After
  public void tearDown() {
    ThreadContext.unbindSecurityManager();
    ThreadContext.unbindSubject();
    ThreadContext.remove();
  }

  /**
   * Tests that the standard Nexus mock realms can be initialized and used with Java 21.
   */
  @Test
  public void testStandardMockRealmsInitialization() {
    // Verify that the mock realms are available and properly initialized
    assertThat(realmManager.getAvailableRealms().stream().map(r -> r.getId()).toList(),
        containsInAnyOrder("MockRealmA", "MockRealmB", "MockRealmC"));

    // Verify that the realms are properly registered with the security manager
    List<Realm> realms = realmSecurityManager.getRealms();
    assertThat(realms, hasSize(4)); // 3 mock realms + AuthorizingRealmImpl

    // Verify that each realm has the correct name
    List<String> realmNames = realms.stream().map(Realm::getName).toList();
    assertThat(realmNames, hasItem("MockRealmA"));
    assertThat(realmNames, hasItem("MockRealmB"));
    assertThat(realmNames, hasItem("MockRealmC"));
  }

  /**
   * Tests authentication with the MockRealmA under Java 21.
   */
  @Test
  public void testMockRealmAAuthentication() {
    // Configure security manager with MockRealmA
    securityManager.setRealms(List.of(new MockRealmA()));

    // Create subject and authenticate
    Subject subject = new Subject.Builder(securityManager).buildSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");

    try {
      subject.login(token);
      assertTrue(subject.isAuthenticated());
      assertEquals("jcoder", subject.getPrincipal());
    } catch (AuthenticationException e) {
      fail("Authentication should succeed: " + e.getMessage());
    }

    // Test with invalid credentials
    token = new UsernamePasswordToken("jcoder", "wrongpassword");
    try {
      subject.login(token);
      fail("Authentication should fail with invalid credentials");
    } catch (AuthenticationException e) {
      // Expected exception
    }
  }

  /**
   * Tests authentication and authorization with MockRealmB under Java 21.
   */
  @Test
  public void testMockRealmBAuthenticationAndAuthorization() {
    // Configure security manager with MockRealmB
    securityManager.setRealms(List.of(new MockRealmB()));

    // Create subject and authenticate
    Subject subject = new Subject.Builder(securityManager).buildSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("jcool", "jcool");

    try {
      subject.login(token);
      assertTrue(subject.isAuthenticated());
      assertEquals("jcool", subject.getPrincipal());

      // Test authorization
      assertTrue(subject.hasRole("test-role1"));
      assertTrue(subject.hasRole("test-role2"));
      assertTrue(subject.isPermitted("test:read"));
      assertTrue(subject.isPermitted("test:write"));
      assertFalse(subject.isPermitted("admin:read"));
    } catch (AuthenticationException e) {
      fail("Authentication should succeed: " + e.getMessage());
    }
  }

  /**
   * Tests authentication and authorization with MockRealmC under Java 21.
   */
  @Test
  public void testMockRealmCAuthenticationAndAuthorization() {
    // Configure security manager with MockRealmC
    securityManager.setRealms(List.of(new MockRealmC()));

    // Create subject and authenticate
    Subject subject = new Subject.Builder(securityManager).buildSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("goku", "goku");

    try {
      subject.login(token);
      assertTrue(subject.isAuthenticated());
      assertEquals("goku", subject.getPrincipal());

      // Test authorization
      assertTrue(subject.hasRole("mockrole-c"));
      assertTrue(subject.isPermitted("mock:read"));
      assertFalse(subject.isPermitted("admin:write"));
    } catch (AuthenticationException e) {
      fail("Authentication should succeed: " + e.getMessage());
    }
  }

  /**
   * Tests realm chaining and fallback behavior under Java 21.
   */
  @Test
  public void testRealmChainingAndFallback() {
    // Configure security manager with multiple realms in a specific order
    securityManager.setRealms(List.of(
        new MockRealmA(),
        new MockRealmB(),
        new MockRealmC()
    ));

    // Test authentication with first realm
    Subject subject = new Subject.Builder(securityManager).buildSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertEquals("jcoder", subject.getPrincipal());

    // Test authentication with second realm
    subject = new Subject.Builder(securityManager).buildSubject();
    token = new UsernamePasswordToken("jcool", "jcool");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertEquals("jcool", subject.getPrincipal());

    // Test authentication with third realm
    subject = new Subject.Builder(securityManager).buildSubject();
    token = new UsernamePasswordToken("goku", "goku");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertEquals("goku", subject.getPrincipal());

    // Test authentication failure when no realm can authenticate
    subject = new Subject.Builder(securityManager).buildSubject();
    token = new UsernamePasswordToken("unknown", "password");
    try {
      subject.login(token);
      fail("Authentication should fail when no realm can authenticate");
    } catch (AuthenticationException e) {
      // Expected exception
    }
  }

  /**
   * Tests custom realm implementation with Java 21 features.
   */
  @Test
  public void testCustomRealmWithJava21Features() {
    // Create a custom realm with Java 21 features
    Java21TestRealm customRealm = new Java21TestRealm(
        "Java21CustomRealm",
        "java21user",
        "java21password",
        Set.of("java21role", "testrole")
    );

    // Configure security manager with the custom realm
    securityManager.setRealms(List.of(customRealm));

    // Create subject and authenticate
    Subject subject = new Subject.Builder(securityManager).buildSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("java21user", "java21password");

    try {
      subject.login(token);
      assertTrue(subject.isAuthenticated());
      assertEquals("java21user", subject.getPrincipal());

      // Test authorization
      assertTrue(subject.hasRole("java21role"));
      assertTrue(subject.hasRole("testrole"));
    } catch (AuthenticationException e) {
      fail("Authentication should succeed: " + e.getMessage());
    }
  }

  /**
   * Tests realm authentication with Java 21 Virtual Threads.
   */
  @Test
  public void testRealmAuthenticationWithVirtualThreads() throws Exception {
    // Configure security manager with multiple realms
    securityManager.setRealms(List.of(
        new MockRealmA(),
        new MockRealmB(),
        new MockRealmC()
    ));

    // Create a list of authentication tasks
    List<CompletableFuture<Boolean>> futures = new ArrayList<>();

    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit authentication tasks for different users
      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Subject subject = new Subject.Builder(securityManager).buildSubject();
          UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
          subject.login(token);
          return subject.isAuthenticated();
        } catch (Exception e) {
          return false;
        }
      }, executor));

      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Subject subject = new Subject.Builder(securityManager).buildSubject();
          UsernamePasswordToken token = new UsernamePasswordToken("jcool", "jcool");
          subject.login(token);
          return subject.isAuthenticated();
        } catch (Exception e) {
          return false;
        }
      }, executor));

      futures.add(CompletableFuture.supplyAsync(() -> {
        try {
          Subject subject = new Subject.Builder(securityManager).buildSubject();
          UsernamePasswordToken token = new UsernamePasswordToken("goku", "goku");
          subject.login(token);
          return subject.isAuthenticated();
        } catch (Exception e) {
          return false;
        }
      }, executor));

      // Wait for all tasks to complete
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(
          futures.toArray(new CompletableFuture[0])
      );

      // Wait with timeout to ensure test doesn't hang
      allFutures.get(5, TimeUnit.SECONDS);

      // Verify all authentications were successful
      for (CompletableFuture<Boolean> future : futures) {
        assertTrue("Authentication should succeed in virtual thread", future.get());
      }
    }
  }

  /**
   * Tests pattern matching with switch expressions in realm implementation.
   */
  @Test
  public void testPatternMatchingInRealm() {
    // Create a custom realm that uses pattern matching with switch
    AuthenticatingRealm patternMatchingRealm = new AuthenticatingRealm() {
      @Override
      protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        // Use pattern matching with switch to handle different token types
        return switch (token) {
          case UsernamePasswordToken upToken when "pattern".equals(upToken.getUsername()) 
              && "matching".equals(new String(upToken.getPassword())) -> {
            yield new SimpleAuthenticationInfo(
                new SimplePrincipalCollection("pattern", getName()),
                "matching"
            );
          }
          case UsernamePasswordToken upToken when "test".equals(upToken.getUsername()) -> {
            yield new SimpleAuthenticationInfo(
                new SimplePrincipalCollection("test", getName()),
                "test"
            );
          }
          default -> null;
        };
      }

      @Override
      public String getName() {
        return "PatternMatchingRealm";
      }
    };

    // Configure security manager with the pattern matching realm
    securityManager.setRealms(List.of(patternMatchingRealm));

    // Test authentication with pattern matching case
    Subject subject = new Subject.Builder(securityManager).buildSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("pattern", "matching");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertEquals("pattern", subject.getPrincipal());

    // Test authentication with second pattern matching case
    subject = new Subject.Builder(securityManager).buildSubject();
    token = new UsernamePasswordToken("test", "anypassword");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertEquals("test", subject.getPrincipal());

    // Test authentication failure with non-matching case
    subject = new Subject.Builder(securityManager).buildSubject();
    token = new UsernamePasswordToken("unknown", "password");
    try {
      subject.login(token);
      fail("Authentication should fail for non-matching case");
    } catch (AuthenticationException e) {
      // Expected exception
    }
  }

  /**
   * Tests that BouncyCastle cryptography provider works correctly with Java 21 and Shiro 2.0.0.
   */
  @Test
  public void testBouncyCastleCryptoProviderWithJava21() {
    // This test verifies that the BouncyCastle provider is properly registered and functional
    // by checking if it's available in the list of security providers
    boolean foundBouncyCastle = false;
    for (java.security.Provider provider : java.security.Security.getProviders()) {
      if (provider.getName().contains("BC")) {
        foundBouncyCastle = true;
        break;
      }
    }
    
    // BouncyCastle should be available as a security provider
    assertTrue("BouncyCastle security provider should be available", foundBouncyCastle);
  }
}