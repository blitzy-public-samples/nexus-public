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

import java.util.Arrays;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.authz.WildcardPermission2;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests demonstrating the use of Java 21's pattern matching for switch in security contexts.
 * 
 * This test class showcases how pattern matching for switch can improve code maintainability
 * and robustness in security-related code, particularly for:
 * <ul>
 *   <li>Permission type checking and evaluation</li>
 *   <li>Authentication token processing</li>
 *   <li>Security role handling</li>
 * </ul>
 * 
 * Pattern matching for switch provides several benefits over traditional approaches:
 * <ul>
 *   <li>More concise and readable code compared to if-else chains with instanceof</li>
 *   <li>Type-safe extraction of values without explicit casting</li>
 *   <li>Exhaustiveness checking ensures all cases are handled</li>
 *   <li>Elegant null handling within the switch statement</li>
 *   <li>Guarded patterns allow for complex conditional logic</li>
 * </ul>
 * 
 * @since 3.60
 */
public class PatternMatchingSecurityTest
    extends TestSupport
{
  /**
   * Demonstrates how pattern matching for switch can simplify permission type checking
   * compared to traditional if-else with instanceof.
   */
  @Test
  public void testPermissionTypeCheckingWithPatternMatching() {
    // Create different types of permissions
    Permission wildcardPermission = new WildcardPermission("nexus:repository:*:read");
    Permission wildcardPermission2 = new WildcardPermission2("nexus:repository:maven:*");
    Permission customPermission = new CustomPermission("repository-view");
    
    // Traditional approach with if-else and instanceof
    String traditionalResult = getPermissionTypeTraditional(wildcardPermission);
    assertEquals("WildcardPermission", traditionalResult);
    
    traditionalResult = getPermissionTypeTraditional(wildcardPermission2);
    assertEquals("WildcardPermission2", traditionalResult);
    
    traditionalResult = getPermissionTypeTraditional(customPermission);
    assertEquals("CustomPermission", traditionalResult);
    
    // Java 21 approach with pattern matching for switch
    String patternResult = getPermissionTypeWithPatternMatching(wildcardPermission);
    assertEquals("WildcardPermission", patternResult);
    
    patternResult = getPermissionTypeWithPatternMatching(wildcardPermission2);
    assertEquals("WildcardPermission2", patternResult);
    
    patternResult = getPermissionTypeWithPatternMatching(customPermission);
    assertEquals("CustomPermission", patternResult);
    
    // Test with null
    traditionalResult = getPermissionTypeTraditional(null);
    assertEquals("Unknown", traditionalResult);
    
    patternResult = getPermissionTypeWithPatternMatching(null);
    assertEquals("Unknown", patternResult);
    
    // Demonstrate the conciseness and readability benefits
    log.info("Traditional approach requires multiple if-else statements with explicit instanceof checks");
    log.info("Pattern matching approach is more concise, readable, and less error-prone");
    log.info("Pattern matching also handles null values elegantly within the switch statement");
  }
  
  /**
   * Traditional approach using if-else with instanceof checks.
   */
  private String getPermissionTypeTraditional(Permission permission) {
    if (permission == null) {
      return "Unknown";
    } else if (permission instanceof WildcardPermission2) {
      return "WildcardPermission2";
    } else if (permission instanceof WildcardPermission) {
      return "WildcardPermission";
    } else if (permission instanceof CustomPermission) {
      return "CustomPermission";
    } else {
      return "Other";
    }
  }
  
  /**
   * Java 21 approach using pattern matching for switch.
   */
  private String getPermissionTypeWithPatternMatching(Permission permission) {
    return switch (permission) {
      case null -> "Unknown";
      case WildcardPermission2 wp2 -> "WildcardPermission2";
      case WildcardPermission wp -> "WildcardPermission";
      case CustomPermission cp -> "CustomPermission";
      default -> "Other";
    };
  }
  
  /**
   * Demonstrates how pattern matching with guarded patterns can be used for
   * more complex permission evaluations.
   */
  @Test
  public void testPermissionEvaluationWithGuardedPatterns() {
    // Create permissions with different patterns
    WildcardPermission readPermission = new WildcardPermission("nexus:repository:maven:read");
    WildcardPermission writePermission = new WildcardPermission("nexus:repository:maven:write");
    WildcardPermission adminPermission = new WildcardPermission("nexus:*:*:*");
    
    // Test permission evaluation with pattern matching
    assertTrue(evaluatePermissionAccess(readPermission, "maven", "read"));
    assertFalse(evaluatePermissionAccess(readPermission, "maven", "write"));
    assertTrue(evaluatePermissionAccess(writePermission, "maven", "write"));
    assertTrue(evaluatePermissionAccess(adminPermission, "maven", "read"));
    assertTrue(evaluatePermissionAccess(adminPermission, "maven", "write"));
    assertTrue(evaluatePermissionAccess(adminPermission, "npm", "read"));
    
    // Demonstrate the power of guarded patterns
    log.info("Guarded patterns with 'when' clause allow for complex conditional logic");
    log.info("This enables more sophisticated permission checks in a concise syntax");
    log.info("The pattern matching approach eliminates complex nested if-else structures");
  }
  
  /**
   * Evaluates if a permission grants access to a specific repository format and action
   * using pattern matching with guarded patterns.
   * 
   * This demonstrates how pattern matching with guards can replace complex if-else chains
   * with a more readable and maintainable structure for permission evaluation.
   */
  private boolean evaluatePermissionAccess(Permission permission, String repoFormat, String action) {
    return switch (permission) {
      // Admin permission that grants access to everything
      case WildcardPermission wp when isWildcardMatch(wp, "nexus:*:*:*") -> true;
      
      // Format-specific permission that grants access to all actions
      case WildcardPermission wp when isWildcardMatch(wp, "nexus:repository:" + repoFormat + ":*") -> true;
      
      // Format-specific permission for a specific action
      case WildcardPermission wp when isWildcardMatch(wp, "nexus:repository:" + repoFormat + ":" + action) -> true;
      
      // Action-specific permission for all formats
      case WildcardPermission wp when isWildcardMatch(wp, "nexus:repository:*:" + action) -> true;
      
      // No matching permission
      default -> false;
    };
  }
  
  private boolean isWildcardMatch(WildcardPermission permission, String pattern) {
    return new WildcardPermission(pattern).implies(permission);
  }
  
  /**
   * Demonstrates how pattern matching can be used for authentication token processing.
   */
  @Test
  public void testAuthTokenProcessingWithPatternMatching() {
    // Create different types of authentication tokens
    AuthToken jwtToken = new JwtAuthToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...", 3600);
    AuthToken basicToken = new BasicAuthToken("admin", "password123");
    AuthToken apiToken = new ApiKeyToken("api-key-12345");
    AuthToken invalidToken = new InvalidToken();
    
    // Process tokens with pattern matching
    assertEquals("JWT token valid for 3600 seconds", processAuthToken(jwtToken));
    assertEquals("Basic auth for user: admin", processAuthToken(basicToken));
    assertEquals("API key: api-key-12345", processAuthToken(apiToken));
    assertEquals("Invalid token", processAuthToken(invalidToken));
    assertEquals("No token provided", processAuthToken(null));
    
    // Demonstrate the benefits for token processing
    log.info("Pattern matching simplifies token type detection and extraction");
    log.info("Records combined with pattern matching provide a powerful way to handle tokens");
    log.info("The switch expression provides a clear, exhaustive handling of all token types");
  }
  
  /**
   * Processes different types of authentication tokens using pattern matching for switch.
   * 
   * This demonstrates how pattern matching simplifies token type detection and data extraction
   * in a single, concise expression. The pattern variables (jwt, basic, api) are automatically
   * bound to the matched value with the correct type, eliminating the need for explicit casting.
   */
  private String processAuthToken(AuthToken token) {
    return switch (token) {
      // Handle null case explicitly within the switch
      case null -> "No token provided";
      
      // Extract JWT token details without casting
      case JwtAuthToken jwt -> "JWT token valid for " + jwt.expiresIn() + " seconds";
      
      // Extract username from BasicAuthToken without casting
      case BasicAuthToken basic -> "Basic auth for user: " + basic.username();
      
      // Extract API key without casting
      case ApiKeyToken api -> "API key: " + api.key();
      
      // Catch-all for any other token type
      default -> "Invalid token";
    };
  }
  
  /**
   * Demonstrates how pattern matching can be used for security role processing.
   */
  @Test
  public void testSecurityRoleProcessingWithPatternMatching() {
    // Create different types of security roles
    SecurityRole adminRole = new AdminRole("admin", List.of("*:*:*"));
    SecurityRole userRole = new UserRole("user", List.of("nexus:repository:maven:read"));
    SecurityRole guestRole = new GuestRole();
    
    // Process roles with pattern matching
    assertEquals("Admin role with full access", describeSecurityRole(adminRole));
    assertEquals("User role with 1 permissions", describeSecurityRole(userRole));
    assertEquals("Guest role with limited access", describeSecurityRole(guestRole));
    assertEquals("No role assigned", describeSecurityRole(null));
    
    // Test with an empty permissions list
    SecurityRole emptyUserRole = new UserRole("empty", List.of());
    assertEquals("User role with no permissions", describeSecurityRole(emptyUserRole));
    
    // Demonstrate the benefits for role processing
    log.info("Pattern matching with guards allows for conditional logic based on role properties");
    log.info("This enables more sophisticated role-based access control logic");
    log.info("The exhaustive nature of switch expressions ensures all role types are handled");
  }
  
  /**
   * Describes security roles using pattern matching for switch.
   * 
   * This demonstrates how pattern matching with guards can be used to implement
   * conditional logic based on the properties of the matched object. The order
   * of case labels is important, as more specific patterns must come before
   * more general ones.
   */
  private String describeSecurityRole(SecurityRole role) {
    return switch (role) {
      // Handle null case explicitly
      case null -> "No role assigned";
      
      // Match AdminRole type
      case AdminRole admin -> "Admin role with full access";
      
      // Match UserRole with a guard condition for non-empty permissions
      case UserRole user when user.permissions().size() > 0 -> 
          "User role with " + user.permissions().size() + " permissions";
      
      // Match UserRole with empty permissions (more specific case must come first)
      case UserRole user -> "User role with no permissions";
      
      // Match GuestRole type
      case GuestRole guest -> "Guest role with limited access";
      
      // Catch-all for any other role type
      default -> "Unknown role type";
    };
  }
  
  // Mock classes for testing
  
  /**
   * Custom permission type for testing pattern matching with different permission types.
   */
  private static class CustomPermission implements Permission {
    private final String type;
    
    public CustomPermission(String type) {
      this.type = type;
    }
    
    @Override
    public boolean implies(Permission p) {
      return false;
    }
    
    public String getType() {
      return type;
    }
  }
  
  /**
   * Authentication token classes using Java Records for immutable data structures.
   * Records work particularly well with pattern matching as they provide
   * built-in accessors that can be used directly in the pattern matching expressions.
   */
  private interface AuthToken {}
  
  private record JwtAuthToken(String token, int expiresIn) implements AuthToken {}
  
  private record BasicAuthToken(String username, String password) implements AuthToken {}
  
  private record ApiKeyToken(String key) implements AuthToken {}
  
  private static class InvalidToken implements AuthToken {}
  
  /**
   * Security role classes demonstrating a mix of records and regular classes.
   * Pattern matching works with both types, but records provide more concise
   * access to their components.
   */
  private interface SecurityRole {}
  
  private record AdminRole(String name, List<String> permissions) implements SecurityRole {}
  
  private record UserRole(String name, List<String> permissions) implements SecurityRole {}
  
  private static class GuestRole implements SecurityRole {}
}