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
package org.sonatype.nexus.scheduling;

import java.lang.ScopedValue;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Support for per-thread storing of cancelable flag.
 *
 * Periodically checking the {@link #checkCancellation()} is the preferred way to detect cancellation in components
 * outside the tasks. Within task, you have the {@link TaskSupport#isCanceled()} method.
 *
 * <p>This implementation supports both platform threads and virtual threads (Java 21+). For virtual threads,
 * it uses {@link ScopedValue} to efficiently propagate cancellation state to child threads with minimal overhead.
 * For platform threads, it maintains backward compatibility using {@link InheritableThreadLocal}.</p>
 *
 * <p>When using structured concurrency with virtual threads, cancellation is automatically propagated to all
 * child threads in the structured task scope.</p>
 *
 * @since 3.0
 */
public class CancelableHelper
{
  private CancelableHelper() {
    // empty
  }

  // ScopedValue for efficient cancellation flag propagation in virtual threads
  private static final ScopedValue<AtomicBoolean> SCOPED_FLAG = ScopedValue.newInstance();

  // InheritableThreadLocal for backward compatibility with platform threads
  private static final InheritableThreadLocal<AtomicBoolean> INHERITABLE_FLAG_HOLDER = new InheritableThreadLocal<>();

  // Legacy ThreadLocal for backward compatibility with existing code
  private static final ThreadLocal<AtomicBoolean> LEGACY_FLAG_HOLDER = new ThreadLocal<>();

  /**
   * Sets the cancellation flag for the current thread.
   * 
   * @param flag the cancellation flag to set
   */
  public static void set(final AtomicBoolean flag) {
    checkNotNull(flag);
    // Set in both ThreadLocal implementations for compatibility
    LEGACY_FLAG_HOLDER.set(flag);
    INHERITABLE_FLAG_HOLDER.set(flag);
  }

  /**
   * Removes the cancellation flag from the current thread.
   */
  public static void remove() {
    LEGACY_FLAG_HOLDER.remove();
    INHERITABLE_FLAG_HOLDER.remove();
  }

  /**
   * Runs the given task with the specified cancellation flag.
   * This method is optimized for virtual threads using ScopedValue.
   *
   * @param flag the cancellation flag
   * @param task the task to run
   */
  public static void runWithFlag(final AtomicBoolean flag, final Runnable task) {
    checkNotNull(flag);
    checkNotNull(task);
    
    // Use ScopedValue for the duration of the task
    ScopedValue.where(SCOPED_FLAG, flag).run(() -> {
      // Also set ThreadLocal for backward compatibility
      AtomicBoolean oldFlag = LEGACY_FLAG_HOLDER.get();
      AtomicBoolean oldInheritableFlag = INHERITABLE_FLAG_HOLDER.get();
      try {
        LEGACY_FLAG_HOLDER.set(flag);
        INHERITABLE_FLAG_HOLDER.set(flag);
        task.run();
      }
      finally {
        // Restore previous flags if they existed
        if (oldFlag != null) {
          LEGACY_FLAG_HOLDER.set(oldFlag);
        }
        else {
          LEGACY_FLAG_HOLDER.remove();
        }
        
        if (oldInheritableFlag != null) {
          INHERITABLE_FLAG_HOLDER.set(oldInheritableFlag);
        }
        else {
          INHERITABLE_FLAG_HOLDER.remove();
        }
      }
    });
  }

  /**
   * Calls the given task with the specified cancellation flag and returns its result.
   * This method is optimized for virtual threads using ScopedValue.
   *
   * @param flag the cancellation flag
   * @param task the task to call
   * @return the result of the task
   * @param <T> the type of the result
   * @throws Exception if the task throws an exception
   */
  public static <T> T callWithFlag(final AtomicBoolean flag, final Callable<T> task) throws Exception {
    checkNotNull(flag);
    checkNotNull(task);
    
    // Use ScopedValue for the duration of the task
    return ScopedValue.where(SCOPED_FLAG, flag).call(() -> {
      // Also set ThreadLocal for backward compatibility
      AtomicBoolean oldFlag = LEGACY_FLAG_HOLDER.get();
      AtomicBoolean oldInheritableFlag = INHERITABLE_FLAG_HOLDER.get();
      try {
        LEGACY_FLAG_HOLDER.set(flag);
        INHERITABLE_FLAG_HOLDER.set(flag);
        return task.call();
      }
      finally {
        // Restore previous flags if they existed
        if (oldFlag != null) {
          LEGACY_FLAG_HOLDER.set(oldFlag);
        }
        else {
          LEGACY_FLAG_HOLDER.remove();
        }
        
        if (oldInheritableFlag != null) {
          INHERITABLE_FLAG_HOLDER.set(oldInheritableFlag);
        }
        else {
          INHERITABLE_FLAG_HOLDER.remove();
        }
      }
    });
  }

  /**
   * Throws {@link TaskInterruptedException} if current task is canceled or interrupted.
   * 
   * @return true if the current thread is not canceled or interrupted
   * @throws TaskInterruptedException if the current thread is canceled or interrupted
   */
  public static boolean checkCancellation() {
    Thread.yield();
    
    // First check ScopedValue (optimized for virtual threads)
    AtomicBoolean scopedFlag = SCOPED_FLAG.orElse(null);
    if (scopedFlag != null && scopedFlag.get()) {
      throw new TaskInterruptedException("Thread '" + Thread.currentThread().getName() + "' is canceled", true);
    }
    
    // Then check InheritableThreadLocal (for platform threads with inheritance)
    AtomicBoolean inheritableFlag = INHERITABLE_FLAG_HOLDER.get();
    if (inheritableFlag != null && inheritableFlag.get()) {
      throw new TaskInterruptedException("Thread '" + Thread.currentThread().getName() + "' is canceled", true);
    }
    
    // Finally check legacy ThreadLocal (for backward compatibility)
    AtomicBoolean legacyFlag = LEGACY_FLAG_HOLDER.get();
    if (legacyFlag != null && legacyFlag.get()) {
      throw new TaskInterruptedException("Thread '" + Thread.currentThread().getName() + "' is canceled", true);
    }
    
    // Check for thread interruption
    if (Thread.interrupted()) {
      throw new TaskInterruptedException("Thread '" + Thread.currentThread().getName() + "' is interrupted", false);
    }
    
    return true;
  }
  
  /**
   * Installs an uncaught exception handler that will propagate cancellation to the current thread
   * if a child thread is canceled. This is useful for structured concurrency scenarios.
   *
   * @param thread the thread to install the handler on
   */
  public static void installCancellationPropagator(Thread thread) {
    checkNotNull(thread);
    
    // Get the current thread's cancellation flag
    AtomicBoolean parentFlag = getCurrentFlag();
    if (parentFlag == null) {
      return; // No flag to propagate
    }
    
    // Install handler that will propagate cancellation back to parent
    thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler() {
      @Override
      public void uncaughtException(Thread t, Throwable e) {
        if (e instanceof TaskInterruptedException && ((TaskInterruptedException) e).isCanceled()) {
          // Propagate cancellation to parent thread
          parentFlag.set(true);
        }
      }
    });
  }
  
  /**
   * Gets the current thread's cancellation flag, checking all possible sources.
   *
   * @return the current cancellation flag or null if none is set
   */
  private static AtomicBoolean getCurrentFlag() {
    // Check ScopedValue first (optimized for virtual threads)
    AtomicBoolean flag = SCOPED_FLAG.orElse(null);
    if (flag != null) {
      return flag;
    }
    
    // Then check InheritableThreadLocal
    flag = INHERITABLE_FLAG_HOLDER.get();
    if (flag != null) {
      return flag;
    }
    
    // Finally check legacy ThreadLocal
    return LEGACY_FLAG_HOLDER.get();
  }
}