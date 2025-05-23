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
 * Marker annotation that indicates a method or class is designed to be safely executed
 * within Java 21 Virtual Threads.
 * <p>
 * Methods or classes marked with this annotation guarantee that they:
 * <ul>
 *   <li>Do not perform thread-pinning operations (e.g., synchronized blocks/methods)</li>
 *   <li>Do not use thread-local storage in ways that would cause performance issues with virtual threads</li>
 *   <li>Do not call native methods that would block the carrier thread</li>
 *   <li>Are generally suitable for I/O-bound rather than CPU-bound operations</li>
 * </ul>
 * <p>
 * This annotation serves both as documentation and as a potential hook for static analysis tools
 * to verify virtual thread compatibility. It helps identify methods that have been optimized for
 * the Virtual Threads execution model introduced in Java 21.
 * <p>
 * Usage guidelines:
 * <ul>
 *   <li>Apply to I/O-bound methods that would benefit from Virtual Threads' efficiency</li>
 *   <li>Apply to classes where all methods are Virtual Thread compatible</li>
 *   <li>Do not apply to methods that use synchronized blocks or methods</li>
 *   <li>Do not apply to methods that perform heavy CPU computation</li>
 *   <li>Consider carefully when applying to methods that use third-party libraries</li>
 * </ul>
 * <p>
 * Example usage:
 * <pre>
 * {@code
 * @VirtualThreadFriendly
 * public Blob get(BlobId blobId) {
 *     // I/O-bound implementation suitable for Virtual Threads
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