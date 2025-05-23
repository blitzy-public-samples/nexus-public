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
package org.sonatype.nexus.pattern;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 Record Pattern Matching features.
 * 
 * These tests validate that record pattern matching works correctly in the Nexus Repository codebase.
 * This ensures that code using these features will function properly in production.
 */
@Category(Java21TestGroup.class)
class RecordPatternMatchingTest
    extends TestSupport
{
  // Sample record definitions for testing
  record Point(int x, int y) {}
  record Rectangle(Point topLeft, Point bottomRight) {}
  record Circle(Point center, int radius) {}
  record ColoredShape(String color, Object shape) {}
  
  /**
   * Tests basic record pattern matching using instanceof with a simple record.
   */
  @Test
  void testBasicRecordPatternMatching() {
    // Create a Point record
    Point point = new Point(10, 20);
    Object obj = point;
    
    // Test pattern matching with instanceof
    if (obj instanceof Point(int x, int y)) {
      assertEquals(10, x, "x coordinate should match");
      assertEquals(20, y, "y coordinate should match");
    }
    else {
      // This should not happen
      assertFalse(true, "Pattern matching failed for Point record");
    }
  }
  
  /**
   * Tests nested record pattern matching with a Rectangle containing Points.
   */
  @Test
  void testNestedRecordPatternMatching() {
    // Create a Rectangle with two Points
    Rectangle rectangle = new Rectangle(new Point(0, 0), new Point(100, 50));
    Object obj = rectangle;
    
    // Test nested pattern matching with instanceof
    if (obj instanceof Rectangle(Point(int x1, int y1), Point(int x2, int y2))) {
      assertEquals(0, x1, "Top-left x should be 0");
      assertEquals(0, y1, "Top-left y should be 0");
      assertEquals(100, x2, "Bottom-right x should be 100");
      assertEquals(50, y2, "Bottom-right y should be 50");
    }
    else {
      // This should not happen
      assertFalse(true, "Nested pattern matching failed for Rectangle record");
    }
  }
  
  /**
   * Tests pattern matching with var instead of explicit types.
   */
  @Test
  void testPatternMatchingWithVar() {
    // Create a Circle record
    Circle circle = new Circle(new Point(50, 50), 25);
    Object obj = circle;
    
    // Test pattern matching with var
    if (obj instanceof Circle(Point(var x, var y), var radius)) {
      assertEquals(50, x, "Center x should be 50");
      assertEquals(50, y, "Center y should be 50");
      assertEquals(25, radius, "Radius should be 25");
    }
    else {
      // This should not happen
      assertFalse(true, "Pattern matching with var failed for Circle record");
    }
  }
  
  /**
   * Tests pattern matching with type patterns and guards.
   */
  @Test
  void testPatternMatchingWithGuards() {
    // Create a ColoredShape with a Circle
    ColoredShape redCircle = new ColoredShape("red", new Circle(new Point(10, 10), 5));
    Object obj = redCircle;
    
    // Test pattern matching with type patterns and guards
    if (obj instanceof ColoredShape(String color, Object shape) && shape instanceof Circle(Point center, int radius)) {
      assertEquals("red", color, "Color should be red");
      assertEquals(10, center.x(), "Center x should be 10");
      assertEquals(10, center.y(), "Center y should be 10");
      assertEquals(5, radius, "Radius should be 5");
    }
    else {
      // This should not happen
      assertFalse(true, "Pattern matching with guards failed for ColoredShape record");
    }
  }
  
  /**
   * Tests pattern matching with conditional guards.
   */
  @Test
  void testPatternMatchingWithConditionalGuards() {
    // Create a Point record
    Point point = new Point(15, 25);
    Object obj = point;
    
    // Test pattern matching with conditional guards
    if (obj instanceof Point(int x, int y) && x > 10 && y > 20) {
      assertEquals(15, x, "x should be 15");
      assertEquals(25, y, "y should be 25");
      assertTrue(x > 10 && y > 20, "Conditional guard should be satisfied");
    }
    else {
      // This should not happen
      assertFalse(true, "Pattern matching with conditional guards failed");
    }
    
    // Test a case where the guard should fail
    Point smallPoint = new Point(5, 5);
    obj = smallPoint;
    
    if (obj instanceof Point(int x, int y) && x > 10 && y > 20) {
      // This should not happen
      assertFalse(true, "Conditional guard should have failed");
    }
    else {
      // This is expected
      assertTrue(true, "Conditional guard correctly failed");
    }
  }
  
  /**
   * Tests pattern matching in combination with record methods.
   */
  @Test
  void testPatternMatchingWithRecordMethods() {
    // Create a Rectangle record
    Rectangle rectangle = new Rectangle(new Point(10, 20), new Point(30, 40));
    Object obj = rectangle;
    
    // Test pattern matching with record methods
    if (obj instanceof Rectangle(Point topLeft, Point bottomRight)) {
      assertEquals(10, topLeft.x(), "Top-left x should be 10");
      assertEquals(20, topLeft.y(), "Top-left y should be 20");
      assertEquals(30, bottomRight.x(), "Bottom-right x should be 30");
      assertEquals(40, bottomRight.y(), "Bottom-right y should be 40");
      
      // Calculate width and height using the extracted components
      int width = bottomRight.x() - topLeft.x();
      int height = bottomRight.y() - topLeft.y();
      
      assertEquals(20, width, "Width should be 20");
      assertEquals(20, height, "Height should be 20");
    }
    else {
      // This should not happen
      assertFalse(true, "Pattern matching with record methods failed");
    }
  }
}