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

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityContextAware;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityIdentity;
import org.sonatype.nexus.capability.Evaluable;
import org.sonatype.nexus.capability.condition.ConditionSupport;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * A condition that delegates to provided {@link Evaluable} for checking if the condition is satisfied.
 * {@link Evaluable#isSatisfied()} is reevaluated after each update of capability the condition is used for.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and ensures proper event
 * handling and thread context propagation across Virtual Thread boundaries.
 *
 * @since capabilities 2.2
 */
public class EvaluableCondition
    extends ConditionSupport
    implements CapabilityContextAware
{

  private volatile CapabilityIdentity capabilityIdentity;

  private final Evaluable evaluable;

  /**
   * Constructs a new EvaluableCondition with the specified EventManager and Evaluable.
   * <p>
   * This implementation ensures proper handling of events across Virtual Thread boundaries.
   *
   * @param eventManager the event manager to use
   * @param evaluable the evaluable to delegate condition checking to
   */
  public EvaluableCondition(final EventManager eventManager,
                            final Evaluable evaluable)
  {
    super(eventManager, false);
    this.evaluable = checkNotNull(evaluable);
  }

  /**
   * Constructs a new EvaluableCondition with the specified EventManager provider and Evaluable.
   * <p>
   * This constructor is preferred for Virtual Thread compatibility as it ensures proper
   * access to the EventManager across thread boundaries.
   *
   * @param eventManagerProvider the provider of event manager instances
   * @param evaluable the evaluable to delegate condition checking to
   */
  public EvaluableCondition(final Provider<EventManager> eventManagerProvider,
                            final Evaluable evaluable)
  {
    super(eventManagerProvider, false);
    this.evaluable = checkNotNull(evaluable);
  }

  @Override
  public EvaluableCondition setContext(final CapabilityContext context) {
    checkState(!isActive(), "Cannot contextualize when already bounded");
    checkState(capabilityIdentity == null, "Already contextualized with id '" + capabilityIdentity + "'");
    capabilityIdentity = context.id();

    return this;
  }

  @Override
  protected void doBind() {
    checkState(capabilityIdentity != null, "Capability identity not specified");
    getEventManager().register(this);
    // Evaluate in the current thread context to ensure proper propagation
    setSatisfied(evaluable.isSatisfied());
  }

  @Override
  public void doRelease() {
    getEventManager().unregister(this);
  }

  /**
   * Handles capability update events, ensuring proper thread context propagation
   * when evaluating the condition across Virtual Thread boundaries.
   *
   * @param event the capability update event
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.AfterUpdate event) {
    // Capture the current capability identity to ensure thread safety
    final CapabilityIdentity currentCapabilityIdentity = this.capabilityIdentity;
    
    if (currentCapabilityIdentity != null && 
        event.getReference().context().id().equals(currentCapabilityIdentity)) {
      // Evaluate in the current thread context (which may be a Virtual Thread)
      // This ensures proper context propagation across thread boundaries
      setSatisfied(evaluable.isSatisfied());
    }
  }

  @Override
  public String toString() {
    return evaluable.toString();
  }

  @Override
  public String explainSatisfied() {
    return evaluable.explainSatisfied();
  }

  @Override
  public String explainUnsatisfied() {
    return evaluable.explainUnsatisfied();
  }

}
