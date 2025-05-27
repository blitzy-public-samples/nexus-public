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

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.repository.types.GroupType;
import org.sonatype.nexus.repository.types.HostedType;
import org.sonatype.nexus.repository.types.ProxyType;
import org.sonatype.nexus.security.SecurityHelper;
import org.sonatype.nexus.security.privilege.Privilege;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 Pattern Matching for switch statements in CoreUI components.
 * 
 * This test class validates the implementation of Java 21's Pattern Matching for switch statements
 * in CoreUI components. It tests various pattern matching scenarios including type patterns,
 * guarded patterns, and null handling in switch expressions.
 */
@ExtendWith(MockitoExtension.class)
public class PatternMatchingTest extends TestSupport
{
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private SecurityHelper securityHelper;
  
  @Mock
  private Repository hostedRepository;
  
  @Mock
  private Repository proxyRepository;
  
  @Mock
  private Repository groupRepository;
  
  private RepositoryTypeHandler repositoryTypeHandler;
  private ContentFormatDetector contentFormatDetector;
  private PermissionEvaluator permissionEvaluator;
  
  @BeforeEach
  void setUp() {
    // Set up repository types
    when(hostedRepository.getType()).thenReturn(new HostedType());
    when(proxyRepository.getType()).thenReturn(new ProxyType());
    when(groupRepository.getType()).thenReturn(new GroupType());
    
    // Initialize test classes that use pattern matching
    repositoryTypeHandler = new RepositoryTypeHandler(repositoryManager);
    contentFormatDetector = new ContentFormatDetector();
    permissionEvaluator = new PermissionEvaluator(securityHelper);
  }
  
  @Test
  @DisplayName("Test type pattern matching for repository types")
  void testTypePatternMatchingForRepositoryTypes() {
    // Test hosted repository type pattern matching
    String hostedResult = repositoryTypeHandler.getRepositoryTypeDescription(hostedRepository);
    assertThat(hostedResult, is(equalTo("Hosted Repository")));
    
    // Test proxy repository type pattern matching
    String proxyResult = repositoryTypeHandler.getRepositoryTypeDescription(proxyRepository);
    assertThat(proxyResult, is(equalTo("Proxy Repository")));
    
    // Test group repository type pattern matching
    String groupResult = repositoryTypeHandler.getRepositoryTypeDescription(groupRepository);
    assertThat(groupResult, is(equalTo("Group Repository")));
  }
  
  @Test
  @DisplayName("Test null handling in pattern matching switch expressions")
  void testNullHandlingInPatternMatching() {
    // Test null repository handling
    String nullResult = repositoryTypeHandler.getRepositoryTypeDescription(null);
    assertThat(nullResult, is(equalTo("Unknown Repository Type")));
    
    // Test null content format handling
    String nullFormatResult = contentFormatDetector.detectContentType(null);
    assertThat(nullFormatResult, is(equalTo("Unknown Format")));
  }
  
  @Test
  @DisplayName("Test pattern matching for content format detection")
  void testPatternMatchingForContentFormatDetection() {
    // Test Maven format detection
    String mavenResult = contentFormatDetector.detectContentType("pom.xml");
    assertThat(mavenResult, is(equalTo("Maven")));
    
    // Test NPM format detection
    String npmResult = contentFormatDetector.detectContentType("package.json");
    assertThat(npmResult, is(equalTo("NPM")));
    
    // Test Docker format detection
    String dockerResult = contentFormatDetector.detectContentType("Dockerfile");
    assertThat(dockerResult, is(equalTo("Docker")));
    
    // Test Raw format detection
    String rawResult = contentFormatDetector.detectContentType("data.bin");
    assertThat(rawResult, is(equalTo("Raw")));
  }
  
  @Test
  @DisplayName("Test guarded patterns for permission evaluation")
  void testGuardedPatternsForPermissionEvaluation() {
    // Mock privileges
    Privilege adminPrivilege = mock(Privilege.class);
    when(adminPrivilege.getType()).thenReturn("application");
    when(adminPrivilege.getPermission()).thenReturn("nexus:*:*");
    
    Privilege readPrivilege = mock(Privilege.class);
    when(readPrivilege.getType()).thenReturn("repository-view");
    when(readPrivilege.getPermission()).thenReturn("nexus:repository-view:*:read");
    
    Privilege invalidPrivilege = mock(Privilege.class);
    when(invalidPrivilege.getType()).thenReturn("unknown");
    when(invalidPrivilege.getPermission()).thenReturn("invalid");
    
    // Test admin privilege evaluation with guarded pattern
    String adminResult = permissionEvaluator.evaluatePermission(adminPrivilege);
    assertThat(adminResult, is(equalTo("Admin Access")));
    
    // Test read privilege evaluation with guarded pattern
    String readResult = permissionEvaluator.evaluatePermission(readPrivilege);
    assertThat(readResult, is(equalTo("Read Access")));
    
    // Test invalid privilege evaluation with guarded pattern
    String invalidResult = permissionEvaluator.evaluatePermission(invalidPrivilege);
    assertThat(invalidResult, is(equalTo("Unknown Access")));
    
    // Test null privilege evaluation
    String nullResult = permissionEvaluator.evaluatePermission(null);
    assertThat(nullResult, is(equalTo("No Access")));
  }
  
  /**
   * Test class that uses pattern matching for switch statements to handle repository types.
   * This demonstrates the use of type patterns in switch expressions.
   */
  private static class RepositoryTypeHandler {
    private final RepositoryManager repositoryManager;
    
    public RepositoryTypeHandler(RepositoryManager repositoryManager) {
      this.repositoryManager = repositoryManager;
    }
    
    /**
     * Gets a description of the repository type using pattern matching for switch.
     * Demonstrates type pattern matching with instanceof checks in switch expressions.
     */
    public String getRepositoryTypeDescription(Repository repository) {
      return switch (repository) {
        case null -> "Unknown Repository Type";
        case Repository r when r.getType() instanceof HostedType -> "Hosted Repository";
        case Repository r when r.getType() instanceof ProxyType -> "Proxy Repository";
        case Repository r when r.getType() instanceof GroupType -> "Group Repository";
        default -> "Other Repository Type";
      };
    }
  }
  
  /**
   * Test class that uses pattern matching for switch statements to detect content formats.
   * This demonstrates the use of string patterns and case guards in switch expressions.
   */
  private static class ContentFormatDetector {
    /**
     * Detects the content format based on filename using pattern matching for switch.
     * Demonstrates string pattern matching with case guards in switch expressions.
     */
    public String detectContentType(String filename) {
      return switch (filename) {
        case null -> "Unknown Format";
        case String s when s.endsWith(".xml") && s.contains("pom") -> "Maven";
        case String s when s.equals("package.json") -> "NPM";
        case String s when s.equals("Dockerfile") || s.endsWith(".dockerfile") -> "Docker";
        case String s when s.endsWith(".jar") || s.endsWith(".war") -> "Java Archive";
        case String s when s.endsWith(".rpm") -> "RPM";
        case String s when s.endsWith(".deb") -> "Debian";
        case String s when s.endsWith(".nuget") || s.endsWith(".nupkg") -> "NuGet";
        case String s when s.endsWith(".gem") -> "RubyGems";
        case String s when s.endsWith(".py") || s.endsWith(".whl") -> "PyPI";
        default -> "Raw";
      };
    }
  }
  
  /**
   * Test class that uses pattern matching for switch statements to evaluate permissions.
   * This demonstrates the use of guarded patterns in switch expressions.
   */
  private static class PermissionEvaluator {
    private final SecurityHelper securityHelper;
    
    public PermissionEvaluator(SecurityHelper securityHelper) {
      this.securityHelper = securityHelper;
    }
    
    /**
     * Evaluates a privilege using pattern matching for switch with guards.
     * Demonstrates guarded pattern matching in switch expressions.
     */
    public String evaluatePermission(Privilege privilege) {
      return switch (privilege) {
        case null -> "No Access";
        case Privilege p when "application".equals(p.getType()) && p.getPermission().contains("nexus:*:*") -> "Admin Access";
        case Privilege p when "repository-view".equals(p.getType()) && p.getPermission().contains(":read") -> "Read Access";
        case Privilege p when "repository-view".equals(p.getType()) && p.getPermission().contains(":browse") -> "Browse Access";
        case Privilege p when "repository-view".equals(p.getType()) && p.getPermission().contains(":add") -> "Add Access";
        case Privilege p when "repository-view".equals(p.getType()) && p.getPermission().contains(":edit") -> "Edit Access";
        case Privilege p when "repository-view".equals(p.getType()) && p.getPermission().contains(":delete") -> "Delete Access";
        default -> "Unknown Access";
      };
    }
  }
}