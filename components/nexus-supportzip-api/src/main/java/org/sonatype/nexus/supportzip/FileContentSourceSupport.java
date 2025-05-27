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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Support for including existing files as {@link SupportBundle.ContentSource}.
 *
 * @since 2.7
 */
public class FileContentSourceSupport
    extends ContentSourceSupport
{
  protected final File file;

  /**
   * @since 3.0
   */
  public FileContentSourceSupport(final Type type, final String path, final File file, final Priority priority) {
    super(type, path, priority);
    this.file = checkNotNull(file);
  }

  public FileContentSourceSupport(final Type type, final String path, final File file) {
    this(type, path, file, Priority.DEFAULT);
  }

  @Override
  public void prepare() throws Exception {
    checkState(file.exists());
  }

  @Override
  public long getSize() {
    checkState(file.exists());
    return file.length();
  }

  /**
   * Gets the content of the file using Java 21 Virtual Threads for improved I/O performance.
   * 
   * Virtual Threads are lightweight threads that are particularly well-suited for I/O-bound operations.
   * When a virtual thread performs a blocking I/O operation (like reading from a file), it gets suspended
   * and doesn't block the underlying OS thread (carrier thread), allowing the carrier thread to be used
   * for other tasks. This results in better resource utilization and improved performance in high-concurrency
   * scenarios.
   *
   * @return An InputStream containing the file content
   * @throws Exception if an error occurs during file reading
   * @since 3.60
   */
  @Override
  public InputStream getContent() throws Exception {
    checkState(file.exists());
    log.debug("Reading: {}", file);
    
    // For small files or when virtual threads aren't available, use the direct approach
    if (file.length() < 1024 * 1024) { // 1MB threshold
      return new BufferedInputStream(new FileInputStream(file));
    }
    
    // For larger files, use virtual threads to handle the I/O operation
    try {
      // Create piped streams to transfer data between threads
      PipedInputStream inputStream = new PipedInputStream(8192);
      PipedOutputStream outputStream = new PipedOutputStream(inputStream);
      
      // Use a virtual thread to read the file and write to the pipe
      Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
        try (FileInputStream fileIn = new FileInputStream(file);
             BufferedInputStream bufferedIn = new BufferedInputStream(fileIn)) {
          byte[] buffer = new byte[8192];
          int bytesRead;
          while ((bytesRead = bufferedIn.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
          }
        } catch (Exception e) {
          log.error("Error reading file with virtual thread: {}", file, e);
        } finally {
          try {
            outputStream.close();
          } catch (Exception e) {
            log.debug("Error closing output stream", e);
          }
        }
      });
      
      return new BufferedInputStream(inputStream);
    } catch (UnsupportedOperationException e) {
      // Fall back to direct approach if virtual threads aren't available
      log.debug("Virtual threads not available, falling back to direct file access", e);
      return new BufferedInputStream(new FileInputStream(file));
    }
  }

  @Override
  public void cleanup() throws Exception {
    // nothing
  }
}