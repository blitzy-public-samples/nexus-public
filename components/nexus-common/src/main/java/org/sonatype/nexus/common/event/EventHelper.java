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
package org.sonatype.nexus.common.event;

import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;
import static java.lang.Boolean.TRUE;

/**
 * Event helpers.
 *
 * @since 3.2
 */
public class EventHelper
{
  /**
   * ThreadLocal variable to track replication state.
   * 
   * <p>Note on Java 21 Virtual Threads: ThreadLocal variables are inherited when a virtual thread
   * is created, but they have their own independent copies. This means that if a virtual thread is
   * created during replication, it will not automatically inherit the replication state from its
   * parent thread. When using virtual threads with this class, ensure that you explicitly set the
   * replication state in each virtual thread context as needed.</p>
   * 
   * <p>Future enhancement: Consider migrating to ScopedValue when it becomes a standard API
   * (non-preview) in future Java versions, as it's specifically designed for sharing immutable
   * values across virtual threads with better performance characteristics.</p>
   */
  private static final ThreadLocal<Boolean> isReplicating = new ThreadLocal<>();

  private EventHelper() {
    // empty
  }

  /**
   * Is this thread currently replicating remote events from another node?
   * 
   * <p>When using with Java 21 Virtual Threads, be aware that each virtual thread has its own
   * independent ThreadLocal values. If you create a new virtual thread during replication,
   * you must explicitly propagate the replication state to that thread using {@link #asReplicating}.</p>
   * 
   * @return true if the current thread is replicating events, false otherwise
   */
  public static boolean isReplicating() {
    return TRUE.equals(isReplicating.get());
  }

  /**
   * Calls the given {@link Supplier} while flagged as replicating remote events.
   * 
   * <p>This method ensures proper cleanup of the ThreadLocal value after execution, which is
   * especially important when using with Java 21 Virtual Threads to prevent memory leaks.</p>
   * 
   * <p>When using with virtual threads, this method should be used to wrap any code that needs
   * to run in a replication context, including code that spawns new virtual threads that need
   * to inherit the replication state.</p>
   * 
   * @param supplier the supplier to execute in replication context
   * @return the result of the supplier
   * @throws IllegalStateException if replication is already in progress on this thread
   */
  public static <T> T asReplicating(final Supplier<T> supplier) {
    checkState(!isReplicating(), "Replication already in progress");
    isReplicating.set(TRUE);
    try {
      return supplier.get();
    }
    finally {
      isReplicating.remove();
    }
  }

  /**
   * Calls the given {@link Runnable} while flagged as replicating remote events.
   * 
   * <p>This method ensures proper cleanup of the ThreadLocal value after execution, which is
   * especially important when using with Java 21 Virtual Threads to prevent memory leaks.</p>
   * 
   * <p>When using with virtual threads, this method should be used to wrap any code that needs
   * to run in a replication context, including code that spawns new virtual threads that need
   * to inherit the replication state.</p>
   * 
   * @param runnable the runnable to execute in replication context
   * @throws IllegalStateException if replication is already in progress on this thread
   */
  public static void asReplicating(final Runnable runnable) {
    checkState(!isReplicating(), "Replication already in progress");
    isReplicating.set(TRUE);
    try {
      runnable.run();
    }
    finally {
      isReplicating.remove();
    }
  }
}