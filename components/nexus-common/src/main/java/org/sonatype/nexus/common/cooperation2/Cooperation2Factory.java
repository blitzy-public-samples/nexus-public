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
package org.sonatype.nexus.common.cooperation2;

import java.time.Duration;

/**
 * Supplies {@link Cooperation2} points allowing different threads to cooperate on computationally intensive tasks.
 * 
 * With Java 21 support, Cooperation2 can leverage Virtual Threads for improved performance and scalability,
 * particularly for I/O-bound operations. Virtual Threads are lightweight threads managed by the JVM that enable
 * efficient handling of blocking operations without consuming excessive resources.
 *
 * @since 3.41
 */
public interface Cooperation2Factory
{
  /**
   * Start configuring a new {@link Cooperation2} point.
   */
  Builder configure();

  /**
   * Fluent builder for configuring {@link Cooperation2} points.
   */
  interface Builder
  {
    /**
     * @param majorTimeout when waiting for the main I/O request
     */
    Builder majorTimeout(Duration majorTimeout);

    /**
     * @param minorTimeout when waiting for any I/O dependencies
     */
    Builder minorTimeout(Duration minorTimeout);

    /**
     * @param threadsPerKey limits the threads waiting under each key
     */
    Builder threadsPerKey(int threadsPerKey);

    /**
     * @param enabled indicates whether the resulting co-operation should enable concurrency controls.
     */
    Builder enabled(boolean enabled);

    /**
     * @param useVirtualThreads indicates whether operations should use Virtual Threads (Java 21+)
     * when executing tasks. Virtual Threads are particularly beneficial for I/O-bound operations
     * as they allow for efficient handling of blocking operations without consuming excessive resources.
     * 
     * When enabled, operations will be executed on Virtual Threads, which are lightweight threads
     * managed by the JVM rather than the OS. This can significantly improve scalability for
     * applications with many concurrent operations.
     * 
     * @since 3.60
     */
    Builder useVirtualThreads(boolean useVirtualThreads);

    /**
     * @param virtualThreadTimeout specific timeout for Virtual Thread operations
     * This allows for different timeout handling when using Virtual Threads compared to platform threads.
     * 
     * @since 3.60
     */
    Builder virtualThreadTimeout(Duration virtualThreadTimeout);

    /**
     * @param maxVirtualThreads limits the maximum number of Virtual Threads that can be created
     * for this cooperation point. This provides a safeguard against creating too many Virtual Threads,
     * which although lightweight, still consume some resources.
     * 
     * A value of 0 or negative indicates no limit.
     * 
     * @since 3.60
     */
    Builder maxVirtualThreads(int maxVirtualThreads);

    /**
     * @param prioritizeVirtualThreadsForIO when true, automatically routes I/O-bound operations to Virtual Threads
     * while keeping CPU-intensive operations on platform threads. This optimizes resource usage by leveraging
     * Virtual Threads where they provide the most benefit.
     * 
     * @since 3.60
     */
    Builder prioritizeVirtualThreadsForIO(boolean prioritizeVirtualThreadsForIO);

    /**
     * Builds a new {@link Cooperation2} point with this configuration.
     *
     * @param id unique identifier for this cooperation point
     */
    Cooperation2 build(String id);

    /**
     * Builds a new {@link Cooperation2} point with this configuration.
     *
     * @param id unique identifier for this cooperation point
     */
    Cooperation2 build(Class<?> id, String... keys);
  }
}