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
package org.sonatype.nexus.stringtemplates;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Tests for Java 21 String Templates integration with the Nexus logging framework.
 * 
 * @since 3.60
 */
public class LoggingStringTemplateTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(LoggingStringTemplateTest.class);
  
  private static final Marker TEST_MARKER = MarkerFactory.getMarker("STRING_TEMPLATE_TEST");
  
  private TestLogAppender testLogAppender;
  
  @Before
  public void setUp() {
    testLogAppender = new TestLogAppender();
    testLogAppender.start();
    
    // Add the test appender to the logger
    ch.qos.logback.classic.Logger rootLogger = 
        (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(testLogAppender);
  }
  
  /**
   * Tests basic String Template usage with different log levels.
   */
  @Test
  public void testBasicStringTemplateLogging() {
    String componentName = "StringTemplateTest";
    int value = 42;
    
    // Test DEBUG level
    log.debug(STR."Component \{componentName} initialized with value \{value}");
    assertThat(testLogAppender.getLastMessage(), 
        containsString("Component StringTemplateTest initialized with value 42"));
    
    // Test INFO level
    log.info(STR."Component \{componentName} is running with value \{value}");
    assertThat(testLogAppender.getLastMessage(), 
        containsString("Component StringTemplateTest is running with value 42"));
    
    // Test WARN level
    log.warn(STR."Component \{componentName} has unusual value: \{value}");
    assertThat(testLogAppender.getLastMessage(), 
        containsString("Component StringTemplateTest has unusual value: 42"));
    
    // Test ERROR level
    log.error(STR."Component \{componentName} failed with error code \{value}");
    assertThat(testLogAppender.getLastMessage(), 
        containsString("Component StringTemplateTest failed with error code 42"));
  }
  
  /**
   * Tests String Template with different data types.
   */
  @Test
  public void testStringTemplateWithDifferentTypes() {
    String text = "test";
    int number = 100;
    double decimal = 3.14159;
    boolean flag = true;
    LocalDateTime now = LocalDateTime.now();
    Object nullValue = null;
    
    log.info(STR."Values - text: \{text}, number: \{number}, decimal: \{decimal}, flag: \{flag}, "
        + STR."date: \{now}, null: \{nullValue}");
    
    String logMessage = testLogAppender.getLastMessage();
    assertThat(logMessage, containsString("text: test"));
    assertThat(logMessage, containsString("number: 100"));
    assertThat(logMessage, containsString("decimal: 3.14159"));
    assertThat(logMessage, containsString("flag: true"));
    assertThat(logMessage, containsString("date: " + now));
    assertThat(logMessage, containsString("null: null"));
  }
  
  /**
   * Tests String Template with complex expressions.
   */
  @Test
  public void testStringTemplateWithComplexExpressions() {
    int x = 10;
    int y = 20;
    
    // Test with arithmetic expressions
    log.info(STR."Calculation: \{x} + \{y} = \{x + y}");
    assertThat(testLogAppender.getLastMessage(), containsString("Calculation: 10 + 20 = 30"));
    
    // Test with conditional expressions
    log.info(STR."Status: \{x > y ? "x is greater" : "y is greater or equal"}");
    assertThat(testLogAppender.getLastMessage(), containsString("Status: y is greater or equal"));
    
    // Test with method calls
    log.info(STR."Uppercase: \{"hello".toUpperCase()}");
    assertThat(testLogAppender.getLastMessage(), containsString("Uppercase: HELLO"));
    
    // Test with multi-line expressions
    log.info(STR."Current time: \{
        // Get current time with zone
        ZonedDateTime.now()
            .toString()
    }");
    assertThat(testLogAppender.getLastMessage(), containsString("Current time: "));
  }
  
  /**
   * Tests String Template with markers.
   */
  @Test
  public void testStringTemplateWithMarkers() {
    String operation = "backup";
    
    log.info(TEST_MARKER, STR."Starting operation: \{operation}");
    assertThat(testLogAppender.getLastMessage(), containsString("Starting operation: backup"));
    assertThat(testLogAppender.getLastMarker(), is(TEST_MARKER));
  }
  
  /**
   * Tests String Template with exception logging.
   */
  @Test
  public void testStringTemplateWithExceptions() {
    String operation = "critical-task";
    Exception exception = new RuntimeException("Test exception");
    
    log.error(STR."Failed to execute \{operation}", exception);
    
    String logMessage = testLogAppender.getLastMessage();
    assertThat(logMessage, containsString("Failed to execute critical-task"));
    assertThat(testLogAppender.getLastThrowable(), is(exception));
  }
  
  /**
   * Tests String Template with conditional logging.
   */
  @Test
  public void testConditionalLoggingWithStringTemplate() {
    String sensitiveData = "password123";
    boolean isDebugEnabled = log.isDebugEnabled();
    
    // Traditional approach with isDebugEnabled check
    if (log.isDebugEnabled()) {
      log.debug(STR."Processing sensitive data: \{sensitiveData}");
    }
    
    // Using conditional expression within template
    log.debug(STR."Debug is \{isDebugEnabled ? "enabled" : "disabled"}");
    assertThat(testLogAppender.getLastMessage(), 
        containsString(isDebugEnabled ? "Debug is enabled" : "Debug is disabled"));
  }
  
  /**
   * Tests String Template with map data.
   */
  @Test
  public void testStringTemplateWithMapData() {
    Map<String, Object> configData = new HashMap<>();
    configData.put("host", "localhost");
    configData.put("port", 8080);
    configData.put("maxConnections", 100);
    
    log.info(STR."Configuration: host=\{configData.get("host")}, "
        + STR."port=\{configData.get("port")}, "
        + STR."maxConnections=\{configData.get("maxConnections")}");
    
    String logMessage = testLogAppender.getLastMessage();
    assertThat(logMessage, containsString("Configuration: host=localhost, port=8080, maxConnections=100"));
  }
  
  /**
   * Tests performance comparison between String Templates and traditional string formatting.
   */
  @Test
  public void testPerformanceComparison() {
    String name = "Nexus Repository";
    String version = "3.60.0";
    int connections = 250;
    
    // Measure traditional string concatenation
    long startConcat = System.nanoTime();
    for (int i = 0; i < 1000; i++) {
      String message = "Application " + name + " version " + version + " has " + connections + " active connections";
      assertThat(message, notNullValue());
    }
    long endConcat = System.nanoTime();
    
    // Measure String.format
    long startFormat = System.nanoTime();
    for (int i = 0; i < 1000; i++) {
      String message = String.format("Application %s version %s has %d active connections", 
          name, version, connections);
      assertThat(message, notNullValue());
    }
    long endFormat = System.nanoTime();
    
    // Measure String Template
    long startTemplate = System.nanoTime();
    for (int i = 0; i < 1000; i++) {
      String message = STR."Application \{name} version \{version} has \{connections} active connections";
      assertThat(message, notNullValue());
    }
    long endTemplate = System.nanoTime();
    
    // Log the results
    log.info(STR."Performance comparison (ns) - Concatenation: \{endConcat - startConcat}, "
        + STR."String.format: \{endFormat - startFormat}, "
        + STR."String Template: \{endTemplate - startTemplate}");
  }
  
  /**
   * A test appender that captures log events for verification.
   */
  private static class TestLogAppender extends ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent> {
    private String lastMessage;
    private Throwable lastThrowable;
    private Marker lastMarker;
    
    @Override
    protected void append(ch.qos.logback.classic.spi.ILoggingEvent event) {
      lastMessage = event.getFormattedMessage();
      lastMarker = event.getMarker();
      
      if (event.getThrowableProxy() != null) {
        lastThrowable = ((ch.qos.logback.classic.spi.ThrowableProxy) event.getThrowableProxy()).getThrowable();
      } else {
        lastThrowable = null;
      }
    }
    
    public String getLastMessage() {
      return lastMessage;
    }
    
    public Throwable getLastThrowable() {
      return lastThrowable;
    }
    
    public Marker getLastMarker() {
      return lastMarker;
    }
  }
}