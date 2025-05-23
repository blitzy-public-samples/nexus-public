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

/**
 * Handles capability activation by reacting capability activation condition being satisfied/unsatisfied.
 * Uses Java 21 Virtual Threads for improved responsiveness and Pattern Matching for more efficient event handling.
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

  @Inject
  ActivationConditionHandler(final EventManager eventManager,
                             final Conditions conditions,
                             @Assisted final DefaultCapabilityReference reference)
  {
    this.eventManager = checkNotNull(eventManager);
    this.conditions = checkNotNull(conditions);
    this.reference = checkNotNull(reference);
  }

  boolean isConditionSatisfied() {
    return activationCondition != null && activationCondition.isSatisfied();
  }

  /**
   * Handles condition events using Pattern Matching for switch to efficiently process different event types.
   * Uses Virtual Threads for improved responsiveness when processing events.
   */
  @AllowConcurrentEvents
  @Subscribe
  public void handle(final ConditionEvent event) {
    // Only process events related to our activation condition
    if (event.getCondition() == activationCondition) {
      // Use Pattern Matching for switch to handle different event types
      Thread.startVirtualThread(() -> {
        switch (event) {
          case ConditionEvent.Satisfied s -> reference.activate();
          case ConditionEvent.Unsatisfied u -> reference.passivate();
          default -> log.debug(STR."Unhandled condition event type: \{event.getClass().getSimpleName()}");
        }
      });
    }
  }
  
  /**
   * Binds this handler to the activation condition of the capability it references.
   * Uses Virtual Threads for parallel condition activation when possible.
   *
   * @return this handler instance for method chaining
   */
  ActivationConditionHandler bind() {
    if (activationCondition == null) {
      Thread.startVirtualThread(() -> {
        try {
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
        }
        catch (Exception e) {
          activationCondition = conditions.never("Failed to determine activation condition");
          log.error(STR."Could not get activation condition from capability \{reference.capability()} (\{reference.context().id()}). Considering it as non activatable", e);
        }
        activationCondition.bind();
        eventManager.register(ActivationConditionHandler.this);
      }).join(); // Wait for the virtual thread to complete
    }
    return this;
  }

  /**
   * Releases this handler from the activation condition of the capability it references.
   * Uses Virtual Threads for parallel condition deactivation when possible.
   *
   * @return this handler instance for method chaining
   */
  ActivationConditionHandler release() {
    if (activationCondition != null) {
      Thread.startVirtualThread(() -> {
        eventManager.unregister(this);
        activationCondition.release();
        activationCondition = null;
      }).join(); // Wait for the virtual thread to complete
    }
    return this;
  }

  @Override
  public String toString() {
    // Using Java 21 String Templates for improved readability and performance
    return STR."Watching '\{activationCondition}' condition to activate/passivate capability '\{reference.capability()} (id=\{reference.context().id()})'";
  }

  public String explainWhyNotSatisfied() {
    return isConditionSatisfied() ? null : activationCondition.explainUnsatisfied();
  }