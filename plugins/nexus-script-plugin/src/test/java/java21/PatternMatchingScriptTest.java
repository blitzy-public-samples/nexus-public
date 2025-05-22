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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonatype.nexus.script.Script;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Java 21's pattern matching for switch feature in the context of script validation and processing.
 * 
 * This test demonstrates how pattern matching for switch can improve code readability and maintainability
 * when handling different script types and content formats.
 */
@DisplayName("Java 21 Pattern Matching for Switch Tests")
public class PatternMatchingScriptTest
{
  /**
   * Simple script implementation for testing.
   */
  private static class TestScript implements Script
  {
    private final String name;
    private final String type;
    private final String content;

    public TestScript(String name, String type, String content) {
      this.name = name;
      this.type = type;
      this.content = content;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getType() {
      return type;
    }

    @Override
    public String getContent() {
      return content;
    }
  }

  /**
   * Record representing script content analysis result.
   */
  private record ScriptAnalysisResult(String scriptType, int contentLength, boolean isValid) {}

  /**
   * Tests basic pattern matching for switch with script types.
   * Demonstrates how to use type patterns to handle different script types.
   */
  @Test
  @DisplayName("Basic pattern matching for script types")
  public void testBasicPatternMatching() {
    // Create test scripts of different types
    Script groovyScript = new TestScript("test-groovy", "groovy", "println 'Hello, Groovy!'");
    Script javascriptScript = new TestScript("test-js", "javascript", "console.log('Hello, JavaScript!');");
    Script unknownScript = new TestScript("test-unknown", "unknown", "print('Hello, Unknown!');");

    // Test pattern matching for switch with script types
    assertEquals("Groovy script detected", getScriptTypeDescription(groovyScript));
    assertEquals("JavaScript script detected", getScriptTypeDescription(javascriptScript));
    assertEquals("Unknown script type: unknown", getScriptTypeDescription(unknownScript));
  }

  /**
   * Tests pattern matching with null handling.
   * Demonstrates how Java 21's pattern matching for switch handles null values.
   */
  @Test
  @DisplayName("Pattern matching with null handling")
  public void testPatternMatchingWithNull() {
    Script nullScript = null;
    assertEquals("Script is null", getScriptTypeDescription(nullScript));
  }

  /**
   * Tests pattern matching with guarded patterns.
   * Demonstrates how to use the 'when' clause for conditional pattern matching.
   */
  @Test
  @DisplayName("Pattern matching with guarded patterns")
  public void testPatternMatchingWithGuards() {
    // Create test scripts with different content lengths
    Script shortGroovyScript = new TestScript("short-groovy", "groovy", "println 'Hi'");
    Script longGroovyScript = new TestScript("long-groovy", "groovy", "println 'This is a much longer script that does something more complex'");
    Script shortJsScript = new TestScript("short-js", "javascript", "alert('Hi');");
    Script longJsScript = new TestScript("long-js", "javascript", "console.log('This is a much longer script that does something more complex');");

    // Test guarded pattern matching
    ScriptAnalysisResult shortGroovyResult = analyzeScript(shortGroovyScript);
    ScriptAnalysisResult longGroovyResult = analyzeScript(longGroovyScript);
    ScriptAnalysisResult shortJsResult = analyzeScript(shortJsScript);
    ScriptAnalysisResult longJsResult = analyzeScript(longJsScript);

    // Verify results
    assertEquals("groovy", shortGroovyResult.scriptType());
    assertTrue(shortGroovyResult.isValid());
    assertEquals("groovy", longGroovyResult.scriptType());
    assertTrue(longGroovyResult.isValid());
    assertEquals("javascript", shortJsResult.scriptType());
    assertTrue(shortJsResult.isValid());
    assertEquals("javascript", longJsResult.scriptType());
    assertTrue(longJsResult.isValid());

    // Verify content lengths
    assertEquals(11, shortGroovyResult.contentLength());
    assertEquals(65, longGroovyResult.contentLength());
    assertEquals(11, shortJsResult.contentLength());
    assertEquals(72, longJsResult.contentLength());
  }

  /**
   * Tests exhaustive pattern matching.
   * Demonstrates how Java 21's pattern matching ensures all cases are handled.
   */
  @Test
  @DisplayName("Exhaustive pattern matching")
  public void testExhaustivePatternMatching() {
    // Create test scripts of different types
    Script groovyScript = new TestScript("test-groovy", "groovy", "println 'Hello, Groovy!'");
    Script javascriptScript = new TestScript("test-js", "javascript", "console.log('Hello, JavaScript!');");
    Script pythonScript = new TestScript("test-python", "python", "print('Hello, Python!')");

    // Test exhaustive pattern matching
    assertTrue(isScriptSupported(groovyScript));
    assertTrue(isScriptSupported(javascriptScript));
    assertFalse(isScriptSupported(pythonScript));
  }

  /**
   * Uses pattern matching for switch to get a description of the script type.
   * Demonstrates basic pattern matching with null handling.
   *
   * @param script The script to analyze
   * @return A description of the script type
   */
  private String getScriptTypeDescription(Script script) {
    // Using pattern matching for switch with null handling
    return switch (script) {
      case null -> "Script is null";
      case Script s when "groovy".equals(s.getType()) -> "Groovy script detected";
      case Script s when "javascript".equals(s.getType()) -> "JavaScript script detected";
      case Script s -> "Unknown script type: " + s.getType();
    };
  }

  /**
   * Analyzes a script using pattern matching for switch with guarded patterns.
   * Demonstrates how to use guarded patterns for more complex conditions.
   *
   * @param script The script to analyze
   * @return A ScriptAnalysisResult containing the analysis results
   */
  private ScriptAnalysisResult analyzeScript(Script script) {
    // Using pattern matching for switch with guarded patterns
    return switch (script) {
      case null -> new ScriptAnalysisResult("unknown", 0, false);
      case Script s when "groovy".equals(s.getType()) && s.getContent().length() < 20 -> 
          new ScriptAnalysisResult(s.getType(), s.getContent().length(), true);
      case Script s when "groovy".equals(s.getType()) -> 
          new ScriptAnalysisResult(s.getType(), s.getContent().length(), true);
      case Script s when "javascript".equals(s.getType()) && s.getContent().length() < 20 -> 
          new ScriptAnalysisResult(s.getType(), s.getContent().length(), true);
      case Script s when "javascript".equals(s.getType()) -> 
          new ScriptAnalysisResult(s.getType(), s.getContent().length(), true);
      case Script s -> new ScriptAnalysisResult(s.getType(), s.getContent().length(), false);
    };
  }

  /**
   * Checks if a script type is supported using pattern matching for switch.
   * Demonstrates exhaustive pattern matching.
   *
   * @param script The script to check
   * @return true if the script type is supported, false otherwise
   */
  private boolean isScriptSupported(Script script) {
    // Using pattern matching for switch with exhaustive cases
    return switch (script.getType()) {
      case "groovy", "javascript" -> true;
      default -> false;
    };
  }
}