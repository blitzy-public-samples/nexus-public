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
package org.sonatype.nexus.transaction;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Helper class for working with Java 21 Virtual Threads in transaction contexts.
 * 
 * @since 3.60
 */
public final class VirtualThreadHelper
{
  private VirtualThreadHelper() {
    // Utility class, no instances
  }
  
  /**
   * Checks if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Creates a new executor that creates a new virtual thread for each task.
   * 
   * @return an executor that creates a new virtual thread for each task
   */
  public static ExecutorService newVirtualThreadPerTaskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Creates a new executor that creates a new virtual thread for each task with the specified name prefix.
   * 
   * @param namePrefix the prefix for thread names
   * @return an executor that creates a new virtual thread for each task
   */
  public static ExecutorService newVirtualThreadPerTaskExecutor(String namePrefix) {
    return Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name(namePrefix, 0).factory());
  }
  
  /**
   * Optimizes transaction retry behavior for virtual threads.
   * This method is a no-op if not running on a virtual thread.
   */
  public static void optimizeRetryForVirtualThread() {
    if (isVirtualThread()) {
      // For virtual threads, we can use a more aggressive retry strategy
      // since they are lightweight and don't block platform threads
      Thread.yield(); // Hint to the scheduler that other virtual threads can run
    }
  }
}