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
package org.sonatype.java21;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.upgrade.Upgrade;
import org.sonatype.nexus.common.upgrade.events.UpgradeCompletedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeFailedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeStartedEvent;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.UpgradeException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 string template features in the Nexus upgrade framework.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class StringTemplateUpgradeTest
    extends TestSupport
{
  private static final String VERSION = "1.0";
  private static final String MODEL = "test-model";
  private static final String STEP_NAME = "TestMigrationStep";

  @Mock
  private DatabaseMigrationStep migrationStep;

  @BeforeEach
  void setUp() {
    when(migrationStep.version()).thenReturn(Optional.of(VERSION));
  }

  /**
   * Test basic string template usage for upgrade log messages.
   */
  @Test
  void testBasicStringTemplateForLogMessages() {
    String stepName = STEP_NAME;
    String version = VERSION;
    String model = MODEL;
    
    // Using string template for log message
    String logMessage = STR."Executing migration step \{stepName} for model \{model} at version \{version}";
    
    // Verify the string template was correctly processed
    assertThat(logMessage, containsString("Executing migration step"));
    assertThat(logMessage, containsString(STEP_NAME));
    assertThat(logMessage, containsString(MODEL));
    assertThat(logMessage, containsString(VERSION));
    
    // Verify the exact string content
    String expectedMessage = "Executing migration step TestMigrationStep for model test-model at version 1.0";
    assertThat(logMessage, is(equalTo(expectedMessage)));
  }

  /**
   * Test string templates with expressions for upgrade event messages.
   */
  @Test
  void testStringTemplateWithExpressionsForEvents() {
    String model = MODEL;
    List<String> steps = Arrays.asList("Step1", "Step2", "Step3");
    int stepCount = steps.size();
    
    // Using string template with expressions for event message
    String eventMessage = STR."Starting upgrade for model \{model} with \{stepCount} steps: \{String.join(", ", steps)}";
    
    // Create an upgrade event with the message
    UpgradeStartedEvent event = new UpgradeStartedEvent(eventMessage);
    
    // Verify the event message
    assertThat(event.getMessage(), containsString("Starting upgrade for model"));
    assertThat(event.getMessage(), containsString("3 steps"));
    assertThat(event.getMessage(), containsString("Step1, Step2, Step3"));
    
    // Verify the exact string content
    String expectedMessage = "Starting upgrade for model test-model with 3 steps: Step1, Step2, Step3";
    assertThat(event.getMessage(), is(equalTo(expectedMessage)));
  }

  /**
   * Test string templates for error message formatting.
   */
  @Test
  void testStringTemplateForErrorMessages() {
    String stepName = STEP_NAME;
    String errorCode = "DB-001";
    String sqlState = "42S02";
    int vendorCode = 208;
    
    // Using string template for error message with embedded expressions
    String errorMessage = STR."Migration step \{stepName} failed with error code \{errorCode}: " +
        STR."SQL error state \{sqlState}, vendor code \{vendorCode}";
    
    // Create an exception with the error message
    UpgradeException exception = new UpgradeException(errorMessage);
    
    // Verify the exception message
    assertThat(exception.getMessage(), containsString("Migration step TestMigrationStep failed"));
    assertThat(exception.getMessage(), containsString("error code DB-001"));
    assertThat(exception.getMessage(), containsString("SQL error state 42S02"));
    assertThat(exception.getMessage(), containsString("vendor code 208"));
    
    // Create an upgrade failed event with the exception
    UpgradeFailedEvent event = new UpgradeFailedEvent(exception);
    
    // Verify the event contains the exception message
    assertThat(event.getCause(), is(exception));
  }

  /**
   * Test string templates for upgrade reporting with complex data.
   */
  @Test
  void testStringTemplateForUpgradeReporting() {
    // Mock upgrade data
    Map<String, Object> upgradeData = Map.of(
        "version", "3.42.0",
        "timestamp", "2025-05-22T14:30:00Z",
        "duration", 1234,
        "success", true,
        "steps", Arrays.asList("Step1", "Step2", "Step3")
    );
    
    // Using string template for upgrade report
    String report = STR."""
        Upgrade Report:
        --------------
        Version: \{upgradeData.get("version")}
        Timestamp: \{upgradeData.get("timestamp")}
        Duration: \{upgradeData.get("duration")} ms
        Success: \{upgradeData.get("success")}
        Steps: \{upgradeData.get("steps")}
        """;
    
    // Verify the report content
    assertThat(report, containsString("Upgrade Report:"));
    assertThat(report, containsString("Version: 3.42.0"));
    assertThat(report, containsString("Timestamp: 2025-05-22T14:30:00Z"));
    assertThat(report, containsString("Duration: 1234 ms"));
    assertThat(report, containsString("Success: true"));
    assertThat(report, containsString("Steps: [Step1, Step2, Step3]"));
  }

  /**
   * Test string templates with conditional expressions.
   */
  @Test
  void testStringTemplateWithConditionalExpressions() {
    boolean success = true;
    int stepCount = 3;
    long duration = 1500;
    
    // Using string template with conditional expressions
    String message = STR."Upgrade \{success ? "completed successfully" : "failed"} " +
        STR."with \{stepCount} steps in \{duration} ms " +
        STR."(\{duration > 1000 ? "slow" : "fast"})";
    
    // Create an upgrade completed event with the message
    UpgradeCompletedEvent event = new UpgradeCompletedEvent(message);
    
    // Verify the event message
    assertThat(event.getMessage(), containsString("completed successfully"));
    assertThat(event.getMessage(), containsString("with 3 steps"));
    assertThat(event.getMessage(), containsString("in 1500 ms"));
    assertThat(event.getMessage(), containsString("(slow)"));
    
    // Verify with different conditions
    success = false;
    duration = 500;
    
    message = STR."Upgrade \{success ? "completed successfully" : "failed"} " +
        STR."with \{stepCount} steps in \{duration} ms " +
        STR."(\{duration > 1000 ? "slow" : "fast"})";
    
    // Verify the message with new conditions
    assertThat(message, containsString("failed"));
    assertThat(message, containsString("with 3 steps"));
    assertThat(message, containsString("in 500 ms"));
    assertThat(message, containsString("(fast)"));
  }

  /**
   * Test string templates with arithmetic expressions.
   */
  @Test
  void testStringTemplateWithArithmeticExpressions() {
    int baseVersion = 3;
    int minorVersion = 42;
    int patchVersion = 1;
    
    // Using string template with arithmetic expressions
    String versionInfo = STR."Current version: \{baseVersion}.\{minorVersion}.\{patchVersion}, " +
        STR."Next version: \{baseVersion}.\{minorVersion}.\{patchVersion + 1}, " +
        STR."Major upgrade: \{baseVersion + 1}.0.0";
    
    // Verify the version info
    assertThat(versionInfo, containsString("Current version: 3.42.1"));
    assertThat(versionInfo, containsString("Next version: 3.42.2"));
    assertThat(versionInfo, containsString("Major upgrade: 4.0.0"));
  }

  /**
   * Test string templates with method calls in embedded expressions.
   */
  @Test
  void testStringTemplateWithMethodCalls() {
    String modelName = "test-model";
    
    // Using string template with method calls
    String message = STR."Model name: \{modelName.toUpperCase()}, " +
        STR."Length: \{modelName.length()}, " +
        STR."First char: \{modelName.charAt(0)}";
    
    // Verify the message
    assertThat(message, containsString("Model name: TEST-MODEL"));
    assertThat(message, containsString("Length: 10"));
    assertThat(message, containsString("First char: t"));
  }

  /**
   * Test string templates with parameterized inputs.
   */
  @ParameterizedTest
  @ValueSource(strings = {"1.0.0", "2.0.0", "3.0.0"})
  void testStringTemplateWithParameterizedInputs(String version) {
    // Using string template with parameterized input
    String message = STR."Processing upgrade to version \{version}";
    
    // Verify the message
    assertThat(message, containsString("Processing upgrade to version " + version));
  }

  /**
   * Test string templates with optional values.
   */
  @Test
  void testStringTemplateWithOptionalValues() {
    Optional<String> presentVersion = Optional.of("1.0.0");
    Optional<String> emptyVersion = Optional.empty();
    
    // Using string template with optional values
    String presentMessage = STR."Version: \{presentVersion.orElse("unknown")}";
    String emptyMessage = STR."Version: \{emptyVersion.orElse("unknown")}";
    
    // Verify the messages
    assertThat(presentMessage, is(equalTo("Version: 1.0.0")));
    assertThat(emptyMessage, is(equalTo("Version: unknown")));
  }

  /**
   * Test compatibility with existing upgrade framework by creating a mock migration step
   * and using string templates to format its description.
   */
  @Test
  void testCompatibilityWithExistingUpgradeFramework() {
    // Mock a database migration step
    when(migrationStep.toString()).thenReturn(STEP_NAME);
    
    // Using string template to format migration step description
    String description = STR."Migration step \{migrationStep} for version \{migrationStep.version().orElse("unknown")}";
    
    // Verify the description
    assertThat(description, is(equalTo("Migration step TestMigrationStep for version 1.0")));
    
    // Test with a null version
    when(migrationStep.version()).thenReturn(Optional.empty());
    description = STR."Migration step \{migrationStep} for version \{migrationStep.version().orElse("unknown")}";
    
    // Verify the description with null version
    assertThat(description, is(equalTo("Migration step TestMigrationStep for version unknown")));
  }
}