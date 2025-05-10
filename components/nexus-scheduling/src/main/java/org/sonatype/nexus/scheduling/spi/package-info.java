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

/**
 * Scheduling service provider support.
 *
 * <p>
 * The scheduling SPI provides interfaces and support classes for implementing task scheduling services.
 * These services manage the execution of tasks within the Nexus Repository system, handling concerns such
 * as scheduling, execution, state management, and resource allocation.
 * </p>
 *
 * <h2>Java 21 Virtual Threads Support</h2>
 *
 * <p>
 * As of version 3.60, the scheduling system supports Java 21 Virtual Threads for improved concurrency and
 * resource utilization. Virtual Threads provide a lightweight threading model that enables high-throughput
 * concurrent task execution with minimal overhead compared to traditional platform threads.
 * </p>
 *
 * <h3>Thread Selection Strategies</h3>
 *
 * <p>
 * The scheduling system supports multiple thread selection strategies for task execution:
 * </p>
 *
 * <ul>
 * <li><b>Virtual Threads</b>: Recommended for I/O-bound tasks such as repository operations, network transfers,
 *     and file system operations. Virtual Threads provide high concurrency with minimal resource overhead.</li>
 * <li><b>Platform Threads</b>: Recommended for CPU-intensive tasks that perform significant computation
 *     with minimal I/O operations. Platform threads are managed through traditional thread pools.</li>
 * <li><b>Hybrid Approach</b>: Some tasks may benefit from a combination, where the main task runs on a platform
 *     thread but delegates I/O operations to virtual threads.</li>
 * </ul>
 *
 * <p>
 * Task implementations can specify their preferred thread type through the TaskSupport class or by implementing
 * the appropriate interfaces. The scheduler will respect these preferences when allocating execution resources.
 * </p>
 *
 * <h3>Thread Pinning Considerations</h3>
 *
 * <p>
 * When using Virtual Threads with database operations, developers should be aware of thread pinning concerns:
 * </p>
 *
 * <ul>
 * <li><b>Thread Pinning</b>: Virtual Threads may become "pinned" to carrier threads during blocking operations
 *     that use synchronized blocks or methods, preventing the runtime from optimizing thread utilization.</li>
 * <li><b>Database Operations</b>: JDBC operations may cause thread pinning with some drivers. The scheduling system
 *     uses compatible JDBC drivers that minimize pinning, but custom database operations should be carefully designed.</li>
 * <li><b>Transaction Boundaries</b>: Long-running transactions should be structured to minimize the duration of
 *     database locks and consider using smaller transaction boundaries where appropriate.</li>
 * <li><b>Detection</b>: Thread pinning can be detected using the JDK's built-in monitoring with
 *     <code>-Djdk.tracePinnedThreads=full</code> JVM flag during development and testing.</li>
 * </ul>
 *
 * <p>
 * For optimal performance with Virtual Threads, task implementations should:
 * </p>
 *
 * <ul>
 * <li>Minimize use of synchronized blocks in favor of java.util.concurrent classes</li>
 * <li>Structure code to release database connections promptly after use</li>
 * <li>Use non-blocking I/O operations where possible</li>
 * <li>Consider chunking large operations into smaller units of work</li>
 * </ul>
 *
 * @since 3.0
 * @see java.lang.Thread#startVirtualThread
 * @see java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor
 */
package org.sonatype.nexus.scheduling.spi;