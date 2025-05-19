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
 * <p>This interface is compatible with both platform threads and virtual threads (Java 21+). When using virtual threads,
 * cooperation is especially beneficial for I/O-bound operations as it reduces the number of redundant I/O requests
 * while maintaining the scalability advantages of virtual threads. The implementation will properly handle the
 * suspension and resumption of virtual threads during blocking operations.</p>
 *
 * @since 3.14
 */
public interface Cooperation
{
  /**
   * Best practices for using Cooperation with Virtual Threads (Java 21+):
   * <ul>
   *   <li>Avoid using synchronized blocks or methods within cooperating code as this can lead to
   *       virtual thread "pinning" which prevents the carrier thread from being released</li>
   *   <li>Prefer using java.util.concurrent locks (ReentrantLock, ReadWriteLock) instead of synchronized</li>
   *   <li>Keep cooperation scopes focused on I/O-bound operations where the benefits are greatest</li>
   *   <li>For CPU-intensive operations, consider using traditional thread pools instead of virtual threads</li>
   *   <li>Be aware that extremely high numbers of virtual threads may increase memory usage due to
   *       the cooperation tracking overhead, even though individual virtual threads are lightweight</li>
   * </ul>
   */
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
   * 
   * <p>When using virtual threads (Java 21+), this method will efficiently manage thread resources
   * by allowing the carrier thread to be released during blocking I/O operations while maintaining
   * the cooperation semantics. This enables high throughput with minimal resource consumption even
   * with a large number of concurrent requests.</p>
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
   * 
   * <p>When using virtual threads (Java 21+), this method is optimized to efficiently handle
   * the join operation without blocking the carrier thread. This allows the system to maintain
   * high throughput even when many virtual threads are waiting to join with cached results.</p>
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
   * 
   * <p>When using virtual threads (Java 21+), this method accurately accounts for both platform
   * threads and virtual threads that are cooperating on the same request key. The count includes
   * all threads regardless of their type, providing a complete view of the cooperation activity.</p>
   *
   * @return number of threads cooperating per request-key.
   */
  Map<String, Integer> getThreadCountPerKey();
}