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
 * Annotation indicating that a method is designed to be safely executed within a Java 21 Virtual Thread.
 * <p>
 * Methods marked with this annotation are guaranteed to:
 * <ul>
 *   <li>Avoid thread pinning operations that would block the carrier thread</li>
 *   <li>Use non-blocking I/O operations where possible</li>
 *   <li>Minimize synchronized blocks that could cause carrier thread blocking</li>
 *   <li>Be safe for high-concurrency execution with thousands of virtual threads</li>
 * </ul>
 * <p>
 * This annotation serves both as documentation and as a marker for static analysis tools
 * to verify virtual thread compatibility.
 *
 * @since 3.60
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface VirtualThreadFriendly {
}