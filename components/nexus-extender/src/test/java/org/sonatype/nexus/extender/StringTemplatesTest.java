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
package org.sonatype.nexus.extender;

import static java.lang.StringTemplate.STR;
import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Java 21 String Templates feature in Nexus extender components.
 * 
 * @since 3.60
 */
@DisplayName("String Templates Tests")
public class StringTemplatesTest
{
  /**
   * Tests basic string interpolation with a simple variable.
   */
  @Test
  @DisplayName("Simple variable interpolation")
  void testSimpleInterpolation() {
    String componentName = "Nexus Repository Manager";
    String message = STR."Component name: \{componentName}";
    
    assertEquals("Component name: Nexus Repository Manager", message);
  }
  
  /**
   * Tests string interpolation with multiple variables of different types.
   */
  @Test
  @DisplayName("Multiple variable interpolation")
  void testMultipleVariables() {
    String repositoryName = "maven-central";
    int itemCount = 1250;
    boolean isProxy = true;
    
    String status = STR."Repository \{repositoryName} contains \{itemCount} items and is \{isProxy ? "a proxy" : "not a proxy"} repository.";
    
    assertEquals("Repository maven-central contains 1250 items and is a proxy repository.", status);
  }
  
  /**
   * Tests string interpolation with complex expressions.
   */
  @Test
  @DisplayName("Complex expression evaluation")
  void testComplexExpressions() {
    int x = 10;
    int y = 20;
    
    String result = STR."The sum of \{x} and \{y} is \{x + y}, and their product is \{x * y}.";
    
    assertEquals("The sum of 10 and 20 is 30, and their product is 200.", result);
  }
  
  /**
   * Tests string interpolation with method calls.
   */
  @Test
  @DisplayName("Method call in template")
  void testMethodCallInTemplate() {
    String input = "nexus-repository";
    
    String result = STR."The uppercase version is \{input.toUpperCase()} and the length is \{input.length()}.";
    
    assertEquals("The uppercase version is NEXUS-REPOSITORY and the length is 15.", result);
  }
  
  /**
   * Tests string interpolation with object properties.
   */
  @Test
  @DisplayName("Object properties in template")
  void testObjectPropertiesInTemplate() {
    record Repository(String name, String format, boolean hosted) {}
    
    Repository repo = new Repository("maven-central", "maven", false);
    
    String description = STR."Repository \{repo.name()} is a \{repo.format()} repository and is \{repo.hosted() ? "hosted" : "not hosted"}.";
    
    assertEquals("Repository maven-central is a maven repository and is not hosted.", description);
  }
  
  /**
   * Tests string interpolation with collections.
   */
  @Test
  @DisplayName("Collections in template")
  void testCollectionsInTemplate() {
    List<String> formats = List.of("maven", "npm", "docker", "raw");
    
    String supportedFormats = STR."Supported formats: \{String.join(", ", formats)}";
    
    assertEquals("Supported formats: maven, npm, docker, raw", supportedFormats);
    
    Map<String, Integer> counts = Map.of("maven", 1200, "npm", 350, "docker", 75);
    String countInfo = STR."Format counts: \{counts}";
    
    assertTrue(countInfo.startsWith("Format counts: {"));
    assertTrue(countInfo.contains("maven=1200"));
    assertTrue(countInfo.contains("npm=350"));
    assertTrue(countInfo.contains("docker=75"));
  }
  
  /**
   * Tests string templates with multiline content.
   */
  @Test
  @DisplayName("Multiline templates")
  void testMultilineTemplates() {
    String user = "admin";
    String role = "nx-admin";
    
    String message = STR."""
        User Information:
        Username: \{user}
        Role: \{role}
        Status: Active
        """;
    
    assertTrue(message.contains("Username: admin"));
    assertTrue(message.contains("Role: nx-admin"));
    assertTrue(message.contains("Status: Active"));
  }
  
  /**
   * Tests escaping in string templates.
   */
  @Test
  @DisplayName("Escaping in templates")
  void testEscapingInTemplates() {
    String value = "sensitive";
    
    // Escaping the backslash to include literal \{ in output
    String escaped = STR."This shows a literal brace: \\{not a template} but this is: \{value}";
    
    assertEquals("This shows a literal brace: \{not a template} but this is: sensitive", escaped);
  }
  
  /**
   * Tests using the RAW processor to get the unprocessed template.
   */
  @Test
  @DisplayName("RAW processor usage")
  void testRawProcessor() {
    String name = "Nexus";
    int version = 3;
    
    var template = RAW."Product: \{name}, Version: \{version}";
    
    // Check fragments and values
    List<String> fragments = template.fragments();
    List<Object> values = template.values();
    
    assertEquals(3, fragments.size()); // Should have 3 fragments
    assertEquals(2, values.size());    // Should have 2 values
    
    assertEquals("Product: ", fragments.get(0));
    assertEquals(", Version: ", fragments.get(1));
    assertEquals("", fragments.get(2));
    
    assertEquals("Nexus", values.get(0));
    assertEquals(3, values.get(1));
    
    // Process the raw template with STR processor
    String processed = STR.process(template);
    assertEquals("Product: Nexus, Version: 3", processed);
  }
  
  /**
   * Tests string templates for log messages.
   */
  @Test
  @DisplayName("Log message formatting")
  void testLogMessageFormatting() {
    String operation = "repository.create";
    String user = "admin";
    Map<String, String> params = Map.of("name", "central", "format", "maven");
    
    String logMessage = STR."Operation '\{operation}' performed by user '\{user}' with parameters \{params}";
    
    assertNotNull(logMessage);
    assertTrue(logMessage.contains("Operation 'repository.create' performed by user 'admin'"));
    assertTrue(logMessage.contains("{name=central, format=maven}"));
  }
  
  /**
   * Tests string templates for error messages.
   */
  @Test
  @DisplayName("Error message formatting")
  void testErrorMessageFormatting() {
    String component = "BlobStore";
    String id = "default";
    Exception cause = new IllegalStateException("Storage unavailable");
    
    String errorMessage = STR."Failed to initialize \{component} with id '\{id}': \{cause.getMessage()}";
    
    assertEquals("Failed to initialize BlobStore with id 'default': Storage unavailable", errorMessage);
  }
  
  /**
   * Tests string templates with conditional expressions.
   */
  @Test
  @DisplayName("Conditional expressions in templates")
  void testConditionalExpressions() {
    boolean isAdmin = true;
    String username = "admin";
    
    String message = STR."User \{username} has \{isAdmin ? "administrator" : "limited"} privileges.";
    
    assertEquals("User admin has administrator privileges.", message);
    
    isAdmin = false;
    message = STR."User \{username} has \{isAdmin ? "administrator" : "limited"} privileges.";
    
    assertEquals("User admin has limited privileges.", message);
  }
}