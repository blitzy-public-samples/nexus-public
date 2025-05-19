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

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Different ways content attributes can be updated.
 * <p>
 * This enum is designed to work with Java 21's pattern matching for switch expressions and statements.
 * Example usage with pattern matching:
 * <pre>
 * {@code
 * Object result = switch (operation) {
 *   case SET -> setAttribute(key, value);
 *   case REMOVE -> removeAttribute(key);
 *   case APPEND -> appendToList(key, value);
 *   case PREPEND -> prependToList(key, value);
 *   case OVERLAY -> overlayMap(key, value);
 * };
 * }
 * </pre>
 *
 * @since 3.21
 */
public enum AttributeOperation
{
  /**
   * Sets the attribute under the key, overwriting any existing value.
   */
  SET((attributes, entry) -> {
    attributes.put(entry.getKey(), entry.getValue());
    return attributes;
  }),

  /**
   * Removes the attribute under the key.
   */
  REMOVE((attributes, entry) -> {
    attributes.remove(entry.getKey());
    return attributes;
  }),

  /**
   * Appends a value to the attribute list under the key.
   */
  APPEND((attributes, entry) -> {
    Object value = attributes.get(entry.getKey());
    if (value instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) value;
      list.add(entry.getValue());
    } else {
      attributes.put(entry.getKey(), entry.getValue());
    }
    return attributes;
  }),

  /**
   * Prepends a value to the attribute list under the key.
   */
  PREPEND((attributes, entry) -> {
    Object value = attributes.get(entry.getKey());
    if (value instanceof List) {
      @SuppressWarnings("unchecked")
      List<Object> list = (List<Object>) value;
      list.add(0, entry.getValue());
    } else {
      attributes.put(entry.getKey(), entry.getValue());
    }
    return attributes;
  }),

  /**
   * Overlays a value onto the attribute map under the key.
   */
  OVERLAY((attributes, entry) -> {
    Object value = attributes.get(entry.getKey());
    if (value instanceof Map && entry.getValue() instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> map = (Map<String, Object>) value;
      @SuppressWarnings("unchecked")
      Map<String, Object> overlay = (Map<String, Object>) entry.getValue();
      map.putAll(overlay);
    } else {
      attributes.put(entry.getKey(), entry.getValue());
    }
    return attributes;
  });

  private final BiFunction<Map<String, Object>, Map.Entry<String, Object>, Map<String, Object>> function;

  /**
   * Constructor with operation implementation.
   *
   * @param function the function that implements the attribute operation
   */
  AttributeOperation(final BiFunction<Map<String, Object>, Map.Entry<String, Object>, Map<String, Object>> function) {
    this.function = function;
  }

  /**
   * Applies this operation to the given attributes and entry.
   *
   * @param attributes the attributes to modify
   * @param entry the key-value entry to apply
   * @return the modified attributes map
   */
  public Map<String, Object> apply(final Map<String, Object> attributes, final Map.Entry<String, Object> entry) {
    return function.apply(attributes, entry);
  }

  /**
   * Returns a description of the operation suitable for logging or display.
   * Demonstrates Java 21 pattern matching with switch expressions.
   *
   * @return a human-readable description of the operation
   */
  public String getDescription() {
    return switch (this) {
      case SET -> "Setting attribute value";
      case REMOVE -> "Removing attribute";
      case APPEND -> "Appending to attribute list";
      case PREPEND -> "Prepending to attribute list";
      case OVERLAY -> "Overlaying attribute map";
    };
  }

  /**
   * Determines if this operation modifies a collection.
   * Demonstrates Java 21 pattern matching with switch expressions and guards.
   *
   * @return true if the operation modifies a collection, false otherwise
   */
  public boolean isCollectionOperation() {
    return switch (this) {
      case APPEND, PREPEND, OVERLAY -> true;
      case SET, REMOVE -> false;
    };
  }
}