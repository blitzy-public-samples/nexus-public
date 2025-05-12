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
 * Threading helpers and components for Nexus Repository Manager.
 * <p>
 * This package provides a comprehensive threading framework that includes:
 * <ul>
 *   <li>Thread factories and executors with consistent naming, lifecycle management, and security context propagation</li>
 *   <li>MDC-aware task wrappers to ensure logging context is maintained across thread boundaries</li>
 *   <li>Database-aware execution services that respect system writability state</li>
 *   <li>I/O utilities for asynchronous stream operations</li>
 *   <li>Virtual thread support for improved scalability of I/O-bound operations</li>
 * </ul>
 * <p>
 * With Java 21 virtual threads integration, this package enables highly scalable concurrent processing
 * for I/O-bound operations such as repository access, blob storage, and network communications. Virtual threads
 * provide significant advantages over traditional platform threads:
 * <ul>
 *   <li>Lightweight resource usage allowing millions of concurrent threads</li>
 *   <li>Automatic unmounting from carrier threads during blocking I/O operations</li>
 *   <li>Simplified programming model compared to reactive approaches</li>
 *   <li>Improved throughput for I/O-intensive workloads</li>
 *   <li>Better resource utilization across the system</li>
 * </ul>
 * <p>
 * Key components for virtual thread support include:
 * <ul>
 *   <li>Thread factories that can create either platform or virtual threads based on workload characteristics</li>
 *   <li>ExecutorService implementations optimized for virtual threads</li>
 *   <li>I/O utilities that leverage virtual threads for non-blocking behavior while maintaining synchronous APIs</li>
 * </ul>
 * <p>
 * For optimal performance with virtual threads, prefer using them for I/O-bound operations (network calls,
 * file system access, database queries) rather than CPU-intensive tasks. Also avoid using synchronized blocks
 * around I/O operations as this can cause thread pinning, which prevents virtual threads from unmounting.
 *
 * @since 3.0
 * @see java.lang.Thread#startVirtualThread(Runnable)
 * @see java.lang.Thread#ofVirtual()
 */
package org.sonatype.nexus.thread;