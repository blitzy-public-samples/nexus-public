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

import java.util.Collections;
import java.util.Map;
import java.util.SequencedMap;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

// Using static imports for cleaner code
import static java.util.Collections.unmodifiableMap;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An immutable {@link NestedAttributesMap}.
 *
 * @since 3.0
 */
public class ImmutableNestedAttributesMap
    extends NestedAttributesMap
{
  /**
   * Constructs an immutable nested attributes map.
   *
   * @param parent The parent map, or null if this is a root map
   * @param key The key for this map
   * @param backing The backing map containing the attributes
   */
  public ImmutableNestedAttributesMap(
      @Nullable final NestedAttributesMap parent,
      final String key,
      final Map<String, Object> backing)
  {
    // Use unmodifiableMap to ensure immutability of the backing map
    // In Java 21, if backing is a SequencedMap, this will preserve the encounter order
    super(parent, key, unmodifiableMap(backing));
  }

  /**
   * Returns nested children attributes for given name.
   * 
   * This implementation leverages Java 21 Pattern Matching for switch to provide
   * a more expressive and concise way to handle different types of child objects.
   */
  @Override
  @SuppressWarnings("unchecked")
  public NestedAttributesMap child(final String name) {
    checkNotNull(name);

    Object child = backing.get(name);
    
    // Using Pattern Matching for switch to handle child type checking
    // This provides a more expressive way to handle different cases
    // and is more maintainable than the previous if-else approach
    return switch (child) {
      // When child is null, return an empty immutable map
      case null -> new ImmutableNestedAttributesMap(this, name, ImmutableMap.of());
      
      // When child is a Map, cast it and create a new immutable map
      // The pattern variable 'm' is automatically typed as Map<?, ?>
      case Map<?, ?> m -> new ImmutableNestedAttributesMap(this, name, (Map<String, Object>) m);
      
      // For any other type, throw an exception
      default -> throw new IllegalStateException("child '" + name + "' not a Map");
    };
  }
}