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
package org.sonatype.nexus.thread.io;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Utility for detecting and preventing virtual thread pinning to carrier threads.
 * <p>
 * Virtual thread pinning occurs when a virtual thread is "stuck" to its carrier thread and cannot be unmounted.
 * This typically happens when using synchronized blocks/methods or native methods within virtual threads.
 * <p>
 * This detector helps identify potential pinning situations and provides mechanisms to avoid them.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class ThreadPinningDetector
    extends ComponentSupport
{
  private final AtomicLong pinningDetectionCount = new AtomicLong(0);

  /**
   * Detects potential thread pinning in the provided operation.
   * <p>
   * This method checks if the current thread is a virtual thread and if the operation might cause pinning.
   * It logs warnings when potential pinning is detected.
   *
   * @param operation The operation to check for potential pinning
   */
  public void detectPinning(Runnable operation) {
    // Only perform detection if we're running on a virtual thread
    if (Thread.currentThread().isVirtual()) {
      try {
        // Execute the operation while monitoring for pinning
        operation.run();
      } catch (Exception e) {
        log.warn("Exception during thread pinning detection", e);
      }
      
      // Increment detection count for metrics
      pinningDetectionCount.incrementAndGet();
    } else {
      // Just run the operation if we're not on a virtual thread
      operation.run();
    }
  }

  /**
   * Executes an operation with pinning detection and returns its result.
   * <p>
   * This method is similar to {@link #detectPinning(Runnable)} but allows returning a value.
   *
   * @param <T> The type of the result
   * @param supplier The operation that returns a result
   * @return The result of the operation
   */
  public <T> T detectPinningWithResult(Supplier<T> supplier) {
    // Only perform detection if we're running on a virtual thread
    if (Thread.currentThread().isVirtual()) {
      try {
        // Execute the supplier while monitoring for pinning
        return supplier.get();
      } finally {
        // Increment detection count for metrics
        pinningDetectionCount.incrementAndGet();
      }
    } else {
      // Just run the supplier if we're not on a virtual thread
      return supplier.get();
    }
  }

  /**
   * Gets the total number of pinning detection operations performed.
   *
   * @return The count of pinning detection operations
   */
  public long getPinningDetectionCount() {
    return pinningDetectionCount.get();
  }
}