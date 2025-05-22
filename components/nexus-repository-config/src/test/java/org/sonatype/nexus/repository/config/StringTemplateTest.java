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
package org.sonatype.nexus.repository.config;

import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;

import com.google.common.collect.ImmutableMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;

/**
 * Tests for Java 21 String Templates in repository configuration context.
 */
@Category(Java21TestGroup.class)
public class StringTemplateTest
    extends TestSupport
{
  @Mock
  private EntityId entityId;

  private ConfigurationData configuration;

  @Before
  public void setup() {
    when(entityId.getValue()).thenReturn("test-entity-id");

    configuration = new ConfigurationData();
    configuration.setId(entityId);
    configuration.setName("test-repo");
    configuration.setRecipeName("maven2-hosted");
    configuration.setOnline(true);
    configuration.setAttributes(ImmutableMap.of(
        "storage", Map.of("blobStoreName", "default"),
        "maven", Map.of("versionPolicy", "RELEASE", "layoutPolicy", "STRICT"),
        "cleanup", Map.of("policyName", "cleanup-weekly")
    ));
  }

  /**
   * Test basic string template interpolation with repository configuration properties.
   */
  @Test
  public void testBasicStringTemplateInterpolation() {
    String name = configuration.getName();
    String recipeName = configuration.getRecipeName();
    boolean online = configuration.isOnline();

    // Using Java 21 String Template for simple interpolation
    String message = STR."""
        Repository: \{name}
        Recipe: \{recipeName}
        Online: \{online}
        """;

    assertThat(message, containsString("Repository: test-repo"));
    assertThat(message, containsString("Recipe: maven2-hosted"));
    assertThat(message, containsString("Online: true"));
  }

  /**
   * Test string templates for log message formatting with repository configuration.
   */
  @Test
  public void testLogMessageFormatting() {
    String name = configuration.getName();
    String recipeName = configuration.getRecipeName();
    Map<String, Map<String, Object>> attributes = configuration.getAttributes();

    // Using Java 21 String Template for log message formatting
    String logMessage = STR."Repository \{name} (\{recipeName}) has been configured with blobStore \{attributes.get("storage").get("blobStoreName")}";

    assertThat(logMessage, is("Repository test-repo (maven2-hosted) has been configured with blobStore default"));
  }

  /**
   * Test string templates for error reporting with repository configuration.
   */
  @Test
  public void testErrorReporting() {
    String name = configuration.getName();
    String recipeName = configuration.getRecipeName();
    
    // Simulate an error condition
    String invalidAttribute = "nonexistent";
    
    // Using Java 21 String Template for error message formatting
    String errorMessage = STR."Error accessing attribute '\{invalidAttribute}' in repository \{name} (\{recipeName}). " +
        "Available attributes: \{String.join(", ", configuration.getAttributes().keySet())}";

    assertThat(errorMessage, containsString("Error accessing attribute 'nonexistent' in repository test-repo (maven2-hosted)"));
    assertThat(errorMessage, containsString("Available attributes: "));
    assertThat(errorMessage, containsString("storage"));
    assertThat(errorMessage, containsString("maven"));
    assertThat(errorMessage, containsString("cleanup"));
  }

  /**
   * Test string templates with complex expressions and conditional logic.
   */
  @Test
  public void testComplexExpressionsAndConditionalLogic() {
    String name = configuration.getName();
    boolean online = configuration.isOnline();
    Map<String, Map<String, Object>> attributes = configuration.getAttributes();
    
    // Using Java 21 String Template with complex expressions and conditional logic
    String statusMessage = STR."""
        Repository \{name} is currently \{online ? "online" : "offline"}.
        Storage: \{attributes.get("storage").get("blobStoreName")}
        Maven Version Policy: \{attributes.get("maven").get("versionPolicy")}
        Maven Layout Policy: \{attributes.get("maven").get("layoutPolicy")}
        Cleanup Policy: \{attributes.get("cleanup").containsKey("policyName") ? 
            attributes.get("cleanup").get("policyName") : "none"}
        """;

    assertThat(statusMessage, containsString("Repository test-repo is currently online."));
    assertThat(statusMessage, containsString("Storage: default"));
    assertThat(statusMessage, containsString("Maven Version Policy: RELEASE"));
    assertThat(statusMessage, containsString("Maven Layout Policy: STRICT"));
    assertThat(statusMessage, containsString("Cleanup Policy: cleanup-weekly"));
  }

  /**
   * Test string templates with proper formatting and escaping.
   */
  @Test
  public void testFormattingAndEscaping() {
    String name = configuration.getName();
    
    // Using Java 21 String Template with formatting and escaping
    String formattedMessage = STR."""
        Repository details for "\{name}":
        - ID: \{entityId.getValue()}
        - Type: \{configuration.getRecipeName().toUpperCase()}
        - Status: \{configuration.isOnline() ? "\u2713 ONLINE" : "\u2717 OFFLINE"}
        """;

    assertThat(formattedMessage, containsString("Repository details for \"test-repo\":"));
    assertThat(formattedMessage, containsString("- ID: test-entity-id"));
    assertThat(formattedMessage, containsString("- Type: MAVEN2-HOSTED"));
    assertThat(formattedMessage, containsString("- Status: \u2713 ONLINE"));
  }

  /**
   * Test string templates for sensitive information handling.
   */
  @Test
  public void testSensitiveInformationHandling() {
    // Create a configuration with sensitive information
    ConfigurationData secureConfig = new ConfigurationData();
    secureConfig.setName("secure-repo");
    secureConfig.setRecipeName("maven2-proxy");
    secureConfig.setAttributes(ImmutableMap.of(
        "httpclient", Map.of(
            "authentication", Map.of(
                "username", "admin",
                "password", "secret123",
                "ntlmHost", "host",
                "ntlmDomain", "domain"
            )
        )
    ));

    // Using Java 21 String Template with sensitive information handling
    String secureMessage = STR."""
        Repository \{secureConfig.getName()} (\{secureConfig.getRecipeName()}) authentication:
        Username: \{secureConfig.getAttributes().get("httpclient").get("authentication").get("username")}
        Password: \{maskPassword(secureConfig.getAttributes().get("httpclient").get("authentication").get("password").toString())}
        """;

    assertThat(secureMessage, containsString("Repository secure-repo (maven2-proxy) authentication:"));
    assertThat(secureMessage, containsString("Username: admin"));
    assertThat(secureMessage, containsString("Password: ******"));
    assertThat(secureMessage, not(containsString("secret123")));
  }

  /**
   * Helper method to mask passwords in log messages.
   */
  private String maskPassword(String password) {
    return password != null ? "******" : null;
  }

  /**
   * Test string templates for configuration validation error messages.
   */
  @Test
  public void testValidationErrorMessages() {
    // Create an invalid configuration
    ConfigurationData invalidConfig = new ConfigurationData();
    // Missing required name
    invalidConfig.setRecipeName("maven2-hosted");
    
    // Using Java 21 String Template for validation error messages
    String validationError = STR."Validation failed for repository configuration: " +
        "\{invalidConfig.getName() == null ? "name is required" : ""}" +
        "\{invalidConfig.getRoutingRuleId() == null ? ", routing rule is required" : ""}";
    
    assertThat(validationError, containsString("Validation failed for repository configuration: name is required"));
    assertThat(validationError, containsString(", routing rule is required"));
  }

  /**
   * Test string templates for configuration comparison and diff reporting.
   */
  @Test
  public void testConfigurationDiffReporting() {
    // Create a second configuration with some differences
    ConfigurationData updatedConfig = new ConfigurationData();
    updatedConfig.setId(entityId);
    updatedConfig.setName("test-repo");
    updatedConfig.setRecipeName("maven2-hosted");
    updatedConfig.setOnline(false); // Changed from true to false
    updatedConfig.setAttributes(ImmutableMap.of(
        "storage", Map.of("blobStoreName", "default"),
        "maven", Map.of("versionPolicy", "RELEASE", "layoutPolicy", "PERMISSIVE"), // Changed from STRICT to PERMISSIVE
        "cleanup", Map.of("policyName", "cleanup-daily") // Changed from weekly to daily
    ));
    
    // Using Java 21 String Template for configuration diff reporting
    String diffReport = STR."""
        Configuration changes for repository \{configuration.getName()}:
        - Online status: \{configuration.isOnline()} → \{updatedConfig.isOnline()}
        - Maven layout policy: \{configuration.getAttributes().get("maven").get("layoutPolicy")} → \{updatedConfig.getAttributes().get("maven").get("layoutPolicy")}
        - Cleanup policy: \{configuration.getAttributes().get("cleanup").get("policyName")} → \{updatedConfig.getAttributes().get("cleanup").get("policyName")}
        """;
    
    assertThat(diffReport, containsString("Configuration changes for repository test-repo:"));
    assertThat(diffReport, containsString("- Online status: true → false"));
    assertThat(diffReport, containsString("- Maven layout policy: STRICT → PERMISSIVE"));
    assertThat(diffReport, containsString("- Cleanup policy: cleanup-weekly → cleanup-daily"));
  }
}