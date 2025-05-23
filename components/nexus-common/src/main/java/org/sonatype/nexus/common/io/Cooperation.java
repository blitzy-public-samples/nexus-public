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
 * the cooperation mechanism automatically optimizes for their lightweight nature, allowing for higher concurrency
 * and better resource utilization during I/O operations.</p>
 *
 * <p><strong>Virtual Thread Best Practices:</strong></p>
 * <ul>
 *   <li>Virtual threads are ideal for I/O-bound operations where threads spend most of their time waiting</li>
 *   <li>Avoid using synchronized blocks within cooperating virtual threads to prevent pinning</li>
 *   <li>For CPU-intensive operations, platform threads may still be more appropriate</li>
 *   <li>Virtual threads automatically yield their carrier thread during blocking I/O operations</li>
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
   * When used with virtual threads (Java 21+), this method optimizes thread coordination
   * to take advantage of virtual thread characteristics, such as automatic unmounting
   * during blocking I/O operations.</p>
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
   * When used with virtual threads (Java 21+), this method optimizes the joining process
   * to prevent unnecessary blocking of carrier threads. Virtual threads will automatically
   * yield their carrier thread during any blocking operations in the join process.</p>
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
   * When virtual threads (Java 21+) are in use, this method accounts for both platform
   * and virtual threads. The count represents the total number of threads (regardless of type)
   * that are currently cooperating on each request key.</p>
   *
   * @return map of request-keys to thread counts
   */
  Map<String, Integer> getThreadCountPerKey();
  
  /**
   * Returns detailed information about threads cooperating per request-key, including
   * a breakdown of platform threads versus virtual threads when running on Java 21+.
   * <p>
   * This method provides more detailed thread statistics than {@link #getThreadCountPerKey()},
   * which is useful for monitoring and diagnostics in environments using virtual threads.</p>
   *
   * @return map of request-keys to thread statistics
   * @since 3.60
   */
  default Map<String, ThreadStatistics> getThreadStatisticsPerKey() {
    // Default implementation for backward compatibility
    return getThreadCountPerKey().entrySet().stream()
        .collect(java.util.stream.Collectors.toMap(
            Map.Entry::getKey,
            e -> new ThreadStatistics(e.getValue(), 0, e.getValue())
        ));
  }
  
  /**
   * Statistics about threads cooperating on a request key.
   *
   * @since 3.60
   */
  class ThreadStatistics {
    private final int platformThreadCount;
    private final int virtualThreadCount;
    private final int totalThreadCount;
    
    /**
     * Creates a new thread statistics instance.
     *
     * @param platformThreadCount number of platform threads
     * @param virtualThreadCount number of virtual threads
     * @param totalThreadCount total number of threads (platform + virtual)
     */
    public ThreadStatistics(int platformThreadCount, int virtualThreadCount, int totalThreadCount) {
      this.platformThreadCount = platformThreadCount;
      this.virtualThreadCount = virtualThreadCount;
      this.totalThreadCount = totalThreadCount;
    }
    
    /**
     * @return number of platform threads cooperating
     */
    public int getPlatformThreadCount() {
      return platformThreadCount;
    }
    
    /**
     * @return number of virtual threads cooperating
     */
    public int getVirtualThreadCount() {
      return virtualThreadCount;
    }
    
    /**
     * @return total number of threads cooperating (platform + virtual)
     */
    public int getTotalThreadCount() {
      return totalThreadCount;
    }
    
    @Override
    public String toString() {
      return "ThreadStatistics{" +
          "platformThreadCount=" + platformThreadCount +
          ", virtualThreadCount=" + virtualThreadCount +
          ", totalThreadCount=" + totalThreadCount +
          '}';
    }
  }
}