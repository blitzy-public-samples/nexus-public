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
package org.sonatype.nexus.scheduling.spi;

import java.util.Optional;

import org.sonatype.nexus.scheduling.TaskInfo;

/**
 * Store for persisted Task state
 * <p>
 * Implementations should be aware of thread compatibility concerns, especially when using Java 21 Virtual Threads.
 * Database operations that involve transactions may experience thread pinning, where a Virtual Thread becomes
 * temporarily bound to its carrier thread during the transaction. This can reduce the concurrency benefits
 * of Virtual Threads if many operations are pinned simultaneously.
 * <p>
 * Guidelines for implementing thread-safe state storage with Virtual Threads:
 * <ul>
 *   <li>Keep database transactions as short as possible to minimize pinning duration</li>
 *   <li>Consider using non-blocking database drivers where available</li>
 *   <li>Implement {@link #requiresPlatformThread()} to indicate if operations must use platform threads</li>
 *   <li>For read-heavy operations, consider using Virtual Threads for better scalability</li>
 *   <li>For write operations that require transactions, consider the trade-offs between platform and virtual threads</li>
 * </ul>
 */
public interface TaskResultStateStore
{
  /**
   * Retrieve a state from the provided {@link TaskInfo}
   */
  Optional<TaskResultState> getState(TaskInfo taskInfo);

  /**
   * Retrieve a state from the provided {@link TaskInfo}, with explicit thread type specification.
   * <p>
   * This overload allows callers to specify whether they're using a virtual thread, which can help
   * implementations optimize their behavior based on the thread context.
   *
   * @param taskInfo the task information to retrieve state for
   * @param isVirtualThread true if called from a virtual thread, false for platform threads
   * @return the task result state if available
   * @since 3.60
   */
  default Optional<TaskResultState> getState(TaskInfo taskInfo, boolean isVirtualThread) {
    return getState(taskInfo);
  }

  void updateJobDataMap(TaskInfo taskInfo);

  /**
   * Indicates whether this state store implementation is supported in the current environment.
   * <p>
   * Implementations should consider thread compatibility when determining support status,
   * especially when running with Java 21 Virtual Threads.
   *
   * @return true if this state store is supported, false otherwise
   */
  default boolean isSupported() {
    return true;
  }
  
  /**
   * Indicates whether this state store implementation requires platform threads for its operations.
   * <p>
   * Some implementations may use database transactions or other operations that can cause thread pinning
   * when used with Virtual Threads. In such cases, using platform threads may be more efficient.
   * <p>
   * If this method returns true, callers should consider using platform threads when interacting with this store.
   * If false, Virtual Threads can be safely used for potentially better scalability with I/O operations.
   *
   * @return true if platform threads are required, false if virtual threads can be used safely
   * @since 3.60
   */
  default boolean requiresPlatformThread() {
    return false;
  }
}
