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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.sonatype.nexus.script.Script;
import org.sonatype.nexus.script.ScriptManager;

/**
 * Tests for Java 21 String Template features in script logging and output formatting.
 * 
 * This test class demonstrates how the Script Plugin can utilize Java 21's string templates
 * for improved readability and reduced string concatenation overhead when generating log
 * messages and formatting script output.
 */
@DisplayName("Java 21 String Template Tests for Script Output")
public class StringTemplateScriptOutputTest
{
  private ScriptManager scriptManager;
  private Script testScript;
  private Map<String, Object> scriptAttributes;
  
  @BeforeEach
  void setUp() {
    // Mock script manager and script
    scriptManager = mock(ScriptManager.class);
    testScript = mock(Script.class);
    
    // Setup script attributes
    scriptAttributes = new HashMap<>();
    scriptAttributes.put("name", "test-script");
    scriptAttributes.put("type", "groovy");
    scriptAttributes.put("content", "println('Hello, World!')");
    
    // Configure mock behavior
    when(testScript.getName()).thenReturn("test-script");
    when(testScript.getType()).thenReturn("groovy");
    when(testScript.getContent()).thenReturn("println('Hello, World!')");
  }
  
  /**
   * Tests basic string template usage for script execution logging.
   * Demonstrates how string templates can improve readability of log messages.
   */
  @Test
  @DisplayName("Basic string template for script logging")
  void testBasicStringTemplateForLogging() {
    // Traditional string concatenation approach
    String traditionalLog = "Executing script '" + testScript.getName() + "' of type '" + 
        testScript.getType() + "' at " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    
    // Using Java 21 string template
    LocalDateTime executionTime = LocalDateTime.now();
    String templateLog = STR."Executing script '\{testScript.getName()}' of type '\{testScript.getType()}' at \{executionTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}";
    
    // Assertions
    assertNotNull(traditionalLog);
    assertNotNull(templateLog);
    assertTrue(templateLog.contains(testScript.getName()));
    assertTrue(templateLog.contains(testScript.getType()));
    assertTrue(templateLog.contains(executionTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
  }
  
  /**
   * Tests complex template expressions with script metadata.
   * Shows how string templates can handle complex expressions and improve code readability.
   */
  @Test
  @DisplayName("Complex template expressions with script metadata")
  void testComplexTemplateExpressionsWithMetadata() {
    // Add more metadata
    scriptAttributes.put("created", LocalDateTime.now().minusDays(1));
    scriptAttributes.put("modified", LocalDateTime.now());
    scriptAttributes.put("author", "admin");
    scriptAttributes.put("lineCount", testScript.getContent().split("\n").length);
    
    // Traditional approach with StringBuilder
    StringBuilder sb = new StringBuilder();
    sb.append("Script Details:\n");
    sb.append("  Name: ").append(scriptAttributes.get("name")).append("\n");
    sb.append("  Type: ").append(scriptAttributes.get("type")).append("\n");
    sb.append("  Author: ").append(scriptAttributes.get("author")).append("\n");
    sb.append("  Line Count: ").append(scriptAttributes.get("lineCount")).append("\n");
    sb.append("  Created: ").append(
        ((LocalDateTime)scriptAttributes.get("created")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
    sb.append("  Modified: ").append(
        ((LocalDateTime)scriptAttributes.get("modified")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
    String traditionalOutput = sb.toString();
    
    // Using Java 21 string template with multi-line text block
    String templateOutput = STR."""
        Script Details:
          Name: \{scriptAttributes.get("name")}
          Type: \{scriptAttributes.get("type")}
          Author: \{scriptAttributes.get("author")}
          Line Count: \{scriptAttributes.get("lineCount")}
          Created: \{((LocalDateTime)scriptAttributes.get("created")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}
          Modified: \{((LocalDateTime)scriptAttributes.get("modified")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}
        """;
    
    // Assertions
    assertNotNull(traditionalOutput);
    assertNotNull(templateOutput);
    assertTrue(templateOutput.contains(scriptAttributes.get("name").toString()));
    assertTrue(templateOutput.contains(scriptAttributes.get("author").toString()));
    assertTrue(templateOutput.contains(
        ((LocalDateTime)scriptAttributes.get("created")).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
  }
  
  /**
   * Tests template-based approach vs traditional string concatenation.
   * Compares the readability and maintainability of both approaches.
   */
  @Test
  @DisplayName("Template-based approach vs traditional string concatenation")
  void testTemplateVsTraditionalConcatenation() {
    // Traditional string concatenation for script execution result
    String status = "SUCCESS";
    int executionTime = 150; // milliseconds
    String result = "Hello, World!";
    
    String traditionalResult = "Script '" + testScript.getName() + "' execution completed.\n" +
        "Status: " + status + "\n" +
        "Execution time: " + executionTime + " ms\n" +
        "Output: " + result;
    
    // Using Java 21 string template
    String templateResult = STR."""
        Script '\{testScript.getName()}' execution completed.
        Status: \{status}
        Execution time: \{executionTime} ms
        Output: \{result}
        """;
    
    // Assertions
    assertNotNull(traditionalResult);
    assertNotNull(templateResult);
    assertTrue(templateResult.contains(testScript.getName()));
    assertTrue(templateResult.contains(status));
    assertTrue(templateResult.contains(String.valueOf(executionTime)));
    assertTrue(templateResult.contains(result));
  }
  
  /**
   * Tests custom template processor for script output formatting.
   * Demonstrates how to create a custom template processor for specialized formatting.
   */
  @Test
  @DisplayName("Custom template processor for script output formatting")
  void testCustomTemplateProcessor() {
    // Define a simple custom template processor for script output
    // This processor adds timestamps to each line of script output
    var timestampProcessor = new StringTemplate.Processor<String>() {
      @Override
      public String process(StringTemplate template) {
        StringBuilder result = new StringBuilder();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_TIME);
        
        for (int i = 0; i < template.values().size(); i++) {
          result.append(template.fragments().get(i));
          if (i < template.values().size()) {
            result.append("[" + timestamp + "] " + template.values().get(i));
          }
        }
        
        // Append the last fragment if it exists
        if (template.fragments().size() > template.values().size()) {
          result.append(template.fragments().get(template.fragments().size() - 1));
        }
        
        return result.toString();
      }
    };
    
    // Use the custom processor
    String scriptOutput = "Starting script execution\nProcessing data\nScript completed";
    String[] lines = scriptOutput.split("\\n");
    
    // Process each line with the custom processor
    StringBuilder processedOutput = new StringBuilder();
    for (String line : lines) {
      String processed = timestampProcessor.process(StringTemplate.RAW."\{line}\n");
      processedOutput.append(processed);
    }
    
    // Assertions
    String result = processedOutput.toString();
    assertNotNull(result);
    assertTrue(result.contains("[")); // Contains timestamp markers
    assertTrue(result.contains("Starting script execution"));
    assertTrue(result.contains("Processing data"));
    assertTrue(result.contains("Script completed"));
  }
  
  /**
   * Tests string templates for formatting error messages.
   * Shows how string templates can improve error message readability.
   */
  @Test
  @DisplayName("String templates for error message formatting")
  void testStringTemplatesForErrorMessages() {
    // Simulate a script execution error
    String errorType = "ScriptExecutionException";
    String errorMessage = "Failed to execute script due to syntax error";
    int lineNumber = 42;
    String scriptContent = testScript.getContent();
    
    // Traditional error message formatting
    String traditionalError = "Error executing script '" + testScript.getName() + "': " + 
        errorType + " at line " + lineNumber + "\n" +
        "Message: " + errorMessage + "\n" +
        "Script content: " + scriptContent;
    
    // Using Java 21 string template with conditional expression
    boolean includeContent = true;
    String templateError = STR."""
        Error executing script '\{testScript.getName()}': \{errorType} at line \{lineNumber}
        Message: \{errorMessage}
        \{includeContent ? "Script content: " + scriptContent : "Content omitted for security reasons"}
        """;
    
    // Assertions
    assertNotNull(traditionalError);
    assertNotNull(templateError);
    assertTrue(templateError.contains(testScript.getName()));
    assertTrue(templateError.contains(errorType));
    assertTrue(templateError.contains(String.valueOf(lineNumber)));
    assertTrue(templateError.contains(errorMessage));
    assertTrue(templateError.contains(scriptContent));
  }
}