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

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityContextAware;
import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.capability.condition.ConditionSupport;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Composite {@link Condition} implementation support.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and ensures proper event
 * handling across Virtual Thread boundaries.
 *
 * @since capabilities 2.0
 */
public abstract class CompositeConditionSupport
    extends ConditionSupport
    implements CapabilityContextAware
{

  private final Condition[] conditions;

  /**
   * Constructs a new CompositeConditionSupport with multiple conditions.
   * <p>
   * This implementation ensures proper handling of conditions across Virtual Thread boundaries.
   *
   * @param eventManager the event manager instance
   * @param conditions the conditions to be managed (at least 2)
   */
  public CompositeConditionSupport(final EventManager eventManager,
                                   final Condition... conditions)
  {
    super(eventManager, false);
    this.conditions = checkNotNull(conditions);
    checkArgument(conditions.length > 1, "A composite must have at least 2 conditions");
  }

  /**
   * Constructs a new CompositeConditionSupport with a single condition.
   * <p>
   * This implementation ensures proper handling of the condition across Virtual Thread boundaries.
   *
   * @param eventManager the event manager instance
   * @param condition the condition to be managed
   */
  public CompositeConditionSupport(final EventManager eventManager,
                                   final Condition condition)
  {
    super(eventManager, false);
    this.conditions = new Condition[]{checkNotNull(condition)};
  }

  @Override
  protected void doBind() {
    for (final Condition condition : conditions) {
      condition.bind();
    }
    getEventManager().register(this);
    setSatisfied(reevaluate(conditions));
  }

  @Override
  public void doRelease() {
    getEventManager().unregister(this);
    for (final Condition condition : conditions) {
      condition.release();
    }
  }

  /**
   * Handles condition satisfied events, ensuring proper thread context propagation.
   * <p>
   * This implementation is Virtual Thread-friendly and maintains proper context across thread boundaries.
   *
   * @param event the satisfied event
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final ConditionEvent.Satisfied event) {
    // Capture the condition from the event to ensure proper context propagation
    final Condition eventCondition = event.getCondition();
    
    if (shouldReevaluateFor(eventCondition)) {
      // Evaluate in the current thread context to ensure proper state management
      final boolean newState = reevaluate(conditions);
      setSatisfied(newState);
    }
  }

  /**
   * Handles condition unsatisfied events, ensuring proper thread context propagation.
   * <p>
   * This implementation is Virtual Thread-friendly and maintains proper context across thread boundaries.
   *
   * @param event the unsatisfied event
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final ConditionEvent.Unsatisfied event) {
    // Capture the condition from the event to ensure proper context propagation
    final Condition eventCondition = event.getCondition();
    
    if (shouldReevaluateFor(eventCondition)) {
      // Evaluate in the current thread context to ensure proper state management
      final boolean newState = reevaluate(conditions);
      setSatisfied(newState);
    }
  }

  @Override
  public CompositeConditionSupport setContext(final CapabilityContext context) {
    for (final Condition condition : conditions) {
      // Using pattern matching for instanceof check (Java 21 feature)
      if (condition instanceof CapabilityContextAware contextAware) {
        contextAware.setContext(context);
      }
    }
    return this;
  }

  @Override
  public String toString() {
    return "Re-evaluate " + CompositeConditionSupport.this;
  }

  /**
   * Whether or not the composite conditions are satisfied as a unit.
   *
   * @param conditions to be checked (there are at least 2 conditions passed in)
   * @return true, if conditions are satisfied as a unit
   */
  protected abstract boolean reevaluate(final Condition... conditions);

  protected Condition[] getConditions() {
    return conditions;
  }

  /**
   * Determines if the condition should trigger a reevaluation.
   * <p>
   * This method is optimized for Virtual Thread execution and avoids unnecessary synchronization.
   *
   * @param condition the condition to check
   * @return true if the condition should trigger a reevaluation
   */
  private boolean shouldReevaluateFor(final Condition condition) {
    for (final Condition watched : conditions) {
      if (watched == condition) {
        return true;
      }
    }
    return false;
  }
}
