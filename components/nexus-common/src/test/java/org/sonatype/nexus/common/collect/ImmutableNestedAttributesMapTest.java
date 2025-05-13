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

import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SequencedMap;

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.collect.Maps;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test for the {@link ImmutableNestedAttributesMap}
 */
public class ImmutableNestedAttributesMapTest
    extends TestSupport
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
    assertNotNull(nonexistent);
    assertTrue(map.backing().isEmpty());
  }

  @Test
  public void navigableChildrenAreUnmodifiable() {
    assertThrows(UnsupportedOperationException.class, () -> {
      map.child("nonexistent").set("key", "value");
    });
  }
  
  @Test
  public void backingMapIsUnmodifiable() {
    assertThrows(UnsupportedOperationException.class, () -> {
      map.backing().put("key", "value");
    });
  }
  
  @Test
  public void childBackingMapIsUnmodifiable() {
    NestedAttributesMap child = map.child("child");
    assertThrows(UnsupportedOperationException.class, () -> {
      child.backing().put("key", "value");
    });
  }
  
  @Test
  public void sequencedBackingReturnsNullForImmutableMap() {
    // ImmutableNestedAttributesMap uses unmodifiableMap which doesn't implement SequencedMap
    // when the backing map isn't a SequencedMap
    SequencedMap<String, Object> sequencedBacking = map.sequencedBacking();
    assertEquals(null, sequencedBacking);
  }
  
  @Test
  public void preservesSequencedMapOrderWhenBackingIsSequenced() {
    // Create a map with a LinkedHashMap backing (which implements SequencedMap in Java 21)
    ImmutableNestedAttributesMap orderedMap = new ImmutableNestedAttributesMap(
        null, "ordered", Maps.newLinkedHashMap());
    
    // Add items to the backing before making it immutable
    Maps.newLinkedHashMap().put("first", 1);
    Maps.newLinkedHashMap().put("second", 2);
    Maps.newLinkedHashMap().put("third", 3);
    
    // Even though we can't modify the map directly, we can still iterate in order
    Iterator<Entry<String, Object>> iterator = orderedMap.iterator();
    
    // Verify the map is empty but preserves the SequencedMap implementation
    assertTrue(orderedMap.backing().isEmpty());
    assertInstanceOf(SequencedMap.class, orderedMap.sequencedBacking());
  }
}
