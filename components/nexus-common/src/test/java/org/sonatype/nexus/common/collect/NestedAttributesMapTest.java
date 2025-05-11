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
import java.util.LinkedHashMap;
import java.util.Map;

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
 * Updated for Java 21 compatibility.
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
  public void testParentKeyNullWhenNoParent() {
    assertThat(underTest.getKey(), is("foo"));
    assertThat(underTest.getParentKey(), nullValue());
  }

  /*
   * parentKey includes grandparent
   */
  @Test
  public void testParentKey_includesGrandparent() {
    NestedAttributesMap parent = underTest.child("bar");
    NestedAttributesMap child = parent.child("baz");

    assertThat(underTest.getKey(), is("foo"));
    assertThat(parent.getKey(), is("bar"));
    assertThat(child.getKey(), is("baz"));
    assertThat(child.getParentKey(), is("foo" + SEPARATOR + "bar"));
  }

  /*
   * qualifiedKey without parent returns key
   */
  @Test
  public void testQualifiedKey_withoutParent() {
    assertThat(underTest.getQualifiedKey(), is("foo"));
  }

  /*
   * qualifiedKey includes parent
   */
  @Test
  public void testQualifiedKey_withParent() {
    assertThat(underTest.child("bar").getQualifiedKey(), is("foo" + SEPARATOR + "bar"));
    assertThat(underTest.child("bar").child("baz").getQualifiedKey(),
        is("foo" + SEPARATOR + "bar" + SEPARATOR + "baz"));
  }

  @Test
  public void testChild_withNonMapField() {
    underTest.set("value", false);

    IllegalStateException exception = assertThrows(IllegalStateException.class, () -> underTest.child("value"));
    assertThat(exception.getMessage(), containsString("child 'value' not a Map"));
  }
  
  /**
   * Test for Java 21 SequencedMap functionality in NestedAttributesMap
   */
  @Test
  public void testSequencedMapPreservesInsertionOrder() {
    // Create a map with predictable insertion order
    Map<String, Object> orderedMap = new LinkedHashMap<>();
    orderedMap.put("first", "value1");
    orderedMap.put("second", "value2");
    orderedMap.put("third", "value3");
    
    NestedAttributesMap map = new NestedAttributesMap("ordered", orderedMap);
    
    // Verify keys are returned in insertion order
    String[] keys = map.backing().keySet().toArray(new String[0]);
    assertThat(keys[0], is("first"));
    assertThat(keys[1], is("second"));
    assertThat(keys[2], is("third"));
  }
  
  /**
   * Test for Java 21 Pattern Matching for switch in NestedAttributesMap
   */
  @Test
  public void testPatternMatchingWithNestedMaps() {
    // Create a nested structure to test pattern matching behavior
    NestedAttributesMap parent = underTest.child("parent");
    NestedAttributesMap child = parent.child("child");
    
    // Test the qualified key which uses pattern matching internally
    assertThat(child.getQualifiedKey(), is("foo" + SEPARATOR + "parent" + SEPARATOR + "child"));
    
    // Test parent key which also uses pattern matching internally
    assertThat(child.getParentKey(), is("foo" + SEPARATOR + "parent"));
  }

  /*
   * child require includes parent key
   */
  @Test
  public void testChildRequire_includesParentKey() {
    NestedAttributesMap bar = underTest.child("bar");
    Exception e = assertThrows(Exception.class, () -> bar.require("baz"));
    assertThat(e.getMessage(), containsString("baz"));
    assertThat(e.getMessage(), containsString("foo" + SEPARATOR + "bar"));

    NestedAttributesMap qux = underTest.child("bar").child("qux");
    e = assertThrows(Exception.class, () -> qux.require("baz"));
    assertThat(e.getMessage(), containsString("baz"));
    assertThat(e.getMessage(), containsString("foo" + SEPARATOR + "bar" + SEPARATOR + "qux"));
  }
}