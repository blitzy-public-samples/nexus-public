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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class VersionNumberExpanderTest
{
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
  
  /**
   * Record representing version components for testing with Record Patterns
   */
  record VersionComponents(String major, String minor, String patch) {}
  
  @Test
  public void versionComponentsWithRecordPatterns() {
    // Test using record patterns to analyze version components
    String version = "1.2.3";
    String expanded = VersionNumberExpander.expand(version);
    
    // Using record pattern to extract components from the expanded version
    if (expanded.matches("\\d+\\.\\d+\\.\\d+")) {
      String[] parts = expanded.split("\\.");
      var components = new VersionComponents(parts[0], parts[1], parts[2]);
      
      // Using record pattern matching to verify components
      switch (components) {
        case VersionComponents(var major, var minor, var patch) 
            when major.equals("000000001") && minor.equals("000000002") && patch.equals("000000003") ->
          assertEquals("000000001.000000002.000000003", expanded);
        default -> 
          assertEquals("000000001.000000002.000000003", expanded, 
              STR."Expected 1.2.3 to expand to 000000001.000000002.000000003 but got \{expanded}");
      }
    }
  }
  
  @Test
  public void patternMatchingForVersionAnalysis() {
    // Test using pattern matching for switch to analyze version strings
    Object version = "2.0.1";
    String result = switch (version) {
      case String s when s.matches("\\d+\\.\\d+\\.\\d+") -> {
        String expanded = VersionNumberExpander.expand(s);
        yield STR."Semantic version expanded: \{expanded}";
      }
      case String s when s.matches("\\d+\\.\\d+") -> {
        String expanded = VersionNumberExpander.expand(s);
        yield STR."Major.Minor version expanded: \{expanded}";
      }
      case String s when s.matches("\\d+") -> {
        String expanded = VersionNumberExpander.expand(s);
        yield STR."Major version only expanded: \{expanded}";
      }
      case String s -> STR."Non-numeric version: \{s}";
      case null -> "Null version";
      default -> "Unknown version format";
    };
    
    assertEquals("Semantic version expanded: 000000002.000000000.000000001", result);
  }
  
  @Test
  public void stringTemplatesForAssertions() {
    String version = "5.4.3";
    String expanded = VersionNumberExpander.expand(version);
    
    // Using String Templates for more readable assertions and error messages
    assertEquals("000000005.000000004.000000003", expanded, 
        STR."Version \{version} should expand to 000000005.000000004.000000003 but was \{expanded}");
    
    // Test with a complex version string using String Templates for the error message
    String complexVersion = "v2-alpha1.0";
    String expandedComplex = VersionNumberExpander.expand(complexVersion);
    assertEquals("v000000002-alpha000000001.000000000", expandedComplex, 
        STR."Complex version \{complexVersion} did not expand correctly");
  }
}
