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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.sonatype.nexus.thread.NexusExecutorService;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Utility class for executing tasks on virtual threads with proper subject and MDC context propagation.
 * 
 * This class provides methods to execute tasks on virtual threads while ensuring that the current
 * security subject and MDC context are properly propagated to the virtual thread.
 *
 * @since 3.60
 */
public final class VirtualThreads
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreads.class);
  
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  private VirtualThreads() {
    // Prevent instantiation
  }

  /**
   * Executes the given task on a virtual thread, propagating the current security subject and MDC context.
   *
   * @param <T> the type of the task's result
   * @param task the task to execute
   * @return the task's result
   * @throws RuntimeException if the task throws an exception
   */
  public static <T> T execute(final Callable<T> task) {
    // Capture the current subject and MDC context
    final Subject subject = SecurityUtils.getSubject();
    final java.util.Map<String, String> mdcContext = MDC.getCopyOfContextMap();
    
    try {
      // Submit the task to the virtual thread executor with subject and MDC context
      Future<T> future = VIRTUAL_THREAD_EXECUTOR.submit(() -> {
        // Set the MDC context in the virtual thread
        if (mdcContext != null) {
          MDC.setContextMap(mdcContext);
        }
        
        try {
          // Execute the task with the subject
          if (subject != null) {
            return subject.execute(task);
          } else {
            return task.call();
          }
        } finally {
          // Clear the MDC context
          MDC.clear();
        }
      });
      
      // Wait for the task to complete and return the result
      return future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Task execution was interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      } else if (cause instanceof Error) {
        throw (Error) cause;
      } else {
        throw new RuntimeException("Task execution failed", cause);
      }
    }
  }

  /**
   * Executes the given task on a virtual thread, propagating the current security subject and MDC context.
   * This is a convenience method for tasks that don't return a result.
   *
   * @param task the task to execute
   */
  public static void execute(final Runnable task) {
    execute(() -> {
      task.run();
      return null;
    });
  }

  /**
   * Executes the given supplier on a virtual thread, propagating the current security subject and MDC context.
   * This is a convenience method for tasks that return a result but don't throw checked exceptions.
   *
   * @param <T> the type of the supplier's result
   * @param supplier the supplier to execute
   * @return the supplier's result
   */
  public static <T> T execute(final Supplier<T> supplier) {
    return execute((Callable<T>) supplier::get);
  }
}