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
import org.sonatype.nexus.security.token.BearerToken;

import org.apache.shiro.authz.Permission;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
  private static final Logger log = LoggerFactory.getLogger(PatternMatchingSecurityFilterTest.class);

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

  @Test
  public void testPatternMatchingForSecurityFilters() {
    Object securityObject1 = new BearerToken(FORMAT);
    Object securityObject2 = createJwtCookie();
    Object securityObject3 = new TestWildcardPermission2();
    Object securityObject4 = "Not a security object";

    for (Object obj : List.of(securityObject1, securityObject2, securityObject3, securityObject4)) {
      String result = getSecurityObjectDescription(obj);
      log.info("Object {} described as: {}", obj.getClass().getSimpleName(), result);
    }
  }

  private String getSecurityObjectDescription(Object obj) {
    return switch (obj) {
      case BearerToken token -> "Bearer token";
      case Cookie cookie when cookie.getName().equals(JwtHelper.JWT_COOKIE_NAME) ->
              "JWT cookie with path: " + cookie.getPath();
      case TestWildcardPermission2 permission -> "Wildcard permission with " + permission.getPartsPublic().size() + " parts";
      case String s -> "String value: " + s;
      case null -> "Null security object";
      default -> "Unknown security object type";
    };
  }

  @Test
  public void testPatternMatchingWithGuardsForPermissions() {
    TestWildcardPermission2 adminPermission = createPermission("admin", "*");
    TestWildcardPermission2 readPermission = createPermission("repository", "read");
    TestWildcardPermission2 emptyPermission = new TestWildcardPermission2();

    for (Permission permission : List.of(adminPermission, readPermission, emptyPermission)) {
      String accessLevel = evaluatePermissionAccess(permission);
      log.info("Permission {} grants access level: {}", permission, accessLevel);
    }
  }

  private String evaluatePermissionAccess(Permission permission) {
    return switch (permission) {
      case TestWildcardPermission2 wp when isAdminPermission(wp) -> "ADMIN";
      case TestWildcardPermission2 wp when isReadPermission(wp) -> "READ";
      case TestWildcardPermission2 wp when wp.getPartsPublic().isEmpty() -> "NONE";
      case Permission p -> "UNKNOWN";
    };
  }

  @Test
  public void testPatternMatchingWithSealedInterfacesForRequests() {
    SecurityRequest adminRequest = new AdminSecurityRequest("/admin/users");
    SecurityRequest userRequest = new UserSecurityRequest("/user/profile");
    SecurityRequest anonymousRequest = new AnonymousSecurityRequest("/public/resources");

    for (SecurityRequest req : List.of(adminRequest, userRequest, anonymousRequest)) {
      String category = categorizeSecurityRequest(req);
      log.info("Request to {} categorized as: {}", req.getPath(), category);
    }
  }

  private String categorizeSecurityRequest(SecurityRequest request) {
    return switch (request) {
      case AdminSecurityRequest r -> "ADMIN_ZONE";
      case UserSecurityRequest r -> "USER_ZONE";
      case AnonymousSecurityRequest r -> "PUBLIC_ZONE";
    };
  }

  @Test
  public void testNestedPatternMatchingForPermissionHierarchies() {
    PermissionContainer container = new PermissionContainer(
            "repository-admin",
            List.of(
                    createPermission("repository", "read"),
                    createPermission("repository", "write"),
                    createPermission("repository", "delete")
            )
    );

    String description = describePermissionContainer(container);
    log.info("Permission container described as: {}", description);

    description = describePermissionContainer(null);
    log.info("Null permission container described as: {}", description);

    description = describePermissionContainer(new PermissionContainer("empty", null));
    log.info("Empty permission container described as: {}", description);
  }

  private String describePermissionContainer(Object obj) {
    return switch (obj) {
      case PermissionContainer pc when pc.permissions() != null && !pc.permissions().isEmpty() ->
              "Container '" + pc.name() + "' with " + pc.permissions().size() + " permissions";
      case PermissionContainer pc -> "Empty container '" + pc.name() + "'";
      case null -> "Null permission container";
      default -> "Not a permission container";
    };
  }

  @Test
  public void testNullHandlingAndExhaustiveness() {
    Object[] testObjects = {new BearerToken(FORMAT), null, "string", 42};

    for (Object obj : testObjects) {
      String result = processSecurityObject(obj);
      log.info("Security object processed as: {}", result);
    }
  }

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

  private TestWildcardPermission2 createPermission(String domain, String action) {
    TestWildcardPermission2 permission = new TestWildcardPermission2();
    permission.setPartsPublic(List.of(domain), List.of(action), true);
    return permission;
  }

  private boolean isAdminPermission(TestWildcardPermission2 permission) {
    List<Set<String>> parts = permission.getPartsPublic();
    return parts.size() >= 2 &&
            parts.get(0).contains("admin") &&
            parts.get(1).contains("*");
  }

  private boolean isReadPermission(TestWildcardPermission2 permission) {
    List<Set<String>> parts = permission.getPartsPublic();
    return parts.size() >= 2 &&
            parts.get(0).contains("repository") &&
            parts.get(1).contains("read");
  }

  // Sealed interface hierarchy for demonstrating exhaustive pattern matching

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

  private record PermissionContainer(String name, List<Permission> permissions) {}
}