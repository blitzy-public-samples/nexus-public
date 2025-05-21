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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * {@link LogicalConditionsImpl} UTs.
 *
 * @since capabilities 2.0
 */
@ExtendWith(MockitoExtension.class)
public class LogicalConditionsImplTest
    extends EventManagerTestSupport
{

  static final boolean UNSATISFIED = false;

  static final boolean SATISFIED = true;

  @Mock
  private Condition left;

  @Mock
  private Condition right;

  private LogicalConditionsImpl underTest;

  @BeforeEach
  public final void setUpLogicalConditions()
      throws Exception
  {
    underTest = new LogicalConditionsImpl(eventManager);
  }

  public Condition prepare(final CompositeConditionSupport condition, boolean leftSatisfied,
                           boolean rightSatisfied)
  {
    condition.bind();

    when(left.isSatisfied()).thenReturn(leftSatisfied);
    when(right.isSatisfied()).thenReturn(rightSatisfied);

    // Using pattern matching for switch to handle condition events
    switch (new boolean[]{leftSatisfied, rightSatisfied}) {
      case boolean[] arr when arr[0] -> condition.handle(new ConditionEvent.Satisfied(left));
      case boolean[] _ -> condition.handle(new ConditionEvent.Unsatisfied(left));
    }

    // Using pattern matching for switch to handle condition events
    switch (new boolean[]{leftSatisfied, rightSatisfied}) {
      case boolean[] arr when arr[1] -> condition.handle(new ConditionEvent.Satisfied(right));
      case boolean[] _ -> condition.handle(new ConditionEvent.Unsatisfied(right));
    }

    return condition;
  }

  /**
   * Tests a logical AND between conditions.
   * <p/>
   * Condition is not satisfied when both operands are not satisfied.
   */
  @Test
  public void and01() {
    final Condition and =
        prepare((CompositeConditionSupport) underTest.and(left, right), UNSATISFIED, UNSATISFIED);
    assertFalse(and.isSatisfied());
  }

  /**
   * Tests a logical AND between conditions.
   * <p/>
   * Condition is not satisfied when left is unsatisfied and right is satisfied.
   */
  @Test
  public void and02() {
    final Condition and =
        prepare((CompositeConditionSupport) underTest.and(left, right), UNSATISFIED, SATISFIED);
    assertFalse(and.isSatisfied());
  }

  /**
   * Tests a logical AND between conditions.
   * <p/>
   * Condition is not satisfied when left is satisfied and right is unsatisfied.
   */
  @Test
  public void and03() {
    final Condition and =
        prepare((CompositeConditionSupport) underTest.and(left, right), SATISFIED, UNSATISFIED);
    assertFalse(and.isSatisfied());
  }

  /**
   * Tests a logical AND between conditions.
   * <p/>
   * Condition is satisfied when left is satisfied and right is satisfied.
   */
  @Test
  public void and04() {
    final Condition and =
        prepare((CompositeConditionSupport) underTest.and(left, right), SATISFIED, SATISFIED);
    assertTrue(and.isSatisfied());
  }

  /**
   * Tests a logical OR between conditions.
   * <p/>
   * Condition is not satisfied when both operands are not satisfied.
   */
  @Test
  public void or01() {
    final Condition or =
        prepare((CompositeConditionSupport) underTest.or(left, right), UNSATISFIED, UNSATISFIED);
    assertFalse(or.isSatisfied());
  }

  /**
   * Tests a logical OR between conditions.
   * <p/>
   * Condition is satisfied when left is unsatisfied and right is satisfied.
   */
  @Test
  public void or02() {
    final Condition or =
        prepare((CompositeConditionSupport) underTest.or(left, right), UNSATISFIED, SATISFIED);
    assertTrue(or.isSatisfied());
  }

  /**
   * Tests a logical OR between conditions.
   * <p/>
   * Condition is satisfied when left is satisfied and right is unsatisfied.
   */
  @Test
  public void or03() {
    final Condition or =
        prepare((CompositeConditionSupport) underTest.or(left, right), SATISFIED, UNSATISFIED);
    assertTrue(or.isSatisfied());
  }

  /**
   * Tests a logical OR between conditions.
   * <p/>
   * Condition is satisfied when left is satisfied and right is satisfied.
   */
  @Test
  public void or04() {
    final Condition or = prepare((CompositeConditionSupport) underTest.or(left, right), SATISFIED, SATISFIED);
    assertTrue(or.isSatisfied());
  }

}
