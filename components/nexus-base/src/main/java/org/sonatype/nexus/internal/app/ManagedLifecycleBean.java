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
package org.sonatype.nexus.internal.app;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.common.app.ManagedLifecycle.Phase;
import org.sonatype.nexus.common.app.ManagedLifecycleManager;
import org.sonatype.nexus.jmx.reflect.ManagedAttribute;
import org.sonatype.nexus.jmx.reflect.ManagedObject;
import org.sonatype.nexus.jmx.reflect.ManagedOperation;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * JMX controller to manage the Nexus application lifecycle.
 * <p>
 * Updated for Java 21 compatibility with enhanced error handling and String Templates for logging.
 *
 * @since 3.16
 */
@Named
@Singleton
@ManagedObject
public class ManagedLifecycleBean
    extends ComponentSupport
{
  private final ManagedLifecycleManager lifecycleManager;

  @Inject
  public ManagedLifecycleBean(final ManagedLifecycleManager lifecycleManager) {
    this.lifecycleManager = checkNotNull(lifecycleManager);
  }

  /**
   * Get the current lifecycle phase.
   * 
   * @return the current phase name
   */
  @ManagedAttribute
  public String getPhase() {
    return lifecycleManager.getCurrentPhase().name();
  }

  /**
   * Set the lifecycle phase.
   * 
   * @param phase the target phase name
   * @throws RuntimeException if there is a problem moving to the specified phase
   */
  @ManagedAttribute
  public void setPhase(final String phase) {
    try {
      Phase targetPhase = Phase.valueOf(phase);
      lifecycleManager.to(targetPhase);
    }
    catch (IllegalArgumentException e) {
      log.warn(STR."Invalid phase name: \{phase}", e);
      throw new RuntimeException(STR."Invalid phase name: \{phase}", e);
    }
    catch (Exception e) {
      log.warn(STR."Problem moving to phase \{phase}", e);
      throw new RuntimeException(STR."Problem moving to phase \{phase}: \{e.getMessage()}", e);
    }
  }

  /**
   * Bounce (restart) the specified lifecycle phase.
   * 
   * @param phase the phase to bounce
   * @throws RuntimeException if there is a problem bouncing the specified phase
   */
  @ManagedOperation
  public void bounce(final String phase) {
    try {
      Phase targetPhase = Phase.valueOf(phase);
      lifecycleManager.bounce(targetPhase);
    }
    catch (IllegalArgumentException e) {
      log.warn(STR."Invalid phase name: \{phase}", e);
      throw new RuntimeException(STR."Invalid phase name: \{phase}", e);
    }
    catch (Exception e) {
      log.warn(STR."Problem bouncing phase \{phase}", e);
      throw new RuntimeException(STR."Problem bouncing phase \{phase}: \{e.getMessage()}", e);
    }
  }
}