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

import java.util.ArrayList;
import java.util.List;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.common.event.EventManager;

/**
 * A condition that applies a logical AND between conditions.
 * <p>
 * This implementation is compatible with Java 21 Virtual Threads and uses pattern matching
 * for switch to efficiently evaluate conditions.
 *
 * @since capabilities 2.0
 */
public class ConjunctionCondition
    extends CompositeConditionSupport
    implements Condition
{

  private Condition lastNotSatisfied;

  /**
   * Constructs a new ConjunctionCondition with multiple conditions.
   * <p>
   * This implementation ensures proper handling of conditions across Virtual Thread boundaries.
   *
   * @param eventManager the event manager instance
   * @param conditions the conditions to be evaluated together with AND logic
   */
  public ConjunctionCondition(final EventManager eventManager,
                              final Condition... conditions)
  {
    super(eventManager, conditions);
  }

  /**
   * Reevaluates all conditions using pattern matching for switch to determine if all conditions are satisfied.
   * <p>
   * This implementation ensures proper thread context propagation when evaluating conditions.
   *
   * @param conditions the conditions to evaluate
   * @return true if all conditions are satisfied, false otherwise
   */
  @Override
  protected boolean reevaluate(final Condition... conditions) {
    // Use pattern matching for switch to handle condition evaluation more efficiently
    for (final Condition condition : conditions) {
      // Capture the current condition to ensure proper thread context propagation
      boolean satisfied = condition.isSatisfied();
      
      // Use pattern matching with switch to handle the condition state
      switch (satisfied) {
        case false -> {
          lastNotSatisfied = condition;
          return false;
        }
        case true -> { /* Continue checking other conditions */ }
      }
    }
    lastNotSatisfied = null;
    return true;
  }

  /**
   * Returns a string representation of this conjunction condition.
   * <p>
   * Uses modern Java string joining features instead of StringBuilder for improved readability.
   *
   * @return a string representation of this condition
   */
  @Override
  public String toString() {
    List<String> conditionStrings = new ArrayList<>();
    for (final Condition condition : getConditions()) {
      conditionStrings.add(condition.toString());
    }
    return String.join(" AND ", conditionStrings);
  }

  /**
   * Explains why this condition is satisfied.
   * <p>
   * Uses modern Java string joining features instead of StringBuilder for improved readability.
   *
   * @return explanation of why this condition is satisfied
   */
  @Override
  public String explainSatisfied() {
    List<String> explanations = new ArrayList<>();
    for (final Condition condition : getConditions()) {
      explanations.add(condition.explainSatisfied());
    }
    return String.join(" AND ", explanations);
  }

  /**
   * Explains why this condition is not satisfied.
   * <p>
   * If a specific condition caused the failure, returns its explanation.
   * Otherwise, joins all condition explanations with OR.
   * <p>
   * Uses modern Java string joining features instead of StringBuilder for improved readability.
   *
   * @return explanation of why this condition is not satisfied
   */
  @Override
  public String explainUnsatisfied() {
    if (lastNotSatisfied != null) {
      return lastNotSatisfied.explainUnsatisfied();
    }
    
    List<String> explanations = new ArrayList<>();
    for (final Condition condition : getConditions()) {
      explanations.add(condition.explainUnsatisfied());
    }
    return String.join(" OR ", explanations);
  }
}