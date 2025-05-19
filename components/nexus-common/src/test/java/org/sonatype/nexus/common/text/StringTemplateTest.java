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
package org.sonatype.nexus.common.text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.text.StringTemplateTest.Java21TestGroup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;

import static java.lang.StringTemplate.STR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Tests for Java 21 String Template feature.
 */
@EnabledForJreRange(min = JRE.JAVA_21)
public class StringTemplateTest
    extends TestSupport
{
  /**
   * Marker interface for Java 21 specific tests.
   */
  public interface Java21TestGroup {
    // Marker interface
  }

  @Test
  public void testBasicStringTemplate() {
    String name = "Nexus";
    String version = "21";
    
    String result = STR."Welcome to \{name} version \{version}";
    
    assertThat(result, is("Welcome to Nexus version 21"));
  }

  @Test
  public void testStringTemplateWithDifferentDataTypes() {
    String name = "Repository";
    int count = 42;
    boolean isActive = true;
    double size = 123.45;
    
    String result = STR."Name: \{name}, Count: \{count}, Active: \{isActive}, Size: \{size}";
    
    assertThat(result, containsString("Name: Repository"));
    assertThat(result, containsString("Count: 42"));
    assertThat(result, containsString("Active: true"));
    assertThat(result, containsString("Size: 123.45"));
  }

  @Test
  public void testStringTemplateWithExpressions() {
    int a = 10;
    int b = 20;
    
    String result = STR."Sum: \{a + b}, Product: \{a * b}, Greater: \{a > b ? a : b}";
    
    assertThat(result, is("Sum: 30, Product: 200, Greater: 20"));
  }

  @Test
  public void testStringTemplateWithMethodCalls() {
    String name = "nexus";
    
    String result = STR."Uppercase: \{name.toUpperCase()}, Length: \{name.length()}";
    
    assertThat(result, is("Uppercase: NEXUS, Length: 5"));
  }

  @Test
  public void testStringTemplateWithTextBlock() {
    String component = "BlobStore";
    String action = "initialize";
    
    String result = STR."""
        Component: \{component}
        Action: \{action}
        Status: \{action.equals("initialize") ? "Starting" : "Unknown"}
        """;
    
    assertThat(result, containsString("Component: BlobStore"));
    assertThat(result, containsString("Action: initialize"));
    assertThat(result, containsString("Status: Starting"));
  }

  @Test
  public void testStringTemplateWithNestedExpressions() {
    String outer = "outer";
    String inner = "inner";
    
    String result = STR."Nested: \{STR."\{outer} contains \{inner}"}";
    
    assertThat(result, is("Nested: outer contains inner"));
  }

  @Test
  public void testStringTemplateWithConditionalExpressions() {
    boolean condition = true;
    
    String result = STR."Status: \{condition ? "Active" : "Inactive"}";
    
    assertThat(result, is("Status: Active"));
    
    condition = false;
    result = STR."Status: \{condition ? "Active" : "Inactive"}";
    
    assertThat(result, is("Status: Inactive"));
  }

  @Test
  public void testStringTemplateWithEscaping() {
    String value = "special\nvalue";
    
    String result = STR."Value: \{value}";
    
    assertThat(result, is("Value: special\nvalue"));
  }

  @Test
  public void testStringTemplateForLogging() {
    String component = "Repository";
    String operation = "save";
    int itemCount = 15;
    
    String logMessage = STR."\{component} - \{operation} completed with \{itemCount} items";
    
    assertThat(logMessage, is("Repository - save completed with 15 items"));
  }

  @Test
  public void testStringTemplateWithDateFormatting() {
    LocalDateTime timestamp = LocalDateTime.of(2023, 12, 25, 10, 30, 0);
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    String result = STR."Event occurred at: \{formatter.format(timestamp)}";
    
    assertThat(result, is("Event occurred at: 2023-12-25 10:30:00"));
  }

  @Test
  public void testStringTemplateWithMultipleOccurrencesOfSameVariable() {
    String name = "Nexus";
    
    String result = STR."\{name} \{name} \{name}";
    
    assertThat(result, is("Nexus Nexus Nexus"));
  }

  @Test
  public void testStringTemplateWithEmptyValues() {
    String empty = "";
    String nullValue = null;
    
    String result = STR."Empty: '\{empty}', Null: '\{nullValue}'";
    
    assertThat(result, is("Empty: '', Null: 'null'"));
  }

  @Test
  public void testStringTemplateWithObjectToString() {
    Object obj = new Object() {
      @Override
      public String toString() {
        return "CustomObject";
      }
    };
    
    String result = STR."Object: \{obj}";
    
    assertThat(result, is("Object: CustomObject"));
  }

  @Test
  public void testStringTemplateDoesNotThrowWithValidInput() {
    String validInput = "valid";
    
    assertDoesNotThrow(() -> {
      String result = STR."Input: \{validInput}";
      assertThat(result, is("Input: valid"));
    });
  }

  @Test
  public void testStringTemplateWithComplexExpression() {
    int[] numbers = {1, 2, 3, 4, 5};
    
    String result = STR."Sum of array: \{java.util.Arrays.stream(numbers).sum()}";
    
    assertThat(result, is("Sum of array: 15"));
  }

  @Test
  public void testStringTemplateForErrorMessages() {
    String operation = "delete";
    String entityType = "repository";
    String entityId = "central";
    int errorCode = 404;
    
    String errorMessage = STR."Failed to \{operation} \{entityType} '\{entityId}': Error code \{errorCode}";
    
    assertThat(errorMessage, 
        is("Failed to delete repository 'central': Error code 404"));
  }

  @Test
  public void testStringTemplateWithLineBreaks() {
    String part1 = "first";
    String part2 = "second";
    
    String result = STR."""
        \{part1}
        \{part2}
        """;
    
    assertThat(result.trim(), equalTo("first\nsecond"));
  }
}