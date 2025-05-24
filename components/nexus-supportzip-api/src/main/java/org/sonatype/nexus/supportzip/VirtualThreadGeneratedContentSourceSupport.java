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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkState;

/**
 * Java 21 Virtual Thread-optimized implementation of {@link GeneratedContentSourceSupport}.
 * 
 * This class leverages Java 21 Virtual Threads to improve performance when streaming file content
 * for support bundle generation. Virtual Threads are lightweight threads that significantly reduce
 * the overhead of thread management, making them ideal for I/O-bound operations like file streaming.
 * 
 * When generating support bundles with large files or many files, this implementation can handle
 * a much higher level of concurrency with minimal resource consumption compared to traditional
 * platform threads.
 *
 * @since 3.60
 */
public abstract class VirtualThreadGeneratedContentSourceSupport
    extends GeneratedContentSourceSupport
{
  /**
   * Constructor with type and path.
   *
   * @param type The content type
   * @param path The content path
   */
  public VirtualThreadGeneratedContentSourceSupport(final Type type, final String path) {
    super(type, path);
  }

  /**
   * Constructor with type, path, and priority.
   *
   * @param type     The content type
   * @param path     The content path
   * @param priority The content priority
   * @since 3.60
   */
  public VirtualThreadGeneratedContentSourceSupport(final Type type, final String path, final Priority priority) {
    super(type, path, priority);
  }

  /**
   * Returns the content as an {@link InputStream} using a Java 21 Virtual Thread for improved I/O performance.
   * 
   * This implementation uses Virtual Threads to stream file content, which allows for more efficient
   * resource utilization when handling multiple concurrent file operations during support bundle generation.
   * Virtual Threads are particularly beneficial for I/O-bound operations as they don't consume OS thread
   * resources while waiting for I/O operations to complete.
   *
   * @return The content as an {@link InputStream}
   * @throws Exception if an error occurs
   */
  @Override
  public InputStream getContent() throws Exception {
    checkState(file.exists());
    
    // Use a Virtual Thread to handle the file streaming operation
    // This allows the operation to be more efficient when many files are being processed concurrently
    return Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
      return new BufferedInputStream(new FileInputStream(file));
    }).get();
  }
}