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

import java.io.InputStream;

import org.sonatype.goodies.common.Loggers;
import org.sonatype.nexus.blobstore.api.Blob;

import org.slf4j.Logger;

// Import for Java 21 String Templates
import static java.lang.StringTemplate.STR;

/**
 * Logs blob store performance statistics, including Virtual Thread specific metrics.
 *
 * @since 3.21
 */
public class PerformanceLogger
{

  private static final String IOSTAT_LOGGER_NAME = "org.sonatype.nexus.blobstore.iostat";
  private static final String VIRTUAL_THREAD_IOSTAT_LOGGER_NAME = "org.sonatype.nexus.blobstore.iostat.virtualthread";
  private static final String THREAD_PINNING_LOGGER_NAME = "org.sonatype.nexus.blobstore.iostat.threadpinning";

  private final Logger log = Loggers.getLogger(IOSTAT_LOGGER_NAME);
  private final Logger virtualThreadLog = Loggers.getLogger(VIRTUAL_THREAD_IOSTAT_LOGGER_NAME);
  private final Logger threadPinningLog = Loggers.getLogger(THREAD_PINNING_LOGGER_NAME);

  private String blobStoreName = "<not set>";
  
  // Threshold in milliseconds to consider a virtual thread operation as potentially pinned
  private static final double PINNING_THRESHOLD_MS = 20.0;

  public void setBlobStoreName(final String blobStoreName) {
    this.blobStoreName = blobStoreName;
  }

  /**
   * Wraps the input stream with performance logging if debug is enabled.
   * Detects if running in a virtual thread and applies appropriate monitoring.
   */
  public InputStream maybeWrapForPerformanceLogging(final InputStream inputStream) {
    if (log.isDebugEnabled() || virtualThreadLog.isDebugEnabled()) {
      return new PerformanceLoggingInputStream(inputStream, this);
    }
    else {
      return inputStream;
    }
  }

  /**
   * Logs read performance metrics. Uses Java 21 String Templates for improved performance.
   * Detects if running in a virtual thread and logs appropriate metrics.
   */
  public void logRead(final long bytes, final long nanos) {
    boolean isVirtualThread = Thread.currentThread().isVirtual();
    
    if (!log.isDebugEnabled() && !(isVirtualThread && virtualThreadLog.isDebugEnabled())) {
      return;
    }

    double millis = 0d;
    double mbPerSecond = Double.NaN;
    if (nanos > 0) {
      millis = ((double) nanos) / 1e6d;
      mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;
    }
    
    // Log to standard performance logger
    if (log.isDebugEnabled()) {
      log.debug(STR."blobstore \{blobStoreName}: \{bytes} bytes read in \{millis} ms (\{mbPerSecond} mb/s)");
    }
    
    // Additional logging for virtual threads
    if (isVirtualThread && virtualThreadLog.isDebugEnabled()) {
      virtualThreadLog.debug(STR."[VirtualThread] blobstore \{blobStoreName}: \{bytes} bytes read in \{millis} ms (\{mbPerSecond} mb/s)");
      
      // Check for potential thread pinning
      if (millis > PINNING_THRESHOLD_MS && threadPinningLog.isDebugEnabled()) {
        threadPinningLog.debug(STR."[POTENTIAL PINNING] VirtualThread read operation in blobstore \{blobStoreName} took \{millis} ms");
      }
    }
  }

  /**
   * Logs blob creation performance metrics. Uses Java 21 String Templates for improved performance.
   * Detects if running in a virtual thread and logs appropriate metrics.
   */
  public void logCreate(final Blob blob, final long nanos) {
    boolean isVirtualThread = Thread.currentThread().isVirtual();
    
    if (!log.isDebugEnabled() && !(isVirtualThread && virtualThreadLog.isDebugEnabled())) {
      return;
    }

    long bytes = blob.getMetrics().getContentSize();
    double millis = 0d;
    double mbPerSecond = Double.NaN;
    if (nanos > 0) {
      millis = ((double) nanos) / 1e6d;
      mbPerSecond = ((double) bytes) / ((double) nanos) * 1e3d;
    }
    
    // Log to standard performance logger
    if (log.isDebugEnabled()) {
      log.debug(STR."blobstore \{blobStoreName}: \{bytes} bytes written in \{millis} ms (\{mbPerSecond} mb/s)");
    }
    
    // Additional logging for virtual threads
    if (isVirtualThread && virtualThreadLog.isDebugEnabled()) {
      virtualThreadLog.debug(STR."[VirtualThread] blobstore \{blobStoreName}: \{bytes} bytes written in \{millis} ms (\{mbPerSecond} mb/s)");
      
      // Check for potential thread pinning
      if (millis > PINNING_THRESHOLD_MS && threadPinningLog.isDebugEnabled()) {
        threadPinningLog.debug(STR."[POTENTIAL PINNING] VirtualThread create operation in blobstore \{blobStoreName} took \{millis} ms");
      }
    }
  }

  /**
   * Logs blob deletion performance metrics. Uses Java 21 String Templates for improved performance.
   * Detects if running in a virtual thread and logs appropriate metrics.
   */
  public void logDelete(final long nanos) {
    boolean isVirtualThread = Thread.currentThread().isVirtual();
    
    if (!log.isDebugEnabled() && !(isVirtualThread && virtualThreadLog.isDebugEnabled())) {
      return;
    }

    double millis = 0d;
    if (nanos > 0) {
      millis = ((double) nanos) / 1e6d;
    }
    
    // Log to standard performance logger
    if (log.isDebugEnabled()) {
      log.debug(STR."blobstore \{blobStoreName}: blob deleted in \{millis} ms");
    }
    
    // Additional logging for virtual threads
    if (isVirtualThread && virtualThreadLog.isDebugEnabled()) {
      virtualThreadLog.debug(STR."[VirtualThread] blobstore \{blobStoreName}: blob deleted in \{millis} ms");
      
      // Check for potential thread pinning
      if (millis > PINNING_THRESHOLD_MS && threadPinningLog.isDebugEnabled()) {
        threadPinningLog.debug(STR."[POTENTIAL PINNING] VirtualThread delete operation in blobstore \{blobStoreName} took \{millis} ms");
      }
    }
  }
  
  /**
   * Logs a virtual thread specific operation with performance metrics.
   * This method is used for tracking virtual thread specific operations that aren't
   * covered by the standard read/create/delete methods.
   *
   * @param operationName The name of the operation being performed
   * @param nanos The duration of the operation in nanoseconds
   * @param additionalInfo Optional additional information about the operation
   */
  public void logVirtualThreadOperation(final String operationName, final long nanos, final String additionalInfo) {
    if (!Thread.currentThread().isVirtual() || !virtualThreadLog.isDebugEnabled()) {
      return;
    }
    
    double millis = 0d;
    if (nanos > 0) {
      millis = ((double) nanos) / 1e6d;
    }
    
    String logMessage = additionalInfo != null && !additionalInfo.isEmpty() 
        ? STR."[VirtualThread] blobstore \{blobStoreName}: \{operationName} completed in \{millis} ms (\{additionalInfo})"
        : STR."[VirtualThread] blobstore \{blobStoreName}: \{operationName} completed in \{millis} ms";
    
    virtualThreadLog.debug(logMessage);
    
    // Check for potential thread pinning
    if (millis > PINNING_THRESHOLD_MS && threadPinningLog.isDebugEnabled()) {
      threadPinningLog.debug(STR."[POTENTIAL PINNING] VirtualThread \{operationName} operation in blobstore \{blobStoreName} took \{millis} ms");
    }
  }
}