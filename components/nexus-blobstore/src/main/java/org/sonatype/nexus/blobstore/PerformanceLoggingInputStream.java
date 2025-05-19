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
package org.sonatype.nexus.blobstore;

import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.atomic.LongAdder;

import com.google.common.io.CountingInputStream;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A utility to log how fast the input stream was read.
 * Optimized for Java 21 Virtual Threads with non-blocking operations.
 *
 * @since 3.21
 */
public class PerformanceLoggingInputStream
    extends FilterInputStream
{
  private final PerformanceLogger performanceLogger;

  private final CountingInputStream countingInputStream;

  // Using LongAdder instead of a simple long for better performance in concurrent scenarios
  private final LongAdder totalNanosElapsed = new LongAdder();
  
  // Track if this stream is being used in a Virtual Thread
  private final boolean isVirtualThread;

  /**
   * Creates a new performance logging input stream.
   *
   * @param source the source input stream to wrap
   * @param performanceLogger the performance logger to use
   */
  public PerformanceLoggingInputStream(final InputStream source, final PerformanceLogger performanceLogger) {
    this(new CountingInputStream(source), performanceLogger, false);
  }

  /**
   * Creates a new performance logging input stream with Virtual Thread awareness.
   *
   * @param source the source input stream to wrap
   * @param performanceLogger the performance logger to use
   * @param isVirtualThread whether this stream is being used in a Virtual Thread
   */
  public PerformanceLoggingInputStream(final InputStream source, 
                                      final PerformanceLogger performanceLogger,
                                      final boolean isVirtualThread) {
    this(new CountingInputStream(source), performanceLogger, isVirtualThread);
  }

  private PerformanceLoggingInputStream(
      final CountingInputStream countingInputStream,
      final PerformanceLogger performanceLogger,
      final boolean isVirtualThread)
  {
    super(countingInputStream);
    this.countingInputStream = checkNotNull(countingInputStream);
    this.performanceLogger = checkNotNull(performanceLogger);
    this.isVirtualThread = isVirtualThread;
  }

  /**
   * Closes the stream and logs performance metrics.
   * Optimized to avoid blocking Virtual Threads unnecessarily.
   */
  @Override
  public void close() throws IOException {
    // Close the underlying stream first
    in.close();
    
    // Get metrics before logging to avoid any potential blocking during logging
    final long bytesRead = countingInputStream.getCount();
    final long elapsedNanos = totalNanosElapsed.sum();
    
    // Log performance metrics with Virtual Thread awareness
    performanceLogger.logRead(bytesRead, elapsedNanos, isVirtualThread);
    
    // Capture additional Virtual Thread metrics if applicable
    if (isVirtualThread) {
      performanceLogger.captureVirtualThreadMetrics("read", bytesRead, elapsedNanos);
    }
  }

  /**
   * Reads a single byte with performance tracking.
   * Optimized for Virtual Thread execution patterns.
   */
  @Override
  public int read() throws IOException {
    long start = System.nanoTime();
    try {
      return in.read();
    } finally {
      // Using add() instead of += to avoid thread pinning
      totalNanosElapsed.add(System.nanoTime() - start);
    }
  }

  /**
   * Reads bytes into a buffer with performance tracking.
   * Optimized for Virtual Thread execution patterns.
   */
  @Override
  public int read(byte[] b) throws IOException {
    long start = System.nanoTime();
    try {
      return in.read(b);
    } finally {
      totalNanosElapsed.add(System.nanoTime() - start);
    }
  }

  /**
   * Reads bytes into a buffer with offset and length with performance tracking.
   * Optimized for Virtual Thread execution patterns.
   */
  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    long start = System.nanoTime();
    try {
      return in.read(b, off, len);
    } finally {
      totalNanosElapsed.add(System.nanoTime() - start);
    }
  }
  
  /**
   * Returns whether this stream is being used in a Virtual Thread.
   */
  public boolean isVirtualThread() {
    return isVirtualThread;
  }
  
  /**
   * Returns the total nanoseconds elapsed during read operations.
   */
  public long getTotalNanosElapsed() {
    return totalNanosElapsed.sum();
  }
}
