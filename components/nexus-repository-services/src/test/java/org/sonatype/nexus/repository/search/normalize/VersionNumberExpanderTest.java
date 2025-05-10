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
package org.sonatype.nexus.repository.search.normalize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link VersionNumberExpander}.
 */
public class VersionNumberExpanderTest
{
  /**
   * Record to represent version components for testing with Record Patterns.
   */
  public record VersionComponents(String major, String minor, String patch) {
    /**
     * Parse a version string into components using Pattern Matching.
     */
    public static VersionComponents parse(String version) {
      if (version == null || version.isBlank()) {
        return new VersionComponents("", "", "");
      }
      
      // Using Pattern Matching to extract version components
      Matcher matcher = Pattern.compile("(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?.*").matcher(version);
      if (matcher.matches()) {
        String major = matcher.group(1);
        String minor = matcher.groupCount() > 1 && matcher.group(2) != null ? matcher.group(2) : "";
        String patch = matcher.groupCount() > 2 && matcher.group(3) != null ? matcher.group(3) : "";
        return new VersionComponents(major, minor, patch);
      }
      return new VersionComponents("", "", "");
    }
    
    /**
     * Format the version components using String Template.
     */
    public String format() {
      return STR."{major}{minor.isEmpty() ? "" : "."+minor}{patch.isEmpty() ? "" : "."+patch}";
    }
  }
  
  @Test
  public void blank() {
    assertEquals("", VersionNumberExpander.expand(null));
    assertEquals("", VersionNumberExpander.expand(""));
    assertEquals("", VersionNumberExpander.expand(" "));
  }

  @Test
  public void noExpansion() {
    assertEquals("alpha", VersionNumberExpander.expand("alpha"));
    assertEquals("beta release", VersionNumberExpander.expand("beta release"));
  }

  @Test
  public void numbers() {
    assertEquals("000000001", VersionNumberExpander.expand("1"));
    assertEquals("000023007", VersionNumberExpander.expand("23007"));
    assertEquals("000000001.000000000.000000002", VersionNumberExpander.expand("1.0.2"));
    assertEquals("000000001.000000023.000000004", VersionNumberExpander.expand("1.23.4"));
  }

  @Test
  public void mixedText() {
    assertEquals("000000001alpha", VersionNumberExpander.expand("1alpha"));
    assertEquals("beta-000000002", VersionNumberExpander.expand("beta-2"));
    assertEquals("000000001.000000000a000000004", VersionNumberExpander.expand("1.0a4"));
    assertEquals("beta-000000001.000000023-alpha000000004-snapshot", VersionNumberExpander.expand("beta-1.23-alpha4-snapshot"));
  }

  @Test
  public void longNumber() {
    assertEquals("v000000001-rev020181217-000000001.000000027.0123456789123456789123456789", VersionNumberExpander.expand("v1-rev20181217-1.27.0123456789123456789123456789"));
  }
  
  @Test
  public void patternMatchingForVersions() {
    // Using Pattern Matching for switch expressions with version strings
    String version = "1.2.3";
    String result = switch (version) {
      case String s when s.matches("\\d+\\.\\d+\\.\\d+") -> 
          STR."Semantic version: {s}";
      case String s when s.matches("\\d+\\.\\d+") -> 
          STR."Major.Minor version: {s}";
      case String s when s.matches("\\d+") -> 
          STR."Major version only: {s}";
      default -> 
          STR."Unrecognized version format: {version}";
    };
    
    assertEquals("Semantic version: 1.2.3", result);
    assertEquals("000000001.000000002.000000003", VersionNumberExpander.expand(version));
  }
  
  @Test
  public void recordPatternsWithVersionComponents() {
    // Test with record patterns to extract and validate version components
    VersionComponents components = VersionComponents.parse("2.4.6");
    
    // Using record pattern in if statement
    if (components instanceof VersionComponents(String major, String minor, String patch)) {
      assertEquals("2", major);
      assertEquals("4", minor);
      assertEquals("6", patch);
      
      // Using String Template for more readable assertion message
      String message = STR."Expected version {major}.{minor}.{patch} but got {components.format()}";
      assertEquals("Expected version 2.4.6 but got 2.4.6", message);
    }
    
    // Test expanded version with record components
    String expanded = VersionNumberExpander.expand(components.format());
    assertEquals("000000002.000000004.000000006", expanded);
  }
  
  @Test
  public void stringTemplatesForErrorMessages() {
    String version = "1.23.4-SNAPSHOT";
    String expanded = VersionNumberExpander.expand(version);
    
    // Using String Templates for more readable error messages
    String errorMessage = STR."Version '{version}' should expand to '{expanded}' for proper sorting";
    assertEquals("Version '1.23.4-SNAPSHOT' should expand to '000000001.000000023.000000004-SNAPSHOT' for proper sorting", 
        errorMessage);
    
    // Verify the actual expansion
    assertEquals("000000001.000000023.000000004-SNAPSHOT", expanded);
  }
  
  @Test
  public void patternMatchingWithRecordPatterns() {
    // Test different version formats with pattern matching and record patterns
    testVersionFormat("1.2.3", "Semantic");
    testVersionFormat("2.0", "Major.Minor");
    testVersionFormat("3", "Major");
    testVersionFormat("invalid", "Unknown");
  }
  
  private void testVersionFormat(String version, String expectedType) {
    VersionComponents components = VersionComponents.parse(version);
    
    String result = switch (components) {
      case VersionComponents(String major, String minor, String patch) when !patch.isEmpty() ->
          STR."Semantic version: {major}.{minor}.{patch}";
      case VersionComponents(String major, String minor, String _) when !minor.isEmpty() ->
          STR."Major.Minor version: {major}.{minor}";
      case VersionComponents(String major, String _, String _) when !major.isEmpty() ->
          STR."Major version only: {major}";
      default ->
          "Unknown version format";
    };
    
    switch (expectedType) {
      case "Semantic" -> assertEquals(STR."Semantic version: {components.major()}.{components.minor()}.{components.patch()}", result);
      case "Major.Minor" -> assertEquals(STR."Major.Minor version: {components.major()}.{components.minor()}", result);
      case "Major" -> assertEquals(STR."Major version only: {components.major()}", result);
      default -> assertEquals("Unknown version format", result);
    }
  }
}
