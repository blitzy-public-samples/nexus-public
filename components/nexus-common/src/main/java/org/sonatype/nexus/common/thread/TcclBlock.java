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
 * This class is compatible with both platform threads and virtual threads in Java 21+.
 *
 * <pre>{@code
 * try (TcclBlock tccl = TcclBlock.begin(newClassLoader)) {
 *   // do something which requires TCCL to be newClassLoader
 * }
 * }</pre>
 *
 * @since 3.0
 */
public final class TcclBlock
    implements AutoCloseable
{
  private final ClassLoader previous;
  private final Thread thread;

  private TcclBlock(final Thread thread, final ClassLoader previous) {
    this.thread = thread;
    this.previous = previous;
  }

  /**
   * Restore the Thread-context-class-loader to previous.
   * This method properly handles context restoration for both platform and virtual threads.
   */
  @Override
  public void close() {
    // Use the captured thread reference to ensure we're restoring the TCCL on the same thread
    // that it was captured from, which works for both platform and virtual threads
    thread.setContextClassLoader(previous);
  }

  /**
   * Set the Thread-context-class-loader to given class-loader and return reference to restore.
   * This method works with both platform and virtual threads.
   */
  public static TcclBlock begin(final ClassLoader classLoader) {
    checkNotNull(classLoader);

    // capture current thread - works for both platform and virtual threads
    Thread thread = Thread.currentThread();
    ClassLoader current = thread.getContextClassLoader();

    // set new context class loader
    thread.setContextClassLoader(classLoader);

    // store both the thread and previous class loader for proper restoration
    return new TcclBlock(thread, current);
  }

  /**
   * Helper to return block using class-loader of given type.
   * This method works with both platform and virtual threads.
   */
  public static TcclBlock begin(final Class<?> type) {
    checkNotNull(type);
    return begin(type.getClassLoader());
  }

  /**
   * Helper to return block using class-loader of given owner.
   * This method works with both platform and virtual threads.
   */
  public static TcclBlock begin(final Object owner) {
    checkNotNull(owner);
    return begin(owner.getClass().getClassLoader());
  }
}