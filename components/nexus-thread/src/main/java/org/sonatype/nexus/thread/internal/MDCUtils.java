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
 * Simple helper class to manipulate MDC.
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
   * Checks if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   * @since 3.60
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Gets a copy of the current thread's MDC context map, filtering out non-inheritable contexts.
   * Optimized for both platform and virtual threads.
   *
   * @return a copy of the MDC context map with non-inheritable contexts removed
   */
  public static Map<String, String> getCopyOfContextMap() {
    final boolean inheritable = MDC.get(CONTEXT_NON_INHERITABLE_KEY) == null;
    Map<String, String> result = null;
    if (inheritable) {
      // noinspection unchecked
      result = MDC.getCopyOfContextMap();
    }
    if (result == null) {
      result = Maps.newHashMap();
    }
    result.remove(CONTEXT_NON_INHERITABLE_KEY);
    return result;
  }

  /**
   * Sets the MDC context map and ensures the user ID is set.
   * Handles both platform and virtual threads appropriately.
   *
   * @param context the context map to set, or null to clear the context
   */
  public static void setContextMap(Map<String, String> context) {
    if (context != null) {
      MDC.setContextMap(context);
      UserIdMdcHelper.setIfNeeded();
    }
    else {
      MDC.clear();
      UserIdMdcHelper.set();
    }
  }

  /**
   * Creates a snapshot of the current MDC context that can be used to propagate context
   * to another thread, including virtual threads.
   *
   * @return a copy of the current MDC context map suitable for propagation
   * @since 3.60
   */
  public static Map<String, String> getContextMapForPropagation() {
    return getCopyOfContextMap();
  }

  /**
   * Applies a previously captured MDC context to the current thread.
   * Optimized for both platform and virtual threads.
   *
   * @param contextMap the context map to apply, or null to clear the context
   * @since 3.60
   */
  public static void applyContextMap(Map<String, String> contextMap) {
    setContextMap(contextMap);
  }

  /**
   * Clears the MDC context for the current thread.
   * Important for virtual threads to prevent memory leaks.
   *
   * @since 3.60
   */
  public static void clearContext() {
    MDC.clear();
  }

  /**
   * Captures the current MDC context, executes the provided runnable,
   * and ensures the original context is restored afterward.
   * This is particularly useful for virtual threads in structured concurrency.
   *
   * @param runnable the code to execute with preserved MDC context
   * @since 3.60
   */
  public static void withContext(Runnable runnable) {
    Map<String, String> originalContext = getCopyOfContextMap();
    try {
      runnable.run();
    }
    finally {
      setContextMap(originalContext);
    }
  }

  /**
   * Captures the current MDC context, applies a new context, executes the provided runnable,
   * and ensures the original context is restored afterward.
   * This is particularly useful for virtual threads in structured concurrency.
   *
   * @param newContext the context to apply before executing the runnable
   * @param runnable the code to execute with the new MDC context
   * @since 3.60
   */
  public static void withContext(Map<String, String> newContext, Runnable runnable) {
    Map<String, String> originalContext = getCopyOfContextMap();
    try {
      setContextMap(newContext);
      runnable.run();
    }
    finally {
      setContextMap(originalContext);
    }
  }
}