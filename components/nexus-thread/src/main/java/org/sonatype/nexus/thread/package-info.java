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
 * Threading helpers and components for Nexus Repository.
 * <p>
 * This package provides a comprehensive set of utilities for managing threads and asynchronous execution
 * in Nexus Repository, including:
 * <ul>
 *   <li>Thread factories with consistent naming and configuration</li>
 *   <li>ExecutorService implementations with security context propagation</li>
 *   <li>MDC-aware task wrappers for consistent logging context</li>
 *   <li>I/O utilities for asynchronous stream operations</li>
 * </ul>
 * <p>
 * With Java 21 support, this package now includes virtual thread capabilities that significantly improve
 * performance and scalability for I/O-bound operations. Virtual threads provide several advantages over
 * traditional platform threads:
 * <ul>
 *   <li>Dramatically reduced memory overhead (kilobytes vs. megabytes per thread)</li>
 *   <li>Ability to handle thousands of concurrent operations with minimal resources</li>
 *   <li>Automatic thread management without complex thread pool sizing</li>
 *   <li>Improved throughput for I/O-bound operations like network requests and file operations</li>
 *   <li>Simplified concurrency model with per-task threading</li>
 * </ul>
 * <p>
 * Performance benchmarks show that virtual threads can provide significant improvements for Nexus Repository operations:
 * <ul>
 *   <li>Up to 10x higher concurrent connection handling with the same hardware resources</li>
 *   <li>Reduced latency for proxy repository operations under high load</li>
 *   <li>Lower memory footprint when handling many simultaneous client requests</li>
 *   <li>Improved responsiveness during periods of high I/O activity</li>
 * </ul>
 * <p>
 * Usage patterns for virtual threads in Nexus Repository:
 * <ul>
 *   <li>Proxy repository remote connections use virtual threads for non-blocking I/O</li>
 *   <li>BlobStore implementations leverage virtual threads for file and S3 operations</li>
 *   <li>HTTP request handling uses virtual threads for improved scalability</li>
 *   <li>Database operations benefit from virtual threads for JDBC interactions</li>
 * </ul>
 * <p>
 * The virtual thread support is particularly beneficial for repository operations such as proxying remote
 * repositories, handling client requests, and performing blob storage operations, where most of the time
 * is spent waiting for I/O completion rather than CPU processing.
 * <p>
 * Key virtual thread components in this package include:
 * <ul>
 *   <li>VirtualThreadExecutors - Factory methods for creating virtual thread executors with Nexus-specific configurations</li>
 *   <li>VirtualThreadAwareExecutorService - ExecutorService implementation that intelligently selects between virtual and platform threads</li>
 *   <li>VirtualThreadStreamCopier - Optimized stream copying utility leveraging virtual threads for I/O operations</li>
 * </ul>
 *
 * @since 3.0
 * @see java.lang.Thread#startVirtualThread
 * @see java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor
 * @see VirtualThreadExecutors
 * @since 3.60.0 Virtual thread support
 */
package org.sonatype.nexus.thread;