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
import java.util.SequencedMap;

import com.google.common.collect.Maps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test for the {@link ImmutableNestedAttributesMap}
 * 
 * @since 3.0
 */
public class ImmutableNestedAttributesMapTest
{
  private ImmutableNestedAttributesMap map;
  
  @BeforeEach
  public void setUp() {
    map = new ImmutableNestedAttributesMap(null, "key", Maps.newHashMap());
  }

  @Test
  public void classKeysUnsettable() {
    assertThrows(UnsupportedOperationException.class, () -> {
      map.set(Integer.class, 15);
    });
  }

  @Test
  public void stringKeysUnsettable() {
    assertThrows(UnsupportedOperationException.class, () -> {
      map.set("key", "value");
    });
  }

  @Test
  public void nonExistentChildrenAreNavigable() {
    final NestedAttributesMap nonexistent = map.child("nonexistent");
    assertThat(nonexistent, is(notNullValue()));
    assertThat(map.backing().isEmpty(), is(true));
  }

  @Test
  public void navigableChildrenAreUnmodifiable() {
    assertThrows(UnsupportedOperationException.class, () -> {
      map.child("nonexistent").set("key", "value");
    });
  }
  
  @Test
  public void patternMatchingHandlesNullCase() {
    // Test the pattern matching for switch with null case
    NestedAttributesMap child = map.child("nonexistent");
    assertThat(child, is(notNullValue()));
    assertInstanceOf(ImmutableNestedAttributesMap.class, child);
  }
  
  @Test
  public void patternMatchingHandlesMapCase() {
    // Create a map with a child that is a map
    Map<String, Object> backing = new HashMap<>();
    Map<String, Object> childMap = new HashMap<>();
    childMap.put("childKey", "childValue");
    backing.put("existingChild", childMap);
    
    ImmutableNestedAttributesMap mapWithChild = new ImmutableNestedAttributesMap(null, "parent", backing);
    
    // Test the pattern matching for switch with Map case
    NestedAttributesMap child = mapWithChild.child("existingChild");
    assertThat(child, is(notNullValue()));
    assertInstanceOf(ImmutableNestedAttributesMap.class, child);
    assertThat(child.get("childKey"), is("childValue"));
  }
  
  @Test
  public void supportsSequencedMapBacking() {
    // Test with a SequencedMap implementation (LinkedHashMap)
    SequencedMap<String, Object> sequencedBacking = new LinkedHashMap<>();
    sequencedBacking.put("first", "firstValue");
    sequencedBacking.put("second", "secondValue");
    
    ImmutableNestedAttributesMap sequencedMap = new ImmutableNestedAttributesMap(null, "sequenced", sequencedBacking);
    
    // Verify the map preserves entries
    assertThat(sequencedMap.get("first"), is("firstValue"));
    assertThat(sequencedMap.get("second"), is("secondValue"));
  }
}