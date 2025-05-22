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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

/**
 * Tests for using Java 21 String Templates in configuration processing.
 */
public class ConfigurationTemplateTest
    extends TestSupport
{
  private static final String TEST_ENV_VAR = "NEXUS_TEST_CONFIG";
  private static final String TEST_SYSTEM_PROP = "nexus.test.config";

  @Mock
  private ConfigurationSource mockConfigSource;

  @Before
  public void setup() {
    // Set up test environment variable and system property
    System.setProperty(TEST_SYSTEM_PROP, "system-value");
    // Note: We can't easily set environment variables in Java, so we'll mock the access
  }

  @After
  public void teardown() {
    System.clearProperty(TEST_SYSTEM_PROP);
  }

  /**
   * Test basic String Template interpolation with configuration values.
   */
  @Test
  public void testBasicTemplateInterpolation() {
    String host = "localhost";
    int port = 8081;
    
    // Using String Templates for configuration formatting
    String configValue = STR."Server running at http://\{host}:\{port}/";
    
    assertThat(configValue, is("Server running at http://localhost:8081/"));
  }

  /**
   * Test String Template interpolation with system properties.
   */
  @Test
  public void testSystemPropertyInterpolation() {
    String configValue = STR."System property value: \{System.getProperty(TEST_SYSTEM_PROP)}";
    
    assertThat(configValue, is("System property value: system-value"));
  }

  /**
   * Test String Template interpolation with environment variables.
   */
  @Test
  public void testEnvironmentVariableInterpolation() {
    // Mock environment variable access
    when(mockConfigSource.getEnvironmentVariable(TEST_ENV_VAR)).thenReturn("env-value");
    
    String configValue = STR."Environment variable value: \{mockConfigSource.getEnvironmentVariable(TEST_ENV_VAR)}";
    
    assertThat(configValue, is("Environment variable value: env-value"));
  }

  /**
   * Test nested expressions in String Templates for configuration.
   */
  @Test
  public void testNestedExpressions() {
    Map<String, Object> config = Map.of(
        "db.url", "jdbc:postgresql://localhost:5432/nexus",
        "db.username", "nexus_user",
        "db.password", "secret"
    );
    
    String connectionString = STR."""
        Database connection configured with:
        URL: \{config.get("db.url")}
        Username: \{config.get("db.username")}
        Password: \{config.get("db.password").toString().replaceAll(".", "*")}
        """;
    
    assertThat(connectionString, containsString("URL: jdbc:postgresql://localhost:5432/nexus"));
    assertThat(connectionString, containsString("Username: nexus_user"));
    assertThat(connectionString, containsString("Password: ******"));
  }

  /**
   * Test conditional formatting in String Templates for configuration.
   */
  @Test
  public void testConditionalFormatting() {
    boolean sslEnabled = true;
    int port = sslEnabled ? 443 : 80;
    
    String configValue = STR."Server running on \{sslEnabled ? "https" : "http"}://localhost:\{port}/";
    
    assertThat(configValue, is("Server running on https://localhost:443/"));
    
    sslEnabled = false;
    port = sslEnabled ? 443 : 80;
    
    configValue = STR."Server running on \{sslEnabled ? "https" : "http"}://localhost:\{port}/";
    
    assertThat(configValue, is("Server running on http://localhost:80/"));
  }

  /**
   * Test error handling with malformed templates.
   */
  @Test
  public void testErrorHandlingWithMalformedTemplates() {
    String nullValue = null;
    
    // Test handling null values in templates
    NullPointerException exception = assertThrows(NullPointerException.class, () -> {
      String configValue = STR."Config value: \{nullValue.toString()}";
    });
    
    assertThat(exception.getMessage(), containsString("Cannot invoke"));
    assertThat(exception.getMessage(), containsString("because "nullValue" is null"));
  }
  
  /**
   * Test String Templates with complex expressions for configuration formatting.
   */
  @Test
  public void testComplexExpressions() {
    int maxHeapSize = 4096;
    int initialHeapSize = maxHeapSize / 2;
    String[] supportedProtocols = {"TLSv1.2", "TLSv1.3"};
    
    String jvmConfig = STR."""
    JVM Configuration:
    -Xmx\{maxHeapSize}m
    -Xms\{initialHeapSize}m
    -Dhttps.protocols=\{String.join(",", supportedProtocols)}
    """;
    
    assertThat(jvmConfig, containsString("-Xmx4096m"));
    assertThat(jvmConfig, containsString("-Xms2048m"));
    assertThat(jvmConfig, containsString("-Dhttps.protocols=TLSv1.2,TLSv1.3"));
  }

  /**
   * Test compatibility with OSGi configuration mechanisms.
   */
  @Test
  public void testOsgiConfigurationCompatibility() {
    // Mock OSGi configuration property access
    when(mockConfigSource.getOsgiProperty("nexus.http.host")).thenReturn("0.0.0.0");
    when(mockConfigSource.getOsgiProperty("nexus.http.port")).thenReturn("8081");
    
    String host = mockConfigSource.getOsgiProperty("nexus.http.host");
    String port = mockConfigSource.getOsgiProperty("nexus.http.port");
    
    String configValue = STR."Nexus listening on \{host}:\{port}";
    
    assertThat(configValue, is("Nexus listening on 0.0.0.0:8081"));
  }
  
  /**
   * Test using String Templates with multi-line configuration values.
   */
  @Test
  public void testMultiLineConfigurationValues() {
    String jdbcUrl = "jdbc:postgresql://localhost:5432/nexus";
    String username = "nexus_user";
    int maxConnections = 50;
    boolean enableSsl = true;
    
    String configBlock = STR."""
    <datasource>
        <url>\{jdbcUrl}</url>
        <username>\{username}</username>
        <max-connections>\{maxConnections}</max-connections>
        <ssl-enabled>\{enableSsl}</ssl-enabled>
    </datasource>
    """;
    
    assertThat(configBlock, containsString("<url>jdbc:postgresql://localhost:5432/nexus</url>"));
    assertThat(configBlock, containsString("<username>nexus_user</username>"));
    assertThat(configBlock, containsString("<max-connections>50</max-connections>"));
    assertThat(configBlock, containsString("<ssl-enabled>true</ssl-enabled>"));
  }

  /**
   * Test using String Templates for sanitizing sensitive configuration data.
   */
  @Test
  public void testSanitizingSensitiveData() {
    String username = "admin";
    String password = "admin123";
    String apiKey = "abcdef123456";
    
    String sanitizedConfig = STR."""
        Configuration:
        Username: \{username}
        Password: \{"*".repeat(password.length())}
        API Key: \{apiKey.substring(0, 3) + "***" + apiKey.substring(apiKey.length() - 3)}
        """;
    
    assertThat(sanitizedConfig, containsString("Username: admin"));
    assertThat(sanitizedConfig, containsString("Password: ********"));
    assertThat(sanitizedConfig, containsString("API Key: abc***456"));
  }
  
  /**
   * Test using String Templates for formatting configuration error messages.
   */
  @Test
  public void testConfigurationErrorMessages() {
    String configKey = "nexus.http.port";
    String expectedType = "Integer";
    String actualValue = "invalid-port";
    
    String errorMessage = STR."Configuration error: Value '\{actualValue}' for key '\{configKey}' cannot be converted to \{expectedType}";
    
    assertThat(errorMessage, is("Configuration error: Value 'invalid-port' for key 'nexus.http.port' cannot be converted to Integer"));
  }

  /**
   * Mock interface for configuration sources.
   */
  interface ConfigurationSource {
    String getEnvironmentVariable(String name);
    String getOsgiProperty(String name);
  }
}