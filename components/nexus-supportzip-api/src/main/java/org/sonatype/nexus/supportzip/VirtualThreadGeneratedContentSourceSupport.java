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
package org.sonatype.nexus.supportzip;

import java.io.File;

/**
 * Java 21 Virtual Thread-optimized implementation of {@link GeneratedContentSourceSupport}.
 * 
 * <p>This implementation leverages Java 21 Virtual Threads to improve performance for I/O operations
 * when generating support bundle content. Virtual Threads provide significant advantages for I/O-bound
 * operations by allowing the JVM to efficiently manage thread resources, reducing overhead and improving
 * scalability when handling multiple concurrent file operations.</p>
 *
 * <p>Key benefits of using this implementation include:</p>
 * <ul>
 *   <li>Reduced thread overhead during support ZIP creation</li>
 *   <li>Improved scalability for large support bundles with many content sources</li>
 *   <li>More efficient use of system resources during I/O operations</li>
 *   <li>Better performance when streaming large files</li>
 * </ul>
 *
 * <p>This implementation automatically enables Virtual Thread optimizations by overriding
 * the {@link #useVirtualThreads()} method to return {@code true}, which causes the parent class
 * to use NIO channels with Virtual Thread-friendly I/O operations.</p>
 *
 * @since 3.60
 */
public abstract class VirtualThreadGeneratedContentSourceSupport
    extends GeneratedContentSourceSupport
{
  /**
   * Constructor with type and path.
   *
   * @param type the content type
   * @param path the content path
   */
  public VirtualThreadGeneratedContentSourceSupport(final Type type, final String path) {
    super(type, path);
  }

  /**
   * Constructor with type, path, and priority.
   *
   * @param type     the content type
   * @param path     the content path
   * @param priority the content priority
   * @since 3.60
   */
  public VirtualThreadGeneratedContentSourceSupport(final Type type, final String path, final Priority priority) {
    super(type, path, priority);
  }

  /**
   * Enables Virtual Thread optimizations for file streaming operations.
   * 
   * <p>This implementation always returns {@code true} to enable the use of Virtual Threads
   * and NIO channels for improved I/O performance when streaming content from temporary files.</p>
   *
   * @return {@code true} to enable Virtual Thread optimizations
   */
  @Override
  protected boolean useVirtualThreads() {
    return true;
  }
}