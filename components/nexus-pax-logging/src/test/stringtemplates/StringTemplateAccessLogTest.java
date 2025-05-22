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

import java.util.HashMap;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import ch.qos.logback.access.spi.AccessEvent;
import ch.qos.logback.access.spi.IAccessEvent;
import ch.qos.logback.core.Context;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 * Tests the integration of Java 21 String Templates with {@link AccessPatternLayoutEncoder}
 * to ensure that HTTP access log patterns correctly process and interpret String Template expressions.
 *
 * @since 3.60
 */
public class StringTemplateAccessLogTest
    extends TestSupport
{
  private static final String TEST_USER_ID = "admin";
  private static final String TEST_REQUEST_URI = "/service/rest/v1/components";
  private static final String TEST_REQUEST_METHOD = "GET";
  private static final String TEST_REMOTE_HOST = "127.0.0.1";
  private static final int TEST_STATUS_CODE = 200;
  private static final long TEST_CONTENT_LENGTH = 1024L;
  private static final long TEST_ELAPSED_TIME = 150L;

  @Mock
  private Context context;

  private AccessPatternLayoutEncoder encoder;
  private IAccessEvent accessEvent;

  @Before
  public void setUp() {
    encoder = new AccessPatternLayoutEncoder();
    encoder.setContext(context);
    
    // Create and configure a mock access event
    accessEvent = createMockAccessEvent();
  }

  /**
   * Tests that a simple String Template expression in an access log pattern is correctly processed.
   */
  @Test
  public void testSimpleStringTemplateExpression() {
    // Configure the encoder with a pattern that uses a simple String Template expression
    String pattern = STR."\{TEST_USER_ID} accessed \{TEST_REQUEST_URI}";
    encoder.setPattern(pattern);
    encoder.start();

    // Encode the access event
    String result = new String(encoder.encode(accessEvent));

    // Verify the result
    assertThat(result, is("admin accessed /service/rest/v1/components"));
  }

  /**
   * Tests that a String Template expression with multiple embedded expressions is correctly processed.
   */
  @Test
  public void testMultipleEmbeddedExpressions() {
    // Configure the encoder with a pattern that uses multiple embedded expressions
    String pattern = STR."\{TEST_REMOTE_HOST} - \{TEST_USER_ID} \{TEST_REQUEST_METHOD} \{TEST_REQUEST_URI} \{TEST_STATUS_CODE} \{TEST_CONTENT_LENGTH}";
    encoder.setPattern(pattern);
    encoder.start();

    // Encode the access event
    String result = new String(encoder.encode(accessEvent));

    // Verify the result
    assertThat(result, is("127.0.0.1 - admin GET /service/rest/v1/components 200 1024"));
  }

  /**
   * Tests that a String Template expression with arithmetic operations is correctly processed.
   */
  @Test
  public void testArithmeticOperationsInTemplates() {
    // Configure the encoder with a pattern that uses arithmetic operations in embedded expressions
    String pattern = STR."Request took \{TEST_ELAPSED_TIME} ms (\{TEST_ELAPSED_TIME / 1000.0} seconds)";
    encoder.setPattern(pattern);
    encoder.start();

    // Encode the access event
    String result = new String(encoder.encode(accessEvent));

    // Verify the result
    assertThat(result, is("Request took 150 ms (0.15 seconds)"));
  }

  /**
   * Tests that a String Template expression mixed with standard pattern layout converters is correctly processed.
   */
  @Test
  public void testMixedWithStandardPatternConverters() {
    // Configure the encoder with a pattern that mixes String Templates with standard pattern converters
    String pattern = "%h %u %m %s \{TEST_ELAPSED_TIME} ms";
    encoder.setPattern(pattern);
    encoder.start();

    // Encode the access event
    String result = new String(encoder.encode(accessEvent));

    // Verify the result contains the expected values
    // Note: We can't predict the exact format as it depends on the standard converters
    assertThat(result, containsString(TEST_REMOTE_HOST));
    assertThat(result, containsString(TEST_USER_ID));
    assertThat(result, containsString(TEST_REQUEST_METHOD));
    assertThat(result, containsString(String.valueOf(TEST_STATUS_CODE)));
    assertThat(result, containsString("150 ms"));
  }

  /**
   * Tests that a complex String Template expression with conditional logic is correctly processed.
   */
  @Test
  public void testConditionalLogicInTemplates() {
    // Configure the encoder with a pattern that uses conditional logic in embedded expressions
    String pattern = STR."Status: \{TEST_STATUS_CODE < 400 ? "Success" : "Error"} (\{TEST_STATUS_CODE})";
    encoder.setPattern(pattern);
    encoder.start();

    // Encode the access event
    String result = new String(encoder.encode(accessEvent));

    // Verify the result
    assertThat(result, is("Status: Success (200)"));
  }

  /**
   * Creates a mock {@link IAccessEvent} with test values.
   */
  private IAccessEvent createMockAccessEvent() {
    AccessEvent event = new AccessEvent(null, null, null);
    
    // Set up the MDC with the user ID
    Map<String, String> mdcMap = new HashMap<>();
    mdcMap.put(NexusUserIdConverter.MDC_KEY, TEST_USER_ID);
    when(event.getRequestURI()).thenReturn(TEST_REQUEST_URI);
    when(event.getMethod()).thenReturn(TEST_REQUEST_METHOD);
    when(event.getRemoteHost()).thenReturn(TEST_REMOTE_HOST);
    when(event.getStatusCode()).thenReturn(TEST_STATUS_CODE);
    when(event.getContentLength()).thenReturn(TEST_CONTENT_LENGTH);
    when(event.getElapsedTime()).thenReturn(TEST_ELAPSED_TIME);
    when(event.getMDCPropertyMap()).thenReturn(mdcMap);
    
    return event;
  }
}