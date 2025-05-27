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
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.UntypedObjectDeserializer;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Implementation of {@link UntypedObjectDeserializer} with using a {@link NestedAttributesMap} as a root map to
 * get existing deserialized references from.
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
   * Deserialize method that uses pattern matching for token handling.
   * This implementation ensures compatibility with Jackson 2.16.1's UntypedObjectDeserializer.
   */
  @Override
  public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
    // Use pattern matching for token handling with record patterns for cleaner code
    JsonToken token = p.currentToken();
    
    // Using pattern matching for switch with enhanced type patterns in Java 21
    return switch (token) {
      case JsonToken.START_OBJECT -> {
        NestedAttributesMap child = getChildFromRoot();
        if (child != null && !isDefaultMapping()) {
          // If we have a child map and we're not in default mapping mode,
          // we can use the existing map
          p.skipChildren();
          yield child.backing();
        } else {
          // Otherwise, delegate to the parent implementation
          yield super.deserialize(p, ctxt);
        }
      }
      case JsonToken.START_ARRAY -> {
        // For arrays, we use the parent implementation but mark that we're inside an array
        try {
          markMappingInsideArray();
          yield super.deserialize(p, ctxt);
        } finally {
          unMarkMappingInsideArray();
        }
      }
      // Group primitive value tokens using pattern matching
      case JsonToken.VALUE_STRING, JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT, 
           JsonToken.VALUE_TRUE, JsonToken.VALUE_FALSE, JsonToken.VALUE_NULL -> {
        // For primitive values, delegate to the parent implementation
        yield super.deserialize(p, ctxt);
      }
      case null -> {
        // Handle null token case with String Template for error message
        String errorMsg = STR."Null token encountered at path \{currentPath()}";
        throw new IllegalStateException(errorMsg);
      }
      default -> {
        // For any other token, use a String Template for the error message
        String errorMsg = STR."Unexpected token \{token} at path \{currentPath()}";
        throw new IllegalStateException(errorMsg);
      }
    };
  }

  /**
   * Overridden to mark when we are mapping inside an array. The marker is available for internal checking.
   * Optimized with Java 21 features for handling arrays.
   */
  @Override
  protected Object mapArray(final JsonParser parser, final DeserializationContext context) throws IOException {
    // Use try-with-resources pattern with a custom AutoCloseable for cleaner code
    record ArrayMappingContext(NestedAttributesMapUntypedObjectDeserializer deserializer) implements AutoCloseable {
      @Override
      public void close() {
        deserializer.unMarkMappingInsideArray();
      }
    }
    
    try (var mappingContext = new ArrayMappingContext(this)) {
      markMappingInsideArray();
      return super.mapArray(parser, context);
    }
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