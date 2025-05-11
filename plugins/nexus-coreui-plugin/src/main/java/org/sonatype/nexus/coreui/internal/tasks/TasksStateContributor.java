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
package org.sonatype.nexus.coreui.internal.tasks;

import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.rapture.StateContributor;

import com.google.common.collect.ImmutableMap;

/**
 * Contributes task-related state to the UI.
 * 
 * <p>Java 21 compatible implementation that maintains the use of Guava's ImmutableMap.
 * Could alternatively use Java's built-in Map.of() for immutable maps, but keeping
 * Guava for consistency with the rest of the codebase.</p>
 */
@Named
@Singleton
public class TasksStateContributor
    extends ComponentSupport
    implements StateContributor
{
  private final Map<String, Object> state;

  @Inject
  public TasksStateContributor(@Named("${nexus.react.tasks:-false}") final Boolean featureFlag) {
    // Using Guava's ImmutableMap for consistency with the rest of the codebase
    // Java 21 alternative: state = Map.of("nexus.react.tasks", featureFlag);
    state = ImmutableMap.of("nexus.react.tasks", featureFlag);
  }

  @Override
  public Map<String, Object> getState() {
    return state;
  }
}