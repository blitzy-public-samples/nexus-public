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
package org.sonatype.nexus.blobstore.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation indicating that a method or class is designed to be safely executed
 * within Java 21 Virtual Threads.
 * <p>
 * Virtual Threads (JEP 444) are lightweight threads that significantly reduce the overhead of
 * managing millions of concurrent operations, particularly for I/O-bound workloads. Methods
 * marked with this annotation are guaranteed not to perform operations that would cause
 * "thread pinning" or use thread-local storage in ways that would degrade Virtual Thread performance.
 * <p>
 * Methods and classes marked with this annotation should adhere to the following guidelines:
 * <ul>
 *   <li>Avoid synchronized blocks or methods on objects that might be contended</li>
 *   <li>Avoid native methods that might block the carrier thread</li>
 *   <li>Avoid operations that pin the thread for extended periods</li>
 *   <li>Use non-blocking I/O operations where possible</li>
 *   <li>Avoid ThreadLocal usage that assumes a long-lived thread identity</li>
 *   <li>Prefer java.util.concurrent non-blocking APIs over blocking alternatives</li>
 * </ul>
 * <p>
 * This annotation serves both as documentation and as a potential hook for static analysis tools
 * to verify virtual thread compatibility. It is particularly useful for I/O-bound operations such as:
 * <ul>
 *   <li>Network operations (HTTP requests, remote repository access)</li>
 *   <li>File system operations (blob storage, file reading/writing)</li>
 *   <li>Database access (JDBC operations with proper configuration)</li>
 *   <li>Any operation that might otherwise block a platform thread</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @VirtualThreadFriendly
 * public InputStream getBlobData(BlobId blobId) {
 *     // Implementation uses non-blocking I/O or properly configured
 *     // operations that work well with Virtual Threads
 *     ...
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface VirtualThreadFriendly {
}