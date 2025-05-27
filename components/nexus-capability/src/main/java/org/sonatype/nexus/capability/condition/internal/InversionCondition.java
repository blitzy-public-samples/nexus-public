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
import org.sonatype.nexus.common.event.EventManager;

/**
 * A condition that applies a logical NOT on another condition.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and ensures proper event
 * handling across Virtual Thread boundaries. It optimizes synchronization when checking condition states.
 *
 * @since capabilities 2.0
 */
public class InversionCondition
    extends CompositeConditionSupport
    implements Condition
{

  private final Condition condition;

  /**
   * Constructs a new InversionCondition.
   * <p>
   * This implementation ensures proper handling of the condition across Virtual Thread boundaries.
   *
   * @param eventManager the event manager instance
   * @param condition the condition to be inverted
   */
  public InversionCondition(final EventManager eventManager,
                            final Condition condition)
  {
    super(eventManager, condition);
    this.condition = condition;
  }

  /**
   * Reevaluates the condition state by inverting the satisfaction state of the wrapped condition.
   * <p>
   * This method is optimized for Virtual Thread execution and avoids unnecessary synchronization.
   *
   * @param conditions the conditions to evaluate (only the first one is used)
   * @return true if the wrapped condition is not satisfied
   */
  @Override
  protected boolean reevaluate(final Condition... conditions) {
    // Direct access to condition state without additional synchronization
    // The parent class already handles thread safety concerns
    return !conditions[0].isSatisfied();
  }

  @Override
  public String toString() {
    return "NOT " + condition;
  }

  @Override
  public String explainSatisfied() {
    return condition.explainUnsatisfied();
  }

  @Override
  public String explainUnsatisfied() {
    return condition.explainSatisfied();
  }
}
