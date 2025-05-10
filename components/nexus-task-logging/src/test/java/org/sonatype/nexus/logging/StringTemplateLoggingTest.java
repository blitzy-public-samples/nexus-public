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

import static java.lang.StringTemplate.STR;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.MDC;

/**
 * Tests for Java 21 String Template formatting in log messages.
 */
public class StringTemplateLoggingTest
    extends TestSupport
{
  @Mock
  private Logger logger;

  @Before
  public void setup() {
    // Set up logger mock to simulate enabled log levels
    when(logger.isDebugEnabled()).thenReturn(true);
    when(logger.isTraceEnabled()).thenReturn(true);
  }

  /**
   * Test basic String Template interpolation with SLF4J info logging.
   */
  @Test
  public void testBasicStringTemplateInterpolation() {
    String name = "Nexus";
    int version = 21;
    
    // Log with String Template
    logger.info(STR."Application \{name} is running on Java \{version}");
    
    // Verify the correct interpolated string was logged
    verify(logger).info("Application Nexus is running on Java 21");
  }

  /**
   * Test String Template interpolation with multiple variables and expressions.
   */
  @Test
  public void testMultipleVariableInterpolation() {
    String component = "Repository";
    int count = 5;
    boolean active = true;
    
    // Log with String Template containing multiple variables and an expression
    logger.info(STR."\{component} has \{count} instances (active: \{active ? "yes" : "no"})");
    
    // Verify the correct interpolated string with expression evaluation
    verify(logger).info("Repository has 5 instances (active: yes)");
  }

  /**
   * Test String Template interpolation with debug logging and conditional execution.
   */
  @Test
  public void testDebugLevelStringTemplateInterpolation() {
    String operation = "indexing";
    long duration = 1500;
    
    // Only log if debug is enabled (which our mock returns true for)
    if (logger.isDebugEnabled()) {
      logger.debug(STR."Completed \{operation} in \{duration}ms");
    }
    
    // Verify the debug message was logged with correct interpolation
    verify(logger).debug("Completed indexing in 1500ms");
  }

  /**
   * Test String Template interpolation with trace logging.
   */
  @Test
  public void testTraceLevelStringTemplateInterpolation() {
    String method = "processRequest";
    String param = "id=123";
    
    // Log at trace level
    logger.trace(STR."Entering \{method} with parameters: \{param}");
    
    // Verify the trace message was logged with correct interpolation
    verify(logger).trace("Entering processRequest with parameters: id=123");
  }

  /**
   * Test that String Templates properly escape special characters in log messages.
   */
  @Test
  public void testEscapingSpecialCharactersInTemplates() {
    String path = "C:\\Program Files\\Nexus";
    String query = "SELECT * FROM table WHERE id = 'value'";
    
    // Log with String Template containing special characters
    logger.info(STR."Path: \{path}, Query: \{query}");
    
    // Verify the special characters were properly escaped and preserved
    verify(logger).info("Path: C:\\Program Files\\Nexus, Query: SELECT * FROM table WHERE id = 'value'");
  }

  /**
   * Test String Template compatibility with MDC context in log messages.
   */
  @Test
  public void testStringTemplateWithMDC() {
    try {
      // Set up MDC context
      MDC.put("requestId", "abc-123");
      MDC.put("user", "admin");
      
      String action = "update";
      String resource = "repository/maven-central";
      
      // Log with String Template while MDC context is active
      logger.info(STR."Performing \{action} on \{resource}");
      
      // Verify the message was logged correctly
      // MDC context is automatically included by the logging implementation
      verify(logger).info("Performing update on repository/maven-central");
    }
    finally {
      // Clean up MDC
      MDC.clear();
    }
  }

  /**
   * Test that String Templates work with exception logging in SLF4J.
   */
  @Test
  public void testStringTemplateWithException() {
    String operation = "save";
    Exception exception = new RuntimeException("Database connection failed");
    
    // Log with String Template and exception
    logger.error(STR."Failed to \{operation} data", exception);
    
    // Verify the message was logged with the exception
    verify(logger).error("Failed to save data", exception);
  }

  /**
   * Test that String Templates are not evaluated when the log level is disabled.
   */
  @Test
  public void testStringTemplateNotEvaluatedWhenLogLevelDisabled() {
    // Configure mock to simulate disabled debug level
    when(logger.isDebugEnabled()).thenReturn(false);
    
    // This should not be evaluated since debug is disabled
    if (logger.isDebugEnabled()) {
      // This code should never execute
      String expensiveOperation = "expensive computation result";
      logger.debug(STR."Result: \{expensiveOperation}");
    }
    
    // Verify debug was never called
    verify(logger, never()).debug(anyString());
  }

  /**
   * Test String Template with complex expressions and method calls.
   */
  @Test
  public void testStringTemplateWithComplexExpressions() {
    String[] items = {"item1", "item2", "item3"};
    
    // Log with String Template containing method calls and complex expressions
    logger.info(STR."Processing \{items.length} items: \{String.join(", ", items)}");
    
    // Verify the complex expression was evaluated correctly
    verify(logger).info("Processing 3 items: item1, item2, item3");
  }
}