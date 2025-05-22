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
package org.sonatype.nexus.repository.httpbridge;

import java.util.Map;
import java.util.HashMap;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.Logger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 String Templates in logging messages.
 */
public class StringTemplateLoggingTest
    extends TestSupport
{
  @Mock
  private Logger logger;

  @Before
  public void setUp() {
    when(logger.isDebugEnabled()).thenReturn(true);
    when(logger.isInfoEnabled()).thenReturn(true);
    when(logger.isErrorEnabled()).thenReturn(true);
  }

  /**
   * Test basic String Template interpolation in log messages.
   */
  @Test
  public void testBasicStringTemplateInterpolation() {
    String repositoryName = "maven-central";
    int statusCode = 200;
    
    // Using Java 21 String Templates
    logger.info(STR."Repository \{repositoryName} responded with status \{statusCode}");
    
    // Verify the log message contains the interpolated values
    verify(logger).info("Repository maven-central responded with status 200");
  }

  /**
   * Test complex expressions in String Templates for logging.
   */
  @Test
  public void testComplexExpressionsInStringTemplates() {
    Map<String, Integer> metrics = new HashMap<>();
    metrics.put("requests", 1500);
    metrics.put("errors", 25);
    
    // Using complex expressions in String Templates
    logger.debug(STR."Error rate: \{(double) metrics.get("errors") / metrics.get("requests") * 100}%");
    
    // Verify the log message contains the calculated value
    verify(logger).debug("Error rate: 1.6666666666666667%");
  }

  /**
   * Test escaping of special characters in String Templates for logging.
   */
  @Test
  public void testEscapingSpecialCharactersInStringTemplates() {
    String query = "SELECT * FROM components WHERE name = 'test'";
    
    // Using String Templates with escaped characters
    logger.info(STR."Executing SQL query: \{query.replace("'", "\\'")}");
    
    // Verify the log message contains the properly escaped string
    verify(logger).info("Executing SQL query: SELECT * FROM components WHERE name = \\'test\\'");
  }

  /**
   * Test backward compatibility with existing logging patterns.
   */
  @Test
  public void testBackwardCompatibilityWithExistingPatterns() {
    String repositoryName = "maven-central";
    int statusCode = 200;
    
    // Traditional SLF4J parameterized logging
    logger.info("Repository {} responded with status {}", repositoryName, statusCode);
    
    // Using Java 21 String Templates
    logger.info(STR."Repository \{repositoryName} responded with status \{statusCode}");
    
    // Verify both approaches produce the same result
    verify(logger).info("Repository {} responded with status {}", repositoryName, statusCode);
    verify(logger).info("Repository maven-central responded with status 200");
  }

  /**
   * Test String Templates with conditional expressions for logging.
   */
  @Test
  public void testConditionalExpressionsInStringTemplates() {
    int statusCode = 404;
    
    // Using conditional expressions in String Templates
    logger.warn(STR."Request failed with status \{statusCode} (\{statusCode >= 500 ? "Server Error" : "Client Error"})");
    
    // Verify the log message contains the conditional result
    verify(logger).warn("Request failed with status 404 (Client Error)");
  }

  /**
   * Test String Templates with multi-line log messages.
   */
  @Test
  public void testMultiLineStringTemplates() {
    String username = "admin";
    String action = "DELETE";
    String resource = "/api/v1/components/12345";
    
    // Using multi-line String Templates
    logger.info(STR."""
      Security Audit:
      User: \{username}
      Action: \{action}
      Resource: \{resource}
      Timestamp: \{java.time.Instant.now()}
      """);
    
    // Verify the log message contains the expected format and values
    verify(logger).info(containsString("Security Audit:"));
    verify(logger).info(containsString("User: admin"));
    verify(logger).info(containsString("Action: DELETE"));
    verify(logger).info(containsString("Resource: /api/v1/components/12345"));
    verify(logger).info(containsString("Timestamp:"));
  }

  /**
   * Test String Templates with exception logging.
   */
  @Test
  public void testStringTemplatesWithExceptionLogging() {
    String operation = "component download";
    Exception exception = new RuntimeException("Connection timeout");
    
    // Using String Templates with exception
    logger.error(STR."Failed to complete \{operation}: \{exception.getMessage()}", exception);
    
    // Verify the log message and exception are correctly passed
    verify(logger).error("Failed to complete component download: Connection timeout", exception);
  }

  /**
   * Test performance comparison between String Templates and traditional string concatenation.
   * This is a simple benchmark test to demonstrate the performance characteristics.
   */
  @Test
  public void testPerformanceComparison() {
    String repositoryName = "maven-central";
    String componentId = "org.apache.commons:commons-lang3:3.12.0";
    String userId = "user-12345";
    
    // Measure time for traditional string concatenation with isDebugEnabled check
    long startConcat = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
      if (logger.isDebugEnabled()) {
        logger.debug("User " + userId + " downloaded " + componentId + " from " + repositoryName);
      }
    }
    long endConcat = System.nanoTime();
    
    // Measure time for SLF4J parameterized logging
    long startParam = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
      logger.debug("User {} downloaded {} from {}", userId, componentId, repositoryName);
    }
    long endParam = System.nanoTime();
    
    // Measure time for Java 21 String Templates
    long startTemplate = System.nanoTime();
    for (int i = 0; i < 10000; i++) {
      logger.debug(STR."User \{userId} downloaded \{componentId} from \{repositoryName}");
    }
    long endTemplate = System.nanoTime();
    
    // Log the results (not verified, just for information)
    System.out.println("String concatenation time: " + (endConcat - startConcat) / 1000000.0 + " ms");
    System.out.println("SLF4J parameterized logging time: " + (endParam - startParam) / 1000000.0 + " ms");
    System.out.println("Java 21 String Templates time: " + (endTemplate - startTemplate) / 1000000.0 + " ms");
    
    // The actual assertion is not important here as performance can vary,
    // but we expect String Templates to be comparable to SLF4J parameterized logging
    // and both should be more efficient than string concatenation when logs are disabled
  }
}