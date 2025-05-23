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
package org.sonatype.nexus.capability.condition.internal;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.capability.Condition;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@link NexusConditionsImpl} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
class NexusConditionsImplTest
    extends TestSupport
{
  @Mock
  private NexusIsActiveCondition nexusIsActiveCondition;

  /**
   * active() factory method returns expected condition.
   */
  @Test
  void active() {
    final NexusConditionsImpl underTest = new NexusConditionsImpl(nexusIsActiveCondition);

    // Using String Template for improved test message
    assertInstanceOf(NexusIsActiveCondition.class, underTest.active(), 
        STR."Expected \{NexusIsActiveCondition.class.getSimpleName()} but got \{underTest.active().getClass().getSimpleName()}");
  }
}