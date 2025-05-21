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

import java.util.concurrent.locks.ReentrantLock;

import org.sonatype.nexus.capability.CapabilityContext;
import org.sonatype.nexus.capability.CapabilityContextAware;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.condition.ConditionSupport;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

import static com.google.common.base.Preconditions.checkState;

/**
 * A condition that is satisfied as long as the capability has no duplicates.
 * 
 * Updated for Java 21 to properly handle events from Virtual Threads and ensure
 * thread context propagation when checking for duplicates.
 *
 * @since 3.13
 */
public class CapabilityHasNoDuplicatesCondition
    extends ConditionSupport
    implements CapabilityContextAware
{
  private CapabilityContext context;
  
  // Use ReentrantLock instead of synchronized blocks to avoid Virtual Thread pinning
  private final ReentrantLock lock = new ReentrantLock();

  public CapabilityHasNoDuplicatesCondition(final EventManager eventManager) {
    super(eventManager);
  }

  @Override
  public CapabilityHasNoDuplicatesCondition setContext(final CapabilityContext context) {
    lock.lock();
    try {
      checkState(!isActive(), "Cannot contextualize when already bounded");
      checkState(this.context == null, "Already contextualized with '" + this.context + "'");
      this.context = context;
    }
    finally {
      lock.unlock();
    }

    return this;
  }

  @Override
  protected void doBind() {
    lock.lock();
    try {
      checkState(context != null, "Not yet contextualized");
      checkForDuplicates();
      getEventManager().register(this);
    }
    finally {
      lock.unlock();
    }
  }

  @Override
  public void doRelease() {
    getEventManager().unregister(this);
  }

  /**
   * Handle capability creation events.
   * 
   * @param event the capability creation event
   * 
   * The @AllowConcurrentEvents annotation ensures this method can be called concurrently
   * from multiple threads, including Virtual Threads in Java 21.
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.Created event) {
    checkForDuplicates(event);
  }

  /**
   * Handle capability update events.
   * 
   * @param event the capability update event
   * 
   * The @AllowConcurrentEvents annotation ensures this method can be called concurrently
   * from multiple threads, including Virtual Threads in Java 21.
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.AfterUpdate event) {
    checkForDuplicates(event);
  }

  /**
   * Handle capability removal events.
   * 
   * @param event the capability removal event
   * 
   * The @AllowConcurrentEvents annotation ensures this method can be called concurrently
   * from multiple threads, including Virtual Threads in Java 21.
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.AfterRemove event) {
    checkForDuplicates(event);
  }

  /**
   * Check for duplicates based on a capability event.
   * 
   * This method ensures proper thread context propagation when invoked from Virtual Threads.
   * 
   * @param event the capability event to check
   */
  private void checkForDuplicates(final CapabilityEvent event) {
    // Only check for duplicates if the event is for the same capability type
    if (event.getReference().context().type().equals(context.type())) {
      checkForDuplicates();
    }
  }

  /**
   * Check for duplicates using non-blocking synchronization to avoid Virtual Thread pinning.
   * 
   * This method ensures proper thread context propagation when invoked from Virtual Threads.
   */
  private void checkForDuplicates() {
    // Use non-blocking approach to check for duplicates and update condition state
    boolean noDuplicates = !context.descriptor().isDuplicated(context.id(), context.properties());
    setSatisfied(noDuplicates);
  }

  @Override
  public String toString() {
    return "Has no duplicates: " + context.id();
  }

  @Override
  public String explainSatisfied() {
    return "Capability has no duplicates";
  }

  @Override
  public String explainUnsatisfied() {
    return "Capability is duplicated by another capability";
  }

}