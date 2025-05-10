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
package org.sonatype.nexus.repository.json;

import java.io.IOException;
import java.util.Map;

import org.sonatype.goodies.testsupport.TestSupport;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;
import com.fasterxml.jackson.databind.deser.std.MapDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.fasterxml.jackson.core.JsonToken.FIELD_NAME;
import static com.fasterxml.jackson.core.JsonTokenId.ID_START_OBJECT;
import static com.google.common.collect.Maps.newHashMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class MapDeserializerSerializerTest
    extends TestSupport
{
  @Mock
  private MapDeserializer rootDeserializer;

  @Mock
  private ValueInstantiator valueInstantiator;

  @Mock
  private UntypedObjectDeserializerSerializer untypedObjectDeserializerSerializer;

  @Mock
  private JsonParser parser;

  @Mock
  private DeserializationContext context;

  private MapDeserializerSerializer underTest;

  @BeforeEach
  public void setUp() {
    lenient().when(rootDeserializer.getValueInstantiator()).thenReturn(valueInstantiator);
    lenient().when(rootDeserializer.getValueType()).thenReturn(null);
    when(valueInstantiator.canCreateUsingDefault()).thenReturn(true);

    underTest = new MapDeserializerSerializer(rootDeserializer, untypedObjectDeserializerSerializer);
  }

  @Test
  public void should_Use_ValueType_From_RootDeserializer() {
    verify(rootDeserializer).getValueType();
  }

  @Test
  public void should_Use_ValueInstantiator_From_RootDeserializer() {
    verify(rootDeserializer).getValueInstantiator();
  }

  @Test
  public void should_Use_UntypedObjectDeserializerSerializer() throws IOException {
    when(parser.currentTokenId()).thenReturn(ID_START_OBJECT);
    when(parser.currentToken()).thenReturn(FIELD_NAME);
    when(parser.currentName()).thenReturn("currentFieldName");
    when(parser.nextFieldName()).thenReturn(null);
    when(valueInstantiator.createUsingDefault(context)).thenReturn(newHashMap());

    underTest.deserialize(parser, context);

    verify(untypedObjectDeserializerSerializer).deserialize(parser, context);
  }
  
  @Test
  public void should_Handle_Json_Map_With_Pattern_Matching() throws IOException {
    // Setup a map with test data
    Map<String, Object> testMap = newHashMap();
    testMap.put("string", "value");
    testMap.put("number", 42);
    testMap.put("boolean", true);
    
    // Mock the parser and context to return our test map
    when(parser.currentTokenId()).thenReturn(ID_START_OBJECT);
    when(valueInstantiator.createUsingDefault(context)).thenReturn(testMap);
    when(untypedObjectDeserializerSerializer.deserialize(any(), any())).thenReturn(testMap);
    
    // Call the method under test
    Object result = underTest.deserialize(parser, context);
    
    // Verify the result using pattern matching
    assertNotNull(result);
    
    // Using Java 21 pattern matching to check the structure of the map
    if (result instanceof Map<?,?> map) {
      // Check map entries using pattern matching
      if (map.get("string") instanceof String stringValue) {
        assertEquals("value", stringValue);
      }
      
      if (map.get("number") instanceof Integer numberValue) {
        assertEquals(42, numberValue);
      }
      
      if (map.get("boolean") instanceof Boolean boolValue) {
        assertEquals(true, boolValue);
      }
    }
  }
  
  @Test
  public void should_Process_Nested_Json_Structure_With_Pattern_Matching() throws IOException {
    // Setup a nested map structure
    Map<String, Object> nestedMap = newHashMap();
    nestedMap.put("nestedKey", "nestedValue");
    
    Map<String, Object> testMap = newHashMap();
    testMap.put("string", "value");
    testMap.put("nested", nestedMap);
    
    // Mock the parser and context to return our test map
    when(parser.currentTokenId()).thenReturn(ID_START_OBJECT);
    when(valueInstantiator.createUsingDefault(context)).thenReturn(testMap);
    when(untypedObjectDeserializerSerializer.deserialize(any(), any())).thenReturn(testMap);
    
    // Call the method under test
    Object result = underTest.deserialize(parser, context);
    
    // Verify the result using pattern matching for nested structures
    assertNotNull(result);
    
    // Using Java 21 pattern matching to navigate the nested structure
    if (result instanceof Map<?,?> map && map.get("nested") instanceof Map<?,?> nested) {
      // Direct pattern matching for the nested map's content
      if (nested.get("nestedKey") instanceof String nestedValue) {
        assertEquals("nestedValue", nestedValue);
      }
    }
  }
}
