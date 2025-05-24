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
package org.sonatype.nexus;

import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import org.apache.logging.log4j.ThreadContext;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * Utility class for propagating thread-local context across Virtual Thread boundaries in Java 21.
 * <p>
 * This class provides methods to capture and restore thread-local state such as:
 * <ul>
 *   <li>Logging context (MDC - Mapped Diagnostic Context)</li>
 *   <li>Security context (Shiro Subject)</li>
 *   <li>Other thread-local variables</li>
 * </ul>
 * <p>
 * When operations span multiple Virtual Threads, this propagator ensures that logging
 * continuity and security context are maintained, which is critical for proper
 * application behavior in asynchronous operations.
 * <p>
 * Usage example:
 * <pre>
 * // Capture context from current thread
 * ThreadContext captured = VirtualThreadContextPropagator.capture();
 * 
 * // Create a new virtual thread with the captured context
 * Thread.ofVirtual().start(() -> {
 *     // Restore the context in the new thread
 *     try (VirtualThreadContextPropagator.ContextHandle handle = 
 *          VirtualThreadContextPropagator.restore(captured)) {
 *         // Execute operations with the restored context
 *         // Logging and security operations will use the captured context
 *     }
 * });
 * </pre>
 *
 * @since 3.60
 */
public final class VirtualThreadContextPropagator
{
  private VirtualThreadContextPropagator() {
    // Utility class, no instances
  }

  /**
   * Captures the current thread's context including MDC and security context.
   *
   * @return A {@link Context} object containing the captured state
   */
  public static Context capture() {
    return new Context(
        captureLoggingContext(),
        captureSecurityContext()
    );
  }

  /**
   * Captures the current thread's logging context (MDC).
   *
   * @return A map containing the current MDC values
   */
  private static Map<String, String> captureLoggingContext() {
    Map<String, String> mdcContext = ThreadContext.getContext();
    return mdcContext != null ? new HashMap<>(mdcContext) : new HashMap<>();
  }

  /**
   * Captures the current thread's security context (Shiro Subject).
   *
   * @return The current Shiro Subject or null if not available
   */
  private static Subject captureSecurityContext() {
    try {
      return SecurityUtils.getSubject();
    }
    catch (Exception e) {
      // No security context available
      return null;
    }
  }

  /**
   * Restores a previously captured context in the current thread.
   *
   * @param context The context to restore
   * @return A {@link ContextHandle} that should be closed to restore the original context
   */
  public static ContextHandle restore(final Context context) {
    // Capture current context before replacing it
    Map<String, String> previousMdc = captureLoggingContext();
    Subject previousSubject = captureSecurityContext();

    // Apply the provided context
    applyLoggingContext(context.loggingContext);
    applySecurityContext(context.securityContext);

    // Return a handle that will restore the previous context when closed
    return new ContextHandle(previousMdc, previousSubject);
  }

  /**
   * Applies the given logging context to the current thread.
   *
   * @param loggingContext The logging context to apply
   */
  private static void applyLoggingContext(final Map<String, String> loggingContext) {
    ThreadContext.clearAll();
    if (loggingContext != null && !loggingContext.isEmpty()) {
      loggingContext.forEach(ThreadContext::put);
    }
  }

  /**
   * Applies the given security context to the current thread.
   *
   * @param securityContext The security context to apply
   */
  private static void applySecurityContext(final Subject securityContext) {
    if (securityContext != null) {
      SecurityUtils.getSubject().associateWith(securityContext.getSession());
    }
  }

  /**
   * Wraps a {@link Runnable} with context propagation.
   *
   * @param runnable The runnable to wrap
   * @return A new runnable that will execute with the current thread's context
   */
  public static Runnable wrap(final Runnable runnable) {
    final Context capturedContext = capture();
    return () -> {
      try (ContextHandle handle = restore(capturedContext)) {
        runnable.run();
      }
    };
  }

  /**
   * Wraps a {@link Callable} with context propagation.
   *
   * @param callable The callable to wrap
   * @param <V> The return type of the callable
   * @return A new callable that will execute with the current thread's context
   */
  public static <V> Callable<V> wrap(final Callable<V> callable) {
    final Context capturedContext = capture();
    return () -> {
      try (ContextHandle handle = restore(capturedContext)) {
        return callable.call();
      }
    };
  }

  /**
   * Wraps a {@link Supplier} with context propagation.
   *
   * @param supplier The supplier to wrap
   * @param <V> The return type of the supplier
   * @return A new supplier that will execute with the current thread's context
   */
  public static <V> Supplier<V> wrap(final Supplier<V> supplier) {
    final Context capturedContext = capture();
    return () -> {
      try (ContextHandle handle = restore(capturedContext)) {
        return supplier.get();
      }
    };
  }

  /**
   * Container class for thread context information.
   */
  public static final class Context
  {
    private final Map<String, String> loggingContext;
    private final Subject securityContext;

    private Context(final Map<String, String> loggingContext, final Subject securityContext) {
      this.loggingContext = loggingContext;
      this.securityContext = securityContext;
    }

    /**
     * Gets the captured logging context.
     *
     * @return The logging context map
     */
    public Map<String, String> getLoggingContext() {
      return loggingContext;
    }

    /**
     * Gets the captured security context.
     *
     * @return The security context
     */
    public Subject getSecurityContext() {
      return securityContext;
    }
  }

  /**
   * Handle for managing context restoration.
   * <p>
   * This class implements {@link AutoCloseable} to allow use with try-with-resources.
   */
  public static final class ContextHandle
      implements AutoCloseable
  {
    private final Map<String, String> previousLoggingContext;
    private final Subject previousSecurityContext;

    private ContextHandle(final Map<String, String> previousLoggingContext, final Subject previousSecurityContext) {
      this.previousLoggingContext = previousLoggingContext;
      this.previousSecurityContext = previousSecurityContext;
    }

    /**
     * Restores the previous context when this handle is closed.
     */
    @Override
    public void close() {
      applyLoggingContext(previousLoggingContext);
      applySecurityContext(previousSecurityContext);
    }
  }
}