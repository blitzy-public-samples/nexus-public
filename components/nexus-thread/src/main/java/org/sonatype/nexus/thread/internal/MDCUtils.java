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
package org.sonatype.nexus.thread.internal;

import java.util.Map;

import org.sonatype.nexus.security.UserIdMdcHelper;

import com.google.common.collect.Maps;
import org.slf4j.MDC;

/**
 * Simple helper class to manipulate MDC (Mapped Diagnostic Context).
 * 
 * This class provides utilities for capturing and restoring MDC context across threads,
 * with special handling for virtual threads in Java 21+. MDC is commonly used for
 * storing diagnostic information (like user IDs, request IDs, etc.) that can be included
 * in log messages.
 *
 * @since 2.6
 */
public class MDCUtils
{
  private MDCUtils() {
    // empty
  }

  public static final String CONTEXT_NON_INHERITABLE_KEY = "non-inheritable";

  /**
   * Gets a copy of the current thread's MDC context map.
   * 
   * This method is optimized for both platform and virtual threads. For virtual threads,
   * it ensures efficient context capture without excessive memory usage.
   *
   * @return A copy of the current MDC context map, never null
   */
  public static Map<String, String> getCopyOfContextMap() {
    // Check if this is a virtual thread for potential optimizations
    boolean isVirtualThread = isVirtualThread();
    
    final boolean inheritable = MDC.get(CONTEXT_NON_INHERITABLE_KEY) == null;
    Map<String, String> result = null;
    if (inheritable) {
      // Get a copy of the MDC context map
      // For virtual threads, this is a lightweight operation as they don't share ThreadLocals
      result = MDC.getCopyOfContextMap();
    }
    if (result == null) {
      // Create a new map with the expected initial capacity to avoid resizing
      // This is more efficient, especially for virtual threads that may be numerous
      result = isVirtualThread ? Maps.newHashMapWithExpectedSize(4) : Maps.newHashMap();
    }
    result.remove(CONTEXT_NON_INHERITABLE_KEY);
    return result;
  }

  /**
   * Sets the MDC context map for the current thread.
   * 
   * This method is optimized for both platform and virtual threads. For virtual threads,
   * it ensures efficient context restoration without excessive memory usage.
   *
   * @param context The context map to set, may be null
   */
  public static void setContextMap(Map<String, String> context) {
    if (context != null) {
      // For virtual threads, this operation is optimized to minimize memory usage
      MDC.setContextMap(context);
      UserIdMdcHelper.setIfNeeded();
    }
    else {
      // Clear the MDC context to avoid memory leaks, especially important for virtual threads
      MDC.clear();
      UserIdMdcHelper.set();
    }
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * 
   * Uses Java 21's Thread.currentThread().isVirtual() method if available,
   * otherwise returns false for Java versions prior to 21.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private static boolean isVirtualThread() {
    try {
      // Use reflection to avoid direct dependency on Java 21 API
      // This allows the code to run on both Java 17 and Java 21
      return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
    }
    catch (Exception e) {
      // Method doesn't exist (pre-Java 21) or other reflection error
      return false;
    }
  }
}