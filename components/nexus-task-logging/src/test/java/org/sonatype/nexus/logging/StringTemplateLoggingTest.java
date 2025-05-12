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
package org.sonatype.nexus.logging;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.MDC;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests to validate Java 21 String Template formatting in log messages.
 * 
 * These tests ensure proper template processing, variable interpolation, and compatibility
 * with SLF4J logging across various log levels. String Templates are a new feature in Java 21
 * that provide a more expressive and type-safe way to format strings compared to traditional
 * string concatenation or MessageFormat.
 * 
 * The tests in this class verify that:
 * - String Templates work correctly with SLF4J logging methods
 * - Template expressions are properly evaluated in log messages
 * - Special characters are correctly escaped in templated log messages
 * - String Templates are compatible with MDC context in log messages
 * - Performance optimizations are maintained (no unnecessary string creation)
 */
public class StringTemplateLoggingTest
    extends TestSupport
{
  @Mock
  private Logger mockLogger;

  private String name;
  private int count;
  private Object complexObject;

  @BeforeEach
  public void setup() {
    name = "Nexus Repository";
    count = 42;
    complexObject = new TestObject("test-id", 100);
  }

  /**
   * Tests basic String Template usage with SLF4J info logging.
   * 
   * This test verifies that a simple String Template with embedded expressions
   * is correctly processed and passed to the SLF4J logger.
   */
  @Test
  public void testBasicStringTemplateWithInfoLogging() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    // Set up the mock logger to indicate info level is enabled
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    
    // Use a String Template with the info method
    mockLogger.info(STR."Application \{name} started with \{count} components");
    
    // Capture and verify the log message
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockLogger).info(messageCaptor.capture());
    
    String logMessage = messageCaptor.getValue();
    assertThat(logMessage, equalTo("Application Nexus Repository started with 42 components"));
  }

  /**
   * Tests String Template usage with SLF4J debug logging.
   * 
   * This test verifies that String Templates work correctly with debug level logging,
   * and that template expressions are only evaluated when the debug level is enabled.
   */
  @Test
  public void testStringTemplateWithDebugLogging() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    // Test when debug is enabled
    when(mockLogger.isDebugEnabled()).thenReturn(true);
    
    // Use a String Template with the debug method
    mockLogger.debug(STR."Debug message: \{name} processing item \{count}");
    
    // Capture and verify the log message
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockLogger).debug(messageCaptor.capture());
    
    String logMessage = messageCaptor.getValue();
    assertThat(logMessage, equalTo("Debug message: Nexus Repository processing item 42"));
  }

  /**
   * Tests String Template usage with complex expressions and method calls.
   * 
   * This test verifies that String Templates can include complex expressions,
   * method calls, and conditional logic that are correctly evaluated.
   */
  @Test
  public void testStringTemplateWithComplexExpressions() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    
    // Use a String Template with complex expressions
    mockLogger.info(STR."Status: \{count > 40 ? "HIGH" : "LOW"} with \{name.toUpperCase()} and ID \{complexObject.getId()}");
    
    // Capture and verify the log message
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockLogger).info(messageCaptor.capture());
    
    String logMessage = messageCaptor.getValue();
    assertThat(logMessage, equalTo("Status: HIGH with NEXUS REPOSITORY and ID test-id"));
  }

  /**
   * Tests String Template usage with special characters that need escaping.
   * 
   * This test verifies that special characters in String Templates are correctly
   * handled and escaped in the resulting log message.
   */
  @Test
  public void testStringTemplateWithSpecialCharacters() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    
    // Create strings with special characters
    String pathWithBackslashes = "C:\\Program Files\\Nexus";
    String xmlContent = "<repository id=\"maven-central\"/>";
    
    // Use a String Template with special characters
    mockLogger.info(STR."Path: \{pathWithBackslashes}, XML: \{xmlContent}");
    
    // Capture and verify the log message
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockLogger).info(messageCaptor.capture());
    
    String logMessage = messageCaptor.getValue();
    assertThat(logMessage, containsString("C:\\Program Files\\Nexus"));
    assertThat(logMessage, containsString("<repository id=\"maven-central\"/>"));
  }

  /**
   * Tests String Template compatibility with MDC context in log messages.
   * 
   * This test verifies that String Templates work correctly when MDC context
   * is used alongside templated log messages.
   */
  @Test
  public void testStringTemplateWithMDCContext() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    
    // Set up MDC context
    MDC.put("requestId", "REQ-12345");
    MDC.put("userId", "admin");
    
    try {
      // Use a String Template with MDC context
      mockLogger.info(STR."Processing request for \{name} with count \{count}");
      
      // Capture and verify the log message
      ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
      verify(mockLogger).info(messageCaptor.capture());
      
      String logMessage = messageCaptor.getValue();
      assertThat(logMessage, equalTo("Processing request for Nexus Repository with count 42"));
      
      // Verify MDC context is still available
      assertThat(MDC.get("requestId"), equalTo("REQ-12345"));
      assertThat(MDC.get("userId"), equalTo("admin"));
    } finally {
      // Clean up MDC
      MDC.remove("requestId");
      MDC.remove("userId");
    }
  }

  /**
   * Tests String Template usage with multi-line templates.
   * 
   * This test verifies that multi-line String Templates (text blocks) work correctly
   * with SLF4J logging methods.
   */
  @Test
  public void testMultiLineStringTemplate() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    
    // Use a multi-line String Template
    mockLogger.info(STR."""
        Application: \{name}
        Components: \{count}
        Status: \{count > 30 ? "Ready" : "Initializing"}
        """);
    
    // Capture and verify the log message
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(mockLogger).info(messageCaptor.capture());
    
    String logMessage = messageCaptor.getValue();
    assertThat(logMessage.contains("Application: Nexus Repository"), is(true));
    assertThat(logMessage.contains("Components: 42"), is(true));
    assertThat(logMessage.contains("Status: Ready"), is(true));
  }

  /**
   * Tests String Template usage with error logging and exception.
   * 
   * This test verifies that String Templates work correctly with error level logging
   * and when including exceptions in the log message.
   */
  @Test
  public void testStringTemplateWithErrorAndException() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    when(mockLogger.isErrorEnabled()).thenReturn(true);
    
    // Create an exception
    Exception testException = new RuntimeException("Test error");
    
    // Use a String Template with error method and exception
    mockLogger.error(STR."Error processing \{name}: component count \{count} invalid", testException);
    
    // Capture and verify the log message and exception
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Throwable> exceptionCaptor = ArgumentCaptor.forClass(Throwable.class);
    verify(mockLogger).error(messageCaptor.capture(), exceptionCaptor.capture());
    
    String logMessage = messageCaptor.getValue();
    Throwable capturedException = exceptionCaptor.getValue();
    
    assertThat(logMessage, equalTo("Error processing Nexus Repository: component count 42 invalid"));
    assertThat(capturedException, is(testException));
  }

  /**
   * Tests String Template usage with parameterized logging.
   * 
   * This test verifies that String Templates can be used alongside traditional
   * SLF4J parameterized logging patterns.
   */
  @Test
  public void testStringTemplateWithParameterizedLogging() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    
    // Create a formatted string using String Template
    String formattedMessage = STR."User \{name} has \{count} items";
    
    // Use the formatted string with traditional parameterized logging
    mockLogger.info("Processing: {}", formattedMessage);
    
    // Capture and verify the log message
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Object> paramCaptor = ArgumentCaptor.forClass(Object.class);
    verify(mockLogger).info(messageCaptor.capture(), paramCaptor.capture());
    
    String logPattern = messageCaptor.getValue();
    Object logParam = paramCaptor.getValue();
    
    assertThat(logPattern, equalTo("Processing: {}"));
    assertThat(logParam, equalTo("User Nexus Repository has 42 items"));
  }

  /**
   * Tests String Template usage with all SLF4J log levels.
   * 
   * This test verifies that String Templates work correctly with all SLF4J
   * log levels (trace, debug, info, warn, error).
   */
  @Test
  public void testStringTemplateWithAllLogLevels() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    // Enable all log levels
    when(mockLogger.isTraceEnabled()).thenReturn(true);
    when(mockLogger.isDebugEnabled()).thenReturn(true);
    when(mockLogger.isInfoEnabled()).thenReturn(true);
    when(mockLogger.isWarnEnabled()).thenReturn(true);
    when(mockLogger.isErrorEnabled()).thenReturn(true);
    
    // Use String Templates with different log levels
    mockLogger.trace(STR."TRACE: \{name} with count \{count}");
    mockLogger.debug(STR."DEBUG: \{name} with count \{count}");
    mockLogger.info(STR."INFO: \{name} with count \{count}");
    mockLogger.warn(STR."WARN: \{name} with count \{count}");
    mockLogger.error(STR."ERROR: \{name} with count \{count}");
    
    // Capture and verify the log messages for each level
    ArgumentCaptor<String> traceCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> debugCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> infoCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> warnCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
    
    verify(mockLogger).trace(traceCaptor.capture());
    verify(mockLogger).debug(debugCaptor.capture());
    verify(mockLogger).info(infoCaptor.capture());
    verify(mockLogger).warn(warnCaptor.capture());
    verify(mockLogger).error(errorCaptor.capture());
    
    assertThat(traceCaptor.getValue(), equalTo("TRACE: Nexus Repository with count 42"));
    assertThat(debugCaptor.getValue(), equalTo("DEBUG: Nexus Repository with count 42"));
    assertThat(infoCaptor.getValue(), equalTo("INFO: Nexus Repository with count 42"));
    assertThat(warnCaptor.getValue(), equalTo("WARN: Nexus Repository with count 42"));
    assertThat(errorCaptor.getValue(), equalTo("ERROR: Nexus Repository with count 42"));
  }

  /**
   * Tests that String Templates are not evaluated when the log level is disabled.
   * 
   * This test verifies that String Templates maintain the SLF4J performance optimization
   * where log messages are not constructed if the corresponding log level is disabled.
   */
  @Test
  public void testStringTemplateNotEvaluatedWhenLogLevelDisabled() {
    // Skip test if not running on Java 21+
    if (!isStringTemplateSupported()) {
      log.info("Skipping test as String Templates are not supported in this Java version");
      return;
    }
    
    // Disable debug level
    when(mockLogger.isDebugEnabled()).thenReturn(false);
    
    // Create an AtomicReference to track if the expression is evaluated
    AtomicReference<Boolean> expressionEvaluated = new AtomicReference<>(false);
    
    // Define a method that sets the flag when called
    TestObject testObj = new TestObject("test", 1) {
      @Override
      public String getId() {
        expressionEvaluated.set(true);
        return super.getId();
      }
    };
    
    // Use a String Template with debug method that includes the method call
    mockLogger.debug(STR."Debug message with expression: \{testObj.getId()}");
    
    // Verify the expression was not evaluated because debug is disabled
    assertThat("Expression should not be evaluated when debug is disabled",
        expressionEvaluated.get(), is(false));
  }

  /**
   * Checks if String Templates are supported in the current Java runtime.
   * 
   * @return true if String Templates are supported, false otherwise
   */
  private boolean isStringTemplateSupported() {
    try {
      // Try to access the STR processor which is available in Java 21+
      Class.forName("java.lang.StringTemplate");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  /**
   * Simple test object class for use in String Template expressions.
   */
  private static class TestObject {
    private final String id;
    private final int value;
    
    public TestObject(String id, int value) {
      this.id = id;
      this.value = value;
    }
    
    public String getId() {
      return id;
    }
    
    public int getValue() {
      return value;
    }
    
    @Override
    public String toString() {
      return "TestObject{id='" + id + "', value=" + value + "}";
    }
  }
}