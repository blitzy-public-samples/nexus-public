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
package org.sonatype.nexus.common.thread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Helper class for carrying context information across Virtual Thread boundaries.
 * 
 * <p>When using Virtual Threads, context information may need to be propagated when a thread
 * is unmounted from its carrier thread during blocking operations and later remounted.
 * This class provides utilities to ensure proper context propagation.</p>
 *
 * <p>This is particularly important for operations that rely on ThreadLocal variables,
 * as Virtual Threads may be unmounted and remounted on different carrier threads during
 * blocking operations.</p>
 *
 * @since 3.60
 */
public class VirtualThreadContextCarrier
{
  private static final ThreadLocal<Map<String, Object>> CONTEXT_STORE = ThreadLocal.withInitial(ConcurrentHashMap::new);

  /**
   * Stores a value in the current thread's context.
   *
   * @param key the context key
   * @param value the value to store
   */
  public static void put(String key, Object value) {
    CONTEXT_STORE.get().put(key, value);
  }

  /**
   * Retrieves a value from the current thread's context.
   *
   * @param key the context key
   * @return the stored value, or null if not found
   */
  @SuppressWarnings("unchecked")
  public static <T> T get(String key) {
    return (T) CONTEXT_STORE.get().get(key);
  }

  /**
   * Retrieves a value from the current thread's context, or computes and stores it if not present.
   *
   * @param key the context key
   * @param supplier the supplier to compute the value if not present
   * @return the stored or computed value
   */
  @SuppressWarnings("unchecked")
  public static <T> T computeIfAbsent(String key, Supplier<T> supplier) {
    return (T) CONTEXT_STORE.get().computeIfAbsent(key, k -> supplier.get());
  }

  /**
   * Removes a value from the current thread's context.
   *
   * @param key the context key
   * @return the removed value, or null if not found
   */
  @SuppressWarnings("unchecked")
  public static <T> T remove(String key) {
    return (T) CONTEXT_STORE.get().remove(key);
  }

  /**
   * Clears all values from the current thread's context.
   */
  public static void clear() {
    CONTEXT_STORE.get().clear();
  }

  /**
   * Executes a task with the current thread's context, ensuring proper context propagation
   * even when the thread is unmounted and remounted during blocking operations.
   *
   * <p>This is particularly useful for Virtual Threads, which may be unmounted from their
   * carrier threads during blocking operations and later remounted on different carriers.</p>
   *
   * @param task the task to execute
   */
  public static void runWithContext(Runnable task) {
    // Capture the current context
    Map<String, Object> capturedContext = new ConcurrentHashMap<>(CONTEXT_STORE.get());
    
    try {
      // Run the task with the captured context
      task.run();
    } 
    finally {
      // Restore the original context
      CONTEXT_STORE.set(capturedContext);
    }
  }

  /**
   * Executes a task with the current thread's context and returns its result,
   * ensuring proper context propagation even when the thread is unmounted and
   * remounted during blocking operations.
   *
   * <p>This is particularly useful for Virtual Threads, which may be unmounted from their
   * carrier threads during blocking operations and later remounted on different carriers.</p>
   *
   * @param task the task to execute
   * @return the result of the task
   */
  public static <T> T supplyWithContext(Supplier<T> task) {
    // Capture the current context
    Map<String, Object> capturedContext = new ConcurrentHashMap<>(CONTEXT_STORE.get());
    
    try {
      // Run the task with the captured context
      return task.get();
    } 
    finally {
      // Restore the original context
      CONTEXT_STORE.set(capturedContext);
    }
  }

  /**
   * Determines if the current thread is a Virtual Thread.
   * Uses reflection to safely check on Java 21+ and gracefully falls back on earlier versions.
   *
   * @return true if the current thread is a Virtual Thread, false otherwise or if running on Java < 21
   */
  public static boolean isCurrentThreadVirtual() {
    try {
      return Thread.currentThread().isVirtual();
    } 
    catch (Throwable e) {
      // If any error occurs (e.g., method not available on Java < 21), assume it's not a Virtual Thread
      return false;
    }
  }
}