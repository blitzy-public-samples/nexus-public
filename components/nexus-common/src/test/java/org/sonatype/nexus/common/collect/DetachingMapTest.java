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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SequencedCollection;
import java.util.SequencedMap;
import java.util.SequencedSet;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.virtualthread.Java21TestGroup;

import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.experimental.categories.Category;
import org.mockito.InOrder;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.isNotNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Category(Java21TestGroup.class)
public class DetachingMapTest
    extends TestSupport
{
  @Mock
  private Map<String, String> backing;

  @Mock
  private BooleanSupplier allowDetach;

  @Mock
  private Function<String, String> detach;

  private DetachingMap<String, String> underTest;

  @BeforeEach
  public void setUp() {
    underTest = new DetachingMap<>(backing, allowDetach, detach);
  }

  @Test
  public void shouldNotDetachForNonEscapingQueries() {

    underTest.containsKey(null);
    underTest.containsValue(null);
    underTest.equals(null);
    underTest.hashCode();
    underTest.isEmpty();
    underTest.size();
    underTest.toString();

    InOrder inOrder = inOrder(backing);

    inOrder.verify(backing).containsKey(null);
    inOrder.verify(backing).containsValue(null);
    // Mockito ignores equals
    // Mockito ignores hashCode
    inOrder.verify(backing).isEmpty();
    inOrder.verify(backing).size();
    // Mockito ignores toString

    verifyNoMoreInteractions(backing);

    verifyNoInteractions(allowDetach, detach);
  }

  @Test
  public void shouldDetachForEscapingQueries() {
    when(allowDetach.getAsBoolean()).thenReturn(true);

    underTest.keySet();
    underTest.entrySet();
    underTest.values();

    InOrder inOrder = inOrder(backing, allowDetach, detach);

    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).size();
    inOrder.verify(backing).entrySet();
    // the rest happens on the detached map

    verifyNoMoreInteractions(backing, allowDetach, detach);
  }

  @Test
  public void shouldDetachForMutations() {
    when(allowDetach.getAsBoolean()).thenReturn(true);

    underTest.put("foo", "bar");
    underTest.clear();
    underTest.remove("foo");

    InOrder inOrder = inOrder(backing, allowDetach, detach);

    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).size();
    inOrder.verify(backing).entrySet();
    // the rest happens on the detached map

    verifyNoMoreInteractions(backing, allowDetach, detach);
  }

  @Test
  public void shouldNotDetachWhenDisallowed() {
    when(allowDetach.getAsBoolean()).thenReturn(false);

    underTest.put("foo", "bar");
    underTest.clear();
    underTest.remove("foo");
    underTest.keySet();
    underTest.entrySet();
    underTest.values();

    InOrder inOrder = inOrder(backing, allowDetach, detach);

    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).put("foo", "bar");
    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).clear();
    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).remove("foo");
    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).keySet();
    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).entrySet();
    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(backing).values();

    verifyNoMoreInteractions(backing, allowDetach, detach);
  }

  @Test
  public void shouldDetachAndMaintainIndependence() {
    Map<String, String> original = ImmutableMap.of("1", "I", "2", "two", "3", "III");

    underTest = new DetachingMap<>(original, allowDetach, detach);

    when(allowDetach.getAsBoolean()).thenReturn(true);
    when(detach.apply(isNotNull())).thenAnswer(returnsFirstArg());

    assertThat(underTest.put("2", "II"), is("two"));
    assertThat(underTest, hasEntry("2", "II"));

    // original map contents should be unchanged
    assertThat(original, hasEntry("2", "two"));

    InOrder inOrder = inOrder(allowDetach, detach);

    inOrder.verify(allowDetach).getAsBoolean();
    inOrder.verify(detach).apply("I");
    inOrder.verify(detach).apply("two");
    inOrder.verify(detach).apply("III");

    verifyNoMoreInteractions(allowDetach, detach);
  }
  @Test
  public void shouldSupportPutFirstOperation() {
    // Setup a LinkedHashMap as backing to preserve order
    Map<String, String> orderedMap = new LinkedHashMap<>();
    orderedMap.put("1", "one");
    orderedMap.put("2", "two");
    
    // Create a DetachingMap with a SequencedMap backing
    DetachingMap<String, String> sequencedMap = new DetachingMap<>(
        (SequencedMap<String, String>) orderedMap, () -> true, Function.identity());
    
    // Test putFirst
    sequencedMap.putFirst("0", "zero");
    
    // Verify the order
    assertEquals("0", sequencedMap.firstEntry().getKey());
    assertEquals("zero", sequencedMap.firstEntry().getValue());
  }
  
  @Test
  public void shouldSupportPutLastOperation() {
    // Setup a LinkedHashMap as backing to preserve order
    Map<String, String> orderedMap = new LinkedHashMap<>();
    orderedMap.put("1", "one");
    orderedMap.put("2", "two");
    
    // Create a DetachingMap with a SequencedMap backing
    DetachingMap<String, String> sequencedMap = new DetachingMap<>(
        (SequencedMap<String, String>) orderedMap, () -> true, Function.identity());
    
    // Test putLast
    sequencedMap.putLast("3", "three");
    
    // Verify the order
    assertEquals("3", sequencedMap.lastEntry().getKey());
    assertEquals("three", sequencedMap.lastEntry().getValue());
  }
  
  @Test
  public void shouldSupportSequencedEntrySetOperation() {
    // Setup a LinkedHashMap as backing to preserve order
    Map<String, String> orderedMap = new LinkedHashMap<>();
    orderedMap.put("1", "one");
    orderedMap.put("2", "two");
    orderedMap.put("3", "three");
    
    // Create a DetachingMap with a SequencedMap backing
    DetachingMap<String, String> sequencedMap = new DetachingMap<>(
        (SequencedMap<String, String>) orderedMap, () -> true, Function.identity());
    
    // Test sequencedEntrySet
    SequencedSet<Map.Entry<String, String>> entries = sequencedMap.sequencedEntrySet();
    
    // Verify the entries
    assertThat(entries, notNullValue());
    assertThat(entries, hasSize(3));
    
    // Verify order is preserved
    String[] expectedKeys = {"1", "2", "3"};
    String[] expectedValues = {"one", "two", "three"};
    int i = 0;
    for (Map.Entry<String, String> entry : entries) {
      assertEquals(expectedKeys[i], entry.getKey());
      assertEquals(expectedValues[i], entry.getValue());
      i++;
    }
  }
  
  @Test
  public void shouldSupportSequencedKeySetOperation() {
    // Setup a LinkedHashMap as backing to preserve order
    Map<String, String> orderedMap = new LinkedHashMap<>();
    orderedMap.put("1", "one");
    orderedMap.put("2", "two");
    orderedMap.put("3", "three");
    
    // Create a DetachingMap with a SequencedMap backing
    DetachingMap<String, String> sequencedMap = new DetachingMap<>(
        (SequencedMap<String, String>) orderedMap, () -> true, Function.identity());
    
    // Test sequencedKeySet
    SequencedSet<String> keys = sequencedMap.sequencedKeySet();
    
    // Verify the keys
    assertThat(keys, notNullValue());
    assertThat(keys, hasSize(3));
    assertThat(keys, contains("1", "2", "3"));
  }
  
  @Test
  public void shouldSupportSequencedValuesOperation() {
    // Setup a LinkedHashMap as backing to preserve order
    Map<String, String> orderedMap = new LinkedHashMap<>();
    orderedMap.put("1", "one");
    orderedMap.put("2", "two");
    orderedMap.put("3", "three");
    
    // Create a DetachingMap with a SequencedMap backing
    DetachingMap<String, String> sequencedMap = new DetachingMap<>(
        (SequencedMap<String, String>) orderedMap, () -> true, Function.identity());
    
    // Test sequencedValues
    SequencedCollection<String> values = sequencedMap.sequencedValues();
    
    // Verify the values
    assertThat(values, notNullValue());
    assertThat(values, hasSize(3));
    assertThat(values, contains("one", "two", "three"));
  }
  
  @Test
  public void shouldThrowExceptionForSequencedOperationsWithNonSequencedBacking() {
    // Setup a non-SequencedMap backing
    Map<String, String> nonOrderedMap = ImmutableMap.of("1", "one", "2", "two");
    
    // Create a DetachingMap with a non-SequencedMap backing
    DetachingMap<String, String> nonSequencedMap = new DetachingMap<>(
        nonOrderedMap, () -> false, Function.identity());
    
    // Test that sequenced operations throw UnsupportedOperationException
    assertThrows(UnsupportedOperationException.class, () -> nonSequencedMap.sequencedEntrySet());
    assertThrows(UnsupportedOperationException.class, () -> nonSequencedMap.sequencedKeySet());
    assertThrows(UnsupportedOperationException.class, () -> nonSequencedMap.sequencedValues());
    assertThrows(UnsupportedOperationException.class, () -> nonSequencedMap.reversed());
  }
}
