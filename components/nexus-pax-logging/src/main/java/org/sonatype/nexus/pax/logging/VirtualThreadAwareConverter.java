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
 * Base abstract class for Logback converters that need to be Virtual Thread aware.
 * Provides utility methods to detect virtual threads, extract thread IDs, and format
 * thread information appropriately based on thread type.
 *
 * @since 3.60
 */
public abstract class VirtualThreadAwareConverter
    extends AccessConverter
{
  /**
   * Determines if the current thread is a virtual thread.
   * Uses Java 21's Thread.isVirtual() method.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  protected boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Determines if the specified thread is a virtual thread.
   * Uses Java 21's Thread.isVirtual() method.
   *
   * @param thread the thread to check
   * @return true if the thread is a virtual thread, false otherwise
   */
  protected boolean isVirtualThread(Thread thread) {
    return thread != null && thread.isVirtual();
  }

  /**
   * Gets the ID of the current thread, handling both platform and virtual threads.
   *
   * @return the thread ID as a String
   */
  protected String getThreadId() {
    Thread thread = Thread.currentThread();
    return getThreadId(thread);
  }

  /**
   * Gets the ID of the specified thread, handling both platform and virtual threads.
   *
   * @param thread the thread to get the ID from
   * @return the thread ID as a String
   */
  protected String getThreadId(Thread thread) {
    if (thread == null) {
      return "?"; 
    }
    return String.valueOf(thread.threadId());
  }

  /**
   * Formats thread information for logging, with different formats for virtual and platform threads.
   *
   * @param thread the thread to format information for
   * @return formatted thread information string
   */
  protected String formatThreadInfo(Thread thread) {
    if (thread == null) {
      return "unknown";
    }
    
    StringBuilder sb = new StringBuilder();
    if (isVirtualThread(thread)) {
      sb.append("VirtualThread[#").append(getThreadId(thread));
      if (thread.getName() != null && !thread.getName().isEmpty()) {
        sb.append(',').append(thread.getName());
      }
      sb.append(']');
      
      // Add carrier thread info if available
      String carrierInfo = getCarrierThreadInfo(thread);
      if (carrierInfo != null && !carrierInfo.isEmpty()) {
        sb.append('/').append(carrierInfo);
      }
    } else {
      // Platform thread
      sb.append("Thread[#").append(getThreadId(thread));
      if (thread.getName() != null && !thread.getName().isEmpty()) {
        sb.append(',').append(thread.getName());
      }
      sb.append(",").append(thread.getPriority());
      sb.append(',').append(thread.getThreadGroup() != null ? thread.getThreadGroup().getName() : "?");
      sb.append(']');
    }
    return sb.toString();
  }

  /**
   * Attempts to extract carrier thread information for a virtual thread.
   * This is implementation-specific and may not be available in all JVMs.
   *
   * @param thread the virtual thread to get carrier information for
   * @return carrier thread information or null if not available
   */
  protected String getCarrierThreadInfo(Thread thread) {
    if (!isVirtualThread(thread)) {
      return null;
    }
    
    // The state of the thread can indicate if it's mounted on a carrier
    Thread.State state = thread.getState();
    if (state == Thread.State.RUNNABLE) {
      // For running threads, we can assume they're mounted
      return "runnable";
    } else if (state == Thread.State.BLOCKED) {
      // For blocked threads, they might be pinned
      return "blocked";
    }
    
    return null;
  }

  /**
   * Detects if a virtual thread is currently pinned to its carrier thread.
   * Pinning occurs when a virtual thread is executing synchronized code or native methods.
   * 
   * Note: This is a best-effort detection and may not be accurate in all cases.
   * For precise pinning detection, use JVM flags like -Djdk.tracePinnedThreads or JFR events.
   *
   * @param thread the thread to check for pinning
   * @return true if the thread appears to be pinned, false otherwise or if not a virtual thread
   */
  protected boolean isPinned(Thread thread) {
    if (!isVirtualThread(thread)) {
      return false;
    }
    
    // A virtual thread in BLOCKED state might be pinned
    // This is a heuristic and not a definitive indicator
    return thread.getState() == Thread.State.BLOCKED;
  }

  /**
   * Abstract method that must be implemented by subclasses to convert an access event
   * into a string representation.
   *
   * @param accessEvent the access event to convert
   * @return the string representation of the event
   */
  @Override
  public abstract String convert(IAccessEvent accessEvent);
}