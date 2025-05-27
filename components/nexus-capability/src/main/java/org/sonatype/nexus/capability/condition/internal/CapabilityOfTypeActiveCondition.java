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
import org.sonatype.nexus.capability.CapabilityDescriptorRegistry;
import org.sonatype.nexus.capability.CapabilityEvent;
import org.sonatype.nexus.capability.CapabilityReference;
import org.sonatype.nexus.capability.CapabilityRegistry;
import org.sonatype.nexus.capability.CapabilityType;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;

/**
 * A condition that is satisfied when a capability of a specified type exists and is in an active state.
 *
 * @since capabilities 2.0
 */
public class CapabilityOfTypeActiveCondition
    extends CapabilityOfTypeExistsCondition
{

  public CapabilityOfTypeActiveCondition(final EventManager eventManager,
                                         final CapabilityDescriptorRegistry descriptorRegistry,
                                         final CapabilityRegistry capabilityRegistry,
                                         final CapabilityType type)
  {
    super(eventManager, descriptorRegistry, capabilityRegistry, type);
  }

  @Override
  boolean isSatisfiedBy(final CapabilityReference reference) {
    if (!super.isSatisfiedBy(reference)) {
      return false;
    }
    
    // Use pattern matching for switch to check capability state
    CapabilityContext context = reference.context();
    return switch (context) {
      case CapabilityContext c when c.isActive() -> true;
      default -> false;
    };
  }

  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.AfterActivated event) {
    // Ensure thread safety when handling events from Virtual Threads
    long stamp = lock.readLock();
    try {
      if (!isSatisfied() && type.equals(event.getReference().context().type())) {
        checkAllCapabilities();
      }
    } finally {
      lock.unlockRead(stamp);
    }
  }

  @AllowConcurrentEvents
  @Subscribe
  public void handle(final CapabilityEvent.BeforePassivated event) {
    // Ensure thread safety when handling events from Virtual Threads
    long stamp = lock.readLock();
    try {
      if (isSatisfied() && type.equals(event.getReference().context().type())) {
        checkAllCapabilities();
      }
    } finally {
      lock.unlockRead(stamp);
    }
  }

  @Override
  public String toString() {
    return "Active " + type;
  }

  @Override
  public String explainSatisfied() {
    return typeName + " is active";
  }

  @Override
  public String explainUnsatisfied() {
    return typeName + " is not active";
  }

}