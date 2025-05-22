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

import java.io.IOException;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for using Java 21 String Templates in exception messages and error reporting contexts.
 *
 * @since 3.60
 */
@DisplayName("String Templates in Error Reporting")
public class ErrorReportingStringTemplateTest
    extends TestSupport
{
  @Test
  @DisplayName("Simple exception message with String Template")
  void testSimpleExceptionMessage() {
    String resourceName = "missing-file.txt";
    int errorCode = 404;
    
    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      throw new IllegalArgumentException(STR."Resource \{resourceName} not found (error code: \{errorCode})");
    });
    
    assertThat(exception.getMessage(), equalTo("Resource missing-file.txt not found (error code: 404)"));
  }
  
  @Test
  @DisplayName("Exception message with multiple data types")
  void testExceptionMessageWithMultipleDataTypes() {
    String operation = "read";
    int attempts = 3;
    double timeout = 1.5;
    boolean critical = true;
    
    Exception exception = assertThrows(RuntimeException.class, () -> {
      throw new RuntimeException(STR."Failed to \{operation} after \{attempts} attempts " +
          STR."(timeout: \{timeout}s, critical: \{critical})");
    });
    
    assertThat(exception.getMessage(), 
        equalTo("Failed to read after 3 attempts (timeout: 1.5s, critical: true)"));
  }
  
  @Test
  @DisplayName("Exception with nested exception using String Templates")
  void testNestedExceptionWithStringTemplates() {
    String outerOperation = "save";
    String innerOperation = "validate";
    
    Exception innerException = new IllegalStateException(STR."Failed to \{innerOperation} data");
    Exception outerException = new RuntimeException(STR."Could not \{outerOperation} due to validation error", innerException);
    
    assertThat(outerException.getMessage(), equalTo("Could not save due to validation error"));
    assertThat(outerException.getCause(), notNullValue());
    assertThat(outerException.getCause().getMessage(), equalTo("Failed to validate data"));
  }
  
  @Test
  @DisplayName("Exception with complex expression in template")
  void testExceptionWithComplexExpression() {
    Map<String, Integer> errorCounts = Map.of(
        "validation", 3,
        "connection", 2,
        "timeout", 1
    );
    
    Exception exception = assertThrows(RuntimeException.class, () -> {
      throw new RuntimeException(STR."Multiple errors occurred: " +
          STR."\{errorCounts.entrySet().stream()
              .map(e -> e.getKey() + ": " + e.getValue())
              .reduce((a, b) -> a + ", " + b)
              .orElse("none")}");
    });
    
    // The order of map entries isn't guaranteed, so we check for the presence of each error
    assertThat(exception.getMessage(), containsString("Multiple errors occurred:"));
    assertThat(exception.getMessage(), containsString("validation: 3"));
    assertThat(exception.getMessage(), containsString("connection: 2"));
    assertThat(exception.getMessage(), containsString("timeout: 1"));
  }
  
  @Test
  @DisplayName("Multi-line exception message with String Template")
  void testMultiLineExceptionMessage() {
    String username = "admin";
    String action = "delete";
    String resource = "important-file.txt";
    
    Exception exception = assertThrows(SecurityException.class, () -> {
      throw new SecurityException(STR."""
          Security violation detected:
          User: \{username}
          Action: \{action}
          Resource: \{resource}
          Timestamp: \{java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME)}
          """);
    });
    
    assertThat(exception.getMessage(), containsString("Security violation detected:"));
    assertThat(exception.getMessage(), containsString("User: admin"));
    assertThat(exception.getMessage(), containsString("Action: delete"));
    assertThat(exception.getMessage(), containsString("Resource: important-file.txt"));
    assertThat(exception.getMessage(), containsString("Timestamp:"));
  }
  
  @Test
  @DisplayName("Exception with conditional expression in template")
  void testExceptionWithConditionalExpression() {
    int retryCount = 5;
    int maxRetries = 3;
    
    Exception exception = assertThrows(IOException.class, () -> {
      throw new IOException(STR."Operation failed after \{retryCount} attempts " +
          STR."\{retryCount > maxRetries ? "(exceeded maximum of " + maxRetries + " retries)" : ""}");
    });
    
    assertThat(exception.getMessage(), 
        equalTo("Operation failed after 5 attempts (exceeded maximum of 3 retries)"));
    
    // Test the negative condition
    retryCount = 2;
    Exception exception2 = assertThrows(IOException.class, () -> {
      throw new IOException(STR."Operation failed after \{retryCount} attempts " +
          STR."\{retryCount > maxRetries ? "(exceeded maximum of " + maxRetries + " retries)" : ""}");
    });
    
    assertThat(exception2.getMessage(), equalTo("Operation failed after 2 attempts "));
  }
  
  @Test
  @DisplayName("Exception with computed values in template")
  void testExceptionWithComputedValues() {
    int[] values = {1, 2, 3, 4, 5};
    
    Exception exception = assertThrows(IllegalArgumentException.class, () -> {
      throw new IllegalArgumentException(STR."Invalid data set with \{values.length} values, " +
          STR."sum: \{java.util.Arrays.stream(values).sum()}, " +
          STR."average: \{java.util.Arrays.stream(values).average().orElse(0)}");
    });
    
    assertThat(exception.getMessage(), 
        equalTo("Invalid data set with 5 values, sum: 15, average: 3.0"));
  }
  
  @Test
  @DisplayName("Custom exception with String Template constructor")
  void testCustomExceptionWithStringTemplate() {
    String operation = "update";
    String entity = "User";
    long entityId = 12345L;
    
    CustomException exception = assertThrows(CustomException.class, () -> {
      throw new CustomException(operation, entity, entityId);
    });
    
    assertThat(exception.getMessage(), 
        equalTo("Failed to perform operation 'update' on User with ID: 12345"));
    assertThat(exception.getOperation(), equalTo("update"));
    assertThat(exception.getEntity(), equalTo("User"));
    assertThat(exception.getEntityId(), equalTo(12345L));
  }
  
  /**
   * Custom exception class that uses String Templates in its constructor.
   */
  private static class CustomException extends Exception {
    private final String operation;
    private final String entity;
    private final long entityId;
    
    public CustomException(String operation, String entity, long entityId) {
      super(STR."Failed to perform operation '\{operation}' on \{entity} with ID: \{entityId}");
      this.operation = operation;
      this.entity = entity;
      this.entityId = entityId;
    }
    
    public String getOperation() {
      return operation;
    }
    
    public String getEntity() {
      return entity;
    }
    
    public long getEntityId() {
      return entityId;
    }
  }
}