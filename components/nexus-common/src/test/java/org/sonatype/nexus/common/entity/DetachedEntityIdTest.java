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
package org.sonatype.nexus.common.entity;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.java21.Java21TestGroup;

import org.junit.jupiter.api.Test;
import org.junit.vintage.engine.descriptor.VintageTestDescriptor; // JUnit Vintage Engine for JUnit 4 backward compatibility
import org.junit.experimental.categories.Category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Tests for {@link DetachedEntityId}
 */
@Category(Java21TestGroup.class)
public class DetachedEntityIdTest
    extends TestSupport
{
  @Test
  public void detachedEquality() {
    DetachedEntityId a = new DetachedEntityId("a");
    assertEquals(a, a, "Entity ID should be equal to itself");
    assertEquals(a, new DetachedEntityId("a"), "Entity IDs with same value should be equal");

    DetachedEntityId b = new DetachedEntityId("b");
    assertNotEquals(a, b, "Entity IDs with different values should not be equal");
  }
}