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
package org.sonatype.nexus.common.app;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @since 3.0
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ManagedLifecycle
{
  /**
   * Lifecycle phases in order of execution.
   * <p>
   * Provides sequenced collection-like behavior for working with phases in order.
   * </p>
   */
  enum Phase
  {
    OFF, KERNEL, STORAGE, RESTORE, UPGRADE, SCHEMAS, EVENTS, SECURITY, SERVICES, REPOSITORIES, CAPABILITIES, TASKS;
    
    // Cache the values to avoid creating a new array for each call to values()
    private static final Phase[] PHASES = values();
    
    // Unmodifiable list of phases in declaration order
    private static final List<Phase> PHASES_LIST = Collections.unmodifiableList(Arrays.asList(PHASES));
    
    // Unmodifiable list of phases in reverse declaration order
    private static final List<Phase> REVERSED_PHASES_LIST = Collections.unmodifiableList(Arrays.asList(PHASES)).reversed();
    
    /**
     * Returns all phases in declaration order.
     *
     * @return unmodifiable list of phases in declaration order
     */
    public static List<Phase> sequencedValues() {
      return PHASES_LIST;
    }
    
    /**
     * Returns all phases in reverse declaration order.
     *
     * @return unmodifiable list of phases in reverse declaration order
     */
    public static List<Phase> reversedValues() {
      return REVERSED_PHASES_LIST;
    }
    
    /**
     * Returns the next phase in declaration order, or null if this is the last phase.
     *
     * @return the next phase or null
     */
    public Phase next() {
      int ordinal = this.ordinal();
      return ordinal < PHASES.length - 1 ? PHASES[ordinal + 1] : null;
    }
    
    /**
     * Returns the previous phase in declaration order, or null if this is the first phase.
     *
     * @return the previous phase or null
     */
    public Phase previous() {
      int ordinal = this.ordinal();
      return ordinal > 0 ? PHASES[ordinal - 1] : null;
    }
  }

  Phase phase();
}