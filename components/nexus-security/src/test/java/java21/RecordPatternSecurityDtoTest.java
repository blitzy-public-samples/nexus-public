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
package java21;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for Java 21 Record Patterns with security-related DTOs.
 * 
 * This test class demonstrates how Record Patterns can simplify data handling
 * for security-focused classes like JWT tokens, security configurations, and
 * role/permission hierarchies.
 */
public class RecordPatternSecurityDtoTest
    extends TestSupport
{
  /**
   * Security token record with nested payload record
   */
  record SecurityToken(String tokenType, TokenPayload payload, String signature) {
    record TokenPayload(String subject, Set<String> roles, Map<String, Object> claims, Instant expiration) {}
  }
  
  /**
   * Security configuration record with nested records
   */
  record SecurityConfig(AuthConfig authConfig, List<RealmConfig> realms, boolean anonymousEnabled) {
    record AuthConfig(int sessionTimeout, boolean requireSsl, int failedLoginDelay) {}
    record RealmConfig(String name, String type, boolean enabled) {}
  }
  
  /**
   * Security role hierarchy records
   */
  record Role(String id, String name, String description, Set<Permission> permissions) {}
  record Permission(String id, String domain, String actions) {}
  
  /**
   * Security exception records
   */
  record AuthenticationException(String message, String username, String realm) extends RuntimeException {}
  record AuthorizationException(String message, String username, String permission) extends RuntimeException {}
  
  @Test
  public void testNestedRecordPatternsForJwtTokenData() {
    // Create a JWT-like token with nested payload
    Map<String, Object> claims = new HashMap<>();
    claims.put("email", "admin@example.com");
    claims.put("userId", 12345);
    
    SecurityToken token = new SecurityToken(
        "JWT",
        new SecurityToken.TokenPayload(
            "admin",
            Set.of("nx-admin", "nx-user"),
            claims,
            Instant.now().plusSeconds(3600)
        ),
        "abc123.signature"
    );
    
    // Use nested record pattern to extract data directly
    if (token instanceof SecurityToken(String type, SecurityToken.TokenPayload(String subject, var roles, var claims_, var expiration), String signature)) {
      assertThat(type, is("JWT"));
      assertThat(subject, is("admin"));
      assertThat(roles, is(Set.of("nx-admin", "nx-user")));
      assertThat(claims_.get("email"), is("admin@example.com"));
      assertThat(signature, is("abc123.signature"));
      
      // We can use the destructured variables directly
      boolean isAdmin = roles.contains("nx-admin");
      assertThat(isAdmin, is(true));
      
      // We can also access nested data from the claims map
      if (claims_.get("userId") instanceof Integer userId) {
        assertThat(userId, is(12345));
      }
    }
  }
  
  @Test
  public void testPatternMatchingWithInstanceofForSecurityTypes() {
    // Create a list of mixed security-related objects
    List<Object> securityObjects = new ArrayList<>();
    securityObjects.add(new Role("admin-role", "Administrator", "Full system access", Set.of(
        new Permission("all-repos", "repository", "*"),
        new Permission("all-users", "security", "*")
    )));
    securityObjects.add("Not a security object");
    securityObjects.add(new Permission("view-logs", "system", "read"));
    
    int roleCount = 0;
    int permissionCount = 0;
    
    // Use pattern matching with instanceof to process different types
    for (Object obj : securityObjects) {
      if (obj instanceof Role(String id, String name, var description, var permissions)) {
        roleCount++;
        assertThat(id, is("admin-role"));
        assertThat(name, is("Administrator"));
        assertThat(permissions.size(), is(2));
        
        // We can further process the nested permissions
        for (Permission permission : permissions) {
          if (permission instanceof Permission(var permId, "repository", var actions)) {
            assertThat(permId, is("all-repos"));
            assertThat(actions, is("*"));
          }
        }
      } 
      else if (obj instanceof Permission(var id, var domain, var actions)) {
        permissionCount++;
        assertThat(id, is("view-logs"));
        assertThat(domain, is("system"));
        assertThat(actions, is("read"));
      }
    }
    
    assertThat(roleCount, is(1));
    assertThat(permissionCount, is(1));
  }
  
  @Test
  public void testRecordPatternsInCatchBlocksForSecurityExceptions() {
    try {
      // Simulate an authentication failure
      throw new AuthenticationException("Invalid credentials", "testuser", "local");
    } 
    catch (AuthenticationException(String message, String username, String realm)) {
      // We can directly use the destructured fields from the exception
      assertThat(message, is("Invalid credentials"));
      assertThat(username, is("testuser"));
      assertThat(realm, is("local"));
      
      // We could log or handle the exception using these variables
      String logMessage = String.format("Authentication failed for user '%s' in realm '%s': %s", 
          username, realm, message);
      assertThat(logMessage, is("Authentication failed for user 'testuser' in realm 'local': Invalid credentials"));
    }
    
    try {
      // Simulate an authorization failure
      throw new AuthorizationException("Permission denied", "testuser", "repository:maven-central:read");
    } 
    catch (AuthorizationException(var message, var username, var permission)) {
      // We can directly use the destructured fields from the exception
      assertThat(message, is("Permission denied"));
      assertThat(username, is("testuser"));
      assertThat(permission, is("repository:maven-central:read"));
      
      // We could parse the permission string using the destructured variable
      String[] parts = permission.split(":");
      assertThat(parts[0], is("repository"));
      assertThat(parts[1], is("maven-central"));
      assertThat(parts[2], is("read"));
    }
  }
  
  @Test
  public void testRecordPatternsInForLoopsWithSecurityCollections() {
    // Create a list of security roles with permissions
    List<Role> roles = Arrays.asList(
        new Role("admin", "Administrator", "Full access", Set.of(
            new Permission("admin-all", "*", "*")
        )),
        new Role("developer", "Developer", "Repository access", Set.of(
            new Permission("repo-read", "repository", "read"),
            new Permission("repo-create", "repository", "create")
        )),
        new Role("viewer", "Viewer", "Read-only access", Set.of(
            new Permission("repo-read", "repository", "read")
        ))
    );
    
    // Use record patterns in enhanced for loop
    int adminPermissionCount = 0;
    int repoReadPermissionCount = 0;
    
    for (Role(var id, var name, var description, var permissions) : roles) {
      // We can directly use the destructured fields
      log.info("Processing role: {} ({})", name, description);
      
      // Process permissions using nested record patterns
      for (Permission(var permId, var domain, var actions) : permissions) {
        if (domain.equals("*") && actions.equals("*")) {
          adminPermissionCount++;
        }
        else if (permId.equals("repo-read")) {
          repoReadPermissionCount++;
        }
      }
    }
    
    assertThat(adminPermissionCount, is(1));
    assertThat(repoReadPermissionCount, is(2));
  }
  
  @Test
  public void testDeconstructionPatternsWithSecurityConfigurationRecords() {
    // Create a security configuration with nested records
    SecurityConfig config = new SecurityConfig(
        new SecurityConfig.AuthConfig(30, true, 5),
        List.of(
            new SecurityConfig.RealmConfig("local", "local", true),
            new SecurityConfig.RealmConfig("ldap", "ldap", true),
            new SecurityConfig.RealmConfig("saml", "saml", false)
        ),
        false
    );
    
    // Use record pattern to deconstruct the configuration
    if (config instanceof SecurityConfig(SecurityConfig.AuthConfig authConfig, var realms, var anonymousEnabled)) {
      // We can directly use the destructured fields
      assertThat(anonymousEnabled, is(false));
      
      // Further deconstruct the nested auth config
      if (authConfig instanceof SecurityConfig.AuthConfig(int timeout, boolean requireSsl, var loginDelay)) {
        assertThat(timeout, is(30));
        assertThat(requireSsl, is(true));
        assertThat(loginDelay, is(5));
      }
      
      // Count enabled realms using record patterns in a loop
      int enabledRealmCount = 0;
      for (SecurityConfig.RealmConfig(var name, var type, boolean enabled) : realms) {
        if (enabled) {
          enabledRealmCount++;
          assertThat(name, is(notNullValue()));
          assertThat(type, is(notNullValue()));
        }
      }
      
      assertThat(enabledRealmCount, is(2));
      
      // Find a specific realm using record patterns with Optional
      Optional<SecurityConfig.RealmConfig> ldapRealm = realms.stream()
          .filter(r -> r instanceof SecurityConfig.RealmConfig(String name, var type, var enabled) && name.equals("ldap"))
          .findFirst();
      
      assertThat(ldapRealm.isPresent(), is(true));
      
      // Use record pattern with the Optional result
      if (ldapRealm.isPresent() && 
          ldapRealm.get() instanceof SecurityConfig.RealmConfig(var name, var type, var enabled)) {
        assertThat(name, is("ldap"));
        assertThat(type, is("ldap"));
        assertThat(enabled, is(true));
      }
    }
  }
}