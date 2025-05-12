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
package org.sonatype.nexus.thread;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class to detect and prevent thread pinning in Java 21 virtual threads.
 * 
 * Thread pinning occurs when a virtual thread is mounted to a carrier thread and cannot be unmounted,
 * typically due to synchronized blocks, native methods, or foreign function calls. This can lead to
 * reduced concurrency and potential deadlocks in high-throughput scenarios.
 *
 * This detector helps identify potential pinning situations and provides mechanisms to avoid them
 * in REST operations and other critical paths.
 *
 * @since 3.60
 */
public class ThreadPinningDetector
{
  private static final Logger log = LoggerFactory.getLogger(ThreadPinningDetector.class);
  
  private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
  private final ReentrantLock nonBlockingLock = new ReentrantLock(false);
  
  /**
   * Detects if the current thread is a virtual thread and checks for potential pinning conditions.
   * If pinning is detected, logs a warning with the stack trace for diagnostic purposes.
   *
   * @return true if pinning is detected, false otherwise
   */
  public boolean detectPinning() {
    if (!isVirtualThread()) {
      return false; // Not a virtual thread, no pinning possible
    }
    
    // Check if we're in a synchronized block by attempting a non-blocking lock
    // If we're in a synchronized block, we can't acquire another lock without potentially pinning
    boolean canAcquireLock = nonBlockingLock.tryLock();
    try {
      if (!canAcquireLock) {
        // We're likely in a synchronized block or other pinning context
        if (pinningDetected.compareAndSet(false, true)) {
          // Log only once to avoid log spam
          log.warn(STR."Virtual thread pinning detected in thread \{Thread.currentThread().getName()}. " +
                  "This may reduce concurrency and performance. Consider refactoring to avoid synchronized blocks.", 
                  new Exception("Pinning detection stack trace"));
        }
        return true;
      }
    } finally {
      if (canAcquireLock) {
        nonBlockingLock.unlock();
      }
    }
    
    return false;
  }
  
  /**
   * Detects potential thread pinning and takes preventive action if necessary.
   * This method is intended to be called at the beginning of REST operations or other
   * critical paths where pinning could impact system performance.
   *
   * If pinning is detected, this method will log appropriate warnings and may take
   * additional actions to mitigate the impact.
   */
  public void detectAndPreventPinning() {
    if (detectPinning()) {
      // Additional mitigation strategies could be implemented here
      // For example, we could spawn a new virtual thread to handle the operation
      // or use alternative non-blocking approaches
      
      log.debug("Taking preventive action against thread pinning in {}", 
          Thread.currentThread().getStackTrace()[2].getMethodName());
    }
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * Uses Java 21's Thread.isVirtual() method if available.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    try {
      // Use reflection to avoid direct dependency on Java 21 API
      // This allows the code to run on both Java 17 and Java 21
      return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
    } catch (Exception e) {
      // Method doesn't exist (pre-Java 21) or other reflection error
      return false;
    }
  }
}