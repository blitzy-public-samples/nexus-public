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
package org.sonatype.nexus.internal.capability.storage;

import java.util.concurrent.Executor;

import org.sonatype.nexus.datastore.api.IterableDataAccess;

/**
 * {@link CapabilityStorageItemData} access.
 * <p>
 * This interface is designed to be compatible with Java 21 JDBC drivers and supports
 * execution in Virtual Thread contexts. All implementations should ensure that database
 * operations do not block Virtual Threads through synchronized blocks or methods.
 * <p>
 * When used with Virtual Threads, implementations should leverage the unmounting and mounting
 * process during I/O operations to maximize concurrency and throughput without excessive
 * resource consumption.
 *
 * @since 3.21
 * @see java.lang.Thread#startVirtualThread(Runnable)  
 * @see java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()
 */
public interface CapabilityStorageItemDAO
    extends IterableDataAccess<CapabilityStorageItemData>
{
  /**
   * Executes the provided database operation in a Virtual Thread-friendly manner.
   * <p>
   * This method ensures that database operations can be efficiently executed in a Virtual Thread context,
   * allowing the thread to unmount during I/O operations and maximizing concurrency.
   * <p>
   * Example usage with Virtual Threads:
   * <pre>
   * {@code
   * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
   *   dao.executeVirtualThreadOperation(() -> {
   *     // Database operations here
   *     dao.browse();
   *   });
   * }
   * }
   * </pre>
   *
   * @param operation The database operation to execute
   * @since Java 21
   */
  default void executeVirtualThreadOperation(Runnable operation) {
    operation.run();
  }
  
  /**
   * Provides an executor optimized for Virtual Thread execution of database operations.
   * <p>
   * Implementations should return an executor that creates a new Virtual Thread for each task,
   * leveraging Java 21's Virtual Thread capabilities for efficient I/O operations.
   * <p>
   * The returned executor can be used to execute multiple database operations concurrently
   * with minimal resource overhead.
   *
   * @return An executor optimized for Virtual Thread execution
   * @since Java 21
   */
  default Executor getVirtualThreadExecutor() {
    return Thread::startVirtualThread;
  }
}