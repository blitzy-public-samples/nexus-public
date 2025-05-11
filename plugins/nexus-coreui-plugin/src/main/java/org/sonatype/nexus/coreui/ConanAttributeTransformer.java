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
package org.sonatype.nexus.coreui;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static java.util.Collections.emptyMap;
import static java.lang.StringTemplate.STR;

/**
 * This is used for transforming "infoBinary" attribute of a Conan asset.
 */
@Named(ConanAttributeTransformer.CONAN_FORMAT)
@Singleton
public class ConanAttributeTransformer
    extends ComponentSupport
    implements AssetAttributeTransformer
{
  static final String CONAN_FORMAT = "conan";

  static final String INFO_BINARY_ATTRIBUTE = "infoBinary";

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final TypeReference<Map<String, Object>> MAP_STRING_OBJECT =
      new TypeReference<Map<String, Object>>() { };

  /**
   * Transforms the given {@link AssetXO} by expanding its "infoBinary"
   * attribute with a map containing the same data with flattened keys.
   *
   * @param assetXO the asset to be transformed
   */
  @Override
  public void transform(final AssetXO assetXO) {
    // Using pattern matching for instanceof check with a binding variable
    var attributes = assetXO.getAttributes().getOrDefault(assetXO.getFormat(), emptyMap());
    if (attributes instanceof Map<?, ?> formatAttributes) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typedFormatAttributes = (Map<String, Object>) formatAttributes;
      
      if (typedFormatAttributes.containsKey(INFO_BINARY_ATTRIBUTE)) {
        updateFormatAttributesMap(typedFormatAttributes);
      }
    }
  }

  /**
   * Updates the format attributes map by replacing the "infoBinary" attribute
   * with a flattened representation of its JSON content.
   *
   * @param formatAttributesMap the map of format attributes to update
   */
  private void updateFormatAttributesMap(Map<String, Object> formatAttributesMap) {
    var infoBinaryValue = formatAttributesMap.get(INFO_BINARY_ATTRIBUTE);
    if (infoBinaryValue == null) {
      return;
    }
    
    String json = infoBinaryValue.toString();
    try {
      formatAttributesMap.putAll(getBinaryInfoAsMap(json));
      formatAttributesMap.remove(INFO_BINARY_ATTRIBUTE);
    } catch (IllegalStateException e) {
      log.debug(STR."Failed to parse \{json} as json");
    }
  }

  /**
   * Converts a JSON string into a flattened map.
   *
   * @param json the JSON string to be converted
   * @return a flattened map containing same data with flattened keys
   */
  private Map<String, Object> getBinaryInfoAsMap(final String json) {
    Map<String, Object> binaryInfo = deserialize(json, MAP_STRING_OBJECT);
    return flattenMap(binaryInfo, null);
  }

  /**
   * Flattens a nested map into a single-level map with dot-separated keys.
   *
   * @param map the nested map to be flattened
   * @param parentKey the parent key to be used as a prefix (can be null)
   * @return a flattened map
   */
  private Map<String, Object> flattenMap(final Map<String, Object> map, final String parentKey) {
    Map<String, Object> flattenedMap = new HashMap<>();
    for (var entry : map.entrySet()) {
      // Using Java 21 Record Pattern for Map.Entry
      var (key, value) = entry;
      String fullKey = parentKey == null ? key : STR."\{parentKey}.\{key}";
      
      // Using Pattern Matching for switch with value type
      switch (value) {
        case Map<?, ?> nestedMap -> 
          flattenedMap.putAll(flattenMap((Map<String, Object>) nestedMap, fullKey));
        case null -> 
          flattenedMap.put(fullKey, null);
        default -> 
          flattenedMap.put(fullKey, value);
      }
    }
    return flattenedMap;
  }

  /**
   * Deserializes a JSON string into an object of the specified type.
   *
   * @param json the JSON string to deserialize
   * @param type the TypeReference describing the target type
   * @return the deserialized object
   * @throws IllegalStateException if JSON processing fails
   */
  private <T> T deserialize(final String json, final TypeReference<T> type) {
    try {
      return OBJECT_MAPPER.readValue(json, type);
    }
    catch (JsonProcessingException e) {
      throw new IllegalStateException(STR."Failed to deserialize JSON: \{e.getMessage()}", e);
    }
  }

}