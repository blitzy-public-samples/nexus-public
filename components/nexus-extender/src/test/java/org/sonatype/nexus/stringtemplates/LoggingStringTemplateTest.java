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

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.log.LoggerLevel;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.slf4j.Logger;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 String Templates integration with Nexus logging framework.
 *
 * @since 3.60
 */
public class LoggingStringTemplateTest
    extends TestSupport
{
  @Mock
  private Logger logger;

  @Before
  public void setup() {
    // Configure logger mock to enable all log levels for testing
    when(logger.isTraceEnabled()).thenReturn(true);
    when(logger.isDebugEnabled()).thenReturn(true);
    when(logger.isInfoEnabled()).thenReturn(true);
    when(logger.isWarnEnabled()).thenReturn(true);
    when(logger.isErrorEnabled()).thenReturn(true);
  }

  @Test
  public void testBasicStringTemplateLogging() {
    String repositoryName = "maven-central";
    int itemCount = 42;

    // Test with different log levels
    logger.trace(STR."Repository \{repositoryName} contains \{itemCount} items");
    logger.debug(STR."Repository \{repositoryName} contains \{itemCount} items");
    logger.info(STR."Repository \{repositoryName} contains \{itemCount} items");
    logger.warn(STR."Repository \{repositoryName} contains \{itemCount} items");
    logger.error(STR."Repository \{repositoryName} contains \{itemCount} items");

    // Verify the logger received the correctly formatted messages
    verify(logger).trace("Repository maven-central contains 42 items");
    verify(logger).debug("Repository maven-central contains 42 items");
    verify(logger).info("Repository maven-central contains 42 items");
    verify(logger).warn("Repository maven-central contains 42 items");
    verify(logger).error("Repository maven-central contains 42 items");
  }

  @Test
  public void testComplexStringTemplateLogging() {
    // Test with complex data types
    Map<String, Object> metadata = Map.of(
        "name", "example-repo",
        "type", "maven2",
        "online", true,
        "blobstore", "default"
    );

    LoggerLevel level = LoggerLevel.INFO;
    Exception exception = new RuntimeException("Test exception");

    // Complex template with multiple expressions and different data types
    logger.info(STR."Repository metadata: \{metadata}, current level: \{level}, status: \{metadata.get("online") ? "ONLINE" : "OFFLINE"}");
    
    // Verify complex template was correctly processed
    verify(logger).info("Repository metadata: {name=example-repo, type=maven2, online=true, blobstore=default}, " +
        "current level: INFO, status: ONLINE");

    // Test with exception
    logger.error(STR."Failed to process repository \{metadata.get("name")}", exception);
    verify(logger).error("Failed to process repository example-repo", exception);
  }

  @Test
  public void testConditionalLogging() {
    String repositoryName = "maven-central";
    int itemCount = 42;

    // Test conditional logging with String Templates
    if (logger.isDebugEnabled()) {
      logger.debug(STR."Detailed repository info - name: \{repositoryName}, items: \{itemCount}, " +
          "calculated value: \{calculateValue(itemCount)}");
    }

    // Verify the conditional log was processed correctly
    verify(logger).debug("Detailed repository info - name: maven-central, items: 42, calculated value: 84");
  }

  @Test
  public void testMultilineStringTemplateLogging() {
    // Test with text block template
    String user = "admin";
    String action = "CREATE";
    String target = "maven-releases";

    logger.info(STR."""
        Audit log entry:
        User: \{user}
        Action: \{action}
        Target: \{target}
        Timestamp: \{java.time.Instant.now()}
        """);

    // We can't verify the exact string due to the timestamp, so we verify that info() was called once
    verify(logger).info(org.mockito.ArgumentMatchers.contains("Audit log entry:"));
    verify(logger).info(org.mockito.ArgumentMatchers.contains("User: admin"));
    verify(logger).info(org.mockito.ArgumentMatchers.contains("Action: CREATE"));
    verify(logger).info(org.mockito.ArgumentMatchers.contains("Target: maven-releases"));
  }

  /**
   * Helper method to demonstrate method calls in templates
   */
  private int calculateValue(int input) {
    return input * 2;
  }
}