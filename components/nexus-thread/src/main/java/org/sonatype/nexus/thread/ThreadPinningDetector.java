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

/**
 * Detects operations that could cause virtual thread pinning to carrier threads.
 * <p>
 * Virtual threads can be "pinned" to their carrier threads in certain situations,
 * preventing the carrier thread from being released for other virtual threads.
 * This happens primarily in two cases:
 * <ul>
 *   <li>When executing code inside a synchronized block or method</li>
 *   <li>When executing native methods or foreign functions</li>
 * </ul>
 * <p>
 * This detector helps identify and prevent operations that could cause pinning,
 * allowing for better utilization of virtual threads in Java 21.
 *
 * @since 3.60
 */
public interface ThreadPinningDetector
{
  /**
   * Checks if the current operation might cause virtual thread pinning.
   * <p>
   * This method analyzes the current execution context to determine if it
   * contains operations that would cause a virtual thread to be pinned to
   * its carrier thread.
   *
   * @param operationName A descriptive name of the operation being checked
   * @return true if the operation might cause pinning, false otherwise
   */
  boolean mightCausePinning(String operationName);

  /**
   * Logs a warning if the current operation might cause virtual thread pinning.
   * <p>
   * This is a convenience method that checks for pinning and logs a warning
   * message if pinning might occur.
   *
   * @param operationName A descriptive name of the operation being checked
   * @return true if the operation might cause pinning, false otherwise
   */
  boolean warnIfPinning(String operationName);

  /**
   * Checks if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  boolean isVirtualThread();
}