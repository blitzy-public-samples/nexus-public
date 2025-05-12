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
 * Implementations should be aware of thread pinning concerns when using Java 21 Virtual Threads.
 * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier platform thread,
 * which happens in synchronized blocks/methods or when using native methods. This is particularly
 * important for implementations that interact with databases, as JDBC operations and transaction
 * management often involve synchronized code or native methods that can cause thread pinning.
 * <p>
 * Guidelines for implementing thread-safe state storage with virtual threads:
 * <ul>
 *   <li>Prefer using java.util.concurrent locks (e.g., ReentrantLock) instead of synchronized blocks</li>
 *   <li>Be aware that database transactions may require platform threads due to JDBC driver implementations</li>
 *   <li>Consider using thread-local storage carefully, as virtual threads are typically short-lived</li>
 *   <li>Implement {@link #requiresPlatformThread()} to indicate if operations require platform threads</li>
 *   <li>Use the {@link #getState(TaskInfo, boolean)} method to optimize state retrieval based on thread type</li>
 * </ul>
 */
public interface TaskResultStateStore
{
  /**
   * Retrieve a state from the provided {@link TaskInfo}
   */
  Optional<TaskResultState> getState(TaskInfo taskInfo);

  /**
   * Retrieve a state from the provided {@link TaskInfo}, with knowledge of the thread type
   * 
   * @param taskInfo the task info to retrieve state for
   * @param isVirtualThread true if called from a virtual thread, false for platform thread
   * @return the task result state if available
   * @since 3.60
   */
  default Optional<TaskResultState> getState(TaskInfo taskInfo, boolean isVirtualThread) {
    return getState(taskInfo);
  }

  void updateJobDataMap(TaskInfo taskInfo);

  /**
   * Indicates whether this store implementation is supported in the current environment.
   * Implementations should consider thread compatibility when determining support status.
   * 
   * @return true if this store is supported, false otherwise
   */
  default boolean isSupported() {
    return true;
  }
  
  /**
   * Indicates whether this implementation requires a platform thread due to thread pinning concerns.
   * <p>
   * Thread pinning occurs when a virtual thread cannot be unmounted from its carrier platform thread,
   * which happens in synchronized blocks/methods or when using native methods. This is particularly
   * important for implementations that interact with databases, as JDBC operations and transaction
   * management often involve synchronized code or native methods that can cause thread pinning.
   * <p>
   * If this method returns true, the scheduler should use platform threads when executing tasks
   * that use this store implementation.
   * 
   * @return true if platform threads are required, false if virtual threads can be used safely
   * @since 3.60
   */
  default boolean requiresPlatformThread() {
    return false;
  }
}