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

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Helper to simplify boilerplate to begin (capture, set) and restore the current Thread's context-class-loader.
 * This implementation is compatible with both platform threads and Java 21 Virtual Threads.
 *
 * <pre>{@code
 * try (TcclBlock tccl = TcclBlock.begin(newClassLoader)) {
 *   // do something which requires TCCL to be newClassLoader
 * }
 * }</pre>
 *
 * <p>When used with Virtual Threads, this class ensures proper context class loader management
 * without causing memory leaks or thread-local inheritance issues. The try-with-resources pattern
 * guarantees that the original context class loader is restored even when exceptions occur.</p>
 *
 * @since 3.0
 */
public final class TcclBlock
    implements AutoCloseable
{
  private final ClassLoader previous;

  private TcclBlock(final ClassLoader previous) {
    this.previous = previous;
  }

  /**
   * Restore the Thread-context-class-loader to previous.
   * 
   * <p>This method works correctly with both platform threads and virtual threads,
   * ensuring proper cleanup of thread context resources.</p>
   */
  @Override
  public void close() {
    Thread.currentThread().setContextClassLoader(previous);
  }

  /**
   * Set the Thread-context-class-loader to given class-loader and return reference to restore.
   * 
   * <p>This method works with both platform threads and Java 21 Virtual Threads, capturing the
   * current thread's context class loader before setting the new one.</p>
   *
   * @param classLoader the class loader to set as the current thread's context class loader
   * @return a TcclBlock that will restore the original context class loader when closed
   */
  public static TcclBlock begin(final ClassLoader classLoader) {
    checkNotNull(classLoader);

    // capture current thread (works for both platform and virtual threads)
    Thread thread = Thread.currentThread();
    ClassLoader current = thread.getContextClassLoader();

    // set new context class loader
    thread.setContextClassLoader(classLoader);

    return new TcclBlock(current);
  }

  /**
   * Helper to return block using class-loader of given type.
   * 
   * @param type the class whose class loader should be used
   * @return a TcclBlock that will restore the original context class loader when closed
   */
  public static TcclBlock begin(final Class<?> type) {
    checkNotNull(type);
    return begin(type.getClassLoader());
  }

  /**
   * Helper to return block using class-loader of given owner.
   * 
   * @param owner the object whose class's class loader should be used
   * @return a TcclBlock that will restore the original context class loader when closed
   */
  public static TcclBlock begin(final Object owner) {
    checkNotNull(owner);
    return begin(owner.getClass().getClassLoader());
  }
}