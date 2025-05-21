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
package org.sonatype.nexus.internal.capability;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

import javax.inject.Inject;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.capability.CapabilityContextAware;
import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.ConditionEvent;
import org.sonatype.nexus.capability.condition.Conditions;
import org.sonatype.nexus.common.event.EventManager;

import com.google.common.eventbus.AllowConcurrentEvents;
import com.google.common.eventbus.Subscribe;
import com.google.inject.assistedinject.Assisted;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.StringTemplate.STR;

/**
 * Handles capability activation by reacting capability activation condition being satisfied/unsatisfied.
 * Uses Java 21 features including Virtual Threads, String Templates, and Pattern Matching for switch.
 *
 * @since capabilities 2.0
 */
public class ActivationConditionHandler
    extends ComponentSupport
{

  private final EventManager eventManager;

  private final DefaultCapabilityReference reference;

  private final Conditions conditions;

  private Condition activationCondition;
  
  // Executor service using Virtual Threads for handling condition events
  private final ExecutorService virtualThreadExecutor;

  @Inject
  ActivationConditionHandler(final EventManager eventManager,
                             final Conditions conditions,
                             @Assisted final DefaultCapabilityReference reference)
  {
    this.eventManager = checkNotNull(eventManager);
    this.conditions = checkNotNull(conditions);
    this.reference = checkNotNull(reference);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  boolean isConditionSatisfied() {
    return activationCondition != null && activationCondition.isSatisfied();
  }

  @AllowConcurrentEvents
  @Subscribe
  public void handle(final ConditionEvent event) {
    // Using Pattern Matching for switch to handle different ConditionEvent types
    if (event.getCondition() == activationCondition) {
      virtualThreadExecutor.submit(() -> {
        switch (event) {
          case ConditionEvent.Satisfied s -> reference.activate();
          case ConditionEvent.Unsatisfied u -> reference.passivate();
          default -> log.debug(STR."Unhandled condition event type: \{event.getClass().getSimpleName()}");
        }
      });
    }
  }

  ActivationConditionHandler bind() {
    if (activationCondition == null) {
      try {
        // Using Virtual Thread to perform the binding operation
        Thread.startVirtualThread(() -> {
          Condition capabilityActivationCondition = reference.capability().activationCondition();
          if (capabilityActivationCondition == null) {
            capabilityActivationCondition = conditions.always("Capability has no activation condition");
          }
          activationCondition = conditions.logical().and(
              capabilityActivationCondition,
              conditions.nexus().active(),
              conditions.capabilities().capabilityHasNoFailures(),
              conditions.capabilities().capabilityHasNoDuplicates()
          );
          if (activationCondition instanceof CapabilityContextAware) {
            ((CapabilityContextAware) activationCondition).setContext(reference.context());
          }
          activationCondition.bind();
        }).join(); // Wait for the virtual thread to complete
        
        eventManager.register(this);
      }
      catch (Exception e) {
        activationCondition = conditions.never("Failed to determine activation condition");
        // Using String Templates for improved logging
        log.error(
            STR."Could not get activation condition from capability \{reference.capability()} (\{reference.context().id()}). Considering it as non activatable",
            e
        );
      }
    }
    return this;
  }

  ActivationConditionHandler release() {
    if (activationCondition != null) {
      eventManager.unregister(this);
      activationCondition.release();
      activationCondition = null;
      virtualThreadExecutor.close(); // Close the executor service
    }
    return this;
  }

  @Override
  public String toString() {
    // Using String Templates instead of String.format
    return STR."Watching '\{activationCondition}' condition to activate/passivate capability '\{reference.capability()} (id=\{reference.context().id()})'";
  }

  public String explainWhyNotSatisfied() {
    return isConditionSatisfied() ? null : activationCondition.explainUnsatisfied();
  }

}