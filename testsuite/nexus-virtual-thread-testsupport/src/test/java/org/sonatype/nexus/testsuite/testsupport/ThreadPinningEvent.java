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
package org.sonatype.nexus.testsuite.testsupport;

import java.time.Instant;

/**
 * Represents a thread pinning event detected in a Virtual Thread.
 * 
 * Thread pinning occurs when a Virtual Thread becomes "pinned" to its carrier thread,
 * preventing the carrier from being reused for other Virtual Threads and reducing efficiency.
 * This class captures information about such events for analysis and debugging.
 *
 * @since 3.60
 */
public class ThreadPinningEvent
{
  private final Instant timestamp;
  private final long durationMillis;
  private final String threadName;
  private final String stackTrace;
  private final String reason;

  /**
   * Creates a new thread pinning event with the specified details.
   *
   * @param timestamp The time when the pinning event was detected
   * @param durationMillis The duration of the pinning in milliseconds
   * @param threadName The name of the virtual thread that was pinned
   * @param stackTrace The stack trace at the time of pinning
   * @param reason The reason for pinning (e.g., "MONITOR" for synchronized blocks)
   */
  public ThreadPinningEvent(final Instant timestamp, 
                           final long durationMillis, 
                           final String threadName, 
                           final String stackTrace,
                           final String reason) {
    this.timestamp = timestamp;
    this.durationMillis = durationMillis;
    this.threadName = threadName;
    this.stackTrace = stackTrace;
    this.reason = reason;
  }

  /**
   * Gets the timestamp when the pinning event was detected.
   *
   * @return The timestamp of the pinning event
   */
  public Instant getTimestamp() {
    return timestamp;
  }

  /**
   * Gets the duration of the pinning event in milliseconds.
   *
   * @return The duration in milliseconds
   */
  public long getDurationMillis() {
    return durationMillis;
  }

  /**
   * Gets the name of the virtual thread that was pinned.
   *
   * @return The thread name
   */
  public String getThreadName() {
    return threadName;
  }

  /**
   * Gets the stack trace at the time of pinning.
   *
   * @return The stack trace as a string
   */
  public String getStackTrace() {
    return stackTrace;
  }

  /**
   * Gets the reason for pinning.
   *
   * @return The reason (e.g., "MONITOR" for synchronized blocks)
   */
  public String getReason() {
    return reason;
  }

  @Override
  public String toString() {
    return "ThreadPinningEvent{" +
        "timestamp=" + timestamp +
        ", durationMillis=" + durationMillis +
        ", threadName='" + threadName + '\'' +
        ", reason='" + reason + '\'' +
        ", stackTrace='" + stackTrace + '\'' +
        '}';
  }
}