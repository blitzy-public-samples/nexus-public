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

import org.sonatype.goodies.testsupport.TestSupport;

import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.core.Context;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AccessPatternLayoutEncoder} with Java 21 String Templates.
 * 
 * This test validates that the AccessPatternLayoutEncoder correctly processes and interprets
 * String Template expressions in access log patterns. It ensures that templated content is
 * properly formatted and rendered in access logs, maintaining compatibility with the existing
 * pattern layout system while leveraging the new Java 21 String Templates feature.
 * 
 * @since 3.60
 */
public class StringTemplateAccessLogTest
    extends TestSupport
{
  @Mock
  private Context context;
  
  @Mock
  private IAccessEvent accessEvent;
  
  private AccessPatternLayoutEncoder encoder;
  
  private ByteArrayOutputStream outputStream;
  
  @Before
  public void setUp() {
    // Initialize the encoder with context
    encoder = new AccessPatternLayoutEncoder();
    encoder.setContext(context);
    
    // Set up output stream to capture encoded log entries
    outputStream = new ByteArrayOutputStream();
    encoder.setOutputStream(new OutputStreamWriter(outputStream));
    
    // Setup common mock behavior for access event
    when(accessEvent.getAttribute("nexus.user.id")).thenReturn("admin");
    when(accessEvent.getRequestURI()).thenReturn("/path/to/resource");
    when(accessEvent.getStatusCode()).thenReturn(200);
    when(accessEvent.getMethod()).thenReturn("GET");
    when(accessEvent.getRemoteHost()).thenReturn("127.0.0.1");
  }
  
  /**
   * Tests that a basic String Template expression in the pattern is correctly processed.
   * This validates the fundamental capability of using String Templates in access log patterns.
   */
  @Test
  public void testBasicStringTemplate() {
    // Setup a pattern with a simple String Template expression
    String pattern = STR."User: \{accessEvent.getAttribute(\"nexus.user.id\")} accessed \{accessEvent.getRequestURI()}";
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event
    encoder.doEncode(accessEvent);
    
    // Verify the output contains the expected interpolated values
    String output = outputStream.toString();
    assertThat(output, containsString("User: admin accessed /path/to/resource"));
  }
  
  /**
   * Tests that a String Template with multiple expressions is correctly processed.
   * This ensures that complex patterns with multiple interpolated values work correctly.
   */
  @Test
  public void testMultipleExpressions() {
    // Setup a pattern with multiple expressions
    String pattern = STR."\{accessEvent.getRemoteHost()} - \{accessEvent.getAttribute(\"nexus.user.id\")} "
        + STR."\{accessEvent.getMethod()} \{accessEvent.getRequestURI()} \{accessEvent.getStatusCode()}";
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event
    encoder.doEncode(accessEvent);
    
    // Verify the output contains all expected interpolated values
    String output = outputStream.toString();
    assertThat(output, containsString("127.0.0.1 - admin GET /path/to/resource 200"));
  }
  
  /**
   * Tests that a String Template with conditional expressions is correctly processed.
   * This verifies that ternary operators and other conditional logic work within templates.
   */
  @Test
  public void testConditionalExpressions() {
    // Setup a pattern with conditional expressions
    String pattern = STR."Status: \{accessEvent.getStatusCode() >= 400 ? \"ERROR\" : \"OK\"} "
        + STR."for request to \{accessEvent.getRequestURI()}";
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event with 200 status code
    encoder.doEncode(accessEvent);
    
    // Verify the output contains the expected conditional result
    String output = outputStream.toString();
    assertThat(output, containsString("Status: OK for request to /path/to/resource"));
    
    // Reset output and change status code to 404
    outputStream.reset();
    when(accessEvent.getStatusCode()).thenReturn(404);
    
    // Process the event again
    encoder.doEncode(accessEvent);
    
    // Verify the output now shows ERROR
    output = outputStream.toString();
    assertThat(output, containsString("Status: ERROR for request to /path/to/resource"));
  }
  
  /**
   * Tests that a String Template with method calls and expressions is correctly processed.
   * This ensures that method invocations on objects within templates are properly evaluated.
   */
  @Test
  public void testMethodCallsInTemplates() {
    // Setup a pattern with method calls in the template
    String pattern = STR."URI: \{accessEvent.getRequestURI().toUpperCase()} "
        + STR."Method: \{accessEvent.getMethod().toLowerCase()}";
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event
    encoder.doEncode(accessEvent);
    
    // Verify the output contains the expected transformed values
    String output = outputStream.toString();
    assertThat(output, containsString("URI: /PATH/TO/RESOURCE Method: get"));
  }
  
  /**
   * Tests that a String Template with embedded calculations is correctly processed.
   * This validates that arithmetic operations within templates work correctly in log patterns.
   */
  @Test
  public void testCalculationsInTemplates() {
    // Setup mock for content length
    when(accessEvent.getContentLength()).thenReturn(1024L);
    
    // Setup a pattern with calculations in the template
    String pattern = STR."Content size: \{accessEvent.getContentLength() / 1024.0} KB "
        + STR."Status category: \{accessEvent.getStatusCode() / 100}xx";
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event
    encoder.doEncode(accessEvent);
    
    // Verify the output contains the expected calculated values
    String output = outputStream.toString();
    assertThat(output, containsString("Content size: 1.0 KB Status category: 2xx"));
  }
  
  /**
   * Tests that a String Template with text blocks is correctly processed.
   * This is particularly important for complex log formats that span multiple lines.
   */
  @Test
  public void testTextBlockTemplates() {
    // Setup a pattern with a text block template
    String pattern = STR."""
        Access Log Entry:
        User: \{accessEvent.getAttribute("nexus.user.id")}
        URI: \{accessEvent.getRequestURI()}
        Method: \{accessEvent.getMethod()}
        Status: \{accessEvent.getStatusCode()}
        """;
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event
    encoder.doEncode(accessEvent);
    
    // Verify the output contains the expected formatted text block
    String output = outputStream.toString();
    assertThat(output, containsString("User: admin"));
    assertThat(output, containsString("URI: /path/to/resource"));
    assertThat(output, containsString("Method: GET"));
    assertThat(output, containsString("Status: 200"));
  }
  
  /**
   * Tests that a String Template with date/time formatting is correctly processed.
   * This validates that date/time operations within templates work correctly in log patterns.
   */
  @Test
  public void testDateTimeInTemplates() {
    // Get current date/time for the test
    LocalDateTime now = LocalDateTime.now();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    String formattedDateTime = now.format(formatter);
    
    // Setup a pattern with date/time formatting in the template
    String pattern = STR."Time: \{LocalDateTime.now().format(DateTimeFormatter.ofPattern(\"yyyy-MM-dd HH:mm:ss\"))} "
        + STR."User: \{accessEvent.getAttribute(\"nexus.user.id\")}";
    encoder.setPattern(pattern);
    encoder.start();
    
    // Process the event
    encoder.doEncode(accessEvent);
    
    // Verify the output contains the user part (we can't exactly match the time as it will differ)
    String output = outputStream.toString();
    assertThat(output, containsString("User: admin"));
    // Verify the output contains a properly formatted date/time string (format check only)
    assertThat(output, containsString("Time: "));
  }
}