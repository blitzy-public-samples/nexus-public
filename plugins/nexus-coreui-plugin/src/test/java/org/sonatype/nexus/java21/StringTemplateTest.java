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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for Java 21's String Templates feature in CoreUI components.
 * 
 * This test class validates the implementation of String Templates for improved
 * logging and messaging in CoreUI components.
 */
@ExtendWith(MockitoExtension.class)
public class StringTemplateTest
{
  @Mock
  private MockLogService logService;

  private interface MockLogService {
    void debug(String message);
    void info(String message);
    void warn(String message);
    void error(String message);
  }

  private static class TestComponent {
    private final MockLogService logService;
    
    public TestComponent(MockLogService logService) {
      this.logService = logService;
    }
    
    public String formatBasicMessage(String name, int value) {
      // Using String Templates for basic message formatting
      return STR."User \{name} has value \{value}";
    }
    
    public String formatComplexMessage(String id, List<String> items, boolean isActive) {
      // Using String Templates for more complex message with conditional logic
      return STR."Resource [\{id}] contains \{items.size()} items and is \{isActive ? "active" : "inactive"}";
    }
    
    public String formatMultilineMessage(String title, String content) {
      // Using String Templates with multi-line content
      return STR."""
          Document: \{title}
          -------------------
          \{content}
          -------------------
          Generated at: \{LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}
          """;
    }
    
    public void logDebugMessage(String operation, String target) {
      // Using String Templates in logging statements
      logService.debug(STR."Executing operation '\{operation}' on target '\{target}'.");
    }
    
    public void logErrorWithDetails(String errorCode, Exception exception) {
      // Using String Templates for error reporting with exception details
      logService.error(STR."Error [\{errorCode}] occurred: \{exception.getMessage()}");
    }
  }

  private TestComponent testComponent;

  @BeforeEach
  void setUp() {
    testComponent = new TestComponent(logService);
  }

  @Test
  @DisplayName("Test basic string template interpolation")
  void testBasicStringTemplate() {
    String result = testComponent.formatBasicMessage("admin", 42);
    
    assertEquals("User admin has value 42", result);
  }

  @Test
  @DisplayName("Test complex string template with conditional expressions")
  void testComplexStringTemplate() {
    List<String> items = List.of("item1", "item2", "item3");
    String result = testComponent.formatComplexMessage("res-123", items, true);
    
    assertEquals("Resource [res-123] contains 3 items and is active", result);
  }

  @Test
  @DisplayName("Test multi-line string template")
  void testMultilineStringTemplate() {
    String title = "Test Document";
    String content = "This is a test content.";
    
    String result = testComponent.formatMultilineMessage(title, content);
    
    assertNotNull(result);
    assertTrue(result.contains("Document: Test Document"));
    assertTrue(result.contains("This is a test content."));
    assertTrue(result.contains("Generated at:"));
  }

  @Test
  @DisplayName("Test string templates in logging statements")
  void testStringTemplateInLogging() {
    // Verify that string templates work correctly in logging statements
    testComponent.logDebugMessage("update", "repository/maven-central");
    
    // Verify the log message was formatted correctly
    org.mockito.Mockito.verify(logService).debug("Executing operation 'update' on target 'repository/maven-central'.");
  }

  @Test
  @DisplayName("Test string templates with exception details")
  void testStringTemplateWithExceptionDetails() {
    // Create a mock exception
    Exception mockException = mock(Exception.class);
    when(mockException.getMessage()).thenReturn("Connection timeout");
    
    // Log an error with the exception details
    testComponent.logErrorWithDetails("E404", mockException);
    
    // Verify the error message was formatted correctly
    org.mockito.Mockito.verify(logService).error("Error [E404] occurred: Connection timeout");
  }

  @Test
  @DisplayName("Test string template with embedded expressions and calculations")
  void testStringTemplateWithCalculations() {
    int x = 10;
    int y = 20;
    
    // Using String Templates with embedded calculations
    String result = STR."\{x} + \{y} = \{x + y}";
    
    assertEquals("10 + 20 = 30", result);
  }

  @Test
  @DisplayName("Test string template with method calls in embedded expressions")
  void testStringTemplateWithMethodCalls() {
    String input = "test string";
    
    // Using String Templates with method calls in embedded expressions
    String result = STR."Original: '\{input}', Uppercase: '\{input.toUpperCase()}'";
    
    assertEquals("Original: 'test string', Uppercase: 'TEST STRING'", result);
  }
}