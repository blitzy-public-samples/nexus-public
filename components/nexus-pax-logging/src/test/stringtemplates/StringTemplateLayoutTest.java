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
package org.sonatype.nexus.pax.logging;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Tests the integration of Java 21 String Templates with {@link NexusLayoutEncoder}.
 * 
 * @since 3.60
 */
public class StringTemplateLayoutTest
{
  private NexusLayoutEncoder encoder;
  private LoggerContext loggerContext;
  private Logger logger;
  private ByteArrayOutputStream outputStream;
  
  @Before
  public void setup() {
    loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
    logger = loggerContext.getLogger(StringTemplateLayoutTest.class);
    
    encoder = new NexusLayoutEncoder();
    encoder.setContext(loggerContext);
    encoder.setPattern("%msg");
    encoder.start();
    
    outputStream = new ByteArrayOutputStream();
  }
  
  @Test
  public void testSimpleStringTemplate() {
    String name = "Nexus";
    String message = STR."Hello, \{name}!";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Hello, Nexus!"));
  }
  
  @Test
  public void testMultipleExpressions() {
    String user = "admin";
    String action = "login";
    String message = STR."User \{user} performed action: \{action}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("User admin performed action: login"));
  }
  
  @Test
  public void testConditionalExpression() {
    boolean success = true;
    String message = STR."Operation \{success ? "succeeded" : "failed"}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Operation succeeded"));
    
    // Test with opposite condition
    success = false;
    message = STR."Operation \{success ? "succeeded" : "failed"}";
    
    event = createLoggingEvent(message);
    encoded = encoder.encode(event);
    
    result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Operation failed"));
  }
  
  @Test
  public void testFormattedValues() {
    double value = 123.456;
    String message = STR."The value is \{String.format("%.2f", value)}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("The value is 123.46"));
  }
  
  @Test
  public void testDateTimeFormatting() {
    LocalDateTime dateTime = LocalDateTime.of(2023, 12, 25, 10, 30, 0);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    String message = STR."Event occurred at \{dateTime.format(formatter)}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Event occurred at 2023-12-25 10:30:00"));
  }
  
  @Test
  public void testNestedExpressions() {
    int count = 5;
    String type = "error";
    String message = STR."Found \{count} \{count == 1 ? type : type + "s"}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Found 5 errors"));
    
    // Test with singular case
    count = 1;
    message = STR."Found \{count} \{count == 1 ? type : type + "s"}";
    
    event = createLoggingEvent(message);
    encoded = encoder.encode(event);
    
    result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Found 1 error"));
  }
  
  @Test
  public void testSpecialCharacters() {
    String data = "value with 'quotes' and \"double quotes\"";
    String message = STR."Special characters: \{data}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Special characters: value with 'quotes' and \"double quotes\""));
  }
  
  @Test
  public void testMultilineTemplates() {
    String name = "Nexus Repository";
    String version = "3.60.0";
    String message = STR."""
        Application: \{name}
        Version: \{version}
        Status: Running
        """;
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Application: Nexus Repository"));
    assertThat(result, containsString("Version: 3.60.0"));
    assertThat(result, containsString("Status: Running"));
  }
  
  @Test
  public void testMethodCallsInTemplates() {
    String text = "nexus repository manager";
    String message = STR."Transformed: \{text.toUpperCase()}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Transformed: NEXUS REPOSITORY MANAGER"));
  }
  
  @Test
  public void testComplexExpression() {
    int[] numbers = {1, 2, 3, 4, 5};
    String message = STR."Sum of numbers: \{java.util.Arrays.stream(numbers).sum()}";
    
    LoggingEvent event = createLoggingEvent(message);
    byte[] encoded = encoder.encode(event);
    
    String result = new String(encoded, StandardCharsets.UTF_8);
    assertThat(result, containsString("Sum of numbers: 15"));
  }
  
  private LoggingEvent createLoggingEvent(String message) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext(loggerContext);
    event.setLogger(logger);
    event.setLevel(Level.INFO);
    event.setMessage(message);
    event.setTimeStamp(System.currentTimeMillis());
    return event;
  }
}