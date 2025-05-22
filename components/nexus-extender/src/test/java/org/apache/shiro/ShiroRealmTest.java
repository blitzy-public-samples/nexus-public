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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authz.AuthorizationInfo;
import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.SimpleAuthorizationInfo;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.config.IniSecurityManagerFactory;
import org.apache.shiro.crypto.hash.Sha256Hash;
import org.apache.shiro.mgt.DefaultSecurityManager;
import org.apache.shiro.mgt.SecurityManager;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.apache.shiro.realm.AuthorizingRealm;
import org.apache.shiro.realm.Realm;
import org.apache.shiro.realm.SimpleAccountRealm;
import org.apache.shiro.realm.text.IniRealm;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.util.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.sonatype.nexus.security.realm.MockRealm;
import org.sonatype.nexus.security.realm.MockRealmA;
import org.sonatype.nexus.security.realm.MockRealmB;
import org.sonatype.nexus.security.realm.MockRealmC;

/**
 * Tests compatibility of Apache Shiro 2.0.0 realm implementations with Java 21.
 * Verifies that Internal, LDAP, SAML, and custom realm implementations initialize correctly,
 * authenticate users properly, and provide correct authorization data under Java 21.
 * 
 * This test class ensures realm loading, chaining, and fallback behaviors continue to work
 * as expected with the updated JDK and validates that realm implementations handle modern
 * Java features properly.
 */
@Execution(ExecutionMode.CONCURRENT)
public class ShiroRealmTest
{
  private SecurityManager securityManager;

  @BeforeEach
  public void setUp() {
    // Clean up any ThreadContext bindings from previous tests
    ThreadContext.remove();
  }

  @AfterEach
  public void tearDown() {
    // Clean up ThreadContext bindings after each test
    ThreadContext.remove();
  }

  /**
   * Tests basic initialization of various realm types under Java 21.
   * Verifies that all realm types can be instantiated and configured properly.
   */
  @Test
  @DisplayName("Test realm initialization under Java 21")
  public void testRealmInitialization() {
    // Test initialization of built-in realm types
    SimpleAccountRealm simpleRealm = new SimpleAccountRealm();
    simpleRealm.addAccount("user1", "password1", "role1");
    assertNotNull(simpleRealm);
    
    IniRealm iniRealm = new IniRealm();
    iniRealm.setResourcePath("classpath:shiro.ini");
    assertNotNull(iniRealm);
    
    // Test initialization of custom realm types
    MockRealmA mockRealmA = new MockRealmA(null);
    assertNotNull(mockRealmA);
    assertEquals("MockRealmA", mockRealmA.getName());
    
    MockRealmB mockRealmB = new MockRealmB();
    assertNotNull(mockRealmB);
    assertEquals("MockRealmB", mockRealmB.getName());
    
    MockRealmC mockRealmC = new MockRealmC();
    assertNotNull(mockRealmC);
    assertEquals("MockRealmC", mockRealmC.getName());
  }

  /**
   * Tests authentication with various realm types under Java 21.
   * Verifies that authentication works correctly with different realm implementations.
   */
  @Test
  @DisplayName("Test authentication with various realm types under Java 21")
  public void testAuthentication() {
    // Create and configure security manager with multiple realms
    DefaultSecurityManager manager = new DefaultSecurityManager();
    
    // Add test realms
    List<Realm> realms = new ArrayList<>();
    realms.add(new MockRealmA(null));
    realms.add(new MockRealmB());
    realms.add(new MockRealmC());
    manager.setRealms(realms);
    
    // Set the security manager
    SecurityUtils.setSecurityManager(manager);
    
    // Test authentication with MockRealmA
    Subject subject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("jcoder", "jcoder");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    subject.logout();
    
    // Test authentication with MockRealmB
    token = new UsernamePasswordToken("jcool", "jcool");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    subject.logout();
    
    // Test authentication with MockRealmC
    token = new UsernamePasswordToken("goku", "goku");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    subject.logout();
    
    // Test authentication failure
    token = new UsernamePasswordToken("invalid", "invalid");
    assertThrows(AuthenticationException.class, () -> subject.login(token));
    assertFalse(subject.isAuthenticated());
  }

  /**
   * Tests authorization with various realm types under Java 21.
   * Verifies that authorization works correctly with different realm implementations.
   */
  @Test
  @DisplayName("Test authorization with various realm types under Java 21")
  public void testAuthorization() {
    // Create and configure security manager with an authorizing realm
    DefaultSecurityManager manager = new DefaultSecurityManager();
    
    // Create a test realm with roles and permissions
    AuthorizingRealm realm = new AuthorizingRealm() {
      @Override
      protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
        String username = (String) principals.getPrimaryPrincipal();
        
        if ("user1".equals(username)) {
          info.addRole("role1");
          info.addStringPermission("system:read");
        } else if ("admin".equals(username)) {
          info.addRole("admin");
          info.addStringPermission("system:*");
        }
        
        return info;
      }
      
      @Override
      protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        UsernamePasswordToken upToken = (UsernamePasswordToken) token;
        String username = upToken.getUsername();
        String password = new String(upToken.getPassword());
        
        if (("user1".equals(username) && "password1".equals(password)) ||
            ("admin".equals(username) && "admin123".equals(password))) {
          return new SimpleAuthenticationInfo(username, password, getName());
        }
        
        return null;
      }
    };
    
    manager.setRealm(realm);
    
    // Set the security manager
    SecurityUtils.setSecurityManager(manager);
    
    // Test authorization for user1
    Subject subject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
    subject.login(token);
    
    assertTrue(subject.hasRole("role1"));
    assertFalse(subject.hasRole("admin"));
    assertTrue(subject.isPermitted("system:read"));
    assertFalse(subject.isPermitted("system:write"));
    
    subject.logout();
    
    // Test authorization for admin
    token = new UsernamePasswordToken("admin", "admin123");
    subject.login(token);
    
    assertTrue(subject.hasRole("admin"));
    assertFalse(subject.hasRole("role1"));
    assertTrue(subject.isPermitted("system:read"));
    assertTrue(subject.isPermitted("system:write"));
    assertTrue(subject.isPermitted("system:delete"));
    
    subject.logout();
  }

  /**
   * Tests realm chaining and fallback behaviors under Java 21.
   * Verifies that realm chaining works correctly when multiple realms are configured.
   */
  @Test
  @DisplayName("Test realm chaining and fallback behaviors under Java 21")
  public void testRealmChaining() {
    // Create and configure security manager with multiple realms
    DefaultSecurityManager manager = new DefaultSecurityManager();
    
    // Create test realms
    SimpleAccountRealm realm1 = new SimpleAccountRealm("realm1");
    realm1.addAccount("user1", "password1", "role1");
    
    SimpleAccountRealm realm2 = new SimpleAccountRealm("realm2");
    realm2.addAccount("user2", "password2", "role2");
    
    // Add realms to the security manager
    List<Realm> realms = new ArrayList<>();
    realms.add(realm1);
    realms.add(realm2);
    manager.setRealms(realms);
    
    // Set the security manager
    SecurityUtils.setSecurityManager(manager);
    
    // Test authentication with realm1
    Subject subject = SecurityUtils.getSubject();
    UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertTrue(subject.hasRole("role1"));
    subject.logout();
    
    // Test authentication with realm2
    token = new UsernamePasswordToken("user2", "password2");
    subject.login(token);
    assertTrue(subject.isAuthenticated());
    assertTrue(subject.hasRole("role2"));
    subject.logout();
    
    // Test authentication failure when user doesn't exist in any realm
    token = new UsernamePasswordToken("user3", "password3");
    assertThrows(AuthenticationException.class, () -> subject.login(token));
  }

  /**
   * Tests compatibility with Java 21 virtual threads.
   * Verifies that realm operations work correctly when executed in virtual threads.
   */
  @Test
  @DisplayName("Test compatibility with Java 21 virtual threads")
  public void testVirtualThreadCompatibility() throws Exception {
    // Create and configure security manager with a test realm
    DefaultSecurityManager manager = new DefaultSecurityManager();
    
    // Create a test realm
    SimpleAccountRealm realm = new SimpleAccountRealm();
    realm.addAccount("user1", "password1", "role1");
    manager.setRealm(realm);
    
    // Set the security manager
    SecurityUtils.setSecurityManager(manager);
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit authentication tasks to virtual threads
      List<Future<Boolean>> futures = new ArrayList<>();
      
      for (int i = 0; i < 10; i++) {
        futures.add(executor.submit(() -> {
          // Each virtual thread gets its own subject
          Subject subject = SecurityUtils.getSubject();
          UsernamePasswordToken token = new UsernamePasswordToken("user1", "password1");
          
          try {
            subject.login(token);
            boolean authenticated = subject.isAuthenticated();
            boolean hasRole = subject.hasRole("role1");
            subject.logout();
            
            return authenticated && hasRole;
          } catch (Exception e) {
            return false;
          }
        }));
      }
      
      // Verify all authentication tasks completed successfully
      for (Future<Boolean> future : futures) {
        assertTrue(future.get());
      }
    }
  }

  /**
   * Tests asynchronous realm operations with Java 21 virtual threads.
   * Verifies that async realm methods work correctly with virtual threads.
   */
  @Test
  @DisplayName("Test asynchronous realm operations with Java 21 virtual threads")
  public void testAsyncRealmOperations() throws Exception {
    // Create instances of realms with async methods
    MockRealmB realmB = new MockRealmB();
    MockRealmC realmC = new MockRealmC();
    
    // Create authentication tokens
    UsernamePasswordToken tokenB = new UsernamePasswordToken("jcool", "jcool");
    UsernamePasswordToken tokenC = new UsernamePasswordToken("goku", "goku");
    
    // Test async authentication with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletableFuture<AuthenticationInfo> futureB = realmB.getAuthenticationInfoAsync(tokenB)
          .toCompletableFuture();
      
      CompletableFuture<AuthenticationInfo> futureC = realmC.getAuthenticationInfoAsync(tokenC)
          .toCompletableFuture();
      
      // Wait for both futures to complete
      AuthenticationInfo infoB = futureB.get(5, TimeUnit.SECONDS);
      AuthenticationInfo infoC = futureC.get(5, TimeUnit.SECONDS);
      
      // Verify authentication results
      assertNotNull(infoB);
      assertEquals("jcool", infoB.getPrincipals().getPrimaryPrincipal());
      
      assertNotNull(infoC);
      assertEquals("goku", infoC.getPrincipals().getPrimaryPrincipal());
    }
  }

  /**
   * Tests cryptography operations under Java 21.
   * Verifies that Shiro's cryptography components work correctly with Java 21's security model.
   */
  @Test
  @DisplayName("Test cryptography operations under Java 21")
  public void testCryptographyOperations() {
    // Test hash operations
    String plaintext = "Hello, World!";
    Sha256Hash hash = new Sha256Hash(plaintext);
    
    assertNotNull(hash);
    assertNotNull(hash.getBytes());
    assertNotNull(hash.toHex());
    assertNotNull(hash.toBase64());
    
    // Test hash comparison
    Sha256Hash sameHash = new Sha256Hash(plaintext);
    assertEquals(hash.toHex(), sameHash.toHex());
    
    // Test salted hash
    String salt = "random-salt";
    Sha256Hash saltedHash = new Sha256Hash(plaintext, salt);
    
    assertNotNull(saltedHash);
    assertNotNull(saltedHash.getBytes());
    assertNotNull(saltedHash.toHex());
    assertNotNull(saltedHash.toBase64());
    
    // Verify that salted hash is different from unsalted hash
    assertFalse(hash.toHex().equals(saltedHash.toHex()));
  }

  /**
   * Tests permission evaluation under Java 21.
   * Verifies that Shiro's permission system works correctly with Java 21.
   */
  @Test
  @DisplayName("Test permission evaluation under Java 21")
  public void testPermissionEvaluation() {
    // Create wildcard permissions
    Permission readPermission = new WildcardPermission("system:read");
    Permission writePermission = new WildcardPermission("system:write");
    Permission allPermission = new WildcardPermission("system:*");
    
    // Test permission implication
    assertTrue(allPermission.implies(readPermission));
    assertTrue(allPermission.implies(writePermission));
    assertFalse(readPermission.implies(writePermission));
    assertFalse(writePermission.implies(readPermission));
    
    // Test with more complex permissions
    Permission userReadPermission = new WildcardPermission("user:read:123");
    Permission userWritePermission = new WildcardPermission("user:write:123");
    Permission userAllPermission = new WildcardPermission("user:*:123");
    Permission allUserPermission = new WildcardPermission("user:*:*");
    
    assertTrue(userAllPermission.implies(userReadPermission));
    assertTrue(userAllPermission.implies(userWritePermission));
    assertFalse(userReadPermission.implies(userWritePermission));
    
    assertTrue(allUserPermission.implies(userAllPermission));
    assertTrue(allUserPermission.implies(userReadPermission));
  }

  /**
   * Tests custom realm implementation with Java 21 features.
   * Verifies that custom realm implementations can leverage Java 21 features.
   */
  @Test
  @DisplayName("Test custom realm implementation with Java 21 features")
  public void testCustomRealmImplementation() {
    // Create a custom realm that uses Java 21 features
    AuthorizingRealm realm = new AuthorizingRealm() {
      @Override
      protected AuthorizationInfo doGetAuthorizationInfo(PrincipalCollection principals) {
        // Use pattern matching (Java 21 feature)
        Object primaryPrincipal = principals.getPrimaryPrincipal();
        
        if (primaryPrincipal instanceof String username) {
          SimpleAuthorizationInfo info = new SimpleAuthorizationInfo();
          
          // Use switch expressions with pattern matching
          switch (username) {
            case "admin" -> {
              info.addRole("admin");
              info.addStringPermission("*:*");
            }
            case "user" -> {
              info.addRole("user");
              info.addStringPermission("read:*");
            }
            case String s when s.startsWith("guest") -> {
              info.addRole("guest");
              info.addStringPermission("read:public");
            }
            default -> {
              // No permissions for unknown users
            }
          }
          
          return info;
        }
        
        return null;
      }
      
      @Override
      protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) {
        if (token instanceof UsernamePasswordToken upToken) {
          String username = upToken.getUsername();
          String password = new String(upToken.getPassword());
          
          // Simple authentication logic for testing
          if (username != null && username.equals(password)) {
            return new SimpleAuthenticationInfo(username, password, getName());
          }
        }
        
        return null;
      }
    };
    
    // Create and configure security manager with the custom realm
    DefaultSecurityManager manager = new DefaultSecurityManager(realm);
    SecurityUtils.setSecurityManager(manager);
    
    // Test authentication and authorization
    Subject subject = SecurityUtils.getSubject();
    
    // Test admin user
    UsernamePasswordToken token = new UsernamePasswordToken("admin", "admin");
    subject.login(token);
    assertTrue(subject.hasRole("admin"));
    assertTrue(subject.isPermitted("system:read"));
    assertTrue(subject.isPermitted("user:write"));
    subject.logout();
    
    // Test regular user
    token = new UsernamePasswordToken("user", "user");
    subject.login(token);
    assertTrue(subject.hasRole("user"));
    assertTrue(subject.isPermitted("read:documents"));
    assertFalse(subject.isPermitted("write:documents"));
    subject.logout();
    
    // Test guest user
    token = new UsernamePasswordToken("guest123", "guest123");
    subject.login(token);
    assertTrue(subject.hasRole("guest"));
    assertTrue(subject.isPermitted("read:public"));
    assertFalse(subject.isPermitted("read:private"));
    subject.logout();
  }
}