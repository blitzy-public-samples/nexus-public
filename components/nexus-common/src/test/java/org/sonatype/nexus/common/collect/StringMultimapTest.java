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

import java.util.List;

import org.sonatype.goodies.testsupport.TestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for {@link StringMultimap}.
 */
public class StringMultimapTest
    extends TestSupport
{
  private StringMultimap underTest;

  @BeforeEach
  public void setUp() {
    underTest = new StringMultimap();
  }

  @Test
  public void testSizeValidation() {
    assertThat(underTest.size(), is(0));

    underTest.set("foo", "bar");
    assertThat(underTest.size(), is(1));

    underTest.set("foo", "baz");
    assertThat(underTest.size(), is(1));

    underTest.set("ick", "poo");
    assertThat(underTest.size(), is(2));
  }
  
  @Test
  public void testGetFirstAndLast() {
    // Test with empty list
    assertThat(underTest.getFirst("foo"), is(nullValue()));
    assertThat(underTest.getLast("foo"), is(nullValue()));
    
    // Test with single value
    underTest.set("foo", "bar");
    assertThat(underTest.getFirst("foo"), is("bar"));
    assertThat(underTest.getLast("foo"), is("bar"));
    
    // Test with multiple values
    underTest.set("foo", "baz");
    assertThat(underTest.getFirst("foo"), is("bar"));
    assertThat(underTest.getLast("foo"), is("baz"));
    
    // Test with replaced values
    underTest.replace("foo", "qux", "quux");
    assertThat(underTest.getFirst("foo"), is("qux"));
    assertThat(underTest.getLast("foo"), is("quux"));
  }
  
  @Test
  public void testAddFirstAndLast() {
    // Add first to empty list
    underTest.addFirst("foo", "bar");
    assertThat(underTest.getAll("foo"), contains("bar"));
    
    // Add last to single-element list
    underTest.addLast("foo", "baz");
    assertThat(underTest.getAll("foo"), contains("bar", "baz"));
    
    // Add first to multi-element list
    underTest.addFirst("foo", "qux");
    assertThat(underTest.getAll("foo"), contains("qux", "bar", "baz"));
    
    // Verify first and last
    assertThat(underTest.getFirst("foo"), is("qux"));
    assertThat(underTest.getLast("foo"), is("baz"));
  }
  
  @Test
  public void testRemoveFirstAndLast() {
    // Setup test data
    underTest.set("foo", "bar", "baz", "qux");
    
    // Remove first
    String first = underTest.removeFirst("foo");
    assertThat(first, is("bar"));
    assertThat(underTest.getAll("foo"), contains("baz", "qux"));
    
    // Remove last
    String last = underTest.removeLast("foo");
    assertThat(last, is("qux"));
    assertThat(underTest.getAll("foo"), contains("baz"));
    
    // Remove last remaining element
    last = underTest.removeLast("foo");
    assertThat(last, is("baz"));
    assertThat(underTest.contains("foo"), is(false));
    
    // Remove from empty list
    assertThat(underTest.removeFirst("foo"), is(nullValue()));
    assertThat(underTest.removeLast("foo"), is(nullValue()));
  }
  
  @Test
  public void testReversed() {
    // Setup test data
    underTest.set("foo", "bar", "baz", "qux");
    
    // Test reversed view
    assertThat(underTest.reversed("foo").toArray(), is(new Object[]{"qux", "baz", "bar"}));
    
    // Original list should remain unchanged
    assertThat(underTest.getAll("foo"), contains("bar", "baz", "qux"));
  }