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
package org.sonatype.nexus.common.collect;

import java.util.Date;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link org.sonatype.nexus.common.collect.AttributesMap}.
 */
public class AttributesMapTest
    extends TestSupport
{
  private AttributesMap underTest;

  @BeforeEach
  public void setUp() {
    underTest = new AttributesMap();
  }

  @Test
  public void setNullValueShouldRemoveAttribute() {
    underTest.set("foo", "bar");
    underTest.set("foo", null);
    assertFalse(underTest.contains("foo"), "Attribute should be removed when set to null");
  }

  @Test
  public void requireShouldReturnValueWhenPresentAndThrowWhenMissing() {
    underTest.set("foo", "bar");
    assertEquals("bar", underTest.require("foo"), "Should return the value when it exists");

    assertThrows(Exception.class, 
        () -> underTest.require("baz"),
        "Should throw exception when required attribute is missing");
  }

  @Test
  public void containsShouldReturnTrueForExistingKeysAndFalseForMissing() {
    underTest.set("foo", "bar");
    assertTrue(underTest.contains("foo"), "Should return true for existing key");
    assertFalse(underTest.contains("baz"), "Should return false for missing key");
  }

  @Test
  public void sizeEmptyAndClearShouldBehaveAsExpected() {
    assertTrue(underTest.isEmpty(), "New map should be empty");
    assertEquals(0, underTest.size(), "New map should have size 0");

    underTest.set("foo", "bar");
    log(underTest);
    assertEquals(1, underTest.size(), "Size should be 1 after adding one element");

    underTest.set("baz", "ick");
    log(underTest);
    assertEquals(2, underTest.size(), "Size should be 2 after adding second element");

    underTest.clear();
    log(underTest);
    assertTrue(underTest.isEmpty(), "Map should be empty after clear");
    assertEquals(0, underTest.size(), "Size should be 0 after clear");
  }

  @Test
  public void typedAccessorsShouldReturnCorrectTypes() {
    underTest.set("foo", 1);
    Object integerValue = underTest.get("foo", Integer.class);
    assertNotNull(integerValue, "Retrieved integer value should not be null");
    assertEquals(Integer.class, integerValue.getClass(), "Retrieved value should be of Integer class");
    assertEquals(1, integerValue, "Retrieved integer value should match the stored value");

    underTest.set("foo", false);
    Object booleanValue = underTest.get("foo", Boolean.class);
    assertNotNull(booleanValue, "Retrieved boolean value should not be null");
    assertEquals(Boolean.class, booleanValue.getClass(), "Retrieved value should be of Boolean class");
    assertEquals(false, booleanValue, "Retrieved boolean value should match the stored value");
  }

  // private to test creation with accessible=true
  private static class GetOrCreateAttribute
  {
    // empty
  }

  @Test
  public void getOrCreateShouldCreateInstanceWhenMissing() {
    assertFalse(underTest.contains(GetOrCreateAttribute.class), "Attribute should not exist initially");
    Object value = underTest.getOrCreate(GetOrCreateAttribute.class);
    assertNotNull(value, "Created value should not be null");
    assertTrue(underTest.contains(GetOrCreateAttribute.class), "Attribute should exist after getOrCreate");
  }

  @Test
  public void shouldHandleDateAsDateOrLong() {
    Date testDate = new Date();
    Long testDateLong = testDate.getTime();

    underTest.set("foo", testDate);
    underTest.set("bar", testDateLong);

    assertEquals(testDate, underTest.get("foo", Date.class), 
        "Should retrieve Date object when stored as Date");
    assertEquals(testDate, underTest.get("bar", Date.class), 
        "Should convert Long to Date when retrieving as Date");
  }

  @Test
  public void shouldHandleBooleanStrings() {
    underTest.set("test", "true");
    assertEquals(true, underTest.get("test", Boolean.class), 
        "Should convert 'true' string to Boolean true");

    underTest.set("test", "false");
    assertEquals(false, underTest.get("test", Boolean.class), 
        "Should convert 'false' string to Boolean false");

    underTest.set("test", "notABooleanString");
    assertEquals(false, underTest.get("test", Boolean.class), 
        "Should convert non-boolean string to Boolean false");

    underTest.set("test", 123);
    assertNull(underTest.get("test", Boolean.class), 
        "Should return null when converting non-string/non-boolean to Boolean");
  }

  private Object testComputeFunction(Object test) {
    if (test instanceof Integer) {
      return (Integer) test + 1;
    }
    return 1;
  }

  @Test
  public void computeShouldApplyFunctionAndUpdateValue() {
    Object result = underTest.compute("foo", this::testComputeFunction);
    assertNull(result, "First compute result should be null for non-existent key");
    assertEquals(1, underTest.get("foo"), "Value should be initialized to 1");
    
    result = underTest.compute("foo", this::testComputeFunction);
    assertEquals(1, result, "Second compute result should be the previous value");
    assertEquals(2, underTest.get("foo"), "Value should be incremented to 2");
    
    result = underTest.compute("foo", this::testComputeFunction);
    assertEquals(2, result, "Third compute result should be the previous value");
    assertEquals(3, underTest.get("foo"), "Value should be incremented to 3");
    
    result = underTest.compute("foo", this::testComputeFunction);
    assertEquals(3, result, "Fourth compute result should be the previous value");
    assertEquals(4, underTest.get("foo"), "Value should be incremented to 4");
  }
}
