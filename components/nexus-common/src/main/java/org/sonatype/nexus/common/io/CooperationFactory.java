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
package org.sonatype.nexus.common.io;

import java.time.Duration;

import org.sonatype.goodies.common.Time;

/**
 * Supplies {@link Cooperation} points. Not intended for use with SQL/Datastore.
 *
 * @since 3.14
 */
public interface CooperationFactory
{
  /**
   * Start configuring a new {@link Cooperation} point.
   */
  Builder configure();

  /**
   * Fluent builder for configuring {@link Cooperation} points.
   */
  interface Builder
  {
    /**
     * @param majorTimeout when waiting for the main I/O request
     */
    Builder majorTimeout(Duration majorTimeout);

    /**
     * @param majorTimeout when waiting for the main I/O request
     * @deprecated use the API utilizing java.time.Duration
     */
    @Deprecated
    default Builder majorTimeout(final Time majorTimeout) {
      return majorTimeout(Duration.ofSeconds(majorTimeout.toSeconds()));
    }

    /**
     * @param minorTimeout when waiting for any I/O dependencies
     */
    Builder minorTimeout(Duration minorTimeout);

    /**
     * @param minorTimeout when waiting for any I/O dependencies
     * @deprecated use the API utilizing java.time.Duration
     */
    @Deprecated
    default Builder minorTimeout(final Time minorTimeout) {
      return majorTimeout(Duration.ofSeconds(minorTimeout.toSeconds()));
    }

    /**
     * @param threadsPerKey limits the threads waiting under each key
     */
    Builder threadsPerKey(int threadsPerKey);
    
    /**
     * Configures whether to use Virtual Threads (Java 21+) for this cooperation point.
     * When enabled, the cooperation mechanism will leverage Virtual Threads for improved
     * performance during I/O-bound operations.
     *
     * @param useVirtualThreads true to use Virtual Threads, false to use platform threads
     * @return this builder for fluent method chaining
     * @since 3.60
     */
    default Builder useVirtualThreads(boolean useVirtualThreads) {
      // Default implementation for backward compatibility
      return this;
    }
    
    /**
     * Configures the maximum number of Virtual Threads allowed per cooperation key.
     * Only applicable when {@link #useVirtualThreads(boolean)} is set to true.
     * 
     * @param virtualThreadsPerKey maximum number of Virtual Threads per key
     * @return this builder for fluent method chaining
     * @since 3.60
     */
    default Builder virtualThreadsPerKey(int virtualThreadsPerKey) {
      // Default implementation for backward compatibility
      return this;
    }

    /**
     * Builds a new {@link Cooperation} point with this configuration.
     *
     * @param id unique identifier for this cooperation point
     */
    Cooperation build(String id);
  }
}