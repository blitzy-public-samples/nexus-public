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
package org.sonatype.nexus.thread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A thread factory that sets an uncaught exception handler for all threads it creates.
 * Supports both platform threads and virtual threads (Java 21+).
 */
public class ExceptionAwareThreadFactory
    extends NexusThreadFactory
{

  private static final Logger log = LoggerFactory.getLogger(ExceptionAwareThreadFactory.class);

  public ExceptionAwareThreadFactory(final String poolId, final String threadGroupName) {
    super(poolId, threadGroupName);
  }

  public ExceptionAwareThreadFactory(final String poolId, final String threadGroupName, final int threadPriority) {
    super(poolId, threadGroupName, threadPriority);
  }

  public ExceptionAwareThreadFactory(
      final String poolId,
      final String threadGroupName,
      final int threadPriority,
      final boolean daemonThread)
  {
    super(poolId, threadGroupName, threadPriority, daemonThread);
  }

  @Override
  public Thread newThread(final Runnable r) {
    Thread tr = super.newThread(r);
    tr.setUncaughtExceptionHandler((t, e) -> {
      if (t.isVirtual()) {
        // Enhanced logging for virtual threads with additional diagnostic information
        logVirtualThreadException(t, e);
      } else {
        // Standard logging for platform threads
        log.error("Uncaught Exception occurred on platform thread: {}, Exception message: {}", 
            t.getName(), e.getMessage(), e);
      }
    });
    return tr;
  }
  
  /**
   * Logs enhanced diagnostic information for uncaught exceptions in virtual threads.
   * Captures virtual thread specific context and state information to aid in debugging.
   *
   * @param thread the virtual thread where the exception occurred
   * @param exception the uncaught exception
   */
  private void logVirtualThreadException(Thread thread, Throwable exception) {
    StringBuilder diagnostics = new StringBuilder();
    
    // Capture basic thread information
    diagnostics.append("Thread ID: ").append(thread.threadId())
              .append(", Name: ").append(thread.getName())
              .append(", State: ").append(thread.getState());
    
    // Capture stack trace information
    StackTraceElement[] stackTrace = thread.getStackTrace();
    if (stackTrace != null && stackTrace.length > 0) {
      diagnostics.append(", Last execution point: ")
                .append(stackTrace[0].getClassName())
                .append(".").append(stackTrace[0].getMethodName())
                .append(" (line ").append(stackTrace[0].getLineNumber()).append(")");
    }
    
    // Log the enhanced information with the full exception
    log.error("Uncaught Exception in virtual thread - Diagnostics: {}, Exception message: {}", 
        diagnostics.toString(), exception.getMessage(), exception);
    
    // Additional logging for monitoring virtual thread issues
    log.debug("Virtual thread diagnostic details - Thread: {}, Exception type: {}", 
        thread, exception.getClass().getName());
  }
}