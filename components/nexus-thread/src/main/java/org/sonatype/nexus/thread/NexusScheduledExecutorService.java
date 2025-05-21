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
 * This implementation supports both platform threads and virtual threads (Java 21+) for scheduled tasks.
 * Virtual threads are particularly beneficial for I/O-bound operations like remote repository access,
 * database operations, and file system operations.
 *
 * @since 3.31
 */
public class NexusScheduledExecutorService
    extends SubjectAwareScheduledExecutorService
{
  private final Supplier<Subject> subjectSupplier;

  /**
   * Creates a new {@link NexusScheduledExecutorService} with the specified target executor and subject supplier.
   *
   * @param target the underlying {@link ScheduledExecutorService} to delegate to
   * @param subjectSupplier the supplier of the {@link Subject} to bind tasks with
   */
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
   * Creates a {@link NexusScheduledExecutorService} that binds all tasks to a fixed subject.
   * Uses platform threads for execution.
   *
   * @param target the underlying {@link ScheduledExecutorService} to delegate to
   * @param subject the fixed {@link Subject} to bind tasks with
   * @return a new {@link NexusScheduledExecutorService} instance
   */
  public static NexusScheduledExecutorService forFixedSubject(
      final ScheduledExecutorService target,
      final Subject subject)
  {
    return new NexusScheduledExecutorService(target, () -> subject);
  }

  /**
   * Creates a {@link NexusScheduledExecutorService} that binds all tasks to the current subject.
   * Uses platform threads for execution.
   *
   * @param target the underlying {@link ScheduledExecutorService} to delegate to
   * @return a new {@link NexusScheduledExecutorService} instance
   */
  public static NexusScheduledExecutorService forCurrentSubject(final ScheduledExecutorService target) {
    return new NexusScheduledExecutorService(target, new CurrentSubjectSupplier());
  }
  
  /**
   * Creates a {@link NexusScheduledExecutorService} that uses Virtual Threads for task execution
   * and binds all tasks to a fixed subject.
   * <p>
   * Virtual Threads are particularly well-suited for I/O-bound operations such as:
   * <ul>
   *   <li>Remote repository access</li>
   *   <li>Database operations</li>
   *   <li>File system operations</li>
   * </ul>
   * <p>
   * Note: This method creates a single-threaded scheduler that delegates actual work to Virtual Threads.
   * Do not use this for CPU-intensive tasks, as those are better served by platform threads.
   *
   * @param subject the fixed {@link Subject} to bind tasks with
   * @return a new {@link NexusScheduledExecutorService} instance using Virtual Threads
   * @since 3.60
   */
  public static NexusScheduledExecutorService forFixedSubjectWithVirtualThreads(final Subject subject) {
    // Create a single-threaded scheduler that will delegate work to virtual threads
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("nexus-virtual-", 0).factory();
    
    // Create an executor service that uses virtual threads
    ScheduledExecutorService virtualThreadScheduler = new VirtualThreadDelegatingScheduledExecutorService(
        scheduler, virtualThreadFactory);
    
    return new NexusScheduledExecutorService(virtualThreadScheduler, () -> subject);
  }

  /**
   * Creates a {@link NexusScheduledExecutorService} that uses Virtual Threads for task execution
   * and binds all tasks to the current subject.
   * <p>
   * Virtual Threads are particularly well-suited for I/O-bound operations such as:
   * <ul>
   *   <li>Remote repository access</li>
   *   <li>Database operations</li>
   *   <li>File system operations</li>
   * </ul>
   * <p>
   * Note: This method creates a single-threaded scheduler that delegates actual work to Virtual Threads.
   * Do not use this for CPU-intensive tasks, as those are better served by platform threads.
   *
   * @return a new {@link NexusScheduledExecutorService} instance using Virtual Threads
   * @since 3.60
   */
  public static NexusScheduledExecutorService forCurrentSubjectWithVirtualThreads() {
    // Create a single-threaded scheduler that will delegate work to virtual threads
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("nexus-virtual-", 0).factory();
    
    // Create an executor service that uses virtual threads
    ScheduledExecutorService virtualThreadScheduler = new VirtualThreadDelegatingScheduledExecutorService(
        scheduler, virtualThreadFactory);
    
    return new NexusScheduledExecutorService(virtualThreadScheduler, new CurrentSubjectSupplier());
  }
}