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
 * Threading helpers and components.
 *
 * <h2>Virtual Threads in Java 21</h2>
 * 
 * <p>Starting with Java 21, this package supports Virtual Threads, a lightweight threading implementation
 * that significantly improves scalability for I/O-bound operations. Virtual Threads are managed by the JVM rather
 * than the operating system, allowing for millions of concurrent threads with minimal overhead.</p>
 *
 * <h3>Virtual Threads vs Platform Threads</h3>
 * 
 * <p>The Nexus Repository Manager uses two types of threads:</p>
 * <ul>
 *   <li><b>Platform Threads</b>: Traditional OS-backed threads with a 1:1 mapping to OS threads. These are suitable for
 *       CPU-intensive operations but have higher memory overhead (~2MB per thread).</li>
 *   <li><b>Virtual Threads</b>: Lightweight JVM-managed threads with a many-to-few mapping to OS threads (carrier threads).
 *       These are ideal for I/O-bound operations and have minimal memory overhead.</li>
 * </ul>
 *
 * <h3>Usage Patterns and Best Practices</h3>
 * 
 * <p>When to use each thread type:</p>
 * <ul>
 *   <li>Use <b>Virtual Threads</b> for I/O-bound operations such as:</li>
 *   <ul>
 *     <li>Network operations (HTTP requests, remote repository access)</li>
 *     <li>File system operations (BlobStore access)</li>
 *     <li>Database operations (JDBC queries)</li>
 *   </ul>
 *   <li>Use <b>Platform Threads</b> for CPU-intensive operations such as:</li>
 *   <ul>
 *     <li>Computation-heavy tasks</li>
 *     <li>Data processing</li>
 *     <li>Operations using synchronized blocks extensively</li>
 *   </ul>
 * </ul>
 *
 * <p>Creating Virtual Threads:</p>
 * <pre>
 * // Using Thread.Builder API
 * Thread vThread = Thread.ofVirtual()
 *     .name("task-", 1)
 *     .start(() -> performTask());
 *
 * // Using ExecutorService
 * try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
 *     executor.submit(() -> performTask());
 * }
 * </pre>
 *
 * <h3>Performance Characteristics</h3>
 * 
 * <p>Virtual Threads provide several performance benefits:</p>
 * <ul>
 *   <li>Significantly higher throughput for I/O-bound operations</li>
 *   <li>Reduced memory footprint compared to platform threads</li>
 *   <li>Automatic yielding during blocking operations</li>
 *   <li>No need for thread pooling or complex executor configurations</li>
 * </ul>
 *
 * <p><b>Important:</b> Virtual Threads can be "pinned" to their carrier thread in certain situations, preventing
 * the carrier from being used by other virtual threads:</p>
 * <ul>
 *   <li>When executing code inside synchronized blocks or methods</li>
 *   <li>When executing native methods or foreign functions</li>
 * </ul>
 *
 * <h3>Resource Management</h3>
 * 
 * <p>Unlike platform threads, Virtual Threads are cheap to create and don't require pooling. However, they still
 * consume other resources:</p>
 * <ul>
 *   <li>Use explicit throttling mechanisms (e.g., Semaphore) to limit concurrent resource usage</li>
 *   <li>Monitor Virtual Thread creation and completion rates</li>
 *   <li>Be aware of downstream resource constraints (database connections, network sockets)</li>
 * </ul>
 *
 * <h3>Monitoring Virtual Threads</h3>
 * 
 * <p>Virtual Threads can be monitored using:</p>
 * <ul>
 *   <li>JDK Flight Recorder (JFR) events</li>
 *   <li>JMX metrics</li>
 *   <li>Thread dumps (jcmd &lt;pid&gt; Thread.dump_to_file -format=json)</li>
 *   <li>Java Mission Control (JMC)</li>
 * </ul>
 *
 * <h3>Migration Guidelines</h3>
 * 
 * <p>When migrating from platform threads to Virtual Threads:</p>
 * <ul>
 *   <li>Replace thread pools with virtual thread per task executors</li>
 *   <li>Minimize use of synchronized blocks in favor of java.util.concurrent locks</li>
 *   <li>Add explicit resource throttling where needed</li>
 *   <li>Test thoroughly under load to identify potential pinning issues</li>
 *   <li>Verify compatibility with third-party libraries</li>
 * </ul>
 *
 * @since 3.0
 * @since 3.60 Added support for Java 21 Virtual Threads
 */
package org.sonatype.nexus.thread;