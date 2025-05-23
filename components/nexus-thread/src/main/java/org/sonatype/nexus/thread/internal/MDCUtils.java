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
   * Gets a copy of the current MDC context map, respecting inheritability settings.
   * Optimized for both platform and virtual threads.
   *
   * @return a copy of the context map or an empty map if none exists
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
   * Sets the MDC context map, ensuring user ID is properly set.
   * Works with both platform and virtual threads.
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
   * Copies the MDC context from the current thread to the provided runnable,
   * optimized for virtual thread execution.
   *
   * @param runnable the runnable to wrap with MDC context
   * @return a runnable with MDC context handling
   * @since 3.60
   */
  public static Runnable withMdcContext(final Runnable runnable) {
    final Map<String, String> context = getCopyOfContextMap();
    return () -> {
      Map<String, String> previous = MDC.getCopyOfContextMap();
      try {
        setContextMap(context);
        runnable.run();
      }
      finally {
        setContextMap(previous);
      }
    };
  }

  /**
   * Propagates the MDC context to a child thread, with optimizations for virtual threads.
   * This method should be called before starting a new thread to ensure proper MDC inheritance.
   *
   * @param thread the thread that will receive the MDC context
   * @since 3.60
   */
  public static void propagateMdcContext(Thread thread) {
    if (thread.isVirtual()) {
      // For virtual threads, we need to explicitly handle MDC propagation
      // since they may not inherit thread locals in the same way as platform threads
      final Map<String, String> context = getCopyOfContextMap();
      thread.setUncaughtExceptionHandler((t, e) -> {
        Map<String, String> previous = MDC.getCopyOfContextMap();
        try {
          setContextMap(context);
          // Re-throw the exception with the proper MDC context
          Thread.getDefaultUncaughtExceptionHandler().uncaughtException(t, e);
        }
        finally {
          setContextMap(previous);
        }
      });
    }
  }

  /**
   * Creates a new virtual thread with MDC context propagation.
   * This is a convenience method for creating virtual threads that inherit the MDC context.
   *
   * @param name the name of the thread
   * @param runnable the runnable to execute in the virtual thread
   * @return a new virtual thread with MDC context propagation
   * @since 3.60
   */
  public static Thread newVirtualThreadWithMdcContext(String name, Runnable runnable) {
    Thread thread = Thread.ofVirtual().name(name).unstarted(withMdcContext(runnable));
    propagateMdcContext(thread);
    return thread;
  }

  /**
   * Clears the MDC context for the current thread.
   * This is particularly useful for virtual threads to prevent memory leaks.
   *
   * @since 3.60
   */
  public static void clearContext() {
    MDC.clear();
  }
}