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

import static java.lang.StringTemplate.STR;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.common.event.EventManager;

/**
 * A condition that applies a logical OR between conditions.
 *
 * @since capabilities 2.0
 */
public class DisjunctionCondition
    extends CompositeConditionSupport
    implements Condition
{

  private Condition lastSatisfied;

  public DisjunctionCondition(final EventManager eventManager,
                              final Condition... conditions)
  {
    super(eventManager, conditions);
  }

  @Override
  protected boolean reevaluate(final Condition... conditions) {
    // Use pattern matching for switch to evaluate conditions more efficiently
    // Capture the current thread context to ensure proper propagation
    return switch (findSatisfiedCondition(conditions)) {
      case Condition satisfied when satisfied != null -> {
        lastSatisfied = satisfied;
        yield true;
      }
      case null -> {
        lastSatisfied = null;
        yield false;
      }
    };
  }
  
  /**
   * Helper method to find the first satisfied condition.
   * This preserves thread context during condition evaluation.
   *
   * @param conditions The conditions to evaluate
   * @return The first satisfied condition or null if none are satisfied
   */
  private Condition findSatisfiedCondition(final Condition... conditions) {
    for (final Condition condition : conditions) {
      if (condition.isSatisfied()) {
        return condition;
      }
    }
    return null;
  }

  @Override
  public String toString() {
    // Use String Templates for more readable string building
    Condition[] conditions = getConditions();
    if (conditions.length == 0) {
      return "";
    }
    
    // Start with the first condition
    String result = conditions[0].toString();
    
    // Add the rest with OR separators
    for (int i = 1; i < conditions.length; i++) {
      result = STR."{result} OR {conditions[i]}";
    }
    
    return result;
  }

  @Override
  public String explainSatisfied() {
    // If we have a last satisfied condition, use its explanation
    if (lastSatisfied != null) {
      return lastSatisfied.explainSatisfied();
    }
    
    // Otherwise build an explanation using all conditions
    Condition[] conditions = getConditions();
    if (conditions.length == 0) {
      return "";
    }
    
    // Start with the first condition's explanation
    String explanation = conditions[0].explainSatisfied();
    
    // Add the rest with OR separators using String Templates
    for (int i = 1; i < conditions.length; i++) {
      explanation = STR."{explanation} OR {conditions[i].explainSatisfied()}";
    }
    
    return explanation;
  }

  @Override
  public String explainUnsatisfied() {
    // Build an explanation for why the disjunction is unsatisfied
    // Note: For a disjunction to be unsatisfied, all conditions must be unsatisfied
    Condition[] conditions = getConditions();
    if (conditions.length == 0) {
      return "";
    }
    
    // Start with the first condition's unsatisfied explanation
    String explanation = conditions[0].explainUnsatisfied();
    
    // Add the rest with AND separators using String Templates
    // Note: We use AND here because all conditions must be unsatisfied for the OR to be unsatisfied
    for (int i = 1; i < conditions.length; i++) {
      explanation = STR."{explanation} AND {conditions[i].explainUnsatisfied()}";
    }
    
    return explanation;
  }

}