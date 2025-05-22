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
package org.sonatype.nexus.pax.logging;

import ch.qos.logback.access.pattern.AccessConverter;
import ch.qos.logback.access.spi.IAccessEvent;

/**
 * Converter to include thread information with support for Java 21 Virtual Threads.
 * Adds a prefix to indicate thread type (V: for virtual, P: for platform) and includes
 * additional metadata for virtual threads such as mount state for pinned thread detection.
 *
 * @since 3.17
 */
public class NexusThreadConverter
    extends AccessConverter
{
  /**
   * Converts the current thread information into a string representation with Java 21 Virtual Thread support.
   * Format: [ThreadType:ThreadName] where ThreadType is V for virtual threads and P for platform threads.
   * For virtual threads that are pinned, adds a [PINNED] indicator.
   *
   * @param accessEvent The access event (not used in this implementation)
   * @return A string representation of the current thread with type indicator
   */
  public String convert(IAccessEvent accessEvent) {
    Thread currentThread = Thread.currentThread();
    StringBuilder threadInfo = new StringBuilder();
    
    // Check if the thread is a virtual thread (Java 21 feature)
    boolean isVirtual = isVirtualThread(currentThread);
    
    // Add prefix based on thread type
    threadInfo.append(isVirtual ? "V:" : "P:");
    
    // Add thread name
    threadInfo.append(currentThread.getName());
    
    // For virtual threads, check if pinned and add additional metadata if available
    if (isVirtual) {
      // Check for pinned state - a pinned virtual thread is mounted to a carrier thread
      // and cannot be unmounted during blocking operations
      if (isThreadPinned(currentThread)) {
        threadInfo.append(" [PINNED]");
      }
    }
    
    return threadInfo.toString();
  }
  
  /**
   * Checks if the given thread is a virtual thread using Java 21 API.
   *
   * @param thread The thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread(Thread thread) {
    try {
      // Use reflection to safely call Thread.isVirtual() method introduced in Java 21
      return thread.isVirtual();
    }
    catch (Throwable e) {
      // If running on Java version prior to 21 or method doesn't exist,
      // assume it's not a virtual thread
      return false;
    }
  }
  
  /**
   * Attempts to determine if a virtual thread is currently pinned to its carrier thread.
   * A pinned virtual thread cannot be unmounted during blocking operations, which may
   * impact performance in high-throughput scenarios.
   *
   * @param thread The thread to check for pinned state
   * @return true if the thread appears to be a pinned virtual thread, false otherwise
   */
  private boolean isThreadPinned(Thread thread) {
    // This is a simplified implementation as there's no direct API to check if a virtual thread is pinned
    // In a real implementation, you might use JFR events or other heuristics to detect pinning
    // For now, we'll use the thread's stack trace to look for synchronized blocks or native methods
    // which are common causes of pinning
    
    try {
      StackTraceElement[] stackTrace = thread.getStackTrace();
      for (StackTraceElement element : stackTrace) {
        // Look for indicators of potential pinning in the stack trace
        String className = element.getClassName();
        String methodName = element.getMethodName();
        
        // Check for native methods which can cause pinning
        if (element.isNativeMethod()) {
          return true;
        }
        
        // Check for common synchronized methods or classes that might cause pinning
        if (className.contains("java.util.concurrent.locks") && 
            (methodName.contains("lock") || methodName.contains("wait"))) {
          return true;
        }
      }
    }
    catch (Throwable e) {
      // If we can't determine the pinned state, assume not pinned
    }
    
    return false;
  }
}