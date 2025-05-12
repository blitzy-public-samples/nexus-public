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
package org.sonatype.nexus.logging.task;

import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import org.slf4j.Logger;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.logging.task.TaskLoggingMarkers.PROGRESS;

/**
 * Helper for logging progress messages, one per defined interval.
 * 
 * This implementation uses Java 21 String Templates for improved efficiency and readability,
 * and is compatible with task loggers running on Virtual Threads.
 */
public class ProgressLogIntervalHelper
    implements AutoCloseable
{
  private final Stopwatch elapsed;

  private final Stopwatch progress;

  private final Logger logger;

  private final int internal;

  private static final long SECONDS_PER_MINUTE = 60;

  private static final long SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE;

  private static final long SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR;

  public ProgressLogIntervalHelper(final Logger logger, int intervalInSeconds) {
    this.logger = checkNotNull(logger);
    this.internal = intervalInSeconds;

    this.elapsed = Stopwatch.createStarted();
    this.progress = Stopwatch.createStarted();
  }

  /**
   * Get elapsed time as a string so it can be included in logs
   */
  public String getElapsed() {
    return formatDuration(elapsed.elapsed().getSeconds());
  }

  /**
   * Format duration in seconds to a human-readable string using String Templates.
   * Format: "Xd Xh Xm Xs" where components are included only when non-zero
   * or when higher-order components are present.
   */
  private String formatDuration(final long durationSeconds) {
    long seconds = durationSeconds;

    long days = seconds / SECONDS_PER_DAY;
    seconds = seconds - (days * SECONDS_PER_DAY);
    
    long hours = seconds / SECONDS_PER_HOUR;
    seconds = seconds - (hours * SECONDS_PER_HOUR);
    
    long minutes = seconds / SECONDS_PER_MINUTE;
    seconds = seconds - (minutes * SECONDS_PER_MINUTE);

    // Using String Templates for more efficient and readable formatting
    // This approach eliminates the need for StringBuilder and conditional string concatenation
    if (days > 0) {
      return STR."\{days}d \{hours}h \{minutes}m \{seconds}s";
    } else if (hours > 0) {
      return STR."\{hours}h \{minutes}m \{seconds}s";
    } else if (minutes > 0) {
      return STR."\{minutes}m \{seconds}s";
    } else {
      return STR."\{seconds}s";
    }
  }

  /**
   * Log the message using the PROGRESS marker. Will only send the log message to logback once per interval, otherwise
   * will store the message in the task logger context.
   * 
   * This method is compatible with Virtual Threads and will not cause thread pinning.
   */
  public void info(String message, Object... args) {
    if (hasIntervalElapsed()) {
      logger.info(PROGRESS, message, args);
    }
    else {
      TaskLoggerHelper.progress(logger, message, args);
    }
  }

  /**
   * Flush any pending progress messages so they are logged immediately. This should be called when the section of code
   * receiving progress logging is complete, otherwise a stale progress message might get logged out of sequence.
   *
   * @see TaskLogger#flush()
   */
  public void flush() {
    TaskLoggerHelper.flush();
  }

  /**
   * Checks if the configured interval has elapsed since the last progress message.
   * This method is non-blocking and compatible with Virtual Threads.
   * 
   * @return true if the interval has elapsed, false otherwise
   */
  private boolean hasIntervalElapsed() {
    boolean logProgress = progress.elapsed(TimeUnit.SECONDS) >= internal;
    if (logProgress) {
      progress.reset().start();
    }
    return logProgress;
  }

  @Override
  public void close() {
    this.flush();
  }
}