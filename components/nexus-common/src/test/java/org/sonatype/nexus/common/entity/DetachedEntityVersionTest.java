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
import org.sonatype.goodies.testsupport.group.Java21TestGroup;

import org.junit.experimental.categories.Category;
import org.junit.jupiter.api.Test;
import org.junit.vintage.engine.descriptor.VintageTestDescriptor;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link DetachedEntityVersion}
 */
@Category(Java21TestGroup.class)
public class DetachedEntityVersionTest
    extends TestSupport
{
  @Test
  public void detachedEquality() {
    DetachedEntityVersion a = new DetachedEntityVersion("a");
    
    // Test self-equality
    assertTrue(a.equals(a), "An entity should equal itself");
    
    // Test equality with identical value
    assertThat(a, equalTo(new DetachedEntityVersion("a")));

    // Test inequality with different value
    DetachedEntityVersion b = new DetachedEntityVersion("b");
    assertThat(a, not(equalTo(b)));
  }
}