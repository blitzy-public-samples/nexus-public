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
 * Input/Output helpers.
 * 
 * <p>
 * With Java 21, this package leverages Virtual Threads for I/O operations, providing significant
 * performance improvements for I/O-bound tasks. Virtual Threads are lightweight threads that dramatically
 * reduce the effort of writing, maintaining, and debugging high-throughput concurrent applications.
 * </p>
 * 
 * <p>
 * When code running in a Virtual Thread calls a blocking I/O operation, the Java runtime suspends the
 * Virtual Thread until it can be resumed, freeing the OS thread (carrier thread) to perform operations
 * for other Virtual Threads. This cooperative I/O model allows for efficient handling of many concurrent
 * connections without exhausting system resources.
 * </p>
 * 
 * <p>
 * Benefits of Virtual Threads for I/O operations include:
 * <ul>
 *   <li>Ability to handle millions of concurrent I/O operations with minimal resource overhead</li>
 *   <li>Blocking I/O calls don't block actual OS threads, making applications more scalable</li>
 *   <li>Improved throughput for I/O-bound applications</li>
 *   <li>Reduced memory footprint compared to platform threads</li>
 *   <li>Simplified programming model compared to reactive approaches</li>
 * </ul>
 * </p>
 *
 * @since 3.0
 */
package org.sonatype.nexus.common.io;