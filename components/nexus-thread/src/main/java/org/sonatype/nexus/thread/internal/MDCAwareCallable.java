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
import java.util.concurrent.Callable;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Callable that properly sets MDC context before invoking the delegate. The delegate will execute in a
 * managed thread with properly set MDC context. To be used with managed threads.
 * <p>
 * This implementation is compatible with both platform threads and virtual threads (Java 21+).
 * When running on virtual threads, it ensures proper MDC context propagation during thread unmounting
 * and remounting operations, and performs proper cleanup after task completion.
 *
 * @since 2.6
 */
public class MDCAwareCallable<T>
    implements Callable<T>
{
  private final Callable<T> delegate;

  private final Map<String, String> mdcContext;

  /**
   * Creates a new MDC-aware callable that will execute the given delegate with the MDC context
   * captured at construction time.
   *
   * @param delegate the callable to execute with MDC context, must not be null
   */
  public MDCAwareCallable(final Callable<T> delegate) {
    this.delegate = checkNotNull(delegate);
    // Capture the current MDC context at construction time
    this.mdcContext = MDCUtils.getCopyOfContextMap();
  }

  /**
   * Checks if the current thread is a virtual thread (Java 21+).
   * This method uses reflection to avoid direct dependencies on Java 21 APIs.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  private boolean isVirtualThread() {
    try {
      // Thread.isVirtual() method was added in Java 21
      return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
    }
    catch (Exception e) {
      // We're running on a Java version that doesn't support virtual threads
      return false;
    }
  }

  @Override
  public T call() throws Exception {
    // Store the original MDC context that might exist in the executing thread
    Map<String, String> originalMdcContext = MDCUtils.getCopyOfContextMap();
    
    try {
      // Set our captured MDC context
      MDCUtils.setContextMap(mdcContext);
      
      // Execute the delegate with our MDC context
      return delegate.call();
    }
    finally {
      // Always clean up MDC context to prevent memory leaks, especially important for virtual threads
      // which are not reused like platform threads in thread pools
      if (isVirtualThread()) {
        // For virtual threads, we always clear the context to prevent memory leaks
        // since virtual threads are not reused
        MDCUtils.clearContext();
      }
      else {
        // For platform threads, restore the original context if it existed
        if (originalMdcContext != null) {
          MDCUtils.setContextMap(originalMdcContext);
        }
        else {
          MDCUtils.clearContext();
        }
      }
    }
  }
}