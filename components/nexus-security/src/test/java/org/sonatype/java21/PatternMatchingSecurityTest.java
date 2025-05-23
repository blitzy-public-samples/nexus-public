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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.security.authc.NexusAuthenticationEvent;
import org.sonatype.nexus.security.authc.LoginEvent;
import org.sonatype.nexus.security.authc.LogoutEvent;
import org.sonatype.nexus.security.authc.AuthenticationFailureReason;

import org.apache.shiro.authz.Permission;
import org.apache.shiro.authz.permission.WildcardPermission;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.apache.shiro.authc.BearerToken;
import org.apache.shiro.subject.PrincipalCollection;
import org.apache.shiro.subject.SimplePrincipalCollection;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Test class demonstrating Java 21's pattern matching for switch statements in security contexts.
 * 
 * This class shows how pattern matching can improve code maintainability and provide more
 * robust type handling for security-related operations like permission evaluation,
 * authentication token processing, and role checking.
 */
public class PatternMatchingSecurityTest
    extends TestSupport
{
  /**
   * Demonstrates the traditional approach to handling different permission types
   * using instanceof checks and casting.
   */
  @Test
  public void testTraditionalPermissionHandling() {
    Permission wildcardPermission = new WildcardPermission("nexus:repository:maven:read");
    Permission customPermission = new CustomPermission("repository:maven:read", true);
    
    String description1 = getPermissionDescriptionTraditional(wildcardPermission);
    String description2 = getPermissionDescriptionTraditional(customPermission);
    
    assertEquals("WildcardPermission with 4 parts", description1);
    assertEquals("CustomPermission with domain repository:maven:read (case sensitive)", description2);
  }
  
  /**
   * Demonstrates using Java 21's pattern matching for switch to handle different permission types
   * in a more concise and maintainable way.
   */
  @Test
  public void testPatternMatchingForPermissions() {
    Permission wildcardPermission = new WildcardPermission("nexus:repository:maven:read");
    Permission customPermission = new CustomPermission("repository:maven:read", true);
    
    String description1 = getPermissionDescriptionWithPatternMatching(wildcardPermission);
    String description2 = getPermissionDescriptionWithPatternMatching(customPermission);
    
    assertEquals("WildcardPermission with 4 parts", description1);
    assertEquals("CustomPermission with domain repository:maven:read (case sensitive)", description2);
  }
  
  /**
   * Traditional approach using instanceof checks and casting.
   */
  private String getPermissionDescriptionTraditional(Permission permission) {
    if (permission instanceof WildcardPermission) {
      WildcardPermission wp = (WildcardPermission) permission;
      return "WildcardPermission with " + wp.getParts().size() + " parts";
    } else if (permission instanceof CustomPermission) {
      CustomPermission cp = (CustomPermission) permission;
      return "CustomPermission with domain " + cp.getDomain() + 
          (cp.isCaseSensitive() ? " (case sensitive)" : " (case insensitive)");
    } else {
      return "Unknown permission type: " + permission.getClass().getSimpleName();
    }
  }
  
  /**
   * Java 21 approach using pattern matching for switch.
   */
  private String getPermissionDescriptionWithPatternMatching(Permission permission) {
    return switch (permission) {
      case WildcardPermission wp -> "WildcardPermission with " + wp.getParts().size() + " parts";
      case CustomPermission cp -> "CustomPermission with domain " + cp.getDomain() + 
          (cp.isCaseSensitive() ? " (case sensitive)" : " (case insensitive)");
      default -> "Unknown permission type: " + permission.getClass().getSimpleName();
    };
  }
  
  /**
   * Demonstrates the traditional approach to handling different authentication token types
   * using instanceof checks and casting.
   */
  @Test
  public void testTraditionalAuthTokenHandling() {
    AuthenticationToken usernamePasswordToken = new UsernamePasswordToken("admin", "password123".toCharArray());
    AuthenticationToken bearerToken = new BearerToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...");
    AuthenticationToken apiKeyToken = new ApiKeyToken("api-key-12345");
    
    String tokenInfo1 = getAuthTokenInfoTraditional(usernamePasswordToken);
    String tokenInfo2 = getAuthTokenInfoTraditional(bearerToken);
    String tokenInfo3 = getAuthTokenInfoTraditional(apiKeyToken);
    
    assertEquals("Username/Password token for user: admin", tokenInfo1);
    assertEquals("Bearer token starting with: eyJhbG", tokenInfo2);
    assertEquals("API Key token: api-key-12345", tokenInfo3);
  }
  
  /**
   * Demonstrates using Java 21's pattern matching for switch to handle different authentication token types
   * in a more concise and maintainable way.
   */
  @Test
  public void testPatternMatchingForAuthTokens() {
    AuthenticationToken usernamePasswordToken = new UsernamePasswordToken("admin", "password123".toCharArray());
    AuthenticationToken bearerToken = new BearerToken("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...");
    AuthenticationToken apiKeyToken = new ApiKeyToken("api-key-12345");
    
    String tokenInfo1 = getAuthTokenInfoWithPatternMatching(usernamePasswordToken);
    String tokenInfo2 = getAuthTokenInfoWithPatternMatching(bearerToken);
    String tokenInfo3 = getAuthTokenInfoWithPatternMatching(apiKeyToken);
    
    assertEquals("Username/Password token for user: admin", tokenInfo1);
    assertEquals("Bearer token starting with: eyJhbG", tokenInfo2);
    assertEquals("API Key token: api-key-12345", tokenInfo3);
  }
  
  /**
   * Traditional approach using instanceof checks and casting.
   */
  private String getAuthTokenInfoTraditional(AuthenticationToken token) {
    if (token instanceof UsernamePasswordToken) {
      UsernamePasswordToken upToken = (UsernamePasswordToken) token;
      return "Username/Password token for user: " + upToken.getUsername();
    } else if (token instanceof BearerToken) {
      BearerToken bToken = (BearerToken) token;
      String tokenValue = bToken.getToken();
      return "Bearer token starting with: " + tokenValue.substring(0, Math.min(6, tokenValue.length()));
    } else if (token instanceof ApiKeyToken) {
      ApiKeyToken akToken = (ApiKeyToken) token;
      return "API Key token: " + akToken.getApiKey();
    } else {
      return "Unknown token type: " + token.getClass().getSimpleName();
    }
  }
  
  /**
   * Java 21 approach using pattern matching for switch.
   */
  private String getAuthTokenInfoWithPatternMatching(AuthenticationToken token) {
    return switch (token) {
      case UsernamePasswordToken upToken -> "Username/Password token for user: " + upToken.getUsername();
      case BearerToken bToken -> {
        String tokenValue = bToken.getToken();
        yield "Bearer token starting with: " + tokenValue.substring(0, Math.min(6, tokenValue.length()));
      }
      case ApiKeyToken akToken -> "API Key token: " + akToken.getApiKey();
      default -> "Unknown token type: " + token.getClass().getSimpleName();
    };
  }
  
  /**
   * Demonstrates the traditional approach to handling different security event types
   * using instanceof checks and casting.
   */
  @Test
  public void testTraditionalSecurityEventHandling() {
    PrincipalCollection principals = new SimplePrincipalCollection("admin", "nexus");
    NexusAuthenticationEvent loginEvent = new LoginEvent(principals);
    NexusAuthenticationEvent logoutEvent = new LogoutEvent(principals);
    NexusAuthenticationEvent failureEvent = new AuthenticationFailureEvent(
        "user", AuthenticationFailureReason.INCORRECT_CREDENTIALS);
    
    String eventInfo1 = getSecurityEventInfoTraditional(loginEvent);
    String eventInfo2 = getSecurityEventInfoTraditional(logoutEvent);
    String eventInfo3 = getSecurityEventInfoTraditional(failureEvent);
    
    assertEquals("Login event for user: admin", eventInfo1);
    assertEquals("Logout event for user: admin", eventInfo2);
    assertEquals("Authentication failure: INCORRECT_CREDENTIALS for user: user", eventInfo3);
  }
  
  /**
   * Demonstrates using Java 21's pattern matching for switch to handle different security event types
   * in a more concise and maintainable way.
   */
  @Test
  public void testPatternMatchingForSecurityEvents() {
    PrincipalCollection principals = new SimplePrincipalCollection("admin", "nexus");
    NexusAuthenticationEvent loginEvent = new LoginEvent(principals);
    NexusAuthenticationEvent logoutEvent = new LogoutEvent(principals);
    NexusAuthenticationEvent failureEvent = new AuthenticationFailureEvent(
        "user", AuthenticationFailureReason.INCORRECT_CREDENTIALS);
    
    String eventInfo1 = getSecurityEventInfoWithPatternMatching(loginEvent);
    String eventInfo2 = getSecurityEventInfoWithPatternMatching(logoutEvent);
    String eventInfo3 = getSecurityEventInfoWithPatternMatching(failureEvent);
    
    assertEquals("Login event for user: admin", eventInfo1);
    assertEquals("Logout event for user: admin", eventInfo2);
    assertEquals("Authentication failure: INCORRECT_CREDENTIALS for user: user", eventInfo3);
  }
  
  /**
   * Traditional approach using instanceof checks and casting.
   */
  private String getSecurityEventInfoTraditional(NexusAuthenticationEvent event) {
    if (event instanceof LoginEvent) {
      LoginEvent loginEvent = (LoginEvent) event;
      return "Login event for user: " + getPrincipalId(loginEvent.getPrincipals());
    } else if (event instanceof LogoutEvent) {
      LogoutEvent logoutEvent = (LogoutEvent) event;
      return "Logout event for user: " + getPrincipalId(logoutEvent.getPrincipals());
    } else if (event instanceof AuthenticationFailureEvent) {
      AuthenticationFailureEvent failureEvent = (AuthenticationFailureEvent) event;
      return "Authentication failure: " + failureEvent.getReason() + 
          " for user: " + failureEvent.getUserId();
    } else {
      return "Unknown event type: " + event.getClass().getSimpleName();
    }
  }
  
  /**
   * Java 21 approach using pattern matching for switch.
   */
  private String getSecurityEventInfoWithPatternMatching(NexusAuthenticationEvent event) {
    return switch (event) {
      case LoginEvent loginEvent -> "Login event for user: " + getPrincipalId(loginEvent.getPrincipals());
      case LogoutEvent logoutEvent -> "Logout event for user: " + getPrincipalId(logoutEvent.getPrincipals());
      case AuthenticationFailureEvent failureEvent -> 
          "Authentication failure: " + failureEvent.getReason() + 
          " for user: " + failureEvent.getUserId();
      default -> "Unknown event type: " + event.getClass().getSimpleName();
    };
  }
  
  /**
   * Demonstrates pattern matching with guarded patterns using the 'when' clause.
   */
  @Test
  public void testPatternMatchingWithGuardedPatterns() {
    Permission readPermission = new WildcardPermission("nexus:repository:maven:read");
    Permission writePermission = new WildcardPermission("nexus:repository:maven:write");
    Permission adminPermission = new WildcardPermission("nexus:*:*:*");
    
    String accessLevel1 = getAccessLevelWithGuardedPatterns(readPermission);
    String accessLevel2 = getAccessLevelWithGuardedPatterns(writePermission);
    String accessLevel3 = getAccessLevelWithGuardedPatterns(adminPermission);
    
    assertEquals("READ access", accessLevel1);
    assertEquals("WRITE access", accessLevel2);
    assertEquals("ADMIN access", accessLevel3);
  }
  
  /**
   * Java 21 approach using pattern matching for switch with guarded patterns.
   */
  private String getAccessLevelWithGuardedPatterns(Permission permission) {
    return switch (permission) {
      case WildcardPermission wp when wp.toString().contains(":read") -> "READ access";
      case WildcardPermission wp when wp.toString().contains(":write") -> "WRITE access";
      case WildcardPermission wp when wp.toString().contains(":*:*") -> "ADMIN access";
      default -> "UNKNOWN access";
    };
  }
  
  /**
   * Helper method to extract the principal ID from a PrincipalCollection.
   */
  private String getPrincipalId(PrincipalCollection principals) {
    return principals.getPrimaryPrincipal().toString();
  }
  
  /**
   * Custom permission class for testing.
   */
  private static class CustomPermission implements Permission {
    private final String domain;
    private final boolean caseSensitive;
    
    public CustomPermission(String domain, boolean caseSensitive) {
      this.domain = domain;
      this.caseSensitive = caseSensitive;
    }
    
    public String getDomain() {
      return domain;
    }
    
    public boolean isCaseSensitive() {
      return caseSensitive;
    }
    
    @Override
    public boolean implies(Permission p) {
      return false; // Simplified for this example
    }
  }
  
  /**
   * Custom API key token class for testing.
   */
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
      return apiKey;
    }
    
    @Override
    public Object getCredentials() {
      return apiKey;
    }
  }
  
  /**
   * Custom authentication failure event class for testing.
   */
  private static class AuthenticationFailureEvent extends NexusAuthenticationEvent {
    private final String userId;
    private final AuthenticationFailureReason reason;
    
    public AuthenticationFailureEvent(String userId, AuthenticationFailureReason reason) {
      this.userId = userId;
      this.reason = reason;
    }
    
    public String getUserId() {
      return userId;
    }
    
    public AuthenticationFailureReason getReason() {
      return reason;
    }
  }
}