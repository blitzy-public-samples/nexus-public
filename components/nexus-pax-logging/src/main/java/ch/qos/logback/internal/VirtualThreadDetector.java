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
package ch.qos.logback.internal;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Utility class for detecting and working with Java 21 Virtual Threads.
 * <p>
 * This class provides methods to:
 * <ul>
 *   <li>Detect if the current thread is a virtual thread</li>
 *   <li>Extract virtual thread information (ID, name)</li>
 *   <li>Monitor virtual thread statistics</li>
 * </ul>
 * <p>
 * Virtual threads are lightweight threads introduced in Java 21 that are managed by the JVM rather than the OS.
 * They enable high concurrency with minimal resource usage, but require proper detection and handling
 * for logging and diagnostics purposes.
 *
 * @since 3.60
 */
public final class VirtualThreadDetector
{
  private static final AtomicLong activeVirtualThreads = new AtomicLong(0);
  private static final LongAdder totalVirtualThreadsCreated = new LongAdder();
  private static final LongAdder pinnedThreadDetections = new LongAdder();

  private VirtualThreadDetector() {
    // Utility class, no instances
  }

  /**
   * Determines if the current thread is a virtual thread.
   * <p>
   * Uses the Java 21 Thread.isVirtual() method to detect virtual threads at runtime.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Determines if the specified thread is a virtual thread.
   * <p>
   * Uses the Java 21 Thread.isVirtual() method to detect virtual threads at runtime.
   *
   * @param thread the thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread(Thread thread) {
    return thread != null && thread.isVirtual();
  }

  /**
   * Gets the ID of the current thread, with special handling for virtual threads.
   * <p>
   * This method ensures consistent thread ID retrieval for both platform and virtual threads.
   *
   * @return the thread ID as a long value
   */
  public static long getCurrentThreadId() {
    return Thread.currentThread().threadId();
  }

  /**
   * Gets the name of the current thread, with special handling for virtual threads.
   * <p>
   * Virtual threads may not have default names, so this method provides a consistent
   * way to retrieve thread names for logging purposes.
   *
   * @return the thread name, or a generated name if the thread has no name
   */
  public static String getCurrentThreadName() {
    Thread currentThread = Thread.currentThread();
    String name = currentThread.getName();
    
    if (name == null || name.isEmpty()) {
      // Virtual threads often have empty names, so generate a descriptive one
      return "VirtualThread-" + currentThread.threadId();
    }
    
    return name;
  }

  /**
   * Registers a new virtual thread creation for statistics tracking.
   * <p>
   * This method should be called when a new virtual thread is created to maintain
   * accurate statistics about virtual thread usage.
   */
  public static void registerVirtualThreadCreation() {
    if (isVirtualThread()) {
      activeVirtualThreads.incrementAndGet();
      totalVirtualThreadsCreated.increment();
    }
  }

  /**
   * Registers a virtual thread termination for statistics tracking.
   * <p>
   * This method should be called when a virtual thread completes to maintain
   * accurate statistics about virtual thread usage.
   */
  public static void registerVirtualThreadTermination() {
    if (isVirtualThread()) {
      activeVirtualThreads.decrementAndGet();
    }
  }

  /**
   * Registers a detection of a pinned virtual thread.
   * <p>
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier thread,
   * typically when executing code inside a synchronized block or when calling native methods.
   * This method helps track the frequency of pinning events for monitoring purposes.
   */
  public static void registerPinnedThreadDetection() {
    pinnedThreadDetections.increment();
  }

  /**
   * Gets the current count of active virtual threads.
   *
   * @return the number of active virtual threads
   */
  public static long getActiveVirtualThreadCount() {
    return activeVirtualThreads.get();
  }

  /**
   * Gets the total number of virtual threads created since JVM startup.
   *
   * @return the total number of virtual threads created
   */
  public static long getTotalVirtualThreadsCreated() {
    return totalVirtualThreadsCreated.sum();
  }

  /**
   * Gets the number of pinned thread detections recorded.
   *
   * @return the count of pinned thread detections
   */
  public static long getPinnedThreadDetectionCount() {
    return pinnedThreadDetections.sum();
  }

  /**
   * Determines if the JVM is running with virtual thread pinning detection enabled.
   * <p>
   * This checks for the presence of the jdk.tracePinnedThreads system property,
   * which enables logging of virtual thread pinning events.
   *
   * @return true if pinning detection is enabled, false otherwise
   */
  public static boolean isPinningDetectionEnabled() {
    String tracePinnedThreads = System.getProperty("jdk.tracePinnedThreads");
    return tracePinnedThreads != null && !tracePinnedThreads.isEmpty();
  }

  /**
   * Generates a formatted string with virtual thread statistics.
   * <p>
   * This method is useful for logging and monitoring purposes.
   *
   * @return a string containing virtual thread statistics
   */
  public static String getVirtualThreadStats() {
    return String.format(
        "VirtualThreadStats[active=%d, total=%d, pinningDetections=%d]",
        getActiveVirtualThreadCount(),
        getTotalVirtualThreadsCreated(),
        getPinnedThreadDetectionCount());
  }

  /**
   * Checks if the current JVM supports virtual threads.
   * <p>
   * This method verifies that the JVM version is Java 21 or later, which is required for virtual thread support.
   *
   * @return true if virtual threads are supported, false otherwise
   */
  public static boolean isVirtualThreadSupported() {
    try {
      // Try to access the isVirtual method which only exists in Java 21+
      Thread.class.getMethod("isVirtual");
      return true;
    }
    catch (NoSuchMethodException e) {
      return false;
    }
  }

  /**
   * Gets the JVM version as a string.
   *
   * @return the JVM version string
   */
  public static String getJavaVersion() {
    return System.getProperty("java.version");
  }
}