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

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;
import org.sonatype.nexus.common.event.EventManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeConditionSupport} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
public class CompositeConditionTestSupport
    extends EventManagerTestSupport
{

  @Mock
  private Condition c1;

  @Mock
  private Condition c2;

  @Mock
  private Condition c3;

  private TestCondition underTest;

  @BeforeEach
  public final void setUpTestCondition()
      throws Exception
  {
    underTest = new TestCondition(eventManager, c1, c2, c3);
    underTest.bind();

    verify(c1).bind();
    verify(c2).bind();
    verify(c3).bind();
  }

  /**
   * On creation, condition is not satisfied.
   */
  @Test
  public void notSatisfiedInitially() {
    assertFalse(underTest.isSatisfied());
  }

  /**
   * When a condition is satisfied, check() is called and it returns true, condition is satisfied and notification
   * sent.
   */
  @Test
  public void whenMemberConditionIsSatisfiedAndReevaluateReturnsTrue() {
    when(c1.isSatisfied()).thenReturn(true);
    underTest.handle(new ConditionEvent.Satisfied(c1));
    assertTrue(underTest.isSatisfied());

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * When a condition is satisfied, check() is called and it returns false, condition is satisfied and notification
   * sent.
   */
  @Test
  public void whenMemberConditionIsSatisfiedAndCheckReturnsFalse() {
    when(c1.isSatisfied()).thenReturn(true);
    underTest.handle(new ConditionEvent.Satisfied(c1));

    when(c1.isSatisfied()).thenReturn(false);
    when(c2.isSatisfied()).thenReturn(false);
    underTest.handle(new ConditionEvent.Satisfied(c2));
    assertFalse(underTest.isSatisfied());

    verifyEventManagerEvents(satisfied(underTest), unsatisfied(underTest));
  }

  /**
   * When a condition is unsatisfied, check() is called and it returns true, condition is satisfied and notification
   * sent.
   */
  @Test
  public void whenMemberConditionIsUnsatisfiedAndCheckReturnsTrue() {
    when(c1.isSatisfied()).thenReturn(false);
    when(c2.isSatisfied()).thenReturn(true);
    underTest.handle(new ConditionEvent.Unsatisfied(c1));
    assertTrue(underTest.isSatisfied());

    verifyEventManagerEvents(satisfied(underTest));
  }

  /**
   * When a condition is unsatisfied, check() is called and it returns false, condition is satisfied and notification
   * sent.
   */
  @Test
  public void whenMemberConditionIsUnsatisfiedAndCheckReturnsFalse() {
    when(c1.isSatisfied()).thenReturn(false);
    when(c2.isSatisfied()).thenReturn(true);
    underTest.handle(new ConditionEvent.Unsatisfied(c1));

    when(c1.isSatisfied()).thenReturn(false);
    when(c2.isSatisfied()).thenReturn(false);
    underTest.handle(new ConditionEvent.Unsatisfied(c2));
    assertFalse(underTest.isSatisfied());

    verifyEventManagerEvents(satisfied(underTest), unsatisfied(underTest));
  }

  /**
   * On release, member conditions are released and handler removed from event bus.
   */
  @Test
  public void listenerRemovedOnRelease() {
    underTest.release();
    verify(c1).release();
    verify(c2).release();
    verify(c3).release();
    verify(eventManager).unregister(underTest);
  }

  private static class TestCondition
      extends CompositeConditionSupport
  {

    public TestCondition(final EventManager eventManager,
                         final Condition... conditions)
    {
      super(eventManager, conditions);
    }

    @Override
    protected boolean reevaluate(final Condition... conditions) {
      for (final Condition condition : conditions) {
        if (condition.isSatisfied()) {
          return true;
        }
      }
      return false;
    }

  }

}
