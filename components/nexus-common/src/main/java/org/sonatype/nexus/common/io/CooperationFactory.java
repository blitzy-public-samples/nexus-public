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
import java.util.concurrent.Executors;

import org.sonatype.goodies.common.Time;

/**
 * Supplies {@link Cooperation} points. Not intended for use with SQL/Datastore.
 * <p>
 * With Java 21, this factory supports configuration of Virtual Threads for high-throughput
 * I/O operations. Virtual Threads provide significant performance benefits for I/O-bound
 * operations by allowing thousands of concurrent operations with minimal resource overhead.
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
   * <p>
   * With Java 21, this builder supports configuration of Virtual Threads for high-throughput
   * I/O operations. Virtual Threads provide significant performance benefits for I/O-bound
   * operations by allowing thousands of concurrent operations with minimal resource overhead.
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
     * Enables or disables the use of Virtual Threads for I/O operations.
     * <p>
     * When enabled, I/O operations will be executed using Java 21 Virtual Threads,
     * which provide significant performance benefits for I/O-bound operations by
     * allowing thousands of concurrent operations with minimal resource overhead.
     * <p>
     * This setting has no effect when running on Java versions prior to Java 21.
     *
     * @param enabled true to enable Virtual Threads, false to use platform threads
     * @return this builder for method chaining
     * @since 3.60
     */
    default Builder virtualThreads(boolean enabled) {
      // Default implementation for backward compatibility
      return this;
    }
    
    /**
     * Configures the maximum number of carrier threads to use for Virtual Threads.
     * <p>
     * Virtual Threads are scheduled on a pool of carrier threads. This setting controls
     * the maximum size of that pool. By default, the pool size is set to the number of
     * available processors.
     * <p>
     * This setting has no effect when running on Java versions prior to Java 21 or when
     * Virtual Threads are disabled.
     *
     * @param maxCarrierThreads the maximum number of carrier threads to use
     * @return this builder for method chaining
     * @since 3.60
     */
    default Builder maxCarrierThreads(int maxCarrierThreads) {
      // Default implementation for backward compatibility
      return this;
    }
    
    /**
     * Configures the pinning behavior for Virtual Threads.
     * <p>
     * When a Virtual Thread executes a synchronized block or method, it becomes "pinned"
     * to its carrier thread, which can reduce concurrency. This setting controls whether
     * to allow pinning (default) or to throw an exception when pinning would occur.
     * <p>
     * This setting has no effect when running on Java versions prior to Java 21 or when
     * Virtual Threads are disabled.
     *
     * @param allowPinning true to allow pinning, false to throw an exception when pinning would occur
     * @return this builder for method chaining
     * @since 3.60
     */
    default Builder allowThreadPinning(boolean allowPinning) {
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