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
package org.sonatype.nexus.repository.content.internal;

import java.sql.Connection;

/**
 * Interface for checking and repairing database integrity issues.
 * 
 * <p>Implementations of this interface are responsible for verifying the integrity of database
 * structures and performing any necessary repairs to maintain data consistency.</p>
 * 
 * <p>With Java 21, implementations may leverage Virtual Threads for improved performance when
 * performing I/O-bound database operations. Virtual Threads are particularly well-suited for
 * database integrity checks that involve multiple queries or updates, as they allow for high
 * concurrency with minimal resource overhead.</p>
 * 
 * <h3>Transaction Handling with Virtual Threads</h3>
 * 
 * <p>When using Virtual Threads for database operations, implementations should be aware of the
 * following transaction handling requirements:</p>
 * 
 * <ul>
 *   <li>Each Virtual Thread should manage its own transaction scope to prevent transaction leakage
 *       between threads.</li>
 *   <li>Long-running transactions should be avoided as they may lead to database contention.</li>
 *   <li>Consider breaking large operations into smaller transactional units when possible.</li>
 *   <li>Ensure proper exception handling to maintain transaction integrity across Virtual Threads.</li>
 * </ul>
 * 
 * <h3>Avoiding Thread Pinning</h3>
 * 
 * <p>To maximize the benefits of Virtual Threads, implementations should avoid operations that
 * cause thread pinning, such as:</p>
 * 
 * <ul>
 *   <li>Using synchronized blocks or methods when performing database operations.</li>
 *   <li>Calling native methods that block the carrier thread.</li>
 *   <li>Holding locks across I/O operations.</li>
 * </ul>
 * 
 * <p>Instead, prefer using java.util.concurrent.locks.ReentrantLock or other explicit locking
 * mechanisms that don't pin Virtual Threads to their carriers.</p>
 * 
 * <h3>Implementation Considerations</h3>
 * 
 * <p>When implementing this interface:</p>
 * 
 * <ul>
 *   <li>Use structured concurrency patterns (e.g., try-with-resources with ExecutorService) to
 *       manage the lifecycle of Virtual Threads.</li>
 *   <li>Consider using Executors.newVirtualThreadPerTaskExecutor() for spawning Virtual Threads.</li>
 *   <li>Be mindful of database connection pool sizing, as Virtual Threads make it easier to
 *       overwhelm connection pools.</li>
 *   <li>Implement appropriate monitoring and metrics to track the performance of integrity
 *       checking operations.</li>
 * </ul>
 */
public interface DatabaseIntegrityChecker
{
  /**
   * Checks and repairs database integrity issues using the provided connection.
   * 
   * <p>This method should perform a comprehensive check of database integrity and
   * apply any necessary repairs to maintain data consistency. When implemented using
   * Virtual Threads, this method can efficiently handle multiple concurrent integrity
   * checks with minimal resource overhead.</p>
   * 
   * <p>Implementations should ensure proper transaction handling, especially when using
   * Virtual Threads, to prevent data corruption or inconsistency.</p>
   *
   * @param connection the database connection to use for integrity checking and repair
   * @throws Exception if an error occurs during the integrity check or repair process
   */
  void checkAndRepair(Connection connection) throws Exception;
}