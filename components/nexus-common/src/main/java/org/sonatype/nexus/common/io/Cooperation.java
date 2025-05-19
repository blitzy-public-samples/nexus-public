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

import java.io.IOException;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * Manages cooperation between multiple threads to reduce duplicated I/O requests.
 *
 * Only one thread is allowed to proceed for a given I/O request key; other threads will wait for it to complete
 * and share the result. If an exception occurs on the lead thread any waiting threads share the same exception.
 *
 * If the original thread takes too long then one of the waiting threads may be woken up to repeat the request.
 * Further threads may be woken up if that thread takes too long, with each wakeup staggered by the same timeout.
 *
 * <h2>Virtual Thread Support</h2>
 * <p>
 * This interface is fully compatible with Java 21 Virtual Threads. When used with Virtual Threads, cooperation
 * provides significant benefits for I/O-bound operations by allowing thousands of concurrent operations with minimal
 * resource consumption. The implementation automatically detects Virtual Threads and optimizes cooperation behavior
 * accordingly.
 * </p>
 * 
 * <p>Best practices for using Cooperation with Virtual Threads:</p>
 * <ul>
 *   <li>Use Virtual Threads for I/O-bound operations that may block, such as remote repository access, database
 *       operations, or file system access</li>
 *   <li>Avoid using Virtual Threads for CPU-intensive operations as they don't benefit from the same advantages</li>
 *   <li>Be aware that Virtual Threads can be "pinned" to carrier threads in certain situations (like synchronized blocks),
 *       which reduces their efficiency</li>
 *   <li>When using Cooperation with Virtual Threads, the implementation will optimize thread management to prevent
 *       unnecessary blocking of carrier threads</li>
 * </ul>
 *
 * @since 3.14
 */
public interface Cooperation
{
  @FunctionalInterface
  interface IOCheck<T>
  {
    @Nullable
    T check() throws IOException;
  }

  @FunctionalInterface
  interface IOCall<T>
  {
    /**
     * @param failover {@code true} if this is a 'fail over' thread that
     *          might want to check caches before repeating the request
     */
    T call(boolean failover) throws IOException;
  }

  /**
   * Requests cooperation before proceeding with the given I/O request.
   * <p>
   * When used with Virtual Threads, this method optimizes cooperation to prevent unnecessary blocking
   * of carrier threads, allowing for higher concurrency with minimal resource overhead.
   * </p>
   *
   * @param requestKey used to match I/O requests for cooperation purposes
   * @param request function that performs some I/O and returns the result
   *
   * @throws IOException when the request fails due to I/O issues
   * @throws CooperationException when the current thread cannot cooperate
   */
  <T> T cooperate(String requestKey, IOCall<T> request) throws IOException;

  /**
   * Requests to join with any cached cooperation results when failing over.
   * If no cached results are found the underlying cooperation may choose to
   * wait and try again, for example to account for lag in distributed setups.
   * <p>
   * When used with Virtual Threads, this method ensures efficient yielding of the carrier thread
   * during wait periods, allowing other Virtual Threads to make progress while waiting for results.
   * </p>
   *
   * @param request function that tries to retrieve cached cooperation results
   * @return {@code null} if no cached results were found
   *
   * @throws IOException when the request fails due to I/O issues
   */
  @Nullable
  <T> T join(IOCheck<T> request) throws IOException;

  /**
   * Returns the number of threads cooperating per request-key.
   * <p>
   * This method properly accounts for both platform threads and Virtual Threads. When Virtual Threads
   * are used, the count represents the actual number of concurrent operations rather than the number of
   * carrier threads, which may be significantly smaller.
   * </p>
   *
   * @return number of threads (both platform and virtual) cooperating per request-key.
   */
  Map<String, Integer> getThreadCountPerKey();
  
  /**
   * Determines if the current thread is a Virtual Thread.
   * <p>
   * This utility method helps implementations optimize their behavior based on the thread type.
   * </p>
   *
   * @return {@code true} if the current thread is a Virtual Thread, {@code false} otherwise
   * @since 3.60
   */
  default boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
}
