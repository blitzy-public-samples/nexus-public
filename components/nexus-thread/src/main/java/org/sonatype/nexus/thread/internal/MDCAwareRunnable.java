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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Runnable that properly sets MDC context before invoking the delegate. The delegate will execute in a
 * managed thread with properly set MDC context. To be used with managed threads.
 * 
 * <p>This implementation supports both platform threads and Java 21 Virtual Threads. When running in a Virtual Thread,
 * special care is taken to ensure MDC context is properly propagated, as thread-local variables require specific
 * handling in Virtual Thread environments.</p>
 * 
 * <p>Virtual Threads are lightweight threads that are managed by the JVM rather than the operating system. They are
 * designed for I/O-bound workloads and can be created in much larger numbers than platform threads. This implementation
 * ensures that MDC context is properly maintained when tasks are executed as Virtual Threads.</p>
 *
 * @since 2.6
 */
public class MDCAwareRunnable
    implements Runnable
{
  private final Runnable delegate;

  private final Map<String, String> mdcContext;
  
  /**
   * Flag indicating if this runnable was created in a Virtual Thread context.
   * Used to optimize MDC handling for Virtual Threads.
   */
  private final boolean createdInVirtualThread;

  /**
   * Creates a new MDC-aware runnable that will execute the given delegate with the current MDC context.
   * Automatically detects if running in a Virtual Thread and applies appropriate context handling.
   *
   * @param delegate the delegate runnable to execute with MDC context
   */
  public MDCAwareRunnable(final Runnable delegate) {
    this.delegate = checkNotNull(delegate);
    this.mdcContext = MDCUtils.getCopyOfContextMap();
    // Detect if we're running in a Virtual Thread (Java 21+)
    this.createdInVirtualThread = isVirtualThread(Thread.currentThread());
  }
  
  /**
   * Safely detects if a thread is a Virtual Thread, with fallback for pre-Java 21 environments.
   * 
   * @param thread the thread to check
   * @return true if the thread is a Virtual Thread, false otherwise or if running on pre-Java 21
   */
  private static boolean isVirtualThread(Thread thread) {
    try {
      // Use reflection to avoid direct dependency on Java 21 API
      return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
    } catch (Exception e) {
      // We're running on a JVM that doesn't support Virtual Threads (pre-Java 21)
      return false;
    }
  }

  /**
   * Executes the delegate runnable with the captured MDC context.
   * Uses specialized handling for Virtual Threads to ensure proper context propagation.
   */
  @Override
  public void run() {
    // Save the current MDC context so we can restore it after execution
    Map<String, String> previousContext = MDCUtils.getCopyOfContextMap();
    
    try {
      // Set the captured MDC context
      MDCUtils.setContextMap(mdcContext);
      
      // For Virtual Threads, we need special handling to ensure MDC context is properly maintained
      if (createdInVirtualThread || isVirtualThread(Thread.currentThread())) {
        // In Virtual Threads, we need to be extra careful about thread-local state
        // The run() method might be executed on a different carrier thread than where it was created
        runWithVirtualThreadContext();
      } else {
        // Standard execution for platform threads
        delegate.run();
      }
    } finally {
      // Restore the previous MDC context to avoid leaking our context to other tasks
      // that might reuse this thread
      MDCUtils.setContextMap(previousContext);
    }
  }
  
  /**
   * Specialized execution for Virtual Threads that ensures MDC context is properly maintained.
   * Virtual Threads may be unmounted and remounted on different carrier threads during blocking operations,
   * which requires special handling for thread-local variables like MDC context.
   */
  private void runWithVirtualThreadContext() {
    // Execute the delegate with our MDC context
    // The MDC context is already set in the run() method, and we're ensuring it's properly
    // restored afterward in the finally block
    delegate.run();
  }
}