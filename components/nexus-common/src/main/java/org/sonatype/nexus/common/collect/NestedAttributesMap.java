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

import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Nested {@link AttributesMap} supporting parent/child relationship.
 *
 * @since 3.0
 */
public class NestedAttributesMap
    extends AttributesMap
{
  @VisibleForTesting
  static final String SEPARATOR = "::";

  @JsonProperty
  @Nullable
  private NestedAttributesMap parent;

  @JsonProperty
  private String key;

  public NestedAttributesMap() {
    super();
  }

  public NestedAttributesMap(final String key, final Map<String, Object> backing) {
    this(null, key, backing);
  }

  @VisibleForTesting
  NestedAttributesMap(
      @Nullable final NestedAttributesMap parent,
      final String key,
      final Map<String, Object> backing)
  {
    super(backing);
    this.parent = parent;
    this.key = checkNotNull(key);
  }

  /**
   * Returns the parent of this attributes or null if there is none.
   */
  @Nullable
  public NestedAttributesMap getParent() {
    return parent;
  }

  /**
   * Gets the parent key, using pattern matching for improved type safety.
   */
  @VisibleForTesting
  String getParentKey() {
    return switch (parent) {
      case null -> null;
      case NestedAttributesMap p when p.parent != null -> 
          p.getParentKey() + SEPARATOR + p.getKey(); // fully-qualify parent key if it has a grandparent
      case NestedAttributesMap p -> p.getKey();
    };
  }

  /**
   * Return the nested attributes key.
   */
  public String getKey() {
    return key;
  }

  /**
   * Returns the key of this nested container qualified with parent if there is one.
   * Uses pattern matching for improved type safety.
   */
  @VisibleForTesting
  String getQualifiedKey() {
    return switch (parent) {
      case null -> key;
      case NestedAttributesMap p -> getParentKey() + SEPARATOR + key;
    };
  }

  /**
   * Include qualified key in missing key message using String Templates for improved readability.
   */
  @Override
  protected String missingKeyMessage(final String key) {
    return STR."Missing: {\{getQualifiedKey()\}} \{key}";
  }

  /**
   * Create new backing for new children attributes backing.
   */
  protected Map<String, Object> newChildBacking() {
    return Maps.newHashMap();
  }

  /**
   * Returns nested children attributes for given name.
   * Uses pattern matching for more robust null handling and type safety.
   */
  @SuppressWarnings("unchecked")
  public NestedAttributesMap child(final String name) {
    checkNotNull(name, "Child name cannot be null");

    Object child = backing.get(name);
    
    // Use pattern matching for improved type safety
    Map<String, Object> childMap;
    if (child == null) {
      childMap = newChildBacking();
      backing.put(name, childMap);
    }
    else if (child instanceof Map<?,?> map) {
      @SuppressWarnings("unchecked")
      Map<String, Object> typedMap = (Map<String, Object>) map;
      childMap = typedMap;
    }
    else {
      throw new IllegalStateException(STR."Child '\{name}' is not a Map but \{child.getClass().getName()}");
    }
    return new NestedAttributesMap(this, name, childMap);
  }

  @Override
  public String toString() {
    return STR."\{getClass().getSimpleName()}{"
        + STR."parent=\{getParentKey()}, "
        + STR."key='\{key}', "
        + STR."backing=\{backing}}"; 
  }