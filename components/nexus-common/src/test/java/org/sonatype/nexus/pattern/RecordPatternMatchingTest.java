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

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 Record Pattern Matching features.
 * 
 * These tests validate that record pattern matching works correctly in the Nexus codebase
 * after upgrading to Java 21. They ensure that basic record patterns, nested record patterns,
 * and pattern matching with type guards function as expected.
 */
@Category(Java21TestGroup.class)
public class RecordPatternMatchingTest
    extends TestSupport
{
  // Sample record definitions for testing
  record Point(int x, int y) {}
  record Rectangle(Point topLeft, Point bottomRight) {}
  record Circle(Point center, int radius) {}
  record ColoredShape(String color, Object shape) {}
  
  /**
   * Tests basic record pattern matching using instanceof with a pattern variable.
   */
  @Test
  public void testBasicRecordPatternMatching() {
    // Create a point record
    Point point = new Point(10, 20);
    
    // Test pattern matching with instanceof
    if (point instanceof Point(int x, int y)) {
      assertEquals(10, x, "x coordinate should match");
      assertEquals(20, y, "y coordinate should match");
    } else {
      // This should never happen
      assertFalse(true, "Pattern matching failed");
    }
    
    // Test pattern matching in a conditional expression
    int sum = point instanceof Point(int x, int y) ? x + y : 0;
    assertEquals(30, sum, "Sum of coordinates should be 30");
  }
  
  /**
   * Tests nested record pattern matching to extract values from multi-level records.
   */
  @Test
  public void testNestedRecordPatternMatching() {
    // Create a rectangle with two points
    Rectangle rectangle = new Rectangle(new Point(10, 20), new Point(30, 40));
    
    // Test nested pattern matching with instanceof
    if (rectangle instanceof Rectangle(Point(int x1, int y1), Point(int x2, int y2))) {
      assertEquals(10, x1, "Top-left x should match");
      assertEquals(20, y1, "Top-left y should match");
      assertEquals(30, x2, "Bottom-right x should match");
      assertEquals(40, y2, "Bottom-right y should match");
      
      // Calculate width and height using extracted values
      int width = x2 - x1;
      int height = y2 - y1;
      assertEquals(20, width, "Width should be 20");
      assertEquals(20, height, "Height should be 20");
    } else {
      // This should never happen
      assertFalse(true, "Nested pattern matching failed");
    }
  }
  
  /**
   * Tests pattern matching with type guards to conditionally extract values based on type.
   */
  @Test
  public void testPatternMatchingWithTypeGuards() {
    // Create colored shapes with different underlying shapes
    ColoredShape redCircle = new ColoredShape("red", new Circle(new Point(10, 10), 5));
    ColoredShape blueRectangle = new ColoredShape("blue", 
        new Rectangle(new Point(0, 0), new Point(20, 30)));
    
    // Test pattern matching with type guards for Circle
    String circleDescription = switch (redCircle) {
      case ColoredShape(String color, Circle(Point center, int radius)) ->
          color + " circle with radius " + radius;
      case ColoredShape(String color, Rectangle(Point topLeft, Point bottomRight)) ->
          color + " rectangle";
      default -> "unknown shape";
    };
    assertEquals("red circle with radius 5", circleDescription, "Circle description should match");
    
    // Test pattern matching with type guards for Rectangle
    String rectangleDescription = switch (blueRectangle) {
      case ColoredShape(String color, Circle(Point center, int radius)) ->
          color + " circle";
      case ColoredShape(String color, Rectangle(Point(int x1, int y1), Point(int x2, int y2))) ->
          color + " rectangle with width " + (x2 - x1) + " and height " + (y2 - y1);
      default -> "unknown shape";
    };
    assertEquals("blue rectangle with width 20 and height 30", rectangleDescription, 
        "Rectangle description should match");
  }
  
  /**
   * Tests pattern matching in switch expressions with multiple patterns.
   */
  @Test
  public void testPatternMatchingInSwitchExpressions() {
    // Create different objects to test in switch expressions
    Object point = new Point(5, 10);
    Object rectangle = new Rectangle(new Point(0, 0), new Point(10, 20));
    Object circle = new Circle(new Point(15, 15), 10);
    Object string = "not a shape";
    
    // Test pattern matching in switch expression
    String pointResult = switch (point) {
      case Point(int x, int y) -> "Point at (" + x + ", " + y + ")";
      case Rectangle(Point p1, Point p2) -> "Rectangle";
      case Circle(Point center, int radius) -> "Circle";
      default -> "Not a recognized shape";
    };
    assertEquals("Point at (5, 10)", pointResult, "Point switch result should match");
    
    // Test rectangle pattern
    String rectangleResult = switch (rectangle) {
      case Point(int x, int y) -> "Point";
      case Rectangle(Point topLeft, Point bottomRight) -> 
          "Rectangle from " + topLeft + " to " + bottomRight;
      case Circle(Point center, int radius) -> "Circle";
      default -> "Not a recognized shape";
    };
    assertEquals("Rectangle from Point[x=0, y=0] to Point[x=10, y=20]", rectangleResult, 
        "Rectangle switch result should match");
    
    // Test circle pattern
    String circleResult = switch (circle) {
      case Point(int x, int y) -> "Point";
      case Rectangle(Point p1, Point p2) -> "Rectangle";
      case Circle(Point(int x, int y), int r) -> 
          "Circle at (" + x + ", " + y + ") with radius " + r;
      default -> "Not a recognized shape";
    };
    assertEquals("Circle at (15, 15) with radius 10", circleResult, 
        "Circle switch result should match");
    
    // Test non-matching pattern
    String stringResult = switch (string) {
      case Point(int x, int y) -> "Point";
      case Rectangle(Point p1, Point p2) -> "Rectangle";
      case Circle(Point center, int radius) -> "Circle";
      default -> "Not a recognized shape";
    };
    assertEquals("Not a recognized shape", stringResult, 
        "Non-matching switch result should match");
  }
  
  /**
   * Tests pattern matching with null handling.
   */
  @Test
  public void testPatternMatchingWithNullHandling() {
    // Create objects including null
    Point point = new Point(1, 2);
    Point nullPoint = null;
    
    // Test pattern matching with non-null object
    String nonNullResult = switch (point) {
      case null -> "null point";
      case Point(int x, int y) -> "Point at (" + x + ", " + y + ")";
    };
    assertEquals("Point at (1, 2)", nonNullResult, "Non-null result should match");
    
    // Test pattern matching with null object
    String nullResult = switch (nullPoint) {
      case null -> "null point";
      case Point(int x, int y) -> "Point at (" + x + ", " + y + ")";
    };
    assertEquals("null point", nullResult, "Null result should match");
  }
}