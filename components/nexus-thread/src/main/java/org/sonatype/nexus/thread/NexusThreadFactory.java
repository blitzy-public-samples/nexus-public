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

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Nexus {@link ThreadFactory}.
 */
public class NexusThreadFactory
    implements ThreadFactory
{
  private static final AtomicInteger poolNumber = new AtomicInteger(1);

  private final AtomicInteger threadNumber = new AtomicInteger(1);

  private final String namePrefix;

  private final ThreadGroup schedulerThreadGroup;

  private final boolean deamonThread;

  private int threadPriority;

  private boolean useVirtualThreads;

  private boolean inheritInheritableThreadLocals;

  public NexusThreadFactory(String poolId, String threadGroupName) {
    this(poolId, threadGroupName, Thread.NORM_PRIORITY);
  }

  public NexusThreadFactory(final String poolId, final String threadGroupName, final int threadPriority) {
    this(poolId, threadGroupName, threadPriority, false);
  }

  public NexusThreadFactory(
      final String poolId,
      final String threadGroupName,
      final int threadPriority,
      final boolean daemonThread)
  {
    this(poolId, threadGroupName, threadPriority, daemonThread, false);
  }

  public NexusThreadFactory(
      final String poolId,
      final String threadGroupName,
      final int threadPriority,
      final boolean daemonThread,
      final boolean useVirtualThreads)
  {
    this(poolId, threadGroupName, threadPriority, daemonThread, useVirtualThreads, true);
  }

  public NexusThreadFactory(
      final String poolId,
      final String threadGroupName,
      final int threadPriority,
      final boolean daemonThread,
      final boolean useVirtualThreads,
      final boolean inheritInheritableThreadLocals)
  {
    int poolNum = poolNumber.getAndIncrement();
    this.schedulerThreadGroup = new ThreadGroup(threadGroupName + " #" + poolNum);
    this.namePrefix = poolId + "-" + poolNum + "-thread-";
    this.deamonThread = daemonThread;
    this.threadPriority = threadPriority;
    this.useVirtualThreads = useVirtualThreads;
    this.inheritInheritableThreadLocals = inheritInheritableThreadLocals;
  }

  public Thread newThread(final Runnable r) {
    if (useVirtualThreads) {
      return createVirtualThread(r);
    } else {
      return createPlatformThread(r);
    }
  }

  /**
   * Creates a platform thread with the configured properties.
   *
   * @param r the Runnable to be executed by the thread
   * @return a new platform thread
   */
  private Thread createPlatformThread(final Runnable r) {
    final Thread result = new Thread(schedulerThreadGroup, r, namePrefix + threadNumber.getAndIncrement());
    result.setDaemon(this.deamonThread);
    result.setPriority(this.threadPriority);
    return result;
  }

  /**
   * Creates a virtual thread with the configured properties using Java 21's Thread.Builder API.
   *
   * @param r the Runnable to be executed by the thread
   * @return a new virtual thread
   */
  private Thread createVirtualThread(final Runnable r) {
    String threadName = "vt-" + namePrefix + threadNumber.getAndIncrement();
    Thread.Builder.OfVirtual builder = Thread.ofVirtual();
    
    if (!inheritInheritableThreadLocals) {
      builder = builder.inheritInheritableThreadLocals(false);
    }
    
    Thread thread = builder.name(threadName).unstarted(r);
    
    // Virtual threads are always daemon threads, but we set it explicitly for clarity
    thread.setDaemon(true);
    
    // Note: Virtual threads ignore priority settings, but we don't need to warn about this
    // as it's a documented limitation of virtual threads
    
    return thread;
  }

  /**
   * Returns whether this factory creates virtual threads.
   *
   * @return true if this factory creates virtual threads, false otherwise
   */
  public boolean isUsingVirtualThreads() {
    return useVirtualThreads;
  }

  /**
   * Sets whether this factory should create virtual threads.
   *
   * @param useVirtualThreads true to create virtual threads, false to create platform threads
   */
  public void setUseVirtualThreads(boolean useVirtualThreads) {
    this.useVirtualThreads = useVirtualThreads;
  }

  /**
   * Returns whether virtual threads created by this factory inherit inheritable thread locals.
   *
   * @return true if virtual threads inherit inheritable thread locals, false otherwise
   */
  public boolean isInheritInheritableThreadLocals() {
    return inheritInheritableThreadLocals;
  }

  /**
   * Sets whether virtual threads created by this factory should inherit inheritable thread locals.
   *
   * @param inheritInheritableThreadLocals true to inherit thread locals, false otherwise
   */
  public void setInheritInheritableThreadLocals(boolean inheritInheritableThreadLocals) {
    this.inheritInheritableThreadLocals = inheritInheritableThreadLocals;
  }
}