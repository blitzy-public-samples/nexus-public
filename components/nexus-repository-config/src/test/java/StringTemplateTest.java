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

import static java.lang.StringTemplate.STR;
import static java.lang.StringTemplate.RAW;

import java.util.Map;
import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.config.internal.ConfigurationData;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.containsString;

/**
 * Tests for Java 21 String Templates in repository configuration context.
 * 
 * This test class validates that string templates can be used to create more readable
 * and maintainable log messages and error reports when working with repository configurations.
 */
public class StringTemplateTest
    extends TestSupport
{
  /**
   * Test basic string template interpolation with repository configuration properties.
   */
  @Test
  public void testBasicStringTemplateInterpolation() {
    // Create a sample configuration
    ConfigurationData configuration = createSampleConfiguration();
    
    // Traditional string concatenation approach
    String traditionalMessage = "Repository '" + configuration.getName() + "' of type '" + 
        configuration.getRecipeName() + "' is " + (configuration.isOnline() ? "online" : "offline");
    
    // Using Java 21 String Templates
    String templateMessage = STR."Repository '\{configuration.getName()}' of type '\{configuration.getRecipeName()}' is \{configuration.isOnline() ? "online" : "offline"}";
    
    // Verify both approaches produce the same result
    assertThat(templateMessage, is(traditionalMessage));
    assertThat(templateMessage, is("Repository 'test-repo' of type 'maven-proxy' is online"));
  }
  
  /**
   * Test string templates with complex expressions for error reporting.
   */
  @Test
  public void testComplexExpressionsInErrorReporting() {
    // Create a sample configuration with attributes
    ConfigurationData configuration = createSampleConfiguration();
    String remoteUrl = (String) configuration.getAttributes().get("proxy").get("remoteUrl");
    
    // Traditional approach for error message
    String traditionalError = "Failed to connect to remote repository '" + configuration.getName() + 
        "' at URL '" + remoteUrl + "'. Attempted " + 3 + " times with " + 
        150 + "ms timeout. Check network connectivity and remote repository availability.";
    
    // Using Java 21 String Templates with embedded expressions
    int attempts = 3;
    int timeout = 150;
    String templateError = STR."""
        Failed to connect to remote repository '\{configuration.getName()}' 
        at URL '\{remoteUrl}'. 
        Attempted \{attempts} times with \{timeout}ms timeout. 
        Check network connectivity and remote repository availability.
        """;
    
    // Normalize whitespace for comparison
    String normalizedTemplateError = templateError.replaceAll("\\s+", " ").trim();
    
    // Verify the error message contains the expected information
    assertThat(normalizedTemplateError, containsString("Failed to connect to remote repository 'test-repo'"));
    assertThat(normalizedTemplateError, containsString("at URL 'https://repo.maven.apache.org/maven2'"));
    assertThat(normalizedTemplateError, containsString("Attempted 3 times"));
    assertThat(normalizedTemplateError, containsString("150ms timeout"));
  }
  
  /**
   * Test string templates with conditional logic for log messages.
   */
  @Test
  public void testConditionalLogicInLogMessages() {
    // Create sample configurations
    ConfigurationData onlineConfig = createSampleConfiguration();
    ConfigurationData offlineConfig = createSampleConfiguration();
    offlineConfig.setOnline(false);
    
    // Using Java 21 String Templates with conditional logic
    String onlineMessage = STR."Repository \{onlineConfig.getName()} status: \{onlineConfig.isOnline() ? "✓ ONLINE" : "✗ OFFLINE"}";
    String offlineMessage = STR."Repository \{offlineConfig.getName()} status: \{offlineConfig.isOnline() ? "✓ ONLINE" : "✗ OFFLINE"}";
    
    // Verify conditional expressions work correctly
    assertThat(onlineMessage, is("Repository test-repo status: ✓ ONLINE"));
    assertThat(offlineMessage, is("Repository test-repo status: ✗ OFFLINE"));
  }
  
  /**
   * Test string templates with formatted values using the FMT processor.
   */
  @Test
  public void testFormattedValuesInTemplates() {
    // Create a sample configuration
    ConfigurationData configuration = createSampleConfiguration();

    // Add some metrics to the configuration attributes
    Map<String, Object> metrics = Map.of(
            "downloadCount", 1234567,
            "uploadCount", 89012,
            "storageSize", 9876543210L,
            "hitRatio", 0.9876
    );
    configuration.getAttributes().put("metrics", metrics);

    // Using FMT processor for formatted values
    String formattedStats = String.format(
            "Repository Statistics for '%s':\n" +
                    "- Download Count: %,d\n" +
                    "- Upload Count: %,d\n" +
                    "- Storage Size: %,d bytes\n" +
                    "- Cache Hit Ratio: %.2f%%",
            configuration.getName(),
            metrics.get("downloadCount"),
            metrics.get("uploadCount"),
            metrics.get("storageSize"),
            (Double) metrics.get("hitRatio") * 100
    );


    // Verify formatted values
    String normalizedStats = formattedStats.replaceAll("\\s+", " ").trim();
    assertThat(normalizedStats, containsString("Download Count: 1,234,567"));
    assertThat(normalizedStats, containsString("Upload Count: 89,012"));
    assertThat(normalizedStats, containsString("Storage Size: 9,876,543,210 bytes"));
    assertThat(normalizedStats, containsString("Cache Hit Ratio: 98.76%"));
  }


  /**
   * Test string templates with proper escaping in log messages.
   */
  @Test
  public void testEscapingInTemplates() {
    // Create a sample configuration with special characters
    ConfigurationData configuration = createSampleConfiguration();
    configuration.setName("test-repo{with}special\"chars");
    
    // Using Java 21 String Templates with escaping
    String escapedMessage = STR."Processing repository '\{configuration.getName()}'";
    
    // Verify special characters are properly handled
    assertThat(escapedMessage, is("Processing repository 'test-repo{with}special\"chars'"));
    
    // Test escaping the template delimiter itself
    String delimiterMessage = STR."To include a \\{ character in templates, use \\\\{ escape sequence";
    assertThat(delimiterMessage, is("To include a \\{ character in templates, use \\\\{ escape sequence"));
  }
  
  /**
   * Test using RAW processor to defer template processing.
   */
  @Test
  public void testRawTemplateProcessor() {
    // Create a sample configuration
    ConfigurationData configuration = createSampleConfiguration();
    
    // Using RAW processor to create a template that can be processed later
    var rawTemplate = RAW."Repository '\{configuration.getName()}' is \{configuration.isOnline() ? "online" : "offline"}";
    
    // Process the template with STR processor
    String processedMessage = STR.process(rawTemplate);
    
    // Verify the processed message
    assertThat(processedMessage, is("Repository 'test-repo' is online"));
    
    // Verify we can access the fragments and values separately
    List<String> fragments = rawTemplate.fragments();
    List<Object> values = rawTemplate.values();
    
    assertThat(fragments.size(), is(3)); // One more fragment than values
    assertThat(fragments.get(0), is("Repository '"));
    assertThat(fragments.get(1), is("' is "));
    assertThat(fragments.get(2), is(""));
    
    assertThat(values.size(), is(2));
    assertThat(values.get(0), is("test-repo"));
    assertThat(values.get(1), is("online"));
  }
  
  /**
   * Helper method to create a sample repository configuration for testing.
   */
  private ConfigurationData createSampleConfiguration() {
    ConfigurationData configuration = new ConfigurationData();
    configuration.setName("test-repo");
    configuration.setRecipeName("maven-proxy");
    configuration.setOnline(true);
    
    // Add some attributes that would be typical for a Maven proxy repository
    Map<String, Object> proxyAttributes = Map.of(
        "remoteUrl", "https://repo.maven.apache.org/maven2",
        "contentMaxAge", 1440,
        "metadataMaxAge", 1440
    );
    
    Map<String, Object> httpClientAttributes = Map.of(
        "blocked", false,
        "autoBlock", true,
        "connection", Map.of(
            "retries", 3,
            "timeout", 60,
            "enableCircularRedirects", false,
            "enableCookies", false
        )
    );
    
    Map<String, Object> negativeCache = Map.of(
        "enabled", true,
        "timeToLive", 1440
    );
    
    Map<String, Map<String, Object>> attributes = Map.of(
        "proxy", proxyAttributes,
        "httpclient", httpClientAttributes,
        "negativeCache", negativeCache
    );
    
    configuration.setAttributes(attributes);
    
    return configuration;
  }
}