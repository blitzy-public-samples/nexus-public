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

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Converter that provides enhanced thread information for Java 21 Virtual Threads.
 * <p>
 * This converter extends the standard thread name output with additional information
 * when the thread is a virtual thread, including:
 * <ul>
 *   <li>Virtual thread ID</li>
 *   <li>Carrier thread information (when available)</li>
 *   <li>Pinned status indicator</li>
 * </ul>
 *
 * @since 3.60.0
 */
public class VirtualThreadAwareConverter
    extends ClassicConverter
{
  @Override
  public String convert(final ILoggingEvent event) {
    Thread currentThread = Thread.currentThread();
    String threadName = event.getThreadName();
    
    // Check if running on Java 21+ with virtual threads
    if (isVirtualThread(currentThread)) {
      StringBuilder threadInfo = new StringBuilder();
      
      // Add virtual thread indicator and ID
      threadInfo.append("VT-").append(threadName);
      
      // Add thread ID
      threadInfo.append("[#").append(currentThread.threadId()).append("]");
      
      // Add carrier thread info if available
      String carrierInfo = getCarrierThreadInfo(currentThread);
      if (carrierInfo != null && !carrierInfo.isEmpty()) {
        threadInfo.append("@").append(carrierInfo);
      }
      
      // Add pinned indicator if thread is pinned
      if (isThreadPinned(currentThread)) {
        threadInfo.append("[pinned]");
      }
      
      return threadInfo.toString();
    }
    
    // Default behavior for platform threads
    return threadName;
  }
  
  /**
   * Determines if the current thread is a virtual thread.
   * 
   * @param thread the thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread(Thread thread) {
    try {
      // Use Java 21 API to check if thread is virtual
      return thread.isVirtual();
    }
    catch (NoSuchMethodError e) {
      // Running on Java version prior to 21
      return false;
    }
  }
  
  /**
   * Gets information about the carrier thread for a virtual thread.
   * 
   * @param thread the virtual thread
   * @return carrier thread information or null if not available
   */
  private String getCarrierThreadInfo(Thread thread) {
    try {
      // This is a simplified approach - in a real implementation,
      // we would use JDK internal APIs or JMX to get carrier thread info
      String threadString = thread.toString();
      int carrierIndex = threadString.indexOf("@ForkJoinPool");
      if (carrierIndex > 0) {
        int endIndex = threadString.indexOf(" ", carrierIndex);
        if (endIndex < 0) {
          endIndex = threadString.length();
        }
        return threadString.substring(carrierIndex + 1, endIndex);
      }
      return null;
    }
    catch (Exception e) {
      return null;
    }
  }
  
  /**
   * Determines if a virtual thread is currently pinned to its carrier thread.
   * 
   * @param thread the virtual thread to check
   * @return true if the thread is pinned, false otherwise or if cannot determine
   */
  private boolean isThreadPinned(Thread thread) {
    try {
      // In a real implementation, we would use JDK internal APIs or JMX
      // to determine if a virtual thread is pinned
      // This is a placeholder implementation
      String threadString = thread.toString();
      return threadString.contains("[pinned]");
    }
    catch (Exception e) {
      return false;
    }
  }
}