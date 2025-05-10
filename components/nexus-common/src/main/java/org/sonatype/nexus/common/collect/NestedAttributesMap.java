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
import java.util.SequencedMap;

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
   * Gets the parent key, fully qualified if it has a grandparent.
   */
  @VisibleForTesting
  String getParentKey() {
    // Using Pattern Matching for switch to handle parent qualification logic
    return switch (parent) {
      case null -> null;
      case NestedAttributesMap p when p.parent != null -> 
          p.getParentKey() + SEPARATOR + p.getKey();
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
   */
  @VisibleForTesting
  String getQualifiedKey() {
    // Using Pattern Matching for switch to handle key qualification logic
    return switch (parent) {
      case null -> key;
      case NestedAttributesMap p -> getParentKey() + SEPARATOR + key;
    };
  }

  /**
   * Include qualified key in missing key message.
   */
  @Override
  protected String missingKeyMessage(final String key) {
    return "Missing: {" + getQualifiedKey() + "} " + key;
  }

  /**
   * Create new backing for new children attributes backing.
   * Uses SequencedMap to maintain insertion order of elements.
   */
  protected Map<String, Object> newChildBacking() {
    return Maps.newLinkedHashMap(); // LinkedHashMap implements SequencedMap in Java 21
  }

    /**
   * Returns nested children attributes for given name.
   * Uses Java 21 features for more concise code.
   */
  @SuppressWarnings("unchecked")
  public NestedAttributesMap child(final String name) {
    checkNotNull(name);

    // Get the child or create a new backing if it doesn't exist
    Object child = backing.get(name);
    if (child == null) {
      child = newChildBacking();
      backing.put(name, child);
    }
    
    // Using Pattern Matching for switch to check the type
    // This is more expressive than the previous instanceof check
    return switch (child) {
      case Map<?, ?> m -> new NestedAttributesMap(this, name, (Map<String, Object>) m);
      default -> throw new IllegalStateException("child '" + name + "' not a Map");
    };
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "{" +
        "parent=" + getParentKey() +
        ", key='" + key + '\'' +
        ", backing=" + backing +
        '}';
  }

}