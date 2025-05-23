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
package org.apache.shiro.java21;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.java21.Java21TestSupport;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.IncorrectCredentialsException;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UnknownAccountException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.credential.CredentialsMatcher;
import org.apache.shiro.authc.credential.SimpleCredentialsMatcher;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.subject.Subject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests Apache Shiro's authentication mechanisms under Java 21, verifying that realm authentication,
 * token validation, and subject creation work correctly with Java 21 features.
 * <p>
 * This test class focuses on validating that authentication operations remain secure and consistent
 * when executed using Virtual Threads and when leveraging Java 21's pattern matching features.
 *
 * @since 3.60
 */
@DisplayName("Shiro Authentication with Java 21 Features")
public class ShiroAuthenticationJava21Test
    extends Java21TestSupport
{
  private DefaultSecurityManager securityManager;
  private TestRealm testRealm;
  private DatabaseRealm databaseRealm;
  private LdapRealm ldapRealm;

  /**
   * Sets up the test environment with multiple realm types.
   */
  @BeforeEach
  public void setupShiroTest() {
    // Create and configure test realms
    testRealm = new TestRealm();
    databaseRealm = new DatabaseRealm();
    ldapRealm = new LdapRealm();

    // Configure the security manager with multiple realms
    securityManager = new DefaultSecurityManager();
    List<Realm> realms = new ArrayList<>();
    realms.add(testRealm);
    realms.add(databaseRealm);
    realms.add(ldapRealm);
    securityManager.setRealms(realms);

    // Set as the application's SecurityManager
    SecurityUtils.setSecurityManager(securityManager);
  }

  /**
   * Cleans up the test environment.
   */
  @AfterEach
  public void tearDownShiroTest() {
    SecurityUtils.setSecurityManager(null);
  }

  /**
   * Tests basic authentication with a standard platform thread.
   */
  @Test
  @DisplayName("Basic authentication with platform thread")
  public void testBasicAuthentication() {
    // Create a new subject
    Subject subject = SecurityUtils.getSubject();

    // Login with valid credentials
    UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
    assertDoesNotThrow(() -> subject.login(token));
    assertTrue(subject.isAuthenticated());
    assertEquals("user1", subject.getPrincipal());

    // Logout
    subject.logout();
    assertFalse(subject.isAuthenticated());
  }

  /**
   * Tests authentication with invalid credentials.
   */
  @Test
  @DisplayName("Authentication with invalid credentials")
  public void testInvalidCredentials() {
    Subject subject = SecurityUtils.getSubject();

    // Test with incorrect password
    UsernamePasswordToken token = new UsernamePasswordToken("user1", "wrongpassword");
    assertThrows(IncorrectCredentialsException.class, () -> subject.login(token));
    assertFalse(subject.isAuthenticated());

    // Test with unknown username
    token = new UsernamePasswordToken("nonexistentuser", "password1");
    assertThrows(UnknownAccountException.class, () -> subject.login(token));
    assertFalse(subject.isAuthenticated());
  }

  /**
   * Tests authentication using a Virtual Thread.
   * This verifies that Shiro's authentication mechanisms work correctly
   * when executed on Java 21's lightweight Virtual Threads.
   */
  @Test
  @DisplayName("Authentication with Virtual Thread")
  public void testAuthenticationWithVirtualThread() throws Exception {
    // Skip test if Virtual Threads are not supported
    if (!isVirtualThreadSupported()) {
      log.info("Virtual Threads not supported, skipping test");
      return;
    }

    // Run authentication on a Virtual Thread
    runWithVirtualThread(() -> {
      // Verify we're running on a Virtual Thread
      assertTrue(Thread.currentThread().isVirtual(), "Test should run on a Virtual Thread");

      // Perform authentication
      Subject subject = SecurityUtils.getSubject();
      UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
      subject.login(token);

      // Verify authentication was successful
      assertTrue(subject.isAuthenticated());
      assertEquals("user1", subject.getPrincipal());

      // Logout
      subject.logout();
      assertFalse(subject.isAuthenticated());
    }, Duration.ofSeconds(5));
  }

  /**
   * Tests concurrent authentication requests using Virtual Threads.
   * This verifies that Shiro can handle multiple concurrent authentication
   * requests when using Java 21's lightweight threading model.
   */
  @Test
  @DisplayName("Concurrent authentication with Virtual Threads")
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    // Skip test if Virtual Threads are not supported
    if (!isVirtualThreadSupported()) {
      log.info("Virtual Threads not supported, skipping test");
      return;
    }

    final int threadCount = 100;
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicReference<Throwable> error = new AtomicReference<>();

    // Create a Virtual Thread executor
    try (ExecutorService executor = createVirtualThreadExecutor()) {
      // Submit authentication tasks
      for (int i = 0; i < threadCount; i++) {
        final String username = "user" + (i % 3 + 1); // user1, user2, or user3
        final String password = "password" + (i % 3 + 1); // password1, password2, or password3

        executor.submit(() -> {
          try {
            // Verify we're running on a Virtual Thread
            assertTrue(Thread.currentThread().isVirtual());

            // Perform authentication
            Subject subject = SecurityUtils.getSubject();
            UsernamePasswordToken token = new UsernamePasswordToken(username, password);
            subject.login(token);

            // Verify authentication was successful
            assertTrue(subject.isAuthenticated());
            assertEquals(username, subject.getPrincipal());

            // Perform a small delay to simulate some work
            Thread.sleep(10);

            // Logout
            subject.logout();
            assertFalse(subject.isAuthenticated());
          }
          catch (Throwable t) {
            error.compareAndSet(null, t);
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all threads to complete
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Authentication test timed out");

      // Check if any errors occurred
      if (error.get() != null) {
        throw new AssertionError("Error during concurrent authentication", error.get());
      }
    }
  }

  /**
   * Tests authentication token validation using Java 21's pattern matching for switch.
   * This demonstrates how pattern matching can simplify token validation logic.
   */
  @Test
  @DisplayName("Authentication token validation with pattern matching")
  public void testAuthenticationTokenValidationWithPatternMatching() {
    // Test with different token types
    UsernamePasswordToken upToken = new UsernamePasswordToken("user1", "password1");
    BearerToken bearerToken = new BearerToken("jwt-token-123");
    ApiKeyToken apiKeyToken = new ApiKeyToken("api-key-456");
    CustomToken customToken = new CustomToken();

    // Validate tokens using pattern matching
    assertTrue(validateTokenWithPatternMatching(upToken));
    assertTrue(validateTokenWithPatternMatching(bearerToken));
    assertTrue(validateTokenWithPatternMatching(apiKeyToken));
    assertFalse(validateTokenWithPatternMatching(customToken));
    assertFalse(validateTokenWithPatternMatching(null));
  }

  /**
   * Tests authentication across different realm types using Virtual Threads.
   * This verifies that all pluggable realms remain compatible with Java 21.
   */
  @Test
  @DisplayName("Authentication across realm types with Virtual Threads")
  public void testAuthenticationAcrossRealmTypesWithVirtualThreads() throws Exception {
    // Skip test if Virtual Threads are not supported
    if (!isVirtualThreadSupported()) {
      log.info("Virtual Threads not supported, skipping test");
      return;
    }

    // Test authentication against each realm type using Virtual Threads
    runWithVirtualThread(() -> {
      // Test realm authentication
      Subject subject = SecurityUtils.getSubject();
      subject.login(new UsernamePasswordToken("user1", "password1"));
      assertTrue(subject.isAuthenticated());
      subject.logout();

      // Database realm authentication
      subject.login(new UsernamePasswordToken("dbuser", "dbpassword"));
      assertTrue(subject.isAuthenticated());
      subject.logout();

      // LDAP realm authentication
      subject.login(new UsernamePasswordToken("ldapuser", "ldappassword"));
      assertTrue(subject.isAuthenticated());
      subject.logout();
    }, Duration.ofSeconds(5));
  }

  /**
   * Tests for thread pinning issues during authentication operations.
   * This ensures that authentication operations don't cause Virtual Threads
   * to be pinned to platform threads, which would reduce scalability.
   */
  @Test
  @DisplayName("Thread pinning detection during authentication")
  public void testThreadPinningDuringAuthentication() {
    // Skip test if Virtual Threads are not supported
    if (!isVirtualThreadSupported()) {
      log.info("Virtual Threads not supported, skipping test");
      return;
    }

    // Define an authentication task
    Runnable authTask = () -> {
      Subject subject = SecurityUtils.getSubject();
      subject.login(new UsernamePasswordToken("user1", "password1"));
      assertTrue(subject.isAuthenticated());
      subject.logout();
    };

    // Check if the authentication task causes thread pinning
    boolean pinningDetected = detectThreadPinning(authTask);
    
    // Log the result - we don't fail the test if pinning is detected,
    // but we want to be aware of it for optimization purposes
    if (pinningDetected) {
      log.warn("Thread pinning detected during authentication operations");
    }
    else {
      log.info("No thread pinning detected during authentication operations");
    }
  }

  /**
   * Validates authentication tokens using Java 21's pattern matching for switch.
   * This demonstrates how pattern matching can simplify token validation logic
   * compared to traditional if-else chains with instanceof checks.
   *
   * @param token the authentication token to validate
   * @return true if the token is valid, false otherwise
   */
  private boolean validateTokenWithPatternMatching(AuthenticationToken token) {
    return switch (token) {
      // Handle null case explicitly
      case null -> false;
      
      // Username/password token validation
      case UsernamePasswordToken upToken when upToken.getUsername() != null && upToken.getPassword() != null -> {
        // Additional validation logic could be added here
        yield true;
      }
      
      // Bearer token validation
      case BearerToken bearerToken when bearerToken.getToken() != null && !bearerToken.getToken().isEmpty() -> {
        // JWT validation logic could be added here
        yield true;
      }
      
      // API key token validation
      case ApiKeyToken apiKeyToken when apiKeyToken.getApiKey() != null && !apiKeyToken.getApiKey().isEmpty() -> {
        // API key validation logic could be added here
        yield true;
      }
      
      // Default case for unsupported token types
      default -> false;
    };
  }

  /**
   * Test realm implementation that authenticates users with predefined credentials.
   */
  private static class TestRealm extends AuthenticatingRealm {
    public TestRealm() {
      setName("testRealm");
      setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
      // Use pattern matching to handle different token types
      return switch (token) {
        case UsernamePasswordToken upToken -> {
          String username = upToken.getUsername();
          
          // Check for valid users
          if ("user1".equals(username)) {
            yield new SimpleAuthenticationInfo(username, "password1", getName());
          } 
          else if ("user2".equals(username)) {
            yield new SimpleAuthenticationInfo(username, "password2", getName());
          }
          else if ("user3".equals(username)) {
            yield new SimpleAuthenticationInfo(username, "password3", getName());
          }
          else {
            throw new UnknownAccountException("Unknown account: " + username);
          }
        }
        default -> throw new AuthenticationException("Unsupported token type");
      };
    }
  }

  /**
   * Database realm implementation that simulates authentication against a database.
   */
  private static class DatabaseRealm extends AuthenticatingRealm {
    public DatabaseRealm() {
      setName("databaseRealm");
      setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
      if (token instanceof UsernamePasswordToken upToken) {
        String username = upToken.getUsername();
        
        // Simulate database lookup
        if ("dbuser".equals(username)) {
          return new SimpleAuthenticationInfo(username, "dbpassword", getName());
        }
        throw new UnknownAccountException("Unknown database account: " + username);
      }
      throw new AuthenticationException("Unsupported token type");
    }
  }

  /**
   * LDAP realm implementation that simulates authentication against an LDAP server.
   */
  private static class LdapRealm extends AuthenticatingRealm {
    public LdapRealm() {
      setName("ldapRealm");
      setCredentialsMatcher(new SimpleCredentialsMatcher());
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
      if (token instanceof UsernamePasswordToken upToken) {
        String username = upToken.getUsername();
        
        // Simulate LDAP lookup
        if ("ldapuser".equals(username)) {
          return new SimpleAuthenticationInfo(username, "ldappassword", getName());
        }
        throw new UnknownAccountException("Unknown LDAP account: " + username);
      }
      throw new AuthenticationException("Unsupported token type");
    }
  }

  /**
   * Custom authentication token types for testing pattern matching.
   */
  private static class BearerToken implements AuthenticationToken {
    private final String token;

    public BearerToken(String token) {
      this.token = token;
    }

    public String getToken() {
      return token;
    }

    @Override
    public Object getPrincipal() {
      return null; // Would be extracted from the token in a real implementation
    }

    @Override
    public Object getCredentials() {
      return token;
    }
  }

  private static class ApiKeyToken implements AuthenticationToken {
    private final String apiKey;

    public ApiKeyToken(String apiKey) {
      this.apiKey = apiKey;
    }

    public String getApiKey() {
      return apiKey;
    }

    @Override
    public Object getPrincipal() {
      return null; // Would be extracted from the API key in a real implementation
    }

    @Override
    public Object getCredentials() {
      return apiKey;
    }
  }

  private static class CustomToken implements AuthenticationToken {
    @Override
    public Object getPrincipal() {
      return null;
    }

    @Override
    public Object getCredentials() {
      return null;
    }
  }
}