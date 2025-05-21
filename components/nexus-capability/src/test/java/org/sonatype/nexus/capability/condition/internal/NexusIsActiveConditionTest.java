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

import org.sonatype.nexus.capability.condition.EventManagerTestSupport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NexusIsActiveCondition} UTs.
 *
 * @since capabilities 2.0
 */
public class NexusIsActiveConditionTest
    extends EventManagerTestSupport
{

  private NexusIsActiveCondition underTest;

  @BeforeEach
  public final void setUpNexusIsActiveCondition() throws Exception {
    underTest = new NexusIsActiveCondition(eventManager);
  }

  /**
   * Condition is not satisfied initially.
   */
  @Test
  public void notSatisfiedInitially() {
    assertFalse(underTest.isSatisfied(), STR."Expected condition to be unsatisfied initially: \{underTest}");
  }

  /**
   * Condition is satisfied when Nexus is started.
   */
  @Test
  public void satisfiedWhenNexusStarted() {
    underTest.start();
    assertTrue(underTest.isSatisfied(), STR."Expected condition to be satisfied after Nexus started: \{underTest}");

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * Condition is satisfied when negated is not satisfied.
   */
  @Test
  public void unsatisfiedWhenNexusStopped() {
    underTest.start();
    underTest.stop();
    assertFalse(underTest.isSatisfied(), STR."Expected condition to be unsatisfied after Nexus stopped: \{underTest}");

    verifyEventManagerEvents(satisfied(underTest), unsatisfied(underTest));
  }

}
