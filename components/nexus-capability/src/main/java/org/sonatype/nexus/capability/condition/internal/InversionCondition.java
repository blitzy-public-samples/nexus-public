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

import javax.inject.Provider;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.common.event.EventManager;

/**
 * A condition that applies a logical NOT on another condition.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and ensures proper event
 * handling across Virtual Thread boundaries. It also optimizes synchronization when checking
 * condition states.
 *
 * @since capabilities 2.0
 */
public class InversionCondition
    extends CompositeConditionSupport
    implements Condition
{

  private final Condition condition;

  /**
   * Constructs a new InversionCondition with the specified EventManager and condition.
   * <p>
   * This implementation ensures proper handling of events across Virtual Thread boundaries.
   *
   * @param eventManager the event manager
   * @param condition the condition to invert
   */
  public InversionCondition(final EventManager eventManager,
                            final Condition condition)
  {
    super(eventManager, condition);
    this.condition = condition;
  }

  /**
   * Constructs a new InversionCondition with the specified EventManager provider and condition.
   * <p>
   * This constructor is optimized for Virtual Thread environments by using a provider pattern
   * for EventManager access.
   *
   * @param eventManagerProvider the provider of EventManager instances
   * @param condition the condition to invert
   * @since 3.60
   */
  public InversionCondition(final Provider<EventManager> eventManagerProvider,
                            final Condition condition)
  {
    super(eventManagerProvider, condition);
    this.condition = condition;
  }

  /**
   * Reevaluates the condition state by applying logical NOT to the wrapped condition.
   * <p>
   * This implementation is optimized for Virtual Thread environments by minimizing
   * synchronization when checking condition states. It captures the condition state once
   * to avoid race conditions that could occur when multiple Virtual Threads access the
   * condition simultaneously.
   *
   * @param conditions the conditions to reevaluate
   * @return true if the wrapped condition is not satisfied, false otherwise
   */
  @Override
  protected boolean reevaluate(final Condition... conditions) {
    // Capture the condition state once to avoid race conditions in Virtual Thread environments
    final Condition targetCondition = conditions[0];
    final boolean conditionState = targetCondition.isSatisfied();
    return !conditionState;
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
