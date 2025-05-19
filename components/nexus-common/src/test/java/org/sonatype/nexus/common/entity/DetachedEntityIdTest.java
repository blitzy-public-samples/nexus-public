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

// JUnit 4 backward compatibility
import org.junit.Test;
import org.junit.experimental.categories.Category;

// JUnit Jupiter API
import org.junit.jupiter.api.Assertions;

// Java 21 compatibility marker
import org.sonatype.nexus.virtualthread.Java21TestGroup;

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
    Assertions.assertEquals(a, a);
    Assertions.assertEquals(a, new DetachedEntityId("a"));

    DetachedEntityId b = new DetachedEntityId("b");
    Assertions.assertNotEquals(a, b);
  }
}