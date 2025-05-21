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

import java.util.Arrays;

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
    // Using pattern matching for switch to evaluate conditions more efficiently
    return switch (conditions) {
      case Condition[] c when c.length == 0 -> false;
      case Condition[] c -> {
        for (Condition condition : c) {
          if (condition.isSatisfied()) {
            lastSatisfied = condition;
            yield true;
          }
        }
        lastSatisfied = null;
        yield false;
      }
    };
  }

  @Override
  public String toString() {
    return String.join(" OR ", Arrays.stream(getConditions())
        .map(Object::toString)
        .toArray(String[]::new));
  }

  @Override
  public String explainSatisfied() {
    if (lastSatisfied != null) {
      return lastSatisfied.explainSatisfied();
    }
    
    return String.join(" OR ", Arrays.stream(getConditions())
        .map(Condition::explainSatisfied)
        .toArray(String[]::new));
  }

  @Override
  public String explainUnsatisfied() {
    return String.join(" AND ", Arrays.stream(getConditions())
        .map(Condition::explainUnsatisfied)
        .toArray(String[]::new));
  }

}