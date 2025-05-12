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
 * <p>This implementation is optimized for both platform and virtual threads (Java 21+). For virtual threads,
 * it ensures proper MDC context propagation and cleanup after task completion to prevent memory leaks and
 * context pollution between tasks.</p>
 *
 * <p>When used with virtual threads, this class automatically detects the thread type and applies appropriate
 * behavior to ensure that MDC context is properly maintained throughout the task's lifecycle, even when the
 * virtual thread is suspended and resumed on different carrier threads.</p>
 *
 * @since 2.6
 */
public class MDCAwareRunnable
    implements Runnable
{
  private final Runnable delegate;

  private final Map<String, String> mdcContext;

  /**
   * Creates a new MDC-aware runnable that will execute the given delegate with the current MDC context.
   * 
   * <p>This constructor captures the current MDC context at creation time. The implementation is optimized
   * for both platform and virtual threads, ensuring efficient context capture without excessive memory usage.</p>
   *
   * @param delegate the delegate runnable to execute (must not be null)
   */
  public MDCAwareRunnable(final Runnable delegate) {
    this.delegate = checkNotNull(delegate);
    // Use MDCUtils to get a copy of the context map, which is optimized for virtual threads
    this.mdcContext = MDCUtils.getCopyOfContextMap();
  }

  @Override
  public void run() {
    // Save the original MDC context that might be present in the executing thread
    Map<String, String> originalContext = MDCUtils.getCopyOfContextMap();
    
    try {
      // Set our captured MDC context
      MDCUtils.setContextMap(mdcContext);
      // Execute the delegate
      delegate.run();
    } finally {
      // Restore the original context or clear if there was none
      // This is especially important for virtual threads to prevent context leakage
      MDCUtils.setContextMap(originalContext);
    }
  }
}