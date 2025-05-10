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
 * When running on virtual threads, it ensures proper MDC context propagation and cleanup to prevent
 * context leakage between tasks, as virtual threads may be reused by the JVM.
 *
 * @since 2.6
 */
public class MDCAwareCallable<T>
    implements Callable<T>
{
  private final Callable<T> delegate;

  private final Map<String, String> mdcContext;

  /**
   * Creates a new MDC-aware callable that will execute the given delegate with the current MDC context.
   * <p>
   * The MDC context is captured at construction time and will be applied when the callable is executed,
   * regardless of which thread (platform or virtual) executes it.
   *
   * @param delegate the callable to execute with the captured MDC context
   */
  public MDCAwareCallable(final Callable<T> delegate) {
    this.delegate = checkNotNull(delegate);
    this.mdcContext = MDCUtils.getCopyOfContextMap();
  }

  @Override
  public T call() throws Exception {
    // Save the original MDC context to restore after execution if needed
    Map<String, String> originalContext = null;
    
    // For virtual threads, always capture the original context to ensure proper cleanup
    boolean isVirtual = Thread.currentThread().isVirtual();
    if (isVirtual) {
      originalContext = MDCUtils.getCopyOfContextMap();
    }
    
    try {
      // Set the captured MDC context for this execution
      MDCUtils.setContextMap(mdcContext);
      
      // Execute the delegate with the proper MDC context
      return delegate.call();
    }
    finally {
      // For virtual threads, restore the original context to prevent context leakage
      // For platform threads, simply leave the MDC context as is (backward compatible behavior)
      if (isVirtual) {
        MDCUtils.setContextMap(originalContext);
      }
    }
  }
}
