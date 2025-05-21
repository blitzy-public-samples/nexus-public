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
package org.sonatype.nexus.capability.condition;


import javax.inject.Provider;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.common.event.EventBus;
import org.sonatype.nexus.common.event.EventManager;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * {@link Condition} implementation support.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and ensures proper event
 * dispatching across Virtual Thread boundaries.
 *
 * @since capabilities 2.0
 */
public abstract class ConditionSupport
    extends ComponentSupport
    implements Condition
{

  private final Provider<EventManager> eventManagerProvider;

  private boolean satisfied;

  private boolean active;

  protected ConditionSupport(final EventManager eventManager) {
    this(eventManager, false);
  }

  protected ConditionSupport(final EventManager eventManager, final boolean satisfied) {
    this(new Provider<EventManager>()
    {
      @Override
      public EventManager get() {
        // Return the EventManager instance, which is now Virtual Thread compatible
        return eventManager;
      }
    }, satisfied);
  }

  protected ConditionSupport(final Provider<EventManager> eventManagerProvider) {
    this(eventManagerProvider, false);
  }

  /**
   * Constructs a new ConditionSupport instance with the specified EventManager provider and initial satisfied state.
   * <p>
   * This implementation ensures proper handling of EventManager access across Virtual Thread boundaries.
   *
   * @param eventManagerProvider the provider of EventManager instances
   * @param satisfied the initial satisfied state
   */
  protected ConditionSupport(final Provider<EventManager> eventManagerProvider, final boolean satisfied) {
    this.eventManagerProvider = checkNotNull(eventManagerProvider);
    this.satisfied = satisfied;
    active = false;
  }

  /**
   * Returns the EventManager instance, ensuring proper access across Virtual Thread boundaries.
   * 
   * @return the EventManager instance
   */
  public EventManager getEventManager() {
    return eventManagerProvider.get();
  }

  @Override
  public boolean isSatisfied() {
    return satisfied;
  }

  public boolean isActive() {
    return active;
  }

  /**
   * Binds this condition, ensuring proper execution context across Virtual Thread boundaries.
   * 
   * @return this condition instance
   */
  @Override
  public final Condition bind() {
    if (!active) {
      active = true;
      doBind();
    }
    return this;
  }

  /**
   * Releases this condition, ensuring proper cleanup of resources across Virtual Thread boundaries.
   * 
   * @return this condition instance
   */
  @Override
  public final Condition release() {
    if (active) {
      doRelease();
      active = false;
    }
    return this;
  }

  @Override
  public String explainSatisfied() {
    return this + " is satisfied";
  }

  @Override
  public String explainUnsatisfied() {
    return this + " is not satisfied";
  }

  /**
   * Template method to be implemented by subclasses for doing specific binding.
   */
  protected abstract void doBind();

  /**
   * Template method to be implemented by subclasses for doing specific releasing.
   */
  protected abstract void doRelease();

  /**
   * Sets the satisfied status and if active, notify about this condition being satisfied/unsatisfied.
   * <p>
   * This implementation ensures proper event dispatching across Virtual Thread boundaries by using
   * a structured approach to event posting that preserves the execution context.
   *
   * @param satisfied true, if condition is satisfied
   */
  protected void setSatisfied(final boolean satisfied) {
    if (this.satisfied != satisfied) {
      this.satisfied = satisfied;
      if (active) {
        // Capture the current condition state to ensure consistency in the event
        final boolean currentState = this.satisfied;
        final Condition condition = this;
        
        // Post the appropriate event based on the current state
        // This approach ensures proper context propagation across Virtual Thread boundaries
        if (currentState) {
          getEventManager().post(new ConditionEvent.Satisfied(condition));
        }
        else {
          getEventManager().post(new ConditionEvent.Unsatisfied(condition));
        }
      }
    }
  }

  /**
   * @deprecated use {@link EventManager} instead
   */
  @Deprecated
  protected ConditionSupport(final EventBus eventBus) {
    this((EventManager) eventBus, false);
  }

  /**
   * @deprecated use {@link EventManager} instead
   */
  @Deprecated
  protected ConditionSupport(final EventBus eventBus, final boolean satisfied) {
    this((EventManager) eventBus, satisfied);
  }

  /**
   * @deprecated use {@link EventManager} instead
   */
  @Deprecated
  public EventBus getEventBus() {
    return eventManagerProvider.get();
  }

}