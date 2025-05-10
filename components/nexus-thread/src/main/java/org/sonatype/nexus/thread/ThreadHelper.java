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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;

/**
 * Helper utility for working with threads, particularly virtual threads in Java 21+.
 * <p>
 * Provides methods for executing code with proper security subject propagation and
 * other thread-related utilities optimized for virtual threads.
 *
 * @since 3.60
 */
@Named
@Singleton
public class ThreadHelper
    extends ComponentSupport
{
  private static final ExecutorService VIRTUAL_THREAD_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

  /**
   * Executes a task with the specified security subject, ensuring proper subject propagation.
   * <p>
   * This method is particularly useful when working with virtual threads to ensure
   * that the security context is properly maintained across thread boundaries.
   *
   * @param <T> The return type of the task
   * @param subject The security subject to associate with the task
   * @param task The task to execute
   * @return The result of the task execution
   * @throws Exception If the task throws an exception
   */
  public static <T> T withSubject(Subject subject, Callable<T> task) throws Exception {
    // If we're already associated with the subject, just execute the task
    if (subject == SecurityUtils.getSubject()) {
      return task.call();
    }
    
    // Otherwise, associate the task with the subject and execute it
    return subject.execute(task);
  }

  /**
   * Executes a task with the specified security subject, ensuring proper subject propagation.
   * <p>
   * This is a convenience method for tasks that don't return a value.
   *
   * @param subject The security subject to associate with the task
   * @param runnable The task to execute
   * @throws Exception If the task throws an exception
   */
  public static void withSubject(Subject subject, Runnable runnable) throws Exception {
    withSubject(subject, () -> {
      runnable.run();
      return null;
    });
  }

  /**
   * Executes a task asynchronously using virtual threads with the specified security subject.
   * <p>
   * This method submits the task to a virtual thread executor while ensuring proper
   * subject propagation.
   *
   * @param <T> The return type of the task
   * @param subject The security subject to associate with the task
   * @param task The task to execute
   * @return A supplier that will provide the result when available
   */
  public static <T> Supplier<T> withSubjectAsync(Subject subject, Callable<T> task) {
    return () -> {
      try {
        return VIRTUAL_THREAD_EXECUTOR.submit(() -> withSubject(subject, task)).get();
      } catch (Exception e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException("Error executing task with subject", e);
      }
    };
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
   * Gets the current thread's name with additional information if it's a virtual thread.
   *
   * @return The enhanced thread name
   */
  public static String getEnhancedThreadName() {
    Thread currentThread = Thread.currentThread();
    if (currentThread.isVirtual()) {
      return STR."VirtualThread[\{currentThread.threadId()}]";
    } else {
      return currentThread.getName();
    }
  }
}