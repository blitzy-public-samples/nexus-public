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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.servlet.http.Cookie;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.security.AbstractSecurityTest;
import org.sonatype.nexus.security.JwtHelper;
import org.sonatype.nexus.security.SecuritySystem;
import org.sonatype.nexus.security.authz.AuthorizationManager;
import org.sonatype.nexus.security.jwt.JwtVerificationException;
import org.sonatype.nexus.security.jwt.SecretStore;
import org.sonatype.nexus.security.privilege.NoSuchPrivilegeException;
import org.sonatype.nexus.security.privilege.Privilege;
import org.sonatype.nexus.security.role.NoSuchRoleException;
import org.sonatype.nexus.security.role.Role;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.inject.Provider;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.Subject;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.security.JwtHelper.ISSUER;
import static org.sonatype.nexus.security.JwtHelper.REALM;
import static org.sonatype.nexus.security.JwtHelper.USER;
import static org.sonatype.nexus.security.JwtHelper.USER_SESSION_ID;

/**
 * Comprehensive test class that demonstrates integration of multiple Java 21 features
 * (Virtual Threads, Pattern Matching, Record Patterns, and String Templates) working together
 * in security contexts, validating that these features interact correctly in complex security
 * scenarios and provide tangible benefits for code quality and performance.
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class SecurityJava21FeaturesTest
    extends AbstractSecurityTest
{
  @Mock
  private Subject subject;

  @Mock
  private PrincipalCollection principals;

  @Mock
  private SecretStore secretStore;

  @Mock
  private Provider<SecretStore> storeProvider;

  private JwtHelper jwtHelper;

  private AuthorizationManager authorizationManager;

  // Record definitions for security data representation
  record UserCredentials(String username, String password) {}
  record UserRole(String roleId, String name, Set<String> permissions) {}
  record SecurityToken(String tokenValue, String subject, long expiresAt) {}
  record AuthResult(boolean success, String message, Optional<SecurityToken> token) {}
  record PermissionCheck(String userId, String permission, String resource) {}
  record PermissionResult(boolean granted, String reason) {}

  @Before
  public void setup() throws Exception {
    MockitoAnnotations.openMocks(this);
    
    when(secretStore.getSecret()).thenReturn(Optional.of("secret"));
    when(storeProvider.get()).thenReturn(secretStore);
    jwtHelper = new JwtHelper(300, "/", storeProvider);
    jwtHelper.doStart();
    
    when(subject.getPrincipal()).thenReturn("admin");
    when(subject.getPrincipals()).thenReturn(principals);
    when(principals.getRealmNames()).thenReturn(new HashSet<>(Arrays.asList("NexusAuthorizingRealm")));
    
    authorizationManager = getAuthorizationManager();
  }

  /**
   * Tests the integration of Virtual Threads with Pattern Matching for concurrent authentication processing.
   * This demonstrates how Java 21 features can improve security operation performance while maintaining
   * code readability and maintainability.
   */
  @Test
  public void testConcurrentAuthenticationWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("auth-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    // Prepare test data using records
    List<UserCredentials> credentialsList = Arrays.asList(
        new UserCredentials("admin", "admin123"),
        new UserCredentials("user1", "password1"),
        new UserCredentials("user2", "password2"),
        new UserCredentials("invalid", "wrongpassword")
    );
    
    int taskCount = credentialsList.size();
    CountDownLatch latch = new CountDownLatch(taskCount);
    Map<String, AuthResult> results = new ConcurrentHashMap<>();
    
    // Process authentication concurrently using virtual threads
    for (UserCredentials credentials : credentialsList) {
      executor.submit(() -> {
        try {
          AuthResult result = authenticateUser(credentials);
          results.put(credentials.username(), result);
        } 
        catch (Exception e) {
          results.put(credentials.username(), new AuthResult(false, 
              "Authentication error: " + e.getMessage(), Optional.empty()));
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all authentication tasks to complete
    assertTrue("Authentication tasks did not complete in time", 
        latch.await(5, TimeUnit.SECONDS));
    
    // Verify results using pattern matching for switch
    for (UserCredentials credentials : credentialsList) {
      AuthResult result = results.get(credentials.username());
      assertNotNull("Missing result for user: " + credentials.username(), result);
      
      switch (credentials) {
        case UserCredentials creds when "admin".equals(creds.username()) -> {
          assertTrue("Admin authentication should succeed", result.success());
          assertTrue("Admin should have a token", result.token().isPresent());
        }
        case UserCredentials creds when "invalid".equals(creds.username()) -> {
          assertFalse("Invalid credentials should fail", result.success());
          assertFalse("Invalid credentials should not have a token", result.token().isPresent());
        }
        case UserCredentials creds when creds.username().startsWith("user") -> {
          assertTrue("Regular user authentication should succeed", result.success());
          assertTrue("Regular user should have a token", result.token().isPresent());
        }
        default -> fail("Unexpected credential type: " + credentials);
      }
    }
    
    executor.shutdown();
  }

  /**
   * Tests the integration of Record Patterns with String Templates for security permission evaluation.
   * This demonstrates how Java 21 features can improve code clarity and maintainability in security contexts.
   */
  @Test
  public void testPermissionEvaluationWithRecordPatternsAndStringTemplates() throws Exception {
    // Create test roles with permissions
    Role adminRole = createTestRole("admin-role", "Admin Role", 
        Set.of("admin:read", "admin:write", "repository:*"));
    Role userRole = createTestRole("user-role", "User Role", 
        Set.of("repository:read", "repository:browse"));
    Role guestRole = createTestRole("guest-role", "Guest Role", 
        Set.of("repository:browse"));
    
    // Create permission check scenarios
    List<Object> permissionChecks = Arrays.asList(
        new PermissionCheck("admin", "admin:write", "system"),
        new PermissionCheck("user", "repository:read", "maven-central"),
        new PermissionCheck("guest", "repository:write", "maven-central"),
        new UserRole("admin-role", "Admin Role", Set.of("admin:*")),
        "invalid-check-type"
    );
    
    // Evaluate permissions using record patterns and string templates
    for (Object check : permissionChecks) {
      PermissionResult result = switch (check) {
        case PermissionCheck(String userId, String permission, String resource) -> {
          // Using string template for logging
          String logMessage = STR."Evaluating permission: \{permission} on \{resource} for user \{userId}";
          System.out.println(logMessage);
          
          boolean hasPermission = evaluatePermission(userId, permission, resource);
          String reason = hasPermission ? 
              STR."User \{userId} has permission \{permission} on \{resource}" :
              STR."User \{userId} lacks permission \{permission} on \{resource}";
              
          yield new PermissionResult(hasPermission, reason);
        }
        case UserRole(String roleId, String name, Set<String> permissions) -> {
          // Using string template for role evaluation
          String logMessage = STR."Evaluating role permissions for role \{roleId} (\{name})";
          System.out.println(logMessage);
          
          boolean hasAdminPermission = permissions.stream()
              .anyMatch(p -> p.equals("admin:*") || p.startsWith("admin:"));
              
          String reason = hasAdminPermission ?
              STR."Role \{roleId} has admin permissions" :
              STR."Role \{roleId} lacks admin permissions";
              
          yield new PermissionResult(hasAdminPermission, reason);
        }
        default -> {
          String errorMessage = STR."Invalid permission check type: \{check.getClass().getName()}";
          System.err.println(errorMessage);
          yield new PermissionResult(false, errorMessage);
        }
      };
      
      assertNotNull("Permission result should not be null", result);
      
      // Verify results based on the check type
      if (check instanceof PermissionCheck permCheck) {
        switch (permCheck.userId()) {
          case "admin" -> assertTrue("Admin should have admin permissions", result.granted());
          case "user" -> {
            if ("repository:read".equals(permCheck.permission())) {
              assertTrue("User should have repository read permission", result.granted());
            } else {
              assertFalse("User should not have other permissions", result.granted());
            }
          }
          case "guest" -> {
            if ("repository:write".equals(permCheck.permission())) {
              assertFalse("Guest should not have repository write permission", result.granted());
            }
          }
        }
      } else if (check instanceof UserRole userRole) {
        if ("admin-role".equals(userRole.roleId())) {
          assertTrue("Admin role should have admin permissions", result.granted());
        } else {
          assertFalse("Non-admin roles should not have admin permissions", result.granted());
        }
      } else {
        assertFalse("Invalid check types should not be granted", result.granted());
      }
    }
  }

  /**
   * Tests the integration of all Java 21 features (Virtual Threads, Pattern Matching, Record Patterns,
   * and String Templates) in JWT token processing for security operations.
   */
  @Test
  public void testJwtTokenProcessingWithAllJava21Features() throws Exception {
    // Create a list of test subjects with different roles
    record SubjectInfo(String username, Set<String> roles, Map<String, Object> attributes) {}
    
    List<SubjectInfo> testSubjects = Arrays.asList(
        new SubjectInfo("admin", Set.of("admin", "user"), Map.of("department", "IT", "level", 3)),
        new SubjectInfo("user1", Set.of("user"), Map.of("department", "HR", "level", 1)),
        new SubjectInfo("system", Set.of("system"), Map.of("type", "service-account", "level", 4))
    );
    
    // Process tokens concurrently using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("jwt-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    List<CompletableFuture<SecurityToken>> futures = new ArrayList<>();
    
    for (SubjectInfo subjectInfo : testSubjects) {
      CompletableFuture<SecurityToken> future = CompletableFuture.supplyAsync(() -> {
        // Create JWT token using string templates for claims
        String tokenValue = createJwtToken(subjectInfo);
        
        // Verify and decode the token
        try {
          DecodedJWT decodedJwt = verifyJwtToken(tokenValue);
          return new SecurityToken(
              tokenValue,
              decodedJwt.getClaim(USER).asString(),
              decodedJwt.getExpiresAt().getTime()
          );
        } catch (Exception e) {
          throw new RuntimeException(STR."Token verification failed for \{subjectInfo.username()}: \{e.getMessage()}");
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Wait for all token processing to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0])
    );
    
    allFutures.join();
    
    // Verify token results using record patterns
    for (int i = 0; i < testSubjects.size(); i++) {
      SubjectInfo subjectInfo = testSubjects.get(i);
      SecurityToken token = futures.get(i).get();
      
      assertNotNull("Token should not be null", token);
      assertEquals("Token subject should match username", subjectInfo.username(), token.subject());
      
      // Use pattern matching to verify token attributes based on subject type
      switch (subjectInfo) {
        case SubjectInfo(String username, var roles, var attrs) when roles.contains("admin") -> {
          // Admin token verification
          assertTrue("Admin token should be valid", isTokenValid(token));
          assertEquals("Admin level should be 3", 3, attrs.get("level"));
        }
        case SubjectInfo(String username, var roles, var attrs) when roles.contains("system") -> {
          // System token verification
          assertTrue("System token should be valid", isTokenValid(token));
          assertEquals("System token should be service account", "service-account", attrs.get("type"));
        }
        case SubjectInfo(var username, var roles, var attrs) -> {
          // Regular user token verification
          assertTrue("User token should be valid", isTokenValid(token));
          assertTrue("User level should be less than admin", (Integer)attrs.get("level") < 3);
        }
      }
    }
    
    executor.shutdown();
  }

  /**
   * Tests the performance benefits of Virtual Threads for concurrent security operations,
   * demonstrating the tangible advantages of Java 21 features in security-critical paths.
   */
  @Test
  public void testSecurityOperationPerformanceWithVirtualThreads() throws Exception {
    // Number of concurrent security operations to perform
    int operationCount = 1000;
    
    // Create test data
    List<String> userIds = new ArrayList<>();
    List<String> permissions = Arrays.asList("read", "write", "delete", "admin");
    List<String> resources = Arrays.asList("repo1", "repo2", "system", "users");
    
    for (int i = 0; i < operationCount; i++) {
      userIds.add("user" + i);
    }
    
    // Create permission checks
    List<PermissionCheck> permissionChecks = new ArrayList<>();
    for (int i = 0; i < operationCount; i++) {
      String userId = userIds.get(i % userIds.size());
      String permission = permissions.get(i % permissions.size());
      String resource = resources.get(i % resources.size());
      permissionChecks.add(new PermissionCheck(userId, permission, resource));
    }
    
    // Measure performance with platform threads
    long platformThreadTime = measureExecutionTime(() -> {
      ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
      return executeSecurityChecks(permissionChecks, platformThreadFactory);
    });
    
    // Measure performance with virtual threads
    long virtualThreadTime = measureExecutionTime(() -> {
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      return executeSecurityChecks(permissionChecks, virtualThreadFactory);
    });
    
    System.out.println(STR."Platform thread execution time: \{platformThreadTime}ms");
    System.out.println(STR."Virtual thread execution time: \{virtualThreadTime}ms");
    
    // Virtual threads should be more efficient for this I/O-bound workload
    assertThat("Virtual threads should be faster than platform threads", 
        virtualThreadTime, lessThan(platformThreadTime));
  }

  /**
   * Tests that all Java 21 features work together seamlessly in a complex security workflow.
   */
  @Test
  public void testCompleteSecurityWorkflowWithAllJava21Features() throws Exception {
    // Define a complex security workflow that uses all Java 21 features
    record WorkflowStep(String name, String action, Map<String, Object> parameters) {}
    record WorkflowResult(boolean success, String message, Map<String, Object> outputs) {}
    
    // Create a workflow with multiple steps
    List<WorkflowStep> workflowSteps = Arrays.asList(
        new WorkflowStep("authentication", "authenticate", 
            Map.of("username", "admin", "password", "admin123")),
        new WorkflowStep("authorization", "checkPermission", 
            Map.of("permission", "admin:write", "resource", "system")),
        new WorkflowStep("token-generation", "generateToken", 
            Map.of("subject", "admin", "roles", Set.of("admin"), "ttl", 3600)),
        new WorkflowStep("token-validation", "validateToken", 
            Map.of("tokenParam", "placeholder")) // Will be filled in during execution
    );
    
    // Execute workflow using virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("workflow-", 0).factory();
    ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
    
    Map<String, Object> workflowContext = new HashMap<>();
    List<WorkflowResult> results = new ArrayList<>();
    
    // Process each step and collect results
    for (WorkflowStep step : workflowSteps) {
      CompletableFuture<WorkflowResult> future = CompletableFuture.supplyAsync(() -> {
        // Use string templates for logging
        System.out.println(STR."Executing workflow step: \{step.name()} (\{step.action()})");
        
        // Update parameters with context values if needed
        Map<String, Object> updatedParams = new HashMap<>(step.parameters());
        if ("token-validation".equals(step.name()) && workflowContext.containsKey("token")) {
          updatedParams.put("tokenParam", workflowContext.get("token"));
        }
        
        // Execute the step based on action type using pattern matching
        return switch (step.action()) {
          case "authenticate" -> {
            try {
              String username = (String) updatedParams.get("username");
              String password = (String) updatedParams.get("password");
              UserCredentials credentials = new UserCredentials(username, password);
              
              AuthResult authResult = authenticateUser(credentials);
              if (authResult.success() && authResult.token().isPresent()) {
                workflowContext.put("authenticated", true);
                workflowContext.put("username", username);
                yield new WorkflowResult(true, 
                    STR."Authentication successful for user \{username}", 
                    Map.of("token", authResult.token().get()));
              } else {
                yield new WorkflowResult(false, 
                    STR."Authentication failed for user \{username}: \{authResult.message()}", 
                    Map.of());
              }
            } catch (Exception e) {
              yield new WorkflowResult(false, 
                  STR."Authentication error: \{e.getMessage()}", Map.of());
            }
          }
          case "checkPermission" -> {
            if (!workflowContext.containsKey("authenticated") || 
                !(boolean)workflowContext.get("authenticated")) {
              yield new WorkflowResult(false, "Not authenticated", Map.of());
            }
            
            String username = (String) workflowContext.get("username");
            String permission = (String) updatedParams.get("permission");
            String resource = (String) updatedParams.get("resource");
            
            boolean hasPermission = evaluatePermission(username, permission, resource);
            workflowContext.put("authorized", hasPermission);
            
            if (hasPermission) {
              yield new WorkflowResult(true, 
                  STR."Permission \{permission} granted for \{username} on \{resource}", 
                  Map.of());
            } else {
              yield new WorkflowResult(false, 
                  STR."Permission \{permission} denied for \{username} on \{resource}", 
                  Map.of());
            }
          }
          case "generateToken" -> {
            if (!workflowContext.containsKey("authorized") || 
                !(boolean)workflowContext.get("authorized")) {
              yield new WorkflowResult(false, "Not authorized", Map.of());
            }
            
            String subject = (String) updatedParams.get("subject");
            @SuppressWarnings("unchecked")
            Set<String> roles = (Set<String>) updatedParams.get("roles");
            int ttl = (int) updatedParams.get("ttl");
            
            String token = generateSecurityToken(subject, roles, ttl);
            workflowContext.put("token", token);
            
            yield new WorkflowResult(true, 
                STR."Token generated for \{subject} with TTL \{ttl}s", 
                Map.of("token", token));
          }
          case "validateToken" -> {
            String token = (String) updatedParams.get("tokenParam");
            
            try {
              DecodedJWT decodedJwt = verifyJwtToken(token);
              String subject = decodedJwt.getClaim(USER).asString();
              Date expiresAt = decodedJwt.getExpiresAt();
              
              yield new WorkflowResult(true, 
                  STR."Token validated for \{subject}, expires at \{expiresAt}", 
                  Map.of("subject", subject, "expiresAt", expiresAt));
            } catch (Exception e) {
              yield new WorkflowResult(false, 
                  STR."Token validation failed: \{e.getMessage()}", Map.of());
            }
          }
          default -> new WorkflowResult(false, 
              STR."Unknown action: \{step.action()}", Map.of());
        };
      }, executor);
      
      results.add(future.get()); // Wait for each step to complete
      
      // Update workflow context with step outputs
      if (results.get(results.size() - 1).success()) {
        workflowContext.putAll(results.get(results.size() - 1).outputs());
      } else {
        // If a step fails, log and continue to see how error handling works
        System.err.println(STR."Step \{step.name()} failed: \{results.get(results.size() - 1).message()}");
      }
    }
    
    executor.shutdown();
    
    // Verify workflow results
    assertTrue("Authentication step should succeed", results.get(0).success());
    assertTrue("Authorization step should succeed", results.get(1).success());
    assertTrue("Token generation step should succeed", results.get(2).success());
    assertTrue("Token validation step should succeed", results.get(3).success());
    
    // Verify workflow context
    assertTrue("User should be authenticated", (boolean)workflowContext.get("authenticated"));
    assertTrue("User should be authorized", (boolean)workflowContext.get("authorized"));
    assertNotNull("Token should be generated", workflowContext.get("token"));
    assertEquals("Token subject should be admin", "admin", workflowContext.get("subject"));
  }

  /*
   * Helper methods
   */
  
  private AuthResult authenticateUser(UserCredentials credentials) {
    // Simulate authentication process
    try {
      // In a real implementation, this would use the SecuritySystem
      if ("admin".equals(credentials.username()) && "admin123".equals(credentials.password())) {
        String tokenValue = generateSecurityToken(credentials.username(), Set.of("admin"), 3600);
        SecurityToken token = new SecurityToken(tokenValue, credentials.username(), 
            System.currentTimeMillis() + 3600 * 1000);
        return new AuthResult(true, "Authentication successful", Optional.of(token));
      } else if (credentials.username().startsWith("user") && 
                 credentials.password().startsWith("password")) {
        String tokenValue = generateSecurityToken(credentials.username(), Set.of("user"), 3600);
        SecurityToken token = new SecurityToken(tokenValue, credentials.username(), 
            System.currentTimeMillis() + 3600 * 1000);
        return new AuthResult(true, "Authentication successful", Optional.of(token));
      } else {
        return new AuthResult(false, "Invalid credentials", Optional.empty());
      }
    } catch (Exception e) {
      return new AuthResult(false, "Authentication error: " + e.getMessage(), Optional.empty());
    }
  }

  private boolean evaluatePermission(String userId, String permission, String resource) {
    // Simulate permission evaluation
    return switch (userId) {
      case "admin" -> true; // Admin has all permissions
      case var id when id.startsWith("user") -> {
        // Users have read and browse permissions only
        yield permission.equals("repository:read") || 
              permission.equals("repository:browse") || 
              permission.equals("read");
      }
      case "guest" -> permission.equals("repository:browse") || permission.equals("read");
      default -> false;
    };
  }

  private String createJwtToken(Object subject) {
    // Create a JWT token based on subject type using pattern matching
    return switch (subject) {
      case SubjectInfo(String username, Set<String> roles, Map<String, Object> attributes) -> {
        Date expiresAt = new Date(System.currentTimeMillis() + 3600 * 1000);
        String userSessionId = UUID.randomUUID().toString();
        
        return JWT.create()
            .withIssuer(ISSUER)
            .withExpiresAt(expiresAt)
            .withClaim(USER_SESSION_ID, userSessionId)
            .withClaim(USER, username)
            .withClaim(REALM, "NexusAuthorizingRealm")
            .withClaim("roles", String.join(",", roles))
            .sign(Algorithm.HMAC256("secret"));
      }
      case String username -> {
        Date expiresAt = new Date(System.currentTimeMillis() + 3600 * 1000);
        String userSessionId = UUID.randomUUID().toString();
        
        return JWT.create()
            .withIssuer(ISSUER)
            .withExpiresAt(expiresAt)
            .withClaim(USER_SESSION_ID, userSessionId)
            .withClaim(USER, username)
            .withClaim(REALM, "NexusAuthorizingRealm")
            .sign(Algorithm.HMAC256("secret"));
      }
      default -> throw new IllegalArgumentException("Unsupported subject type: " + subject.getClass().getName());
    };
  }

  private DecodedJWT verifyJwtToken(String token) throws JwtVerificationException {
    try {
      return jwtHelper.verifyJwt(token);
    } catch (Exception e) {
      throw new JwtVerificationException("Token verification failed: " + e.getMessage());
    }
  }

  private boolean isTokenValid(SecurityToken token) {
    return token.expiresAt() > System.currentTimeMillis();
  }

  private String generateSecurityToken(String subject, Set<String> roles, int ttlSeconds) {
    Date expiresAt = new Date(System.currentTimeMillis() + ttlSeconds * 1000);
    String userSessionId = UUID.randomUUID().toString();
    
    return JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(expiresAt)
        .withClaim(USER_SESSION_ID, userSessionId)
        .withClaim(USER, subject)
        .withClaim(REALM, "NexusAuthorizingRealm")
        .withClaim("roles", String.join(",", roles))
        .sign(Algorithm.HMAC256("secret"));
  }

  private Role createTestRole(String roleId, String name, Set<String> privileges) throws Exception {
    Role role = new Role();
    role.setRoleId(roleId);
    role.setName(name);
    role.setDescription("Test role: " + name);
    role.setPrivileges(privileges);
    
    try {
      authorizationManager.addRole(role);
    } catch (Exception e) {
      // Role might already exist, try to update it
      try {
        authorizationManager.updateRole(role);
      } catch (NoSuchRoleException nsre) {
        throw new RuntimeException("Could not create or update role: " + roleId, nsre);
      }
    }
    
    return role;
  }

  private long measureExecutionTime(Supplier<Void> task) {
    Instant start = Instant.now();
    task.get();
    Instant end = Instant.now();
    return Duration.between(start, end).toMillis();
  }

  private Void executeSecurityChecks(List<PermissionCheck> checks, ThreadFactory threadFactory) {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    int checkCount = checks.size();
    CountDownLatch latch = new CountDownLatch(checkCount);
    AtomicInteger successCount = new AtomicInteger(0);
    
    for (PermissionCheck check : checks) {
      executor.submit(() -> {
        try {
          // Simulate some I/O or processing delay
          Thread.sleep(5);
          
          boolean result = evaluatePermission(check.userId(), check.permission(), check.resource());
          if (result) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          System.err.println(STR."Error in security check: \{e.getMessage()}");
        } finally {
          latch.countDown();
        }
      });
    }
    
    try {
      latch.await(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Security checks interrupted", e);
    } finally {
      executor.shutdown();
    }
    
    return null;
  }

  private AuthorizationManager getAuthorizationManager() throws Exception {
    return this.lookup(AuthorizationManager.class);
  }
}