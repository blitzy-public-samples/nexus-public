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
import org.sonatype.nexus.capability.CapabilityDescriptorRegistry;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.common.event.EventManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.sonatype.nexus.capability.CapabilityType.capabilityType;

/**
 * {@link CapabilityConditionsImpl} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
class CapabilityConditionsImplTest
    extends TestSupport
{
  @Mock
  private EventManager eventManager;
  
  @Mock
  private CapabilityDescriptorRegistry descriptorRegistry;
  
  @Mock
  private CapabilityRegistry capabilityRegistry;

  private CapabilityConditionsImpl underTest;

  @BeforeEach
  void setUpCapabilityConditions() {
    underTest = new CapabilityConditionsImpl(eventManager, descriptorRegistry, capabilityRegistry);
  }

  /**
   * capabilityOfTypeExists() factory method returns expected condition.
   */
  @Test
  void capabilityOfTypeExists() {
    assertInstanceOf(
        CapabilityOfTypeExistsCondition.class,
        underTest.capabilityOfTypeExists(capabilityType("test")),
        STR."Expected condition of type \{CapabilityOfTypeExistsCondition.class.getSimpleName()}"
    );
  }

  /**
   * capabilityOfTypeActive() factory method returns expected condition.
   */
  @Test
  void capabilityOfTypeActive() {
    assertInstanceOf(
        CapabilityOfTypeActiveCondition.class,
        underTest.capabilityOfTypeActive(capabilityType("test")),
        STR."Expected condition of type \{CapabilityOfTypeActiveCondition.class.getSimpleName()}"
    );
  }

  /**
   * reactivateCapabilityOnUpdate() factory method returns expected condition.
   */
  @Test
  void reactivateCapabilityOnUpdate() {
    assertInstanceOf(
        PassivateCapabilityDuringUpdateCondition.class,
        underTest.passivateCapabilityDuringUpdate(),
        STR."Expected condition of type \{PassivateCapabilityDuringUpdateCondition.class.getSimpleName()}"
    );
  }
}