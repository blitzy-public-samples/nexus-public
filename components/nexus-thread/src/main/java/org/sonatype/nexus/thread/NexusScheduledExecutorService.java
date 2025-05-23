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
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

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
 * This implementation supports both platform threads and Java 21 Virtual Threads. Virtual Threads are lightweight threads
 * that are managed by the JVM rather than the operating system. They are particularly well-suited for I/O-bound operations
 * such as network requests, file operations, and database queries.
 * <p>
 * When to use Virtual Threads:
 * <ul>
 *   <li>For I/O-bound tasks that spend most of their time waiting for external resources</li>
 *   <li>When you need to handle a large number of concurrent operations</li>
 *   <li>For tasks that involve blocking operations like network or file I/O</li>
 * </ul>
 * <p>
 * When to use Platform Threads:
 * <ul>
 *   <li>For CPU-intensive tasks that require continuous computation</li>
 *   <li>For tasks that use native code or synchronized blocks extensively</li>
 *   <li>For long-running background tasks that don't involve much blocking</li>
 * </ul>
 *
 * @since 3.31
 */
public class NexusScheduledExecutorService
    extends SubjectAwareScheduledExecutorService
{
  private final Supplier<Subject> subjectSupplier;
  private final boolean usingVirtualThreads;

  /**
   * Creates a new NexusScheduledExecutorService with the specified target executor and subject supplier.
   *
   * @param target the underlying ScheduledExecutorService to delegate to
   * @param subjectSupplier the supplier of Shiro Subject to associate with threads
   */
  public NexusScheduledExecutorService(final ScheduledExecutorService target, final Supplier<Subject> subjectSupplier) {
    super(checkNotNull(target));
    this.subjectSupplier = checkNotNull(subjectSupplier);
    this.usingVirtualThreads = false; // Default constructor assumes platform threads
  }

  /**
   * Creates a new NexusScheduledExecutorService with the specified target executor, subject supplier, and thread type.
   *
   * @param target the underlying ScheduledExecutorService to delegate to
   * @param subjectSupplier the supplier of Shiro Subject to associate with threads
   * @param usingVirtualThreads true if this executor is using virtual threads, false otherwise
   */
  protected NexusScheduledExecutorService(final ScheduledExecutorService target, 
                                        final Supplier<Subject> subjectSupplier,
                                        final boolean usingVirtualThreads) {
    super(checkNotNull(target));
    this.subjectSupplier = checkNotNull(subjectSupplier);
    this.usingVirtualThreads = usingVirtualThreads;
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

  /**
   * Returns whether this executor service is using virtual threads.
   *
   * @return true if this executor is using virtual threads, false if using platform threads
   */
  public boolean isUsingVirtualThreads() {
    return usingVirtualThreads;
  }

  //
  // Factory access
  //

  /**
   * Creates a NexusScheduledExecutorService that always uses the specified fixed subject.
   *
   * @param target the underlying ScheduledExecutorService to delegate to
   * @param subject the Shiro Subject to associate with threads
   * @return a new NexusScheduledExecutorService instance
   */
  public static NexusScheduledExecutorService forFixedSubject(
      final ScheduledExecutorService target,
      final Subject subject)
  {
    return new NexusScheduledExecutorService(target, () -> subject);
  }

  /**
   * Creates a NexusScheduledExecutorService that uses the current subject for each task.
   *
   * @param target the underlying ScheduledExecutorService to delegate to
   * @return a new NexusScheduledExecutorService instance
   */
  public static NexusScheduledExecutorService forCurrentSubject(final ScheduledExecutorService target) {
    return new NexusScheduledExecutorService(target, new CurrentSubjectSupplier());
  }

  /**
   * Creates a NexusScheduledExecutorService that uses Virtual Threads for task execution.
   * This is optimal for I/O-bound tasks that spend most of their time waiting for external resources.
   * <p>
   * Virtual Threads are lightweight threads managed by the JVM rather than the OS, allowing for much higher
   * concurrency with minimal resource overhead. They automatically yield during blocking operations,
   * making them ideal for tasks that involve network or file I/O.
   *
   * @param corePoolSize the number of threads to keep in the scheduler pool
   * @param threadNamePrefix prefix to use for the created threads
   * @param subject the Shiro Subject to associate with threads
   * @return a new NexusScheduledExecutorService instance using Virtual Threads
   * @since 3.60
   */
  public static NexusScheduledExecutorService forFixedSubjectWithVirtualThreads(
      final int corePoolSize,
      final String threadNamePrefix,
      final Subject subject)
  {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name(threadNamePrefix, 0)
        .factory();
    
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(corePoolSize, virtualThreadFactory);
    return new NexusScheduledExecutorService(scheduler, () -> subject, true);
  }

  /**
   * Creates a NexusScheduledExecutorService that uses Virtual Threads for task execution and
   * the current subject for each task.
   * <p>
   * This is optimal for I/O-bound tasks that spend most of their time waiting for external resources
   * and need to execute with the security context of the current subject.
   *
   * @param corePoolSize the number of threads to keep in the scheduler pool
   * @param threadNamePrefix prefix to use for the created threads
   * @return a new NexusScheduledExecutorService instance using Virtual Threads
   * @since 3.60
   */
  public static NexusScheduledExecutorService forCurrentSubjectWithVirtualThreads(
      final int corePoolSize,
      final String threadNamePrefix)
  {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name(threadNamePrefix, 0)
        .factory();
    
    ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(corePoolSize, virtualThreadFactory);
    return new NexusScheduledExecutorService(scheduler, new CurrentSubjectSupplier(), true);
  }

  /**
   * Creates a NexusScheduledExecutorService that uses a single Virtual Thread for task execution.
   * This is useful for tasks that need to be executed sequentially but can benefit from the lightweight
   * nature of Virtual Threads for I/O operations.
   *
   * @param threadName name to use for the created thread
   * @param subject the Shiro Subject to associate with threads
   * @return a new NexusScheduledExecutorService instance using a single Virtual Thread
   * @since 3.60
   */
  public static NexusScheduledExecutorService forFixedSubjectWithSingleVirtualThread(
      final String threadName,
      final Subject subject)
  {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name(threadName)
        .factory();
    
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(virtualThreadFactory);
    return new NexusScheduledExecutorService(scheduler, () -> subject, true);
  }

  /**
   * Creates a NexusScheduledExecutorService that uses a single Virtual Thread for task execution
   * and the current subject for each task.
   *
   * @param threadName name to use for the created thread
   * @return a new NexusScheduledExecutorService instance using a single Virtual Thread
   * @since 3.60
   */
  public static NexusScheduledExecutorService forCurrentSubjectWithSingleVirtualThread(final String threadName) {
    ThreadFactory virtualThreadFactory = Thread.ofVirtual()
        .name(threadName)
        .factory();
    
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(virtualThreadFactory);
    return new NexusScheduledExecutorService(scheduler, new CurrentSubjectSupplier(), true);
  }
}
