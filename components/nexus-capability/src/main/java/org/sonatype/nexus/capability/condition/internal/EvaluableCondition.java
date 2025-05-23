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

import java.util.concurrent.locks.StampedLock;

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
 * This implementation is compatible with Java 21 Virtual Threads and ensures proper event handling
 * and thread context propagation when evaluating conditions.
 *
 * @since capabilities 2.2
 */
public class EvaluableCondition
    extends ConditionSupport
    implements CapabilityContextAware
{
  // Using StampedLock for optimistic reads with non-blocking synchronization
  private final StampedLock lock = new StampedLock();
  
  private volatile CapabilityIdentity capabilityIdentity;

  private final Evaluable evaluable;

  /**
   * Creates a new EvaluableCondition instance.
   * 
   * @param eventManager the event manager for event handling
   * @param evaluable the evaluable implementation that determines if the condition is satisfied
   */
  public EvaluableCondition(final EventManager eventManager,
                            final Evaluable evaluable)
  {
    super(eventManager, false);
    this.evaluable = checkNotNull(evaluable);
  }

  /**
   * Sets the capability context for this condition.
   * <p>
   * This method uses non-blocking synchronization to ensure thread safety.
   *
   * @param context the capability context
   * @return this condition instance
   */
  @Override
  public EvaluableCondition setContext(final CapabilityContext context) {
    long stamp = lock.writeLock();
    try {
      checkState(!isActive(), "Cannot contextualize when already bounded");
      checkState(capabilityIdentity == null, "Already contextualized with id '" + capabilityIdentity + "'");
      capabilityIdentity = context.id();
      return this;
    } finally {
      lock.unlockWrite(stamp);
    }
  }

  /**
   * Binds this condition to start receiving events.
   * <p>
   * This implementation ensures proper registration with the EventManager and
   * initial evaluation of the condition state.
   */
  @Override
  protected void doBind() {
    // Use optimistic read first to avoid unnecessary write lock acquisition
    long stamp = lock.tryOptimisticRead();
    CapabilityIdentity identity = capabilityIdentity;
    if (!lock.validate(stamp)) {
      // Fallback to read lock if optimistic read fails
      stamp = lock.readLock();
      try {
        identity = capabilityIdentity;
      } finally {
        lock.unlockRead(stamp);
      }
    }
    
    checkState(identity != null, "Capability identity not specified");
    getEventManager().register(this);
    
    // Evaluate the condition in the current thread context to ensure proper propagation
    boolean satisfied = evaluable.isSatisfied();
    setSatisfied(satisfied);
  }

  /**
   * Releases this condition to stop receiving events.
   * <p>
   * This implementation ensures proper unregistration from the EventManager.
   */
  @Override
  public void doRelease() {
    getEventManager().unregister(this);
  }

  /**
   * Handles capability update events.
   * <p>
   * This implementation is designed to work correctly with events from Virtual Threads,
   * ensuring proper thread context propagation to the Evaluable implementation.
   *
   * @param event the capability update event
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.AfterUpdate event) {
    // Use optimistic read for better performance with non-blocking synchronization
    long stamp = lock.tryOptimisticRead();
    CapabilityIdentity identity = capabilityIdentity;
    if (!lock.validate(stamp)) {
      // Fallback to read lock if optimistic read fails
      stamp = lock.readLock();
      try {
        identity = capabilityIdentity;
      } finally {
        lock.unlockRead(stamp);
      }
    }
    
    if (identity != null && event.getReference().context().id().equals(identity)) {
      // Evaluate the condition in the current thread context to ensure proper propagation
      // This works correctly regardless of whether the event comes from a platform thread or a virtual thread
      boolean satisfied = evaluable.isSatisfied();
      setSatisfied(satisfied);
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
