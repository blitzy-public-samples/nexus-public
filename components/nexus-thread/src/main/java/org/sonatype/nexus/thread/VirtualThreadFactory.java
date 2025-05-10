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

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nonnull;

/**
 * Nexus {@link ThreadFactory} for creating Java 21 virtual threads.
 * <p>
 * Virtual threads are lightweight threads that are managed by the JVM rather than the OS,
 * making them ideal for I/O-bound operations with minimal overhead compared to platform threads.
 * <p>
 * This factory creates virtual threads with consistent naming patterns following Nexus conventions,
 * enabling clear identification in logs and profiling tools.
 *
 * @since 3.60
 * @see Thread#ofVirtual()
 */
public class VirtualThreadFactory
    implements ThreadFactory
{
  private static final AtomicInteger poolNumber = new AtomicInteger(1);

  private final AtomicInteger threadNumber = new AtomicInteger(1);

  private final String namePrefix;

  private final boolean inheritInheritableThreadLocals;

  /**
   * Creates a new virtual thread factory with the specified pool ID and thread group name.
   * Thread-local variables will be inherited from the parent thread.
   *
   * @param poolId the identifier for this thread pool
   * @param threadGroupName the name of the thread group
   */
  public VirtualThreadFactory(String poolId, String threadGroupName) {
    this(poolId, threadGroupName, true);
  }

  /**
   * Creates a new virtual thread factory with the specified pool ID, thread group name,
   * and thread-local inheritance setting.
   *
   * @param poolId the identifier for this thread pool
   * @param threadGroupName the name of the thread group
   * @param inheritInheritableThreadLocals whether the virtual threads should inherit thread-local variables
   */
  public VirtualThreadFactory(
      final String poolId,
      final String threadGroupName,
      final boolean inheritInheritableThreadLocals)
  {
    int poolNum = poolNumber.getAndIncrement();
    this.namePrefix = poolId + "-" + poolNum + "-vthread-";
    this.inheritInheritableThreadLocals = inheritInheritableThreadLocals;
  }

  /**
   * Creates a new virtual thread to execute the given runnable.
   * <p>
   * The thread will be named according to the pattern: [poolId]-[poolNumber]-vthread-[threadNumber]
   *
   * @param r the runnable task to be executed by the new thread
   * @return a new virtual thread
   */
  @Override
  @Nonnull
  public Thread newThread(final Runnable r) {
    String threadName = namePrefix + threadNumber.getAndIncrement();
    return Thread.ofVirtual()
        .name(threadName)
        .inheritInheritableThreadLocals(inheritInheritableThreadLocals)
        .unstarted(r);
  }
  
  /**
   * Creates a new {@link ThreadFactory} that creates virtual threads.
   * <p>
   * This is a convenience method that uses the default Executors factory with Nexus naming conventions.
   *
   * @param poolId the identifier for this thread pool
   * @param threadGroupName the name of the thread group
   * @return a new thread factory that creates virtual threads
   */
  public static ThreadFactory builder(String poolId, String threadGroupName) {
    return new VirtualThreadFactory(poolId, threadGroupName);
  }
  
  /**
   * Creates a new {@link java.util.concurrent.ExecutorService} that creates a new virtual thread for each task.
   * <p>
   * This is a convenience method that creates an executor service using this factory.
   *
   * @param poolId the identifier for this thread pool
   * @param threadGroupName the name of the thread group
   * @return a new executor service that creates a new virtual thread for each task
   */
  public static java.util.concurrent.ExecutorService newExecutorService(String poolId, String threadGroupName) {
    return Executors.newThreadPerTaskExecutor(new VirtualThreadFactory(poolId, threadGroupName));
  }
}