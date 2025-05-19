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
package org.sonatype.nexus.repository.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.content.AttributeChangeSet.AttributeChange;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.StringTemplate.STR;

/**
 * Helper for applying {@link AttributeOperation}s to repository content.
 *
 * @since 3.24
 */
public class AttributesHelper
{
  private AttributesHelper() {
    // static utility methods
  }

  /**
   * Apply a change request to the {@link RepositoryContent}'s attributes.
   */
  public static boolean applyAttributeChange(
      final RepositoryContent content,
      final AttributeOperation change,
      final String key,
      @Nullable final Object value)
  {
    return applyAttributeChange(content.attributes(), change, key, value);
  }

  /**
   * Apply a change request to the given {@link AttributesMap}.
   *
   * @since 3.29
   */
  public static boolean applyAttributeChange(final AttributesMap attributes, final AttributeChange change) {
    return applyAttributeChange(attributes, change.getOperation(), change.getKey(), change.getValue());
  }

  /**
   * Apply a change request to the given {@link AttributesMap}.
   */
  public static boolean applyAttributeChange(
      final AttributesMap attributes,
      final AttributeOperation change,
      final String key,
      @Nullable final Object value)
  {
    switch (change) {
      case SET:
        return !value.equals(attributes.set(key, checkNotNull(value)));
      case REMOVE:
        return attributes.remove(key) != null; // value is ignored
      case APPEND:
        attributes.compute(key, v -> append(v, checkNotNull(value)));
        return true;
      case PREPEND:
        attributes.compute(key, v -> prepend(v, checkNotNull(value)));
        return true;
      case OVERLAY:
        Object oldMap = attributes.get(key);
        Object newMap = overlay(oldMap, checkNotNull(value));
        if (!newMap.equals(oldMap)) {
          attributes.set(key, newMap);
          return true;
        }
        return false;
      default:
        throw new IllegalArgumentException(STR."Unknown request: \{change}");
    }
  }

  /**
   * Attempts to append a value to an attribute list.
   *
   * @throws IllegalArgumentException if the attribute is not a list
   */
  @SuppressWarnings("unchecked")
  private static Object append(final Object list, final Object value) {
    if (list == null) {
      return newArrayList(value);
    }
    
    // Using pattern matching for instanceof
    if (list instanceof List<?> listObj) {
      listObj.add(value);
      return list;
    }
    
    throw new IllegalArgumentException(STR."Cannot append to non-list attribute: \{list}");
  }

  /**
   * Attempts to prepend a value to an attribute list.
   *
   * @throws IllegalArgumentException if the attribute is not a list
   */
  @SuppressWarnings("unchecked")
  private static Object prepend(final Object list, final Object value) {
    if (list == null) {
      return newArrayList(value);
    }
    
    // Using pattern matching for instanceof
    if (list instanceof List<?> listObj) {
      listObj.add(0, value);
      return list;
    }
    
    throw new IllegalArgumentException(STR."Cannot prepend to non-list attribute: \{list}");
  }

  /**
   * Attempts to overlay a map value onto an attribute map.
   *
   * @throws IllegalArgumentException if either the value or attribute is not a map
   */
  private static Object overlay(final Object map, final Object value) {
    // Using pattern matching for instanceof
    if (!(value instanceof Map<?, ?> valueMap)) {
      throw new IllegalArgumentException(STR."Conflict: cannot overlay '\{value}' onto '\{map}'");
    }
    
    if (map == null) {
      return value;
    }
    
    // Using pattern matching for instanceof
    if (!(map instanceof Map<?, ?> resultMap)) {
      throw new IllegalArgumentException(STR."Conflict: cannot overlay '\{value}' onto '\{map}'");
    }
    
    // Create a mutable copy of the map to work with
    Map<Object, Object> mutableMap = new HashMap<>(resultMap);
    
    // Process each entry in the value map
    for (Entry<?, ?> entry : valueMap.entrySet()) {
      Object oldValue = mutableMap.get(entry.getKey());
      Object newValue = entry.getValue();
      
      // Recursively overlay nested maps
      if (oldValue instanceof Map<?, ?> && !oldValue.equals(newValue)) {
        newValue = overlay(oldValue, newValue);
      }
      
      // Only update if the value has changed
      if (!Objects.equals(oldValue, newValue)) {
        mutableMap.put(entry.getKey(), newValue);
      }
    }
    
    return mutableMap;
  }
}