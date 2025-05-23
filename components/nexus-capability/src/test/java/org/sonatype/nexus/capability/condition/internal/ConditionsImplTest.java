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
import org.sonatype.nexus.capability.condition.CapabilityConditions;
import org.sonatype.nexus.capability.condition.CryptoConditions;
import org.sonatype.nexus.capability.condition.LogicalConditions;
import org.sonatype.nexus.capability.condition.NexusConditions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ConditionsImpl} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
public class ConditionsImplTest
    extends TestSupport
{
  @Mock
  private LogicalConditions logicalConditions;
  
  @Mock
  private CapabilityConditions capabilityConditions;
  
  @Mock
  private NexusConditions nexusConditions;
  
  @Mock
  private CryptoConditions cryptoConditions;

  /**
   * Passed in factories are returned.
   */
  @Test
  public void and01() {
    // Using String Template for improved test logging
    String testName = STR."Testing ConditionsImpl with mocked dependencies: \{logicalConditions.getClass().getSimpleName()}";
    log(testName);
    
    final ConditionsImpl underTest = new ConditionsImpl(
        logicalConditions, capabilityConditions, nexusConditions, cryptoConditions
    );
    
    // Using JUnit 5 assertions instead of Hamcrest
    assertSame(logicalConditions, underTest.logical(), "Logical conditions should be the same instance");
    assertSame(capabilityConditions, underTest.capabilities(), "Capability conditions should be the same instance");
    assertSame(nexusConditions, underTest.nexus(), "Nexus conditions should be the same instance");
  }
}
