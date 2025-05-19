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

import java.util.HashMap;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sonatype.nexus.common.collect.NestedAttributesMap.SEPARATOR;

/**
 * Tests for {@link org.sonatype.nexus.common.collect.NestedAttributesMap}.
 */
public class NestedAttributesMapTest
    extends TestSupport
{
  private NestedAttributesMap underTest;

  @BeforeEach
  public void setUp() {
    underTest = new NestedAttributesMap("foo", new HashMap<>());
  }

  /*
   * parentKey null when no parent"
   */
  @Test
  public void parentKeyIsNullWhenNoParent() {
    assertThat("Key should match the constructor parameter", underTest.getKey(), is("foo"));
    assertThat("Parent key should be null when there is no parent", underTest.getParentKey(), nullValue());
  }

  /*
   * parentKey includes grandparent
   */
  @Test
  public void parentKeyIncludesGrandparent() {
    NestedAttributesMap parent = underTest.child("bar");
    NestedAttributesMap child = parent.child("baz");

    assertThat("Root key should be 'foo'", underTest.getKey(), is("foo"));
    assertThat("Parent key should be 'bar'", parent.getKey(), is("bar"));
    assertThat("Child key should be 'baz'", child.getKey(), is("baz"));
    assertThat("Child's parent key should include the full hierarchy", 
        child.getParentKey(), is("foo" + SEPARATOR + "bar"));
  }

  /*
   * qualifiedKey without parent returns key
   */
  @Test
  public void qualifiedKeyWithoutParentReturnsKey() {
    assertThat("Qualified key should be the same as key when there is no parent", 
        underTest.getQualifiedKey(), is("foo"));
  }

  /*
   * qualifiedKey includes parent
   */
  @Test
  public void qualifiedKeyIncludesParent() {
    assertThat("Qualified key should include parent key", 
        underTest.child("bar").getQualifiedKey(), is("foo" + SEPARATOR + "bar"));
    assertThat("Qualified key should include the full hierarchy", 
        underTest.child("bar").child("baz").getQualifiedKey(),
        is("foo" + SEPARATOR + "bar" + SEPARATOR + "baz"));
  }

  @Test
  public void childThrowsExceptionWithNonMapField() {
    underTest.set("value", false);

    IllegalStateException exception = assertThrows(IllegalStateException.class, 
        () -> underTest.child("value"),
        "Should throw IllegalStateException when trying to get a child from a non-map field");
    
    assertThat("Exception message should contain the field name", 
        exception.getMessage(), containsString("value"));
  }

  /*
   * child require includes parent key
   */
  @Test
  public void childRequireIncludesParentKey() {
    NestedAttributesMap bar = underTest.child("bar");
    Exception e = assertThrows(Exception.class, 
        () -> bar.require("baz"),
        "Should throw exception when requiring a non-existent field");
    assertThat("Exception message should contain the required field name", 
        e.getMessage(), containsString("baz"));
    assertThat("Exception message should contain the qualified parent key", 
        e.getMessage(), containsString("foo" + SEPARATOR + "bar"));

    NestedAttributesMap qux = underTest.child("bar").child("qux");
    e = assertThrows(Exception.class, 
        () -> qux.require("baz"),
        "Should throw exception when requiring a non-existent field in a nested map");
    assertThat("Exception message should contain the required field name", 
        e.getMessage(), containsString("baz"));
    assertThat("Exception message should contain the full qualified key path", 
        e.getMessage(), containsString("foo" + SEPARATOR + "bar" + SEPARATOR + "qux"));
  }
}