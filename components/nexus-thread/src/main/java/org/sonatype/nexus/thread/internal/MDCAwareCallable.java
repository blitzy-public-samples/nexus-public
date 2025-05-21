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
 * This implementation supports both platform threads and Java 21 Virtual Threads. When used with Virtual Threads,
 * it ensures that MDC context is properly propagated across thread boundaries and cleaned up after execution
 * to prevent memory leaks.
 * <p>
 * Note that Virtual Threads have different thread-local variable behavior compared to platform threads.
 * This implementation handles these differences to ensure consistent MDC context propagation regardless
 * of the thread type.
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
   * regardless of whether it runs on a platform thread or a virtual thread.
   *
   * @param delegate the callable to execute with the captured MDC context
   */
  public MDCAwareCallable(final Callable<T> delegate) {
    this.delegate = checkNotNull(delegate);
    // Use getContextMapForPropagation which is optimized for both platform and virtual threads
    this.mdcContext = MDCUtils.getContextMapForPropagation();
  }

  @Override
  public T call() throws Exception {
    // Apply the captured MDC context
    MDCUtils.applyContextMap(mdcContext);
    try {
      // Execute the delegate with the applied MDC context
      return delegate.call();
    }
    finally {
      // Clean up MDC context to prevent memory leaks, especially important for virtual threads
      MDCUtils.clearContext();
    }
  }
}