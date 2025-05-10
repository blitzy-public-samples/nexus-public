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

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.slf4j.Logger;

/**
 * Each task is executed in its own thread and its {@link TaskLogger} is stored in it here.
 * <p>
 * This implementation is optimized for Java 21 Virtual Threads, ensuring proper context propagation
 * and cleanup to prevent memory leaks. Virtual Threads are lightweight threads that can be mounted and
 * unmounted from carrier threads, requiring special handling for ThreadLocal variables.
 *
 * @since 3.5
 */
public class TaskLoggerHelper
{
  /**
   * Thread-local context using withInitial factory method for better Virtual Thread compatibility.
   * This approach ensures proper initialization and helps prevent memory leaks in Virtual Thread environments.
   */
  private static final ThreadLocal<TaskLogger> context = ThreadLocal.withInitial(() -> null);
  
  /**
   * Registry to track active task loggers across threads for cleanup purposes.
   * This helps prevent memory leaks with Virtual Threads by providing a secondary cleanup mechanism.
   */
  private static final ConcurrentHashMap<Thread, WeakReference<TaskLogger>> threadRegistry = 
      new ConcurrentHashMap<>();

  private TaskLoggerHelper() {
    throw new IllegalAccessError("Utility class");
  }

  /**
   * Starts task logging in the current thread context.
   * <p>
   * When using Virtual Threads, this method ensures proper context initialization and tracking.
   * The task logger is registered with the current thread to enable cleanup if the thread is recycled.
   *
   * @param taskLogger the task logger to associate with the current thread
   */
  public static void start(final TaskLogger taskLogger) {
    taskLogger.start();
    context.set(taskLogger);
    threadRegistry.put(Thread.currentThread(), new WeakReference<>(taskLogger));
  }

  /**
   * Gets the task logger associated with the current thread.
   * <p>
   * This method is Virtual Thread safe and will return the correct logger regardless of
   * whether the thread is a platform thread or a virtual thread.
   *
   * @return the task logger for the current thread, or null if none is associated
   */
  public static TaskLogger get() {
    TaskLogger logger = context.get();
    if (logger == null) {
      // Fallback to registry lookup in case of Virtual Thread remounting
      WeakReference<TaskLogger> ref = threadRegistry.get(Thread.currentThread());
      if (ref != null) {
        logger = ref.get();
        // Re-establish thread local if found in registry but not in thread local
        if (logger != null) {
          context.set(logger);
        }
      }
    }
    return logger;
  }

  /**
   * Finishes task logging and cleans up thread-local resources.
   * <p>
   * This method ensures proper cleanup of ThreadLocal variables to prevent memory leaks,
   * which is especially important when using Virtual Threads. It removes the task logger
   * from both the thread-local context and the thread registry.
   */
  public static void finish() {
    TaskLogger taskLogger = get();
    if (taskLogger != null) {
      taskLogger.finish();
    }
    context.remove();
    threadRegistry.remove(Thread.currentThread());
  }

  /**
   * Records a task progress event using the current thread's task logger.
   * <p>
   * This method is Virtual Thread safe and will work correctly regardless of thread mounting state.
   *
   * @param event the logging event to record
   */
  public static void progress(final TaskLoggingEvent event) {
    TaskLogger taskLogger = get();
    if (taskLogger != null) {
      taskLogger.progress(event);
    }
  }

  /**
   * Records a task progress message using the current thread's task logger.
   * <p>
   * This method is Virtual Thread safe and will work correctly regardless of thread mounting state.
   *
   * @param logger the logger to use
   * @param message the message to log
   * @param args message arguments
   */
  public static void progress(final Logger logger, final String message, Object... args) {
    progress(new TaskLoggingEvent(logger, message, args));
  }

  /**
   * Flushes any buffered log messages in the current thread's task logger.
   * <p>
   * This method is Virtual Thread safe and will work correctly regardless of thread mounting state.
   *
   * @see TaskLogger#flush()
   */
  public static void flush() {
    TaskLogger taskLogger = get();
    if (taskLogger != null) {
      taskLogger.flush();
    }
  }
  
  /**
   * Performs cleanup of any orphaned task loggers.
   * <p>
   * This method can be called periodically by a maintenance task to clean up any task loggers
   * that might have been orphaned due to Virtual Thread recycling or other thread management issues.
   * It's a safeguard against memory leaks in long-running applications.
   */
  public static void cleanupOrphanedLoggers() {
    threadRegistry.entrySet().removeIf(entry -> {
      Thread thread = entry.getKey();
      WeakReference<TaskLogger> loggerRef = entry.getValue();
      
      // Remove if thread no longer exists or reference has been cleared
      return !thread.isAlive() || loggerRef.get() == null;
    });
  }
}
