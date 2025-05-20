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
package org.sonatype.java21;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.UserPrincipalsHelper;
import org.sonatype.nexus.security.token.BearerToken;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Tests demonstrating the use of Java 21's record patterns for processing security data structures.
 * 
 * Record patterns provide a concise way to extract data from records, making security-related code
 * more readable and maintainable while improving type safety.
 */
public class RecordPatternSecurityTest
    extends TestSupport
{
  // Security-related record definitions
  
  /**
   * Represents a JWT token with its components
   */
  record JwtToken(String header, String payload, String signature) {}
  
  /**
   * Represents user credentials
   */
  record Credentials(String username, String password, boolean temporary) {}
  
  /**
   * Represents a security permission
   */
  record Permission(String domain, String action, String target) {}
  
  /**
   * Represents a role with its permissions
   */
  record Role(String id, String name, String description, Set<Permission> permissions) {}
  
  /**
   * Represents a user with roles
   */
  record User(String id, String username, Set<Role> roles) {}
  
  /**
   * Represents an authentication token
   */
  record AuthToken(String tokenValue, String userId, Instant expiration, Set<String> scopes) {}
  
  /**
   * Represents an authentication result
   */
  record AuthResult(User user, AuthToken token, boolean success, String message) {}
  
  /**
   * Represents a security event
   */
  record SecurityEvent(String eventType, String userId, Instant timestamp, Map<String, String> details) {}
  
  @Mock
  private UserPrincipalsHelper userPrincipalsHelper;
  
  @Before
  public void setup() {
    // Setup code if needed
  }
  
  /**
   * Test demonstrating basic record pattern matching for JWT token validation.
   * Shows how record patterns simplify token component extraction compared to traditional methods.
   */
  @Test
  public void testJwtTokenValidationWithRecordPatterns() {
    // Create a sample JWT token
    JwtToken validToken = new JwtToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", 
                                      "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ", 
                                      "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c");
    
    // Traditional approach without record patterns
    boolean isValidTraditional = false;
    if (validToken != null) {
      String header = validToken.header();
      String payload = validToken.payload();
      String signature = validToken.signature();
      
      if (header != null && payload != null && signature != null &&
          !header.isEmpty() && !payload.isEmpty() && !signature.isEmpty()) {
        isValidTraditional = true;
      }
    }
    
    // Using record patterns for more concise validation
    boolean isValidWithPattern = switch (validToken) {
      case JwtToken(String h, String p, String s) when !h.isEmpty() && !p.isEmpty() && !s.isEmpty() -> true;
      default -> false;
    };
    
    assertTrue(isValidTraditional);
    assertTrue(isValidWithPattern);
    
    // Invalid token test
    JwtToken invalidToken = new JwtToken("", "payload", "signature");
    
    boolean isInvalidWithPattern = switch (invalidToken) {
      case JwtToken(String h, String p, String s) when !h.isEmpty() && !p.isEmpty() && !s.isEmpty() -> true;
      default -> false;
    };
    
    assertFalse(isInvalidWithPattern);
  }
  
  /**
   * Test demonstrating nested record pattern matching for complex security structures.
   * Shows how record patterns can simplify working with nested security objects.
   */
  @Test
  public void testNestedRecordPatternsForSecurityStructures() {
    // Create a complex nested security structure
    Permission viewPermission = new Permission("repository", "view", "maven2:*");
    Permission createPermission = new Permission("repository", "create", "maven2:*");
    Permission deletePermission = new Permission("repository", "delete", "maven2:*");
    
    Set<Permission> adminPermissions = Set.of(viewPermission, createPermission, deletePermission);
    Set<Permission> userPermissions = Set.of(viewPermission);
    
    Role adminRole = new Role("admin", "Administrator", "Full system access", adminPermissions);
    Role userRole = new Role("user", "User", "Basic system access", userPermissions);
    
    User adminUser = new User("1", "admin", Set.of(adminRole));
    User regularUser = new User("2", "user", Set.of(userRole));
    
    // Check if admin has delete permission using nested record patterns
    boolean adminHasDeletePermission = hasPermission(adminUser, "repository", "delete", "maven2:*");
    boolean userHasDeletePermission = hasPermission(regularUser, "repository", "delete", "maven2:*");
    
    assertTrue(adminHasDeletePermission);
    assertFalse(userHasDeletePermission);
  }
  
  /**
   * Helper method that uses nested record patterns to check if a user has a specific permission.
   * Demonstrates how record patterns simplify permission checking logic.
   */
  private boolean hasPermission(User user, String domain, String action, String target) {
    // Using nested record patterns to check permissions
    for (Role role : user.roles()) {
      for (Permission permission : role.permissions()) {
        // Using record pattern in if statement
        if (permission instanceof Permission(String d, String a, String t) && 
            d.equals(domain) && a.equals(action) && t.equals(target)) {
          return true;
        }
      }
    }
    return false;
  }
  
  /**
   * Test demonstrating how record patterns improve error handling for security data validation.
   * Shows how pattern matching can make validation code more robust and readable.
   */
  @Test
  public void testImprovedErrorHandlingWithRecordPatterns() {
    // Create various authentication results
    AuthResult successResult = new AuthResult(
        new User("1", "admin", Set.of()),
        new AuthToken("token123", "1", Instant.now().plusSeconds(3600), Set.of("read", "write")),
        true,
        "Authentication successful"
    );
    
    AuthResult failedResult = new AuthResult(null, null, false, "Invalid credentials");
    AuthResult expiredResult = new AuthResult(
        new User("1", "admin", Set.of()),
        new AuthToken("token123", "1", Instant.now().minusSeconds(3600), Set.of("read", "write")),
        false,
        "Token expired"
    );
    
    // Process authentication results using record patterns
    String successMessage = processAuthResult(successResult);
    String failedMessage = processAuthResult(failedResult);
    String expiredMessage = processAuthResult(expiredResult);
    
    assertEquals("User admin authenticated successfully with scopes: [read, write]", successMessage);
    assertEquals("Authentication failed: Invalid credentials", failedMessage);
    assertEquals("Authentication failed: Token expired", expiredMessage);
  }
  
  /**
   * Helper method that uses record patterns to process authentication results.
   * Demonstrates how record patterns simplify conditional logic and error handling.
   */
  private String processAuthResult(AuthResult result) {
    return switch (result) {
      // Successful authentication with valid user and token
      case AuthResult(User(var id, var username, var roles), AuthToken(var tokenValue, var userId, var expiration, var scopes), true, var message) 
          when expiration.isAfter(Instant.now()) -> 
          "User " + username + " authenticated successfully with scopes: " + scopes;
      
      // Failed authentication with error message
      case AuthResult(null, null, false, var message) -> 
          "Authentication failed: " + message;
      
      // Expired token
      case AuthResult(var user, AuthToken(var tokenValue, var userId, var expiration, var scopes), var success, var message) 
          when !expiration.isAfter(Instant.now()) -> 
          "Authentication failed: Token expired";
      
      // Any other case
      default -> "Unknown authentication result";
    };
  }
  
  /**
   * Test demonstrating record patterns for security event processing.
   * Shows how record patterns can be used to filter and process security events.
   */
  @Test
  public void testSecurityEventProcessingWithRecordPatterns() {
    // Create sample security events
    Instant now = Instant.now();
    SecurityEvent loginEvent = new SecurityEvent("LOGIN", "user1", now, 
        Map.of("ip", "192.168.1.1", "browser", "Chrome"));
    SecurityEvent logoutEvent = new SecurityEvent("LOGOUT", "user1", now.plusSeconds(3600), 
        Map.of("ip", "192.168.1.1"));
    SecurityEvent failedLoginEvent = new SecurityEvent("LOGIN_FAILED", "user2", now, 
        Map.of("ip", "10.0.0.1", "reason", "Invalid password"));
    
    List<SecurityEvent> events = List.of(loginEvent, logoutEvent, failedLoginEvent);
    
    // Process events using record patterns
    long failedLoginCount = events.stream()
        .filter(event -> event instanceof SecurityEvent(String type, var userId, var timestamp, var details) 
            && type.equals("LOGIN_FAILED"))
        .count();
    
    Optional<String> failureReason = events.stream()
        .filter(event -> event instanceof SecurityEvent(String type, var userId, var timestamp, var details) 
            && type.equals("LOGIN_FAILED"))
        .findFirst()
        .map(event -> {
          if (event instanceof SecurityEvent(var type, var userId, var timestamp, var details)) {
            return details.getOrDefault("reason", "Unknown reason");
          }
          return "Unknown reason";
        });
    
    assertEquals(1, failedLoginCount);
    assertTrue(failureReason.isPresent());
    assertEquals("Invalid password", failureReason.get());
  }
  
  /**
   * Test demonstrating how record patterns can be used with BearerToken for improved token extraction.
   * Shows integration with existing Nexus security classes.
   */
  @Test
  public void testBearerTokenExtractionWithRecordPatterns() {
    // Create a record to represent a parsed bearer token
    record ParsedBearerToken(String format, String token) {}
    
    // Sample authorization header
    String authHeader = "Bearer Format.abc123xyz";
    
    // Parse the bearer token using record patterns
    Optional<ParsedBearerToken> parsedToken = Optional.ofNullable(authHeader)
        .filter(header -> header.startsWith("Bearer "))
        .map(header -> header.substring(7)) // Remove "Bearer " prefix
        .filter(token -> token.contains("."))
        .map(token -> {
          String[] parts = token.split("\\.", 2);
          return new ParsedBearerToken(parts[0], parts[1]);
        });
    
    // Verify the parsed token
    assertTrue(parsedToken.isPresent());
    
    // Using record pattern to extract components
    if (parsedToken.isPresent() && parsedToken.get() instanceof ParsedBearerToken(String format, String token)) {
      assertEquals("Format", format);
      assertEquals("abc123xyz", token);
    } else {
      // This should not happen if the test is working correctly
      assertTrue("Failed to extract token using record pattern", false);
    }
    
    // Test with invalid header
    String invalidHeader = "Basic dXNlcjpwYXNzd29yZA==";
    Optional<ParsedBearerToken> invalidToken = Optional.ofNullable(invalidHeader)
        .filter(header -> header.startsWith("Bearer "))
        .map(header -> header.substring(7))
        .filter(token -> token.contains("."))
        .map(token -> {
          String[] parts = token.split("\\.", 2);
          return new ParsedBearerToken(parts[0], parts[1]);
        });
    
    assertFalse(invalidToken.isPresent());
  }
}