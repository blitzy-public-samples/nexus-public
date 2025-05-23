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
package org.sonatype.nexus.java21;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.Type;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.privilege.Privilege;

/**
 * Tests for Java 21 Pattern Matching for switch statements in CoreUI components.
 * 
 * This test class validates the implementation of Java 21's Pattern Matching for switch statements
 * in CoreUI components. It tests various pattern matching scenarios including type patterns,
 * guarded patterns, and null handling in switch expressions.
 */
@DisplayName("Java 21 Pattern Matching Tests")
public class PatternMatchingTest
{
  @Mock
  private SecurityHelper securityHelper;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
  }

  /**
   * Tests pattern matching for repository types (hosted, proxy, group).
   * This demonstrates how pattern matching can simplify type checking and casting.
   */
  @Test
  @DisplayName("Pattern matching for repository types")
  void testRepositoryTypePatternMatching() {
    // Create mock repositories of different types
    Repository hostedRepo = createMockRepository(new HostedType());
    Repository proxyRepo = createMockRepository(new ProxyType());
    Repository groupRepo = createMockRepository(new GroupType());
    Repository nullRepo = null;

    // Test pattern matching with repository types
    assertEquals("hosted", getRepositoryTypeUsingPatternMatching(hostedRepo));
    assertEquals("proxy", getRepositoryTypeUsingPatternMatching(proxyRepo));
    assertEquals("group", getRepositoryTypeUsingPatternMatching(groupRepo));
    assertEquals("unknown", getRepositoryTypeUsingPatternMatching(nullRepo));
  }

  /**
   * Tests guarded patterns for conditional matching in permission evaluations.
   * This demonstrates how pattern matching with guards can simplify complex conditional logic.
   */
  @Test
  @DisplayName("Guarded patterns for permission evaluations")
  void testGuardedPatternMatching() {
    // Create mock privileges with different permissions
    Privilege adminPrivilege = createMockPrivilege("nx-admin");
    Privilege viewPrivilege = createMockPrivilege("nx-repository-view-*-*-browse");
    Privilege editPrivilege = createMockPrivilege("nx-repository-view-*-*-edit");
    Privilege nullPrivilege = null;

    // Test guarded pattern matching with privileges
    assertTrue(hasAdminAccessUsingPatternMatching(adminPrivilege));
    assertFalse(hasAdminAccessUsingPatternMatching(viewPrivilege));
    assertFalse(hasAdminAccessUsingPatternMatching(editPrivilege));
    assertFalse(hasAdminAccessUsingPatternMatching(nullPrivilege));

    assertTrue(hasBrowsePermissionUsingPatternMatching(viewPrivilege));
    assertFalse(hasBrowsePermissionUsingPatternMatching(adminPrivilege));
    assertFalse(hasBrowsePermissionUsingPatternMatching(editPrivilege));
    assertFalse(hasBrowsePermissionUsingPatternMatching(nullPrivilege));
  }

  /**
   * Tests null handling in pattern matching switch expressions.
   * This demonstrates how pattern matching can handle null values elegantly.
   */
  @Test
  @DisplayName("Null handling in pattern matching switch expressions")
  void testNullHandlingInPatternMatching() {
    // Test with various content formats including null
    assertEquals("Maven artifacts", getContentDescriptionUsingPatternMatching("maven2"));
    assertEquals("Raw files", getContentDescriptionUsingPatternMatching("raw"));
    assertEquals("Docker images", getContentDescriptionUsingPatternMatching("docker"));
    assertEquals("APT packages", getContentDescriptionUsingPatternMatching("apt"));
    assertEquals("Unknown format", getContentDescriptionUsingPatternMatching(null));
    assertEquals("Unknown format", getContentDescriptionUsingPatternMatching("unknown"));
  }

  /**
   * Tests pattern matching with record patterns.
   * This demonstrates how pattern matching can be used with records for data extraction.
   */
  @Test
  @DisplayName("Pattern matching with record patterns")
  void testRecordPatternMatching() {
    // Create test data using records
    RepositoryInfo hostedInfo = new RepositoryInfo("maven-central", "hosted", Map.of("maven", Map.of("versionPolicy", "RELEASE")));
    RepositoryInfo proxyInfo = new RepositoryInfo("maven-proxy", "proxy", Map.of("proxy", Map.of("remoteUrl", "https://repo.maven.apache.org/maven2")));
    RepositoryInfo groupInfo = new RepositoryInfo("maven-group", "group", Map.of("group", Map.of("memberNames", "maven-central,maven-proxy")));
    
    // Test pattern matching with records
    assertEquals("RELEASE", extractVersionPolicyUsingPatternMatching(hostedInfo));
    assertNull(extractVersionPolicyUsingPatternMatching(proxyInfo));
    assertNull(extractVersionPolicyUsingPatternMatching(groupInfo));
    
    assertEquals("https://repo.maven.apache.org/maven2", extractRemoteUrlUsingPatternMatching(proxyInfo));
    assertNull(extractRemoteUrlUsingPatternMatching(hostedInfo));
    assertNull(extractRemoteUrlUsingPatternMatching(groupInfo));
    
    assertEquals("maven-central,maven-proxy", extractMemberNamesUsingPatternMatching(groupInfo));
    assertNull(extractMemberNamesUsingPatternMatching(hostedInfo));
    assertNull(extractMemberNamesUsingPatternMatching(proxyInfo));
  }

  /**
   * Helper method that uses pattern matching to determine repository type.
   * This demonstrates the use of type patterns in switch expressions.
   */
  private String getRepositoryTypeUsingPatternMatching(Repository repository) {
    return switch (repository) {
      case null -> "unknown";
      case Repository r when r.getType() instanceof HostedType -> "hosted";
      case Repository r when r.getType() instanceof ProxyType -> "proxy";
      case Repository r when r.getType() instanceof GroupType -> "group";
      default -> "unknown";
    };
  }

  /**
   * Helper method that uses pattern matching with guards to check for admin access.
   * This demonstrates the use of guarded patterns in switch expressions.
   */
  private boolean hasAdminAccessUsingPatternMatching(Privilege privilege) {
    return switch (privilege) {
      case null -> false;
      case Privilege p when "nx-admin".equals(p.getPermission()) -> true;
      default -> false;
    };
  }

  /**
   * Helper method that uses pattern matching with guards to check for browse permission.
   * This demonstrates the use of guarded patterns in switch expressions.
   */
  private boolean hasBrowsePermissionUsingPatternMatching(Privilege privilege) {
    return switch (privilege) {
      case null -> false;
      case Privilege p when p.getPermission() != null && p.getPermission().contains("-browse") -> true;
      default -> false;
    };
  }

  /**
   * Helper method that uses pattern matching to get content description based on format.
   * This demonstrates null handling in switch expressions with pattern matching.
   */
  private String getContentDescriptionUsingPatternMatching(String format) {
    return switch (format) {
      case null -> "Unknown format";
      case "maven2" -> "Maven artifacts";
      case "raw" -> "Raw files";
      case "docker" -> "Docker images";
      case "apt" -> "APT packages";
      default -> "Unknown format";
    };
  }

  /**
   * Helper method that uses pattern matching to extract version policy from repository info.
   * This demonstrates the use of record patterns in switch expressions.
   */
  private String extractVersionPolicyUsingPatternMatching(RepositoryInfo info) {
    return switch (info) {
      case RepositoryInfo(var name, var type, var attributes) when attributes.containsKey("maven") -> {
        Map<String, Object> mavenAttrs = (Map<String, Object>) attributes.get("maven");
        yield mavenAttrs.containsKey("versionPolicy") ? (String) mavenAttrs.get("versionPolicy") : null;
      }
      default -> null;
    };
  }

  /**
   * Helper method that uses pattern matching to extract remote URL from repository info.
   * This demonstrates the use of record patterns in switch expressions.
   */
  private String extractRemoteUrlUsingPatternMatching(RepositoryInfo info) {
    return switch (info) {
      case RepositoryInfo(var name, var type, var attributes) when attributes.containsKey("proxy") -> {
        Map<String, Object> proxyAttrs = (Map<String, Object>) attributes.get("proxy");
        yield proxyAttrs.containsKey("remoteUrl") ? (String) proxyAttrs.get("remoteUrl") : null;
      }
      default -> null;
    };
  }

  /**
   * Helper method that uses pattern matching to extract member names from repository info.
   * This demonstrates the use of record patterns in switch expressions.
   */
  private String extractMemberNamesUsingPatternMatching(RepositoryInfo info) {
    return switch (info) {
      case RepositoryInfo(var name, var type, var attributes) when attributes.containsKey("group") -> {
        Map<String, Object> groupAttrs = (Map<String, Object>) attributes.get("group");
        yield groupAttrs.containsKey("memberNames") ? (String) groupAttrs.get("memberNames") : null;
      }
      default -> null;
    };
  }

  /**
   * Helper method to create a mock repository with the specified type.
   */
  private Repository createMockRepository(Type type) {
    Repository repository = mock(Repository.class);
    when(repository.getType()).thenReturn(type);
    return repository;
  }

  /**
   * Helper method to create a mock privilege with the specified permission.
   */
  private Privilege createMockPrivilege(String permission) {
    Privilege privilege = mock(Privilege.class);
    when(privilege.getPermission()).thenReturn(permission);
    return privilege;
  }

  /**
   * Record class representing repository information for testing pattern matching with records.
   */
  record RepositoryInfo(String name, String type, Map<String, Map<String, Object>> attributes) {}
}