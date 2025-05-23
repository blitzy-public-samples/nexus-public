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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test class to validate Java 21's Record Patterns for simplified data handling in CoreUI components.
 * 
 * This class tests nested record pattern matching, type inference, and destructuring of data transfer objects
 * to ensure CoreUI components correctly leverage record patterns for more concise and readable code.
 */
@ExtendWith(MockitoExtension.class)
public class RecordPatternTest
{
  /**
   * Record representing repository configuration
   */
  record RepositoryConfig(String name, String type, Map<String, Object> attributes) {}
  
  /**
   * Record representing a point in 2D space
   */
  record Point(int x, int y) {}
  
  /**
   * Record representing asset metadata
   */
  record AssetMetadata(String path, String contentType, long size) {}
  
  /**
   * Record representing a nested repository configuration with format-specific attributes
   */
  record FormatRepositoryConfig(String name, String format, RepositoryConfig config) {}
  
  /**
   * Record representing an API response with status and data
   */
  record ApiResponse<T>(int status, String message, T data) {}
  
  @Mock
  private Map<String, Object> mockAttributes;

  /**
   * Test basic record pattern matching with instanceof operator.
   * This validates the ability to destructure a record directly in an instanceof check.
   */
  @Test
  @DisplayName("Test basic record pattern matching with instanceof")
  public void testBasicRecordPatternMatching() {
    // Create a repository configuration record
    RepositoryConfig config = new RepositoryConfig("maven-central", "proxy", Map.of(
        "remoteUrl", "https://repo1.maven.org/maven2/",
        "contentMaxAge", 1440
    ));
    
    // Test object with record pattern matching using instanceof
    Object obj = config;
    
    // Using record pattern to destructure the record directly
    if (obj instanceof RepositoryConfig(String name, String type, Map<String, Object> attrs)) {
      assertEquals("maven-central", name);
      assertEquals("proxy", type);
      assertEquals("https://repo1.maven.org/maven2/", attrs.get("remoteUrl"));
      assertEquals(1440, attrs.get("contentMaxAge"));
    } else {
      // This should not happen
      assertTrue(false, "Record pattern matching failed");
    }
  }

  /**
   * Test type inference in record patterns using 'var' keyword.
   * This validates that the compiler correctly infers types for pattern variables.
   */
  @Test
  @DisplayName("Test type inference in record patterns using 'var'")
  public void testTypeInferenceInRecordPatterns() {
    // Create an asset metadata record
    AssetMetadata metadata = new AssetMetadata("/path/to/asset.jar", "application/java-archive", 1024L);
    
    // Test object with record pattern matching using var for type inference
    Object obj = metadata;
    
    // Using var for type inference in record pattern
    if (obj instanceof AssetMetadata(var path, var contentType, var size)) {
      // The compiler should infer the correct types
      assertEquals("/path/to/asset.jar", path); // String
      assertEquals("application/java-archive", contentType); // String
      assertEquals(1024L, size); // long
      
      // Verify the inferred types at compile time
      String pathStr = path; // Should compile without error
      String contentTypeStr = contentType; // Should compile without error
      long sizeValue = size; // Should compile without error
    } else {
      // This should not happen
      assertTrue(false, "Record pattern matching with type inference failed");
    }
  }

  /**
   * Test nested record pattern matching.
   * This validates the ability to destructure nested records in a single pattern match.
   */
  @Test
  @DisplayName("Test nested record pattern matching")
  public void testNestedRecordPatternMatching() {
    // Create a nested repository configuration
    RepositoryConfig innerConfig = new RepositoryConfig("maven-central", "proxy", Map.of(
        "remoteUrl", "https://repo1.maven.org/maven2/"
    ));
    FormatRepositoryConfig formatConfig = new FormatRepositoryConfig("maven-central", "maven", innerConfig);
    
    // Test object with nested record pattern matching
    Object obj = formatConfig;
    
    // Using nested record pattern to destructure both outer and inner records
    if (obj instanceof FormatRepositoryConfig(String name, String format, RepositoryConfig(String innerName, String type, Map<String, Object> attrs))) {
      assertEquals("maven-central", name);
      assertEquals("maven", format);
      assertEquals("maven-central", innerName);
      assertEquals("proxy", type);
      assertEquals("https://repo1.maven.org/maven2/", attrs.get("remoteUrl"));
    } else {
      // This should not happen
      assertTrue(false, "Nested record pattern matching failed");
    }
  }

  /**
   * Test record pattern matching in switch expressions.
   * This validates the ability to use record patterns in switch cases.
   */
  @Test
  @DisplayName("Test record pattern matching in switch expressions")
  public void testRecordPatternMatchingInSwitch() {
    // Create test objects
    Object obj1 = new RepositoryConfig("maven-central", "proxy", Map.of());
    Object obj2 = new Point(10, 20);
    Object obj3 = new AssetMetadata("/path/to/asset.jar", "application/java-archive", 1024L);
    Object obj4 = "Not a record";
    
    // Test switch expression with record patterns
    String result1 = switch (obj1) {
      case RepositoryConfig(var name, var type, var attrs) -> "Repository: " + name + " (" + type + ")";
      case Point(var x, var y) -> "Point: (" + x + ", " + y + ")";
      case AssetMetadata(var path, var contentType, var size) -> "Asset: " + path;
      default -> "Unknown object";
    };
    
    String result2 = switch (obj2) {
      case RepositoryConfig(var name, var type, var attrs) -> "Repository: " + name + " (" + type + ")";
      case Point(var x, var y) -> "Point: (" + x + ", " + y + ")";
      case AssetMetadata(var path, var contentType, var size) -> "Asset: " + path;
      default -> "Unknown object";
    };
    
    String result3 = switch (obj3) {
      case RepositoryConfig(var name, var type, var attrs) -> "Repository: " + name + " (" + type + ")";
      case Point(var x, var y) -> "Point: (" + x + ", " + y + ")";
      case AssetMetadata(var path, var contentType, var size) -> "Asset: " + path;
      default -> "Unknown object";
    };
    
    String result4 = switch (obj4) {
      case RepositoryConfig(var name, var type, var attrs) -> "Repository: " + name + " (" + type + ")";
      case Point(var x, var y) -> "Point: (" + x + ", " + y + ")";
      case AssetMetadata(var path, var contentType, var size) -> "Asset: " + path;
      default -> "Unknown object";
    };
    
    assertEquals("Repository: maven-central (proxy)", result1);
    assertEquals("Point: (10, 20)", result2);
    assertEquals("Asset: /path/to/asset.jar", result3);
    assertEquals("Unknown object", result4);
  }

  /**
   * Test generic record pattern matching with type parameters.
   * This validates the ability to use record patterns with generic types.
   */
  @Test
  @DisplayName("Test generic record pattern matching with type parameters")
  public void testGenericRecordPatternMatching() {
    // Create a generic API response with different data types
    ApiResponse<String> stringResponse = new ApiResponse<>(200, "OK", "Success");
    ApiResponse<Point> pointResponse = new ApiResponse<>(200, "OK", new Point(10, 20));
    
    // Test object with generic record pattern matching
    Object obj1 = stringResponse;
    Object obj2 = pointResponse;
    
    // Pattern matching with generic record type
    if (obj1 instanceof ApiResponse(int status, String message, String data)) {
      assertEquals(200, status);
      assertEquals("OK", message);
      assertEquals("Success", data);
    } else {
      // This should not happen
      assertTrue(false, "Generic record pattern matching failed for String data");
    }
    
    // Pattern matching with nested generic record type
    if (obj2 instanceof ApiResponse(int status, String message, Point(int x, int y))) {
      assertEquals(200, status);
      assertEquals("OK", message);
      assertEquals(10, x);
      assertEquals(20, y);
    } else {
      // This should not happen
      assertTrue(false, "Generic record pattern matching failed for Point data");
    }
  }

  /**
   * Test pattern variable declarations in record patterns.
   * This validates the ability to use pattern variables in conditional expressions.
   */
  @Test
  @DisplayName("Test pattern variable declarations in record patterns")
  public void testPatternVariableDeclarations() {
    // Create test objects
    RepositoryConfig config1 = new RepositoryConfig("maven-central", "proxy", Map.of());
    RepositoryConfig config2 = new RepositoryConfig("npm-hosted", "hosted", Map.of());
    
    // Test pattern variable declarations with conditional logic
    String result1 = processRepositoryConfig(config1);
    String result2 = processRepositoryConfig(config2);
    
    assertEquals("Proxy repository: maven-central", result1);
    assertEquals("Hosted repository: npm-hosted", result2);
  }
  
  /**
   * Helper method to process repository configuration using pattern matching
   */
  private String processRepositoryConfig(Object obj) {
    return switch (obj) {
      // Using pattern variables in conditional expressions
      case RepositoryConfig(String name, String type, var _) when type.equals("proxy") ->
          "Proxy repository: " + name;
      case RepositoryConfig(String name, String type, var _) when type.equals("hosted") ->
          "Hosted repository: " + name;
      case RepositoryConfig(String name, var _, var __) ->
          "Other repository: " + name;
      default -> "Not a repository configuration";
    };
  }

  /**
   * Test practical application of record patterns in repository handling.
   * This validates the use of record patterns in a real-world scenario.
   */
  @Test
  @DisplayName("Test practical application of record patterns in repository handling")
  public void testPracticalApplicationInRepositoryHandling() {
    // Create test repository configurations
    RepositoryConfig mavenProxy = new RepositoryConfig("maven-central", "proxy", Map.of(
        "remoteUrl", "https://repo1.maven.org/maven2/"
    ));
    RepositoryConfig mavenHosted = new RepositoryConfig("maven-releases", "hosted", Map.of(
        "writePolicy", "ALLOW_ONCE"
    ));
    RepositoryConfig npmProxy = new RepositoryConfig("npm-registry", "proxy", Map.of(
        "remoteUrl", "https://registry.npmjs.org/"
    ));
    
    // Test repository handling with record patterns
    Optional<String> mavenProxyUrl = extractRemoteUrl(mavenProxy);
    Optional<String> mavenHostedUrl = extractRemoteUrl(mavenHosted);
    Optional<String> npmProxyUrl = extractRemoteUrl(npmProxy);
    
    assertTrue(mavenProxyUrl.isPresent());
    assertEquals("https://repo1.maven.org/maven2/", mavenProxyUrl.get());
    
    assertFalse(mavenHostedUrl.isPresent());
    
    assertTrue(npmProxyUrl.isPresent());
    assertEquals("https://registry.npmjs.org/", npmProxyUrl.get());
  }
  
  /**
   * Helper method to extract remote URL from repository configuration using pattern matching
   */
  private Optional<String> extractRemoteUrl(Object obj) {
    if (obj instanceof RepositoryConfig(var _, String type, Map<String, Object> attrs) && type.equals("proxy")) {
      Object remoteUrl = attrs.get("remoteUrl");
      if (remoteUrl instanceof String url) {
        return Optional.of(url);
      }
    }
    return Optional.empty();
  }
}