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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Java 21's record pattern matching features with script data handling.
 * 
 * This test class demonstrates how record patterns can improve data extraction and validation
 * while reducing boilerplate code when working with script data objects.
 */
@DisplayName("Record Pattern Matching for Script Data")
public class RecordPatternScriptDataTest
{
  /**
   * Record representing basic script information.
   */
  record ScriptInfo(String name, String type) {}

  /**
   * Record representing script content with metadata.
   */
  record ScriptContent(String content, boolean isValid) {}

  /**
   * Record representing a complete script with nested records.
   */
  record Script(ScriptInfo info, ScriptContent content) {}

  /**
   * Record representing a script execution result.
   */
  record ScriptResult(String output, int exitCode) {}

  /**
   * Record representing a script execution with input and result.
   */
  record ScriptExecution(Script script, ScriptResult result) {}

  @Test
  @DisplayName("Basic record pattern matching with instanceof")
  void testBasicRecordPatternMatching() {
    // Create a script object
    Script script = new Script(
        new ScriptInfo("hello", "groovy"),
        new ScriptContent("log.info('hello')", true)
    );
    
    // Traditional approach (without record patterns)
    if (script instanceof Script) {
      ScriptInfo info = script.info();
      String name = info.name();
      String type = info.type();
      
      assertEquals("hello", name);
      assertEquals("groovy", type);
    }
    
    // Using record pattern matching
    if (script instanceof Script(ScriptInfo info, ScriptContent content)) {
      assertEquals("hello", info.name());
      assertEquals("groovy", info.type());
      assertEquals("log.info('hello')", content.content());
      assertTrue(content.isValid());
    } else {
      fail("Record pattern matching failed");
    }
  }

  @Test
  @DisplayName("Nested record pattern matching")
  void testNestedRecordPatternMatching() {
    // Create a script execution object with nested records
    ScriptExecution execution = new ScriptExecution(
        new Script(
            new ScriptInfo("backup", "groovy"),
            new ScriptContent("backup.perform()", true)
        ),
        new ScriptResult("Backup completed successfully", 0)
    );
    
    // Using nested record pattern matching to directly access deeply nested fields
    if (execution instanceof ScriptExecution(Script(ScriptInfo(var name, var type), var content), var result)) {
      // Direct access to nested record fields
      assertEquals("backup", name);
      assertEquals("groovy", type);
      assertEquals("Backup completed successfully", result.output());
      assertEquals(0, result.exitCode());
    } else {
      fail("Nested record pattern matching failed");
    }
  }

  @Test
  @DisplayName("Pattern matching in switch expressions")
  void testPatternMatchingInSwitchExpressions() {
    // Create various script objects
    Script validGroovyScript = new Script(
        new ScriptInfo("hello", "groovy"),
        new ScriptContent("log.info('hello')", true)
    );
    
    Script invalidGroovyScript = new Script(
        new ScriptInfo("broken", "groovy"),
        new ScriptContent("log.info('hello'", false) // Missing closing parenthesis
    );
    
    Script validJavaScript = new Script(
        new ScriptInfo("alert", "javascript"),
        new ScriptContent("console.log('alert');", true)
    );
    
    // Using pattern matching in switch expressions
    String message = getScriptValidationMessage(validGroovyScript);
    assertEquals("Valid Groovy script: hello", message);
    
    message = getScriptValidationMessage(invalidGroovyScript);
    assertEquals("Invalid Groovy script: broken", message);
    
    message = getScriptValidationMessage(validJavaScript);
    assertEquals("Valid JavaScript script: alert", message);
  }
  
  /**
   * Helper method that uses pattern matching in switch expressions to generate validation messages.
   */
  private String getScriptValidationMessage(Script script) {
    return switch (script) {
      case Script(ScriptInfo(var name, "groovy"), ScriptContent(var content, true)) ->
          "Valid Groovy script: " + name;
          
      case Script(ScriptInfo(var name, "groovy"), ScriptContent(var content, false)) ->
          "Invalid Groovy script: " + name;
          
      case Script(ScriptInfo(var name, "javascript"), ScriptContent(var content, true)) ->
          "Valid JavaScript script: " + name;
          
      case Script(ScriptInfo(var name, "javascript"), ScriptContent(var content, false)) ->
          "Invalid JavaScript script: " + name;
          
      default -> "Unknown script type";
    };
  }

  @Test
  @DisplayName("Comparing traditional approach with record pattern approach")
  void testComparisonWithTraditionalApproach() {
    // Create a script execution object
    ScriptExecution execution = new ScriptExecution(
        new Script(
            new ScriptInfo("cleanup", "groovy"),
            new ScriptContent("cleanup.perform()", true)
        ),
        new ScriptResult("Cleanup completed successfully", 0)
    );
    
    // Traditional approach (without record patterns)
    String traditionalResult = getResultTraditionally(execution);
    assertEquals("Script 'cleanup' executed with exit code 0", traditionalResult);
    
    // Using record pattern approach
    String patternResult = getResultWithPatternMatching(execution);
    assertEquals("Script 'cleanup' executed with exit code 0", patternResult);
  }
  
  /**
   * Helper method that uses the traditional approach to extract data.
   */
  private String getResultTraditionally(ScriptExecution execution) {
    Script script = execution.script();
    ScriptInfo info = script.info();
    String name = info.name();
    
    ScriptResult result = execution.result();
    int exitCode = result.exitCode();
    
    return "Script '" + name + "' executed with exit code " + exitCode;
  }
  
  /**
   * Helper method that uses record pattern matching to extract data.
   */
  private String getResultWithPatternMatching(ScriptExecution execution) {
    if (execution instanceof ScriptExecution(Script(ScriptInfo(var name, var type), var content), ScriptResult(var output, var exitCode))) {
      return "Script '" + name + "' executed with exit code " + exitCode;
    }
    return "Unknown execution";
  }

  @Test
  @DisplayName("Using var for type inference in record patterns")
  void testVarTypeInferenceInRecordPatterns() {
    // Create a script object
    Script script = new Script(
        new ScriptInfo("metrics", "groovy"),
        new ScriptContent("metrics.collect()", true)
    );
    
    // Using var for type inference in record patterns
    if (script instanceof Script(var info, var content)) {
      // The compiler infers ScriptInfo for info and ScriptContent for content
      assertEquals("metrics", info.name());
      assertEquals("groovy", info.type());
      assertEquals("metrics.collect()", content.content());
      assertTrue(content.isValid());
    } else {
      fail("Record pattern matching with var failed");
    }
  }
}