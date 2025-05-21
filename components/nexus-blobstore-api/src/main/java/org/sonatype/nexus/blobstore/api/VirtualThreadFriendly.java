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
 * Annotation indicating that a method is safe to be called from a Virtual Thread.
 * <p>
 * Methods annotated with {@code @VirtualThreadFriendly} are designed to work efficiently with
 * Java 21 Virtual Threads by avoiding operations that would cause thread pinning. This includes:
 * <ul>
 *   <li>Avoiding synchronized blocks or methods for I/O operations</li>
 *   <li>Using non-blocking I/O where possible</li>
 *   <li>Properly handling thread mounting/unmounting during blocking operations</li>
 * </ul>
 * <p>
 * Implementations of methods marked with this annotation should ensure they don't perform
 * operations that would cause a Virtual Thread to be pinned to its carrier thread, such as:
 * <ul>
 *   <li>Using synchronized blocks around I/O operations</li>
 *   <li>Calling native methods that block</li>
 *   <li>Using third-party libraries that aren't Virtual Thread aware</li>
 * </ul>
 *
 * @since 3.60
 */
@Documented
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface VirtualThreadFriendly {
}