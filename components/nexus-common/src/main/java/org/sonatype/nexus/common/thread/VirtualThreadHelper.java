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
package org.sonatype.nexus.common.thread;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static java.lang.StringTemplate.STR;

/**
 * Helper class for working with Java 21 Virtual Threads.
 * 
 * Provides utilities for creating Virtual Thread executors and ensuring proper
 * context propagation when working with Virtual Threads.
 *
 * @since 3.60
 */
public class VirtualThreadHelper
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadHelper.class);

  private VirtualThreadHelper() {
    // Utility class, no instances
  }

  /**
   * Creates a new ExecutorService that creates a new virtual thread for each task.
   * 
   * @return an ExecutorService that creates a new virtual thread for each task
   */
  public static ExecutorService newVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Determines if the current thread is a virtual thread.
   * 
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Runs the given operation with the current thread's context properly propagated.
   * 
   * This method captures the current thread's context (MDC, ClassLoader, etc.) and ensures
   * it's properly restored when the operation is executed, which is particularly important
   * when working with Virtual Threads that may be scheduled on different carrier threads.
   * 
   * @param <T> the type of the result
   * @param operation the operation to run with the thread context
   * @return the result of the operation
   * @throws Exception if the operation throws an exception
   */
  public static <T> T runWithThreadContext(Callable<T> operation) throws Exception {
    // Capture the current thread's context
    Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
    
    try {
      // If we're already in a virtual thread, just run the operation
      if (isVirtualThread()) {
        return operation.call();
      }
      
      // Otherwise, submit to a virtual thread and ensure context propagation
      try (ExecutorService executor = newVirtualThreadExecutor()) {
        return executor.submit(() -> {
          // Restore the context in the virtual thread
          if (mdcContext != null) {
            MDC.setContextMap(mdcContext);
          }
          Thread.currentThread().setContextClassLoader(contextClassLoader);
          
          try {
            // Run the operation with the restored context
            return operation.call();
          } finally {
            // Clean up
            MDC.clear();
          }
        }).get();
      }
    } catch (Exception e) {
      log.error(STR."Error executing operation with thread context: {e.getMessage()}", e);
      throw e;
    }
  }

  /**
   * Runs the given operation with the current thread's context properly propagated.
   * 
   * This is a convenience method for operations that don't return a result.
   * 
   * @param runnable the operation to run with the thread context
   * @throws Exception if the operation throws an exception
   */
  public static void runWithThreadContext(Runnable runnable) throws Exception {
    runWithThreadContext(() -> {
      runnable.run();
      return null;
    });
  }
}