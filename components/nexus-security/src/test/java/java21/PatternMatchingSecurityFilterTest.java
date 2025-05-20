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

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.authz.WildcardPermission2;
import org.sonatype.nexus.security.token.BearerToken;

import org.apache.shiro.authz.Permission;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 Pattern Matching for switch in security filter decision logic.
 * 
 * This test class demonstrates how Java 21's Pattern Matching for switch can be used
 * to simplify and improve security filter type checking, permission evaluation, and
 * authorization decisions.
 */
public class PatternMatchingSecurityFilterTest
    extends TestSupport
{
  private static final String JWT_TOKEN = "jwt-token-value";
  private static final String BEARER_TOKEN = "bearer-token-value";
  private static final String FORMAT = "Format";
  
  @Mock
  private HttpServletRequest request;
  
  @Mock
  private HttpServletResponse response;
  
  @Mock
  private JwtHelper jwtHelper;
  
  @Before
  public void setup() {
    when(request.getServletPath()).thenReturn("/api/v1/security");
  }
  
  /**
   * Demonstrates pattern matching for switch with security filter types.
   * Java 21 allows pattern matching directly in switch statements, eliminating
   * the need for multiple instanceof checks and explicit casting.
   */
  @Test
  public void testPatternMatchingForSecurityFilters() {
    // Create different types of security-related objects
    Object securityObject1 = new BearerToken(FORMAT);
    Object securityObject2 = createJwtCookie();
    Object securityObject3 = new WildcardPermission2();
    Object securityObject4 = "Not a security object";
    
    // Test pattern matching with each object
    for (Object obj : List.of(securityObject1, securityObject2, securityObject3, securityObject4)) {
      String result = getSecurityObjectDescription(obj);
      log.info("Object {} described as: {}", obj.getClass().getSimpleName(), result);
    }
  }
  
  /**
   * Uses Java 21 pattern matching for switch to identify security object types.
   * This demonstrates replacing traditional instanceof-cast patterns with pattern variables.
   */
  private String getSecurityObjectDescription(Object obj) {
    // Pattern matching for switch with type patterns
    return switch (obj) {
      case BearerToken token -> "Bearer token with format: " + token.getFormat();
      case Cookie cookie when cookie.getName().equals(JwtHelper.JWT_COOKIE_NAME) -> 
          "JWT cookie with path: " + cookie.getPath();
      case WildcardPermission2 permission -> "Wildcard permission with " + permission.getParts().size() + " parts";
      case String s -> "String value: " + s;
      case null -> "Null security object";
      default -> "Unknown security object type";
    };
  }
  
  /**
   * Tests pattern matching with guarded patterns for permission validation.
   * Demonstrates how to use pattern matching with guards (when clause) to perform
   * conditional matching based on object properties.
   */
  @Test
  public void testPatternMatchingWithGuardsForPermissions() {
    // Create permissions with different characteristics
    WildcardPermission2 adminPermission = createPermission("admin", "*");
    WildcardPermission2 readPermission = createPermission("repository", "read");
    WildcardPermission2 emptyPermission = new WildcardPermission2();
    
    // Test permission evaluation with pattern matching
    for (Permission permission : List.of(adminPermission, readPermission, emptyPermission)) {
      String accessLevel = evaluatePermissionAccess(permission);
      log.info("Permission {} grants access level: {}", permission, accessLevel);
    }
  }
  
  /**
   * Uses pattern matching with guards to evaluate permission access levels.
   * This demonstrates how to combine type patterns with conditional guards for more
   * precise pattern matching.
   */
  private String evaluatePermissionAccess(Permission permission) {
    return switch (permission) {
      // Pattern with guard - matches WildcardPermission2 with admin:* pattern
      case WildcardPermission2 wp when isAdminPermission(wp) -> "ADMIN";
      
      // Pattern with guard - matches WildcardPermission2 with repository:read pattern
      case WildcardPermission2 wp when isReadPermission(wp) -> "READ";
      
      // Pattern with guard - matches WildcardPermission2 with empty parts
      case WildcardPermission2 wp when wp.getParts().isEmpty() -> "NONE";
      
      // Default case for any other Permission implementation
      case Permission p -> "UNKNOWN";
    };
  }
  
  /**
   * Tests pattern matching with sealed interfaces for HTTP request security categorization.
   * Demonstrates how pattern matching works with sealed hierarchies for exhaustive matching.
   */
  @Test
  public void testPatternMatchingWithSealedInterfacesForRequests() {
    // Create different types of security requests
    SecurityRequest adminRequest = new AdminSecurityRequest("/admin/users");
    SecurityRequest userRequest = new UserSecurityRequest("/user/profile");
    SecurityRequest anonymousRequest = new AnonymousSecurityRequest("/public/resources");
    
    // Test request categorization with pattern matching
    for (SecurityRequest req : List.of(adminRequest, userRequest, anonymousRequest)) {
      String category = categorizeSecurityRequest(req);
      log.info("Request to {} categorized as: {}", req.getPath(), category);
    }
  }
  
  /**
   * Uses pattern matching with sealed interfaces for exhaustive matching.
   * This demonstrates how Java 21 pattern matching works with sealed types to ensure
   * all possible subtypes are handled.
   */
  private String categorizeSecurityRequest(SecurityRequest request) {
    // Pattern matching with sealed interface - compiler ensures exhaustiveness
    return switch (request) {
      case AdminSecurityRequest r -> "ADMIN_ZONE";
      case UserSecurityRequest r -> "USER_ZONE";
      case AnonymousSecurityRequest r -> "PUBLIC_ZONE";
      // No default needed as all possible subtypes of the sealed interface are covered
    };
  }
  
  /**
   * Tests pattern matching with nested patterns for complex permission hierarchies.
   * Demonstrates how to use nested patterns to match and extract data from complex objects.
   */
  @Test
  public void testNestedPatternMatchingForPermissionHierarchies() {
    // Create nested permission structure
    PermissionContainer container = new PermissionContainer(
        "repository-admin",
        List.of(
            createPermission("repository", "read"),
            createPermission("repository", "write"),
            createPermission("repository", "delete")
        )
    );
    
    // Test nested pattern matching
    String description = describePermissionContainer(container);
    log.info("Permission container described as: {}", description);
    
    // Test with null values to demonstrate null handling
    description = describePermissionContainer(null);
    log.info("Null permission container described as: {}", description);
    
    description = describePermissionContainer(new PermissionContainer("empty", null));
    log.info("Empty permission container described as: {}", description);
  }
  
  /**
   * Uses nested pattern matching to extract and process data from complex objects.
   * This demonstrates how to match against object structures and handle null cases.
   */
  private String describePermissionContainer(Object obj) {
    return switch (obj) {
      // Nested pattern matching with null handling
      case PermissionContainer pc when pc.permissions() != null && !pc.permissions().isEmpty() ->
          "Container '" + pc.name() + "' with " + pc.permissions().size() + " permissions";
      
      // Pattern matching for empty container
      case PermissionContainer pc -> "Empty container '" + pc.name() + "'";
      
      // Explicit null handling
      case null -> "Null permission container";
      
      // Default case
      default -> "Not a permission container";
    };
  }
  
  /**
   * Tests handling of null cases and exhaustiveness in switch expressions.
   * Demonstrates how Java 21 pattern matching handles null values and ensures
   * exhaustive coverage of all possible cases.
   */
  @Test
  public void testNullHandlingAndExhaustiveness() {
    // Test with various objects including null
    Object[] testObjects = {new BearerToken(FORMAT), null, "string", 42};
    
    for (Object obj : testObjects) {
      String result = processSecurityObject(obj);
      log.info("Security object processed as: {}", result);
    }
  }
  
  /**
   * Uses pattern matching with explicit null handling in switch expressions.
   * This demonstrates how Java 21 allows null to be handled directly in switch expressions.
   */
  private String processSecurityObject(Object obj) {
    return switch (obj) {
      case BearerToken token -> "Processing bearer token";
      case String s -> "Processing string: " + s;
      case Integer i -> "Processing integer: " + i;
      case null -> "Processing null object";
      default -> "Processing unknown object type";
    };
  }
  
  // Helper methods
  
  private Cookie createJwtCookie() {
    Cookie cookie = new Cookie(JwtHelper.JWT_COOKIE_NAME, JWT_TOKEN);
    cookie.setMaxAge(300);
    cookie.setPath("/");
    cookie.setHttpOnly(true);
    return cookie;
  }
  
  private WildcardPermission2 createPermission(String domain, String action) {
    WildcardPermission2 permission = new WildcardPermission2();
    permission.setParts(List.of(domain), List.of(action), true);
    return permission;
  }
  
  private boolean isAdminPermission(WildcardPermission2 permission) {
    List<Set<String>> parts = permission.getParts();
    return parts.size() >= 2 && 
           parts.get(0).contains("admin") && 
           parts.get(1).contains("*");
  }
  
  private boolean isReadPermission(WildcardPermission2 permission) {
    List<Set<String>> parts = permission.getParts();
    return parts.size() >= 2 && 
           parts.get(0).contains("repository") && 
           parts.get(1).contains("read");
  }
  
  // Sealed interface hierarchy for demonstrating exhaustive pattern matching
  
  /**
   * Sealed interface for security request types.
   * Demonstrates how sealed types work with pattern matching for exhaustiveness.
   */
  private sealed interface SecurityRequest permits 
      AdminSecurityRequest, UserSecurityRequest, AnonymousSecurityRequest {
    String getPath();
  }
  
  private final class AdminSecurityRequest implements SecurityRequest {
    private final String path;
    
    AdminSecurityRequest(String path) {
      this.path = path;
    }
    
    @Override
    public String getPath() {
      return path;
    }
  }
  
  private final class UserSecurityRequest implements SecurityRequest {
    private final String path;
    
    UserSecurityRequest(String path) {
      this.path = path;
    }
    
    @Override
    public String getPath() {
      return path;
    }
  }
  
  private final class AnonymousSecurityRequest implements SecurityRequest {
    private final String path;
    
    AnonymousSecurityRequest(String path) {
      this.path = path;
    }
    
    @Override
    public String getPath() {
      return path;
    }
  }
  
  /**
   * Record for containing a collection of permissions.
   * Demonstrates how records work with pattern matching.
   */
  private record PermissionContainer(String name, List<Permission> permissions) {}
}