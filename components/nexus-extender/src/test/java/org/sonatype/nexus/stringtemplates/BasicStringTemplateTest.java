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

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.lang.StringTemplate.RAW;
import static java.lang.StringTemplate.STR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for basic Java 21 String Template functionality in the Nexus OSGi environment.
 * 
 * @since 3.60
 */
@DisplayName("Basic String Template Tests")
public class BasicStringTemplateTest
{
  @Test
  @DisplayName("Basic string interpolation with STR processor")
  void testBasicInterpolation() {
    String name = "Nexus";
    int version = 21;
    
    String result = STR."Hello \{name} running on Java \{version}!";
    
    assertEquals("Hello Nexus running on Java 21!", result);
  }
  
  @Test
  @DisplayName("String templates with different data types")
  void testDifferentDataTypes() {
    String text = "repository";
    int count = 42;
    double size = 3.14159;
    boolean isActive = true;
    
    String result = STR."The \{text} contains \{count} artifacts with a total size of \{size} MB. Active: \{isActive}";
    
    assertEquals("The repository contains 42 artifacts with a total size of 3.14159 MB. Active: true", result);
  }
  
  @Test
  @DisplayName("String templates with expressions")
  void testExpressions() {
    int a = 10;
    int b = 20;
    
    String result = STR."The sum of \{a} and \{b} is \{a + b}";
    
    assertEquals("The sum of 10 and 20 is 30", result);
  }
  
  @Test
  @DisplayName("String templates with method calls")
  void testMethodCalls() {
    String name = "nexus repository";
    
    String result = STR."The capitalized name is \{name.toUpperCase()}";
    
    assertEquals("The capitalized name is NEXUS REPOSITORY", result);
  }
  
  @Test
  @DisplayName("Multi-line text blocks with string templates")
  void testMultiLineTextBlocks() {
    String title = "Nexus Repository Manager";
    String version = "3.60";
    
    String result = STR."""
        Welcome to \{title}
        Version: \{version}
        Running on Java 21
        """;
    
    String expected = """
        Welcome to Nexus Repository Manager
        Version: 3.60
        Running on Java 21
        """;
    
    assertEquals(expected, result);
  }
  
  @Test
  @DisplayName("Using RAW processor to access template fragments and values")
  void testRawProcessor() {
    String name = "Nexus";
    int version = 21;
    
    var template = RAW."Hello \{name} running on Java \{version}!";
    
    // Check fragments
    List<String> fragments = template.fragments();
    assertEquals(3, fragments.size());
    assertEquals("Hello ", fragments.get(0));
    assertEquals(" running on Java ", fragments.get(1));
    assertEquals("!", fragments.get(2));
    
    // Check values
    List<Object> values = template.values();
    assertEquals(2, values.size());
    assertEquals("Nexus", values.get(0));
    assertEquals(21, values.get(1));
    
    // Convert RAW to interpolated string
    String interpolated = template.interpolate();
    assertEquals("Hello Nexus running on Java 21!", interpolated);
  }
  
  @Test
  @DisplayName("Nested string templates")
  void testNestedTemplates() {
    String outer = "outer";
    String inner = "inner";
    
    String result = STR."This is an \{outer} template with an \{STR."\{inner} template"}";
    
    assertEquals("This is an outer template with an inner template", result);
  }
  
  @Test
  @DisplayName("String templates with conditional expressions")
  void testConditionalExpressions() {
    boolean isAdmin = true;
    
    String result = STR."User has \{isAdmin ? "admin" : "limited"} access";
    
    assertEquals("User has admin access", result);
    
    isAdmin = false;
    result = STR."User has \{isAdmin ? "admin" : "limited"} access";
    
    assertEquals("User has limited access", result);
  }
  
  @Test
  @DisplayName("String templates with object methods")
  void testObjectMethods() {
    record User(String name, String role) {
      @Override
      public String toString() {
        return name + " (" + role + ")";
      }
    }
    
    User user = new User("admin", "Administrator");
    
    String result = STR."Current user: \{user}";
    
    assertEquals("Current user: admin (Administrator)", result);
  }
  
  @Test
  @DisplayName("String templates with null values")
  void testNullValues() {
    String nullValue = null;
    
    String result = STR."The value is \{nullValue}";
    
    assertEquals("The value is null", result);
  }
  
  @Test
  @DisplayName("String templates with escaped braces")
  void testEscapedBraces() {
    String name = "Nexus";
    
    // Use double backslash to escape the brace in the template
    String result = STR."Hello \{name}! This is a literal \\{brace}";
    
    assertEquals("Hello Nexus! This is a literal {brace}", result);
  }
  
  /**
   * A simple custom template processor that converts all embedded values to uppercase.
   */
  static class UpperCaseProcessor
      implements StringTemplate.Processor<String, RuntimeException>
  {
    @Override
    public String process(StringTemplate template) {
      StringBuilder result = new StringBuilder();
      List<String> fragments = template.fragments();
      List<Object> values = template.values();
      
      for (int i = 0; i < values.size(); i++) {
        result.append(fragments.get(i));
        Object value = values.get(i);
        result.append(value != null ? value.toString().toUpperCase() : "NULL");
      }
      
      result.append(fragments.get(fragments.size() - 1));
      return result.toString();
    }
  }
  
  private static final UpperCaseProcessor UPPER = new UpperCaseProcessor();
  
  @Test
  @DisplayName("Custom template processor")
  void testCustomProcessor() {
    String name = "nexus";
    String version = "repository";
    
    String result = UPPER.process(RAW."Hello \{name} \{version}!");
    
    assertEquals("Hello NEXUS REPOSITORY!", result);
  }
}