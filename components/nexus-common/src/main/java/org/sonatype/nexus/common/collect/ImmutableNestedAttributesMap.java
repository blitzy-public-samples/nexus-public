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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * An immutable {@link NestedAttributesMap}.
 * <p>
 * This implementation leverages Java 21 features including Pattern Matching for switch
 * and the Sequenced Collections API for improved code readability and performance.
 *
 * @since 3.0
 */
public class ImmutableNestedAttributesMap
    extends NestedAttributesMap
{
  public ImmutableNestedAttributesMap(
      @Nullable final NestedAttributesMap parent,
      final String key,
      final Map<String, Object> backing)
  {
    super(parent, key, Collections.unmodifiableMap(backing));
  }

  /**
   * Returns nested children attributes for given name.
   * <p>
   * This implementation uses Java 21 Pattern Matching for switch to handle different
   * cases more elegantly and with improved type safety.
   */
  @Override
  public NestedAttributesMap child(final String name) {
    checkNotNull(name);

    Object child = backing.get(name);
    // Use pattern matching for switch to handle different cases more elegantly
    return switch (child) {
      case null -> new ImmutableNestedAttributesMap(this, name, ImmutableMap.of());
      case Map<?, ?> map -> {
        // Safe cast as we know it's a Map
        @SuppressWarnings("unchecked")
        Map<String, Object> childMap = (Map<String, Object>) map;
        yield new ImmutableNestedAttributesMap(this, name, childMap);
      }
      default -> throw new IllegalStateException("child '" + name + "' not a Map");
    };
  }