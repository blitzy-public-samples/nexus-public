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
import java.lang.Thread;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.sonatype.nexus.common.io.Cooperation;

/**
 * Configuration holder for {@link Cooperation} points.
 * 
 * <p>With Java 21 Virtual Threads support, this configuration provides optimized thread limit management
 * that distinguishes between Virtual Threads and Platform Threads. Virtual Threads are lightweight and
 * can be created in much larger numbers than Platform Threads, so different thread counting strategies
 * are applied based on the thread type.</p>
 *
 * <p>When using Virtual Threads, higher values for threadsPerKey can be used without performance degradation,
 * as Virtual Threads are designed for high-concurrency I/O-bound operations with minimal resource overhead.</p>
 */
public class Config
{
  protected int majorTimeoutSeconds = 0;

  protected int minorTimeoutSeconds = 0;

  protected int threadsPerKey = 0;
  
  // Cache for the isVirtual method availability to avoid repeated reflection checks
  private static final boolean IS_VIRTUAL_METHOD_AVAILABLE = checkIsVirtualMethodAvailable();
  
  /**
   * Checks if the Thread.isVirtual() method is available (Java 21+)
   */
  private static boolean checkIsVirtualMethodAvailable() {
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      lookup.findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
      return true;
    } 
    catch (NoSuchMethodException | IllegalAccessException e) {
      return false;
    }
  }

  public Duration majorTimeout() {
    return Duration.ofSeconds(majorTimeoutSeconds);
  }

  public Duration minorTimeout() {
    return Duration.ofSeconds(minorTimeoutSeconds);
  }

  /**
   * Returns the configured number of threads per key, with optimizations for Virtual Threads.
   * 
   * <p>When running with Java 21+ Virtual Threads, this method applies different thread counting
   * strategies based on the thread type:</p>
   * <ul>
   *   <li>For Platform Threads: Uses the configured threadsPerKey value directly, as these are limited resources</li>
   *   <li>For Virtual Threads: Applies a more generous limit calculation, as Virtual Threads are lightweight</li>
   * </ul>
   * 
   * @return the effective threads per key value, adjusted for the current thread type
   */
  public int threadsPerKey() {
    if (isCurrentThreadVirtual()) {
      // For Virtual Threads, we can allow more concurrent threads per key
      // since they are lightweight and designed for high concurrency
      return calculateVirtualThreadsPerKey();
    }
    // For Platform Threads, use the configured value directly
    return threadsPerKey;
  }
  
  /**
   * Calculates the optimal number of Virtual Threads per key.
   * Virtual Threads are lightweight and can be created in much larger numbers,
   * so we can use a more generous limit than for Platform Threads.
   *
   * @return the calculated number of Virtual Threads per key
   */
  protected int calculateVirtualThreadsPerKey() {
    // If threadsPerKey is explicitly set to 0 or negative, keep that value
    if (threadsPerKey <= 0) {
      return threadsPerKey;
    }
    
    // For Virtual Threads, we can allow significantly more threads per key
    // The multiplier is chosen to balance between allowing more concurrency
    // while still providing some limit to prevent resource exhaustion
    return Math.max(threadsPerKey * 10, 100);
  }
  
  /**
   * Determines if the current thread is a Virtual Thread.
   * Uses reflection to safely check on Java 21+ and gracefully falls back on earlier versions.
   *
   * @return true if the current thread is a Virtual Thread, false otherwise or if running on Java < 21
   */
  protected boolean isCurrentThreadVirtual() {
    if (!IS_VIRTUAL_METHOD_AVAILABLE) {
      return false; // Pre-Java 21, no Virtual Threads available
    }
    
    try {
      MethodHandles.Lookup lookup = MethodHandles.lookup();
      var isVirtual = lookup.findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
      return (boolean) isVirtual.invoke(Thread.currentThread());
    } 
    catch (Throwable e) {
      // If any error occurs, assume it's not a Virtual Thread
      return false;
    }
  }

  /**
   * Creates a copy of this configuration.
   *
   * @return a new Config instance with the same values as this one
   */
  protected Config copy() {
    Config copy = new Config();
    copy.majorTimeoutSeconds = majorTimeoutSeconds;
    copy.minorTimeoutSeconds = minorTimeoutSeconds;
    copy.threadsPerKey = threadsPerKey;
    return copy;
  }
}