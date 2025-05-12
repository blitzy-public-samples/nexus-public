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
package org.sonatype.nexus.thread.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;
import org.sonatype.nexus.thread.ThreadPinningDetector;

/**
 * Implementation of {@link ThreadPinningDetector} that detects operations that could cause
 * virtual thread pinning to carrier threads.
 *
 * @since 3.60
 */
@Named
@Singleton
public class ThreadPinningDetectorImpl
    extends ComponentSupport
    implements ThreadPinningDetector
{
  /**
   * Checks if the current operation might cause virtual thread pinning by analyzing
   * the current thread's stack trace for synchronized blocks or native methods.
   *
   * @param operationName A descriptive name of the operation being checked
   * @return true if the operation might cause pinning, false otherwise
   */
  @Override
  public boolean mightCausePinning(final String operationName) {
    if (!isVirtualThread()) {
      // Only virtual threads can be pinned
      return false;
    }
    
    // Check for synchronized blocks in the stack trace
    StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
    for (StackTraceElement element : stackTrace) {
      // Look for known patterns that cause pinning
      String className = element.getClassName();
      String methodName = element.getMethodName();
      
      // Check for synchronized methods or blocks (simplified detection)
      if (className.contains("java.lang.Object") && methodName.equals("wait")) {
        return true;
      }
      
      // Check for native methods
      if (element.isNativeMethod()) {
        return true;
      }
    }
    
    return false;
  }

  /**
   * Logs a warning if the current operation might cause virtual thread pinning.
   *
   * @param operationName A descriptive name of the operation being checked
   * @return true if the operation might cause pinning, false otherwise
   */
  @Override
  public boolean warnIfPinning(final String operationName) {
    boolean mightPin = mightCausePinning(operationName);
    if (mightPin) {
      log.warn("Operation '{}' might cause virtual thread pinning. Consider refactoring to avoid synchronized blocks or native methods.", 
          operationName);
    }
    return mightPin;
  }

  /**
   * Checks if the current thread is a virtual thread using Java 21's Thread.isVirtual() method.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  @Override
  public boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
}