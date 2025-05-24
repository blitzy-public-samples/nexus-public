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
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

import static com.google.common.base.Preconditions.checkState;

/**
 * Support for generated {@link SupportBundle.ContentSource} implementations.
 *
 * These sources will buffer output to a file on prepare.
 *
 * @since 2.7
 */
public abstract class GeneratedContentSourceSupport
    extends ContentSourceSupport
{
  protected File file;

  public GeneratedContentSourceSupport(final Type type, final String path) {
    super(type, path);
  }

  /**
   * @since 3.0
   */
  public GeneratedContentSourceSupport(final Type type, final String path, final Priority priority) {
    super(type, path, priority);
  }

  @Override
  public void prepare() throws Exception {
    checkState(file == null);
    file = File.createTempFile(getPath().replaceAll("/", "-") + "-", ".tmp").getCanonicalFile();
    log.trace("Preparing: {}", file);
    generate(file);
  }

  protected abstract void generate(File file) throws Exception;

  @Override
  public long getSize() {
    checkState(file.exists());
    return file.length();
  }

  /**
   * Determines whether to use Virtual Threads for file streaming operations.
   * 
   * <p>This method can be overridden by subclasses to enable Virtual Thread optimizations
   * for I/O operations. When enabled, the {@link #getContent()} method will use NIO channels
   * with Virtual Thread-friendly I/O operations for improved performance.</p>
   * 
   * <p>The default implementation returns {@code false} for backward compatibility.</p>
   * 
   * @return {@code true} to enable Virtual Thread optimizations, {@code false} otherwise
   * @since 3.60
   */
  protected boolean useVirtualThreads() {
    return false;
  }

  @Override
  public InputStream getContent() throws Exception {
    checkState(file.exists());
    
    if (useVirtualThreads()) {
      // Use NIO channels with Virtual Thread-friendly I/O operations
      // This approach is optimized for Java 21 Virtual Threads
      FileChannel channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);
      return Channels.newInputStream(channel);
    } else {
      // Use traditional I/O for backward compatibility
      return new BufferedInputStream(new FileInputStream(file));
    }
  }

  @Override
  public void cleanup() throws Exception {
    if (file != null) {
      log.trace("Cleaning: {}", file);
      Files.delete(file.toPath());
      file = null;
    }
  }
}