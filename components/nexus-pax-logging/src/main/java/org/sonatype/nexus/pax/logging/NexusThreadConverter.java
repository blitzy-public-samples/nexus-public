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
 * Converter to include thread information with Java 21 Virtual Thread support.
 * Adds a thread type indicator prefix (V: for virtual, P: for platform) and
 * includes Virtual Thread mount state for pinned thread detection.
 *
 * @since 3.17
 */
public class NexusThreadConverter
    extends AccessConverter
{
  /**
   * Converts the current thread information into a string representation with
   * Java 21 Virtual Thread awareness. The format is:
   * - For virtual threads: "V: [thread-name]" or "V(pinned): [thread-name]" if pinned
   * - For platform threads: "P: [thread-name]"
   *
   * @param accessEvent The access event (not used directly in this implementation)
   * @return A string representation of the current thread with type indicator
   */
  public String convert(IAccessEvent accessEvent) {
    Thread currentThread = Thread.currentThread();
    StringBuilder threadInfo = new StringBuilder();
    
    // Check if the thread is a virtual thread (Java 21 feature)
    if (currentThread.isVirtual()) {
      threadInfo.append("V: ");
      
      // Check if the virtual thread is pinned to its carrier thread
      // This is a simplified check - in a real implementation you might use JFR events
      // or other mechanisms to detect pinning more accurately
      if (isPinned(currentThread)) {
        threadInfo.append("(pinned): ");
      }
    } else {
      threadInfo.append("P: ");
    }
    
    threadInfo.append(currentThread.getName());
    return threadInfo.toString();
  }
  
  /**
   * Checks if a virtual thread is currently pinned to its carrier thread.
   * This is a simplified implementation that uses thread state as a heuristic.
   * In a production environment, more sophisticated detection using JFR events
   * or JVM-specific APIs would be preferred.
   *
   * @param thread The thread to check
   * @return true if the thread appears to be pinned, false otherwise
   */
  private boolean isPinned(Thread thread) {
    // This is a simplified heuristic - in reality, pinning detection
    // would use more sophisticated mechanisms like JFR events
    // or examining the thread's stack trace for synchronized blocks
    return thread.isVirtual() && 
           (thread.getState() == Thread.State.BLOCKED || 
            thread.getStackTrace().length > 0 && 
            containsSynchronizedOrNativeFrames(thread.getStackTrace()));
  }
  
  /**
   * Examines a thread's stack trace to look for potential indicators of pinning,
   * such as synchronized methods or native method calls.
   *
   * @param stackTrace The thread's stack trace
   * @return true if potential pinning indicators are found, false otherwise
   */
  private boolean containsSynchronizedOrNativeFrames(StackTraceElement[] stackTrace) {
    // This is a simplified heuristic that looks for native methods in the stack trace
    // A more accurate implementation would require deeper JVM integration
    for (StackTraceElement element : stackTrace) {
      if (element.isNativeMethod()) {
        return true;
      }
      // Note: We can't directly detect synchronized methods from stack trace elements
      // This would require bytecode analysis or JVM-specific APIs
    }
    return false;
  }
}