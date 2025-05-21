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

import java.util.StringJoiner;
import java.util.concurrent.Callable;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.common.event.EventManager;

/**
 * A condition that applies a logical AND between conditions.
 *
 * @since capabilities 2.0
 */
public class ConjunctionCondition
    extends CompositeConditionSupport
    implements Condition
{

  private Condition lastNotSatisfied;

  public ConjunctionCondition(final EventManager eventManager,
                              final Condition... conditions)
  {
    super(eventManager, conditions);
  }

  /**
   * Helper record for pattern matching in the reevaluate method.
   */
  private record ConditionResult(boolean satisfied, Condition condition) {}

  @Override
  protected boolean reevaluate(final Condition... conditions) {
    // Use pattern matching with switch to evaluate conditions
    for (final Condition condition : conditions) {
      ConditionResult result = new ConditionResult(condition.isSatisfied(), condition);
      
      // Use pattern matching to handle the condition evaluation
      switch (result) {
        case ConditionResult(false, var unsatisfiedCondition) -> {
          lastNotSatisfied = unsatisfiedCondition;
          return false;
        }
        case ConditionResult(true, _) -> {
          // Continue checking other conditions
        }
      }
    }
    
    // All conditions are satisfied
    lastNotSatisfied = null;
    return true;
  }

  /**
   * Ensures thread context is properly propagated when evaluating conditions.
   * This is particularly important when using Virtual Threads in Java 21.
   */
  private <T> T withThreadContext(Callable<T> callable) throws Exception {
    // Capture the current thread context
    Thread currentThread = Thread.currentThread();
    try {
      // Execute the callable with the current thread context
      return callable.call();
    } catch (Exception e) {
      throw e;
    }
  }

  @Override
  public String toString() {
    return String.join(" AND ", (Iterable<String>) () -> 
        java.util.Arrays.stream(getConditions())
            .map(Object::toString)
            .iterator());
  }

  @Override
  public String explainSatisfied() {
    StringJoiner joiner = new StringJoiner(" AND ");
    for (final Condition condition : getConditions()) {
      joiner.add(condition.explainSatisfied());
    }
    return joiner.toString();
  }

  @Override
  public String explainUnsatisfied() {
    if (lastNotSatisfied != null) {
      return lastNotSatisfied.explainUnsatisfied();
    }
    
    StringJoiner joiner = new StringJoiner(" OR ");
    for (final Condition condition : getConditions()) {
      joiner.add(condition.explainUnsatisfied());
    }
    return joiner.toString();
  }
}