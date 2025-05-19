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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.SequencedCollection;

import javax.annotation.Nullable;

import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.repository.content.fluent.FluentAttributes;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Lists.newArrayList;
import static java.lang.StringTemplate.STR;

/**
 * A set of attribute changes to be applied to repository content.
 * 
 * @since 3.29
 */
public class AttributeChangeSet
    implements FluentAttributes<AttributeChangeSet>
{
  private final SequencedCollection<AttributeChange> changes = new ArrayList<>();

  public AttributeChangeSet(final AttributeOperation operation, final String key, final Object value) {
    changes.add(new AttributeChange(operation, key, value));
  }

  public AttributeChangeSet() {
    // do nothing
  }

  @Override
  public AttributeChangeSet attributes(final AttributeOperation change, final String key, final Object value) {
    changes.add(new AttributeChange(change, key, value));

    return this;
  }

  /**
   * Returns an unmodifiable view of the changes in this set.
   */
  public List<AttributeChange> getChanges() {
    return Collections.unmodifiableList(new ArrayList<>(changes));
  }
  
  /**
   * Apply all changes in this set to the given attributes map.
   * 
   * @param attributes the attributes to modify
   * @return true if any changes were applied
   */
  public boolean applyTo(final AttributesMap attributes) {
    return changes.stream()
        .map(change -> applyAttributeChange(attributes, change))
        .reduce(Boolean::logicalOr)
        .orElse(false);
  }
  
  /**
   * Apply a single attribute change to the given attributes map using pattern matching.
   * 
   * @param attributes the attributes to modify
   * @param change the change to apply
   * @return true if the change was applied
   */
  private boolean applyAttributeChange(final AttributesMap attributes, final AttributeChange change) {
    return switch (change.getOperation()) {
      case SET -> {
        Object oldValue = attributes.set(change.getKey(), checkNotNull(change.getValue()));
        yield !change.getValue().equals(oldValue);
      }
      case REMOVE -> attributes.remove(change.getKey()) != null; // value is ignored
      case APPEND -> {
        attributes.compute(change.getKey(), v -> append(v, checkNotNull(change.getValue())));
        yield true;
      }
      case PREPEND -> {
        attributes.compute(change.getKey(), v -> prepend(v, checkNotNull(change.getValue())));
        yield true;
      }
      case OVERLAY -> {
        Object oldMap = attributes.get(change.getKey());
        Object newMap = overlay(oldMap, checkNotNull(change.getValue()));
        if (!newMap.equals(oldMap)) {
          attributes.set(change.getKey(), newMap);
          yield true;
        }
        yield false;
      }
    };
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
  @SuppressWarnings("unchecked")
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
    
    Map<Object, Object> mutableMap = new java.util.HashMap<>((Map<Object, Object>) resultMap);
    
    for (Map.Entry<?, ?> entry : ((Map<?, ?>) valueMap).entrySet()) {
      Object oldValue = mutableMap.get(entry.getKey());
      Object newValue = entry.getValue();
      
      if (oldValue instanceof Map && newValue instanceof Map && !oldValue.equals(newValue)) {
        newValue = overlay(oldValue, newValue);
      }
      
      if (oldValue == null || !oldValue.equals(newValue)) {
        mutableMap.put(entry.getKey(), newValue);
      }
    }
    
    return mutableMap;
  }

  /**
   * Represents a single attribute change operation.
   */
  public static class AttributeChange
  {
    private final AttributeOperation operation;

    private final String key;

    private final Object value;

    private AttributeChange(final AttributeOperation operation, final String key, @Nullable final Object value) {
      this.operation = checkNotNull(operation);
      this.key = checkNotNull(key);
      this.value = value;
    }

    public AttributeOperation getOperation() {
      return operation;
    }

    public String getKey() {
      return key;
    }

    @Nullable
    public Object getValue() {
      return value;
    }

    @Override
    public String toString() {
      return STR."AttributeChange{operation=\{operation}, key='\{key}', value=\{value}}";
    }
  }
}
