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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;

import org.sonatype.nexus.thread.ScheduledExecutorServiceVirtualThreadAdapter;

import org.sonatype.nexus.security.subject.CurrentSubjectSupplier;
import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import org.apache.shiro.concurrent.SubjectAwareScheduledExecutorService;
import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A modification of Shiro's {@link SubjectAwareScheduledExecutorService} that in turn returns always the same, supplied
 * {@link Subject} to bind threads with.
 * <p>
 * This class provides factory methods for creating scheduled executor services using either platform threads
 * (traditional threads managed by the OS) or virtual threads (lightweight threads managed by the JVM).
 * <p>
 * Virtual threads are recommended for I/O-bound operations as they provide higher throughput with lower resource
 * consumption compared to platform threads. They are particularly well-suited for tasks that spend most of their time
 * waiting for I/O operations to complete, such as network requests or database operations.
 *
 * @since 3.31
 */
public class NexusScheduledExecutorService
    extends SubjectAwareScheduledExecutorService
{
  private final Supplier<Subject> subjectSupplier;

  public NexusScheduledExecutorService(final ScheduledExecutorService target, final Supplier<Subject> subjectSupplier) {
    super(checkNotNull(target));
    this.subjectSupplier = checkNotNull(subjectSupplier);
  }

  /**
   * Override, use our SubjectProvider to get subject from.
   */
  @Override
  protected Subject getSubject() {
    return subjectSupplier.get();
  }

  @Override
  protected Runnable associateWithSubject(Runnable r) {
    Subject subject = getSubject();
    return subject.associateWith(new MDCAwareRunnable(r));
  }

  @Override
  protected <T> Callable<T> associateWithSubject(Callable<T> task) {
    Subject subject = getSubject();
    return subject.associateWith(new MDCAwareCallable<>(task));
  }

  //
  // Factory access
  //

  /**
   * Creates a {@link NexusScheduledExecutorService} with a fixed subject using platform threads.
   *
   * @param target the underlying scheduled executor service
   * @param subject the fixed subject to use for all tasks
   * @return a new {@link NexusScheduledExecutorService} instance
   */
  public static NexusScheduledExecutorService forFixedSubject(
      final ScheduledExecutorService target,
      final Subject subject)
  {
    return new NexusScheduledExecutorService(target, () -> subject);
  }

  /**
   * Creates a {@link NexusScheduledExecutorService} using the current subject and platform threads.
   *
   * @param target the underlying scheduled executor service
   * @return a new {@link NexusScheduledExecutorService} instance
   */
  public static NexusScheduledExecutorService forCurrentSubject(final ScheduledExecutorService target) {
    return new NexusScheduledExecutorService(target, new CurrentSubjectSupplier());
  }

  /**
   * Creates a {@link NexusScheduledExecutorService} with a fixed subject using virtual threads.
   * <p>
   * This method creates a scheduled executor service that uses virtual threads for executing tasks.
   * Virtual threads are lightweight threads that are managed by the JVM rather than the OS, making them
   * ideal for I/O-bound operations where tasks spend most of their time waiting.
   * <p>
   * Note: The underlying implementation uses a single-threaded scheduled executor to manage the scheduling
   * of tasks, but each task is executed on a new virtual thread. This approach provides the benefits of
   * virtual threads while maintaining the scheduling capabilities of {@link ScheduledExecutorService}.
   *
   * @param corePoolSize the number of threads to keep in the scheduler pool
   * @param subject the fixed subject to use for all tasks
   * @return a new {@link NexusScheduledExecutorService} instance using virtual threads
   * @since 3.60
   */
  public static NexusScheduledExecutorService forFixedSubjectWithVirtualThreads(
      final int corePoolSize,
      final Subject subject)
  {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(corePoolSize);
    scheduler.setRemoveOnCancelPolicy(true);
    
    // Wrap the scheduler to execute tasks on virtual threads
    ScheduledExecutorService virtualThreadScheduler = new ScheduledExecutorServiceVirtualThreadAdapter(scheduler);
    
    return new NexusScheduledExecutorService(virtualThreadScheduler, () -> subject);
  }

  /**
   * Creates a {@link NexusScheduledExecutorService} using the current subject and virtual threads.
   * <p>
   * This method creates a scheduled executor service that uses virtual threads for executing tasks.
   * Virtual threads are lightweight threads that are managed by the JVM rather than the OS, making them
   * ideal for I/O-bound operations where tasks spend most of their time waiting.
   * <p>
   * Note: The underlying implementation uses a single-threaded scheduled executor to manage the scheduling
   * of tasks, but each task is executed on a new virtual thread. This approach provides the benefits of
   * virtual threads while maintaining the scheduling capabilities of {@link ScheduledExecutorService}.
   *
   * @param corePoolSize the number of threads to keep in the scheduler pool
   * @return a new {@link NexusScheduledExecutorService} instance using virtual threads
   * @since 3.60
   */
  public static NexusScheduledExecutorService forCurrentSubjectWithVirtualThreads(final int corePoolSize) {
    ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(corePoolSize);
    scheduler.setRemoveOnCancelPolicy(true);
    
    // Wrap the scheduler to execute tasks on virtual threads
    ScheduledExecutorService virtualThreadScheduler = new ScheduledExecutorServiceVirtualThreadAdapter(scheduler);
    
    return new NexusScheduledExecutorService(virtualThreadScheduler, new CurrentSubjectSupplier());
  }
}