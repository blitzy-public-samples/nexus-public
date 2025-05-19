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

import com.google.common.collect.Maps;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test for the {@link ImmutableNestedAttributesMap}
 */
public class ImmutableNestedAttributesMapTest
{
  private ImmutableNestedAttributesMap map = new ImmutableNestedAttributesMap(null, "key", Maps.newHashMap());

  @Test
  void shouldThrowExceptionWhenSettingClassKeys() {
    assertThrows(UnsupportedOperationException.class, 
        () -> map.set(Integer.class, 15),
        "Should throw UnsupportedOperationException when setting class keys");
  }

  @Test
  void shouldThrowExceptionWhenSettingStringKeys() {
    assertThrows(UnsupportedOperationException.class, 
        () -> map.set("key", "value"),
        "Should throw UnsupportedOperationException when setting string keys");
  }

  @Test
  void shouldAllowNavigationToNonExistentChildren() {
    final NestedAttributesMap nonexistent = map.child("nonexistent");
    assertThat("Non-existent child should be navigable", nonexistent, is(notNullValue()));
    assertThat("Map backing should be empty after navigating to non-existent child", map.backing().isEmpty(), is(true));
  }

  @Test
  void shouldThrowExceptionWhenModifyingNavigableChildren() {
    assertThrows(UnsupportedOperationException.class, 
        () -> map.child("nonexistent").set("key", "value"),
        "Should throw UnsupportedOperationException when modifying navigable children");
  }
}