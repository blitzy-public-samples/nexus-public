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
package org.sonatype.nexus.java21;

import static java.lang.StringTemplate.RAW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.StringTemplate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Java 21's String Templates feature in CoreUI components.
 * 
 * @since 3.60
 */
public class StringTemplateTest
{
  /**
   * Tests basic string template expressions using the STR processor.
   */
  @Test
  @DisplayName("Basic string template expressions with STR processor")
  public void testBasicStringTemplateExpressions() {
    String repositoryName = "maven-central";
    int itemCount = 42;
    
    // Basic string template with single variable
    String message = STR."Repository \{repositoryName} contains \{itemCount} items.";
    
    assertNotNull(message);
    assertEquals("Repository maven-central contains 42 items.", message);
  }
  
  /**
   * Tests embedded expressions with method calls and arithmetic operations.
   */
  @Test
  @DisplayName("Embedded expressions with method calls and arithmetic")
  public void testEmbeddedExpressions() {
    String username = "admin";
    int permissionLevel = 3;
    
    // Template with method calls in embedded expressions
    String userInfo = STR."User \{username.toUpperCase()} has permission level \{permissionLevel * 2}.";
    
    assertNotNull(userInfo);
    assertEquals("User ADMIN has permission level 6.", userInfo);
    
    // Template with conditional expression
    String accessMessage = STR."User \{username} has \{permissionLevel > 2 ? "admin" : "user"} access.";
    
    assertNotNull(accessMessage);
    assertEquals("User admin has admin access.", accessMessage);
  }
  
  /**
   * Tests multi-line template processing using text blocks.
   */
  @Test
  @DisplayName("Multi-line template processing with text blocks")
  public void testMultiLineTemplates() {
    String componentName = "nexus-core";
    String version = "3.60.0";
    boolean isSnapshot = true;
    
    // Multi-line template using text block
    String componentInfo = STR."""
        Component Information:
        - Name: \{componentName}
        - Version: \{version}\{isSnapshot ? "-SNAPSHOT" : ""}
        - Status: \{isSnapshot ? "Development" : "Release"}
        """;
    
    assertNotNull(componentInfo);
    assertTrue(componentInfo.contains("Component Information:"));
    assertTrue(componentInfo.contains("Name: nexus-core"));
    assertTrue(componentInfo.contains("Version: 3.60.0-SNAPSHOT"));
    assertTrue(componentInfo.contains("Status: Development"));
  }
  
  /**
   * Tests the RAW template processor and StringTemplate methods.
   */
  @Test
  @DisplayName("RAW template processor and StringTemplate methods")
  public void testRawTemplateProcessor() {
    String blobStoreName = "default";
    long sizeInBytes = 1024 * 1024 * 100; // 100 MB
    
    // Using RAW processor to get the StringTemplate object
    StringTemplate template = RAW."BlobStore '\{blobStoreName}' size: \{sizeInBytes} bytes";
    
    // Verify fragments and values
    List<String> fragments = template.fragments();
    List<Object> values = template.values();
    
    assertEquals(3, fragments.size());
    assertEquals("BlobStore '", fragments.get(0));
    assertEquals("' size: ", fragments.get(1));
    assertEquals(" bytes", fragments.get(2));
    
    assertEquals(2, values.size());
    assertEquals("default", values.get(0));
    assertEquals(104857600L, values.get(1));
    
    // Use interpolate to get the final string
    String result = template.interpolate();
    assertEquals("BlobStore 'default' size: 104857600 bytes", result);
  }
  
  /**
   * Tests template expressions for logging scenarios in CoreUI components.
   */
  @Test
  @DisplayName("Template expressions for logging scenarios")
  public void testLoggingTemplates() {
    String operation = "upload";
    String fileName = "example.jar";
    String repository = "maven-releases";
    
    // Log message template
    String logMessage = STR."[\{System.currentTimeMillis()}] \{operation.toUpperCase()} operation for '\{fileName}' to repository '\{repository}'";
    
    assertNotNull(logMessage);
    assertTrue(logMessage.matches("\\[\\d+\\] UPLOAD operation for 'example\.jar' to repository 'maven-releases'"));
  }
  
  /**
   * Tests template expressions for error reporting in CoreUI components.
   */
  @Test
  @DisplayName("Template expressions for error reporting")
  public void testErrorReportingTemplates() {
    int errorCode = 404;
    String resourcePath = "/api/v1/repositories";
    
    // Error message template
    String errorMessage = STR."Error \{errorCode}: Resource not found at path '\{resourcePath}'";
    
    assertNotNull(errorMessage);
    assertEquals("Error 404: Resource not found at path '/api/v1/repositories'", errorMessage);
    
    // Error with details template
    Exception cause = new IllegalArgumentException("Invalid parameter");
    String detailedError = STR."""
        Error Details:
        - Code: \{errorCode}
        - Path: \{resourcePath}
        - Cause: \{cause.getMessage()}
        """;
    
    assertNotNull(detailedError);
    assertTrue(detailedError.contains("Error Details:"));
    assertTrue(detailedError.contains("Code: 404"));
    assertTrue(detailedError.contains("Path: /api/v1/repositories"));
    assertTrue(detailedError.contains("Cause: Invalid parameter"));
  }
  
  /**
   * Tests template expressions for user-facing messages in CoreUI components.
   */
  @Test
  @DisplayName("Template expressions for user-facing messages")
  public void testUserFacingMessageTemplates() {
    String userName = "admin";
    int itemsProcessed = 150;
    int totalItems = 200;
    
    // User notification template
    String notification = STR."Hello \{userName}, \{itemsProcessed} out of \{totalItems} items processed (\{itemsProcessed * 100 / totalItems}%).";
    
    assertNotNull(notification);
    assertEquals("Hello admin, 150 out of 200 items processed (75%).", notification);
  }
}