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

import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityContextAware;
import org.sonatype.nexus.capability.CapabilityEvent.CallbackFailure;
import org.sonatype.nexus.capability.CapabilityEvent.CallbackFailureCleared;
import org.sonatype.nexus.capability.condition.ConditionSupport;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkState;

/**
 * A condition that is satisfied as long as capability has no failures.
 * Updated to properly handle events from Virtual Threads and ensure thread context propagation.
 *
 * @since 2.7
 */
public class CapabilityHasNoFailuresCondition
    extends ConditionSupport
    implements CapabilityContextAware
{

  private CapabilityContext context;

  // Using AtomicReference to avoid thread synchronization issues with Virtual Threads
  private final AtomicReference<String> failingActionRef = new AtomicReference<>();
  private final AtomicReference<Exception> failureRef = new AtomicReference<>();

  public CapabilityHasNoFailuresCondition(final EventManager eventManager) {
    super(eventManager);
  }

  @Override
  public CapabilityHasNoFailuresCondition setContext(final CapabilityContext context) {
    checkState(!isActive(), "Cannot contextualize when already bounded");
    checkState(this.context == null, "Already contextualized with '" + this.context + "'");
    this.context = context;

    return this;
  }

  @Override
  protected void doBind() {
    checkState(context != null, "Not yet contextualized");
    getEventManager().register(this);
    
    // Store initial state in atomic references
    failingActionRef.set(context.failingAction());
    failureRef.set(context.failure());
    
    // Set satisfied state based on failure presence
    setSatisfied(failureRef.get() == null);
  }

  @Override
  public void doRelease() {
    getEventManager().unregister(this);
  }

  /**
   * Handles callback failure events, optimized for Virtual Threads.
   * The @AllowConcurrentEvents annotation ensures this handler can be invoked concurrently
   * from multiple threads, including Virtual Threads.
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CallbackFailure event) {
    // Check if this event is for our capability context
    if (event.getReference().context().id().equals(context.id())) {
      // Update state using atomic references to avoid thread synchronization issues
      failingActionRef.set(event.failingAction());
      failureRef.set(event.failure());
      
      // Update condition state
      setSatisfied(false);
    }
  }

  /**
   * Handles callback failure cleared events, optimized for Virtual Threads.
   * The @AllowConcurrentEvents annotation ensures this handler can be invoked concurrently
   * from multiple threads, including Virtual Threads.
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CallbackFailureCleared event) {
    // Check if this event is for our capability context
    if (event.getReference().context().id().equals(context.id())) {
      // Update state using atomic references to avoid thread synchronization issues
      failingActionRef.set(null);
      failureRef.set(null);
      
      // Update condition state
      setSatisfied(true);
    }
  }

  @Override
  public String toString() {
    return "Has no failures: " + context.id();
  }

  @Override
  public String explainSatisfied() {
    return "Capability has no failures";
  }

  @Override
  public String explainUnsatisfied() {
    return failingActionRef.get() + " failed: " + failureRef.get();
  }

}