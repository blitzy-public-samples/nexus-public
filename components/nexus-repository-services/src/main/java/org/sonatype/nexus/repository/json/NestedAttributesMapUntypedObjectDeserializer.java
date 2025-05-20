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

import org.sonatype.nexus.common.collect.NestedAttributesMap;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.JsonTokenId;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Implementation of {@link UntypedObjectDeserializer} that uses a {@link NestedAttributesMap} as a root map to
 * get existing deserialized references from. This class is updated for Java 21 compatibility with
 * pattern matching for token handling and optimized array/object processing.
 *
 * @since 3.16
 */
public class NestedAttributesMapUntypedObjectDeserializer
    extends UntypedObjectDeserializer
{
  private NestedAttributesMapJsonParser jsonParser;

  public NestedAttributesMapUntypedObjectDeserializer(final NestedAttributesMapJsonParser jsonParser) {
    super(null, null); // using this null, null constructor like the default deprecated one
    this.jsonParser = checkNotNull(jsonParser);
  }
  
  /**
   * Deserializes JSON content into Java objects using pattern matching for token handling.
   * This implementation is compatible with Jackson 2.16.1 and leverages Java 21 pattern matching.
   */
  @Override
  public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    // Use pattern matching to handle different token types
    return switch (p.currentToken()) {
      case START_OBJECT -> _deserializeObject(p, ctxt);
      case START_ARRAY -> _deserializeArray(p, ctxt);
      case FIELD_NAME -> p.getCurrentName();
      case VALUE_EMBEDDED_OBJECT -> p.getEmbeddedObject();
      case VALUE_STRING -> p.getText();
      case VALUE_NUMBER_INT -> _deserializeNumberInt(p, ctxt);
      case VALUE_NUMBER_FLOAT -> _deserializeNumberFloat(p, ctxt);
      case VALUE_TRUE -> Boolean.TRUE;
      case VALUE_FALSE -> Boolean.FALSE;
      case VALUE_NULL -> null;
      default -> ctxt.handleUnexpectedToken(Object.class, p);
    };
  }
  
  /**
   * Deserializes a JSON object using pattern matching and record patterns for cleaner code.
   */
  private Object _deserializeObject(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (isDefaultMapping()) {
      return mapObject(p, ctxt);
    }
    return super.deserialize(p, ctxt);
  }
  
  /**
   * Deserializes a JSON array using pattern matching and record patterns for cleaner code.
   */
  private Object _deserializeArray(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (isDefaultMapping()) {
      return mapArray(p, ctxt);
    }
    return super.deserialize(p, ctxt);
  }
  
  /**
   * Deserializes a JSON integer number using pattern matching.
   */
  private Object _deserializeNumberInt(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (ctxt.isEnabled(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)) {
      return p.getBigIntegerValue();
    }
    return p.getNumberValue();
  }
  
  /**
   * Deserializes a JSON floating point number using pattern matching.
   */
  private Object _deserializeNumberFloat(JsonParser p, DeserializationContext ctxt) throws IOException {
    if (ctxt.isEnabled(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)) {
      return p.getDecimalValue();
    }
    return p.getDoubleValue();
  }

  /**
   * Overridden to mark when we are mapping inside an array. The marker is available for internal checking.
   * This implementation uses Java 21 pattern matching for improved readability and error handling.
   */
  @Override
  protected Object mapArray(final JsonParser parser, final DeserializationContext context) throws IOException {
    try {
      markMappingInsideArray();
      
      // Use pattern matching to optimize array handling
      if (parser.isExpectedStartArrayToken()) {
        return _mapArray(parser, context);
      } else {
        // Using String Template for improved error message
        String errorMsg = STR."Unexpected token \{parser.currentToken()} (expected START_ARRAY)";
        throw context.mappingException(errorMsg);
      }
    }
    finally {
      unMarkMappingInsideArray();
    }
  }
  
  /**
   * Internal method to map array elements using pattern matching for token handling.
   */
  private Object _mapArray(final JsonParser parser, final DeserializationContext context) throws IOException {
    // Create result array with initial capacity
    java.util.ArrayList<Object> result = new java.util.ArrayList<>();
    parser.nextToken();
    
    // Use pattern matching with record patterns for cleaner code
    while (parser.currentToken() != JsonToken.END_ARRAY) {
      Object value = deserialize(parser, context);
      result.add(value);
      parser.nextToken();
    }
    
    return result;
  }

  /**
   * Maps a JSON object to a Java Map using pattern matching for token handling.
   * This implementation is optimized for Java 21 with record patterns for cleaner code.
   */
  protected Object mapObject(final JsonParser parser, final DeserializationContext context) throws IOException {
    // Ensure we're at the start of an object
    if (!parser.isExpectedStartObjectToken()) {
      // Using String Template for improved error message
      String errorMsg = STR."Unexpected token \{parser.currentToken()} (expected START_OBJECT)";
      throw context.mappingException(errorMsg);
    }
    
    // Create result map
    java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
    String fieldName;
    
    // Process all fields in the object using pattern matching
    while ((fieldName = _nextFieldName(parser)) != null) {
      parser.nextToken();
      Object value = deserialize(parser, context);
      result.put(fieldName, value);
    }
    
    return result;
  }
  
  /**
   * Helper method to get the next field name, handling token advancement.
   */
  private String _nextFieldName(JsonParser parser) throws IOException {
    // Move to next token and check if it's a field name
    parser.nextToken();
    return switch (parser.currentToken()) {
      case FIELD_NAME -> parser.getCurrentName();
      case END_OBJECT -> null;
      default -> {
        // Using String Template for improved error message
        String errorMsg = STR."Unexpected token \{parser.currentToken()} (expected FIELD_NAME or END_OBJECT)";
        throw new IOException(errorMsg);
      }
    };
  }

  protected NestedAttributesMap getChildFromRoot() {
    return jsonParser.getChildFromRoot();
  }

  protected boolean isMappingInsideArray() {
    return jsonParser.isMappingInsideArray();
  }

  protected String currentPath() {
    return jsonParser.currentPath();
  }

  protected boolean isMappingField(final String name) {
    return jsonParser.currentPath().endsWith(name);
  }

  protected void markMappingInsideArray() {
    jsonParser.markMappingInsideArray();
  }

  protected void unMarkMappingInsideArray() {
    jsonParser.unMarkMappingInsideArray();
  }

  protected boolean isDefaultMapping() {
    return jsonParser.isDefaultMapping();
  }

  protected void enableDefaultMapping() {
    jsonParser.enableDefaultMapping();
  }

  protected void disableDefaultMapping() {
    jsonParser.disableDefaultMapping();
  }
}