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

import org.sonatype.nexus.security.subject.CurrentSubjectSupplier;
import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import org.apache.shiro.concurrent.SubjectAwareExecutorService;
import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A modification of Shiro's {@link SubjectAwareExecutorService} that in turn returns
 * always the same, supplied {@link Subject} to bind threads with.
 * <p>
 * This class supports both platform threads (traditional OS threads) and virtual threads (Java 21+).
 * Platform threads are suitable for CPU-bound tasks, while virtual threads are ideal for I/O-bound
 * operations like network calls, file operations, and database access.
 *
 * @since 2.6
 */
public class NexusExecutorService
    extends SubjectAwareExecutorService
{
  private final Supplier<Subject> subjectSupplier;

  public NexusExecutorService(final ExecutorService target, final Supplier<Subject> subjectSupplier) {
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
   * Creates a {@link NexusExecutorService} that binds a fixed {@link Subject} to platform threads.
   * <p>
   * This is suitable for CPU-bound tasks that require a specific security subject.
   *
   * @param target the executor service to delegate to
   * @param subject the fixed subject to associate with threads
   * @return a new executor service that associates the given subject with platform threads
   */
  public static NexusExecutorService forFixedSubject(final ExecutorService target, final Subject subject) {
    return new NexusExecutorService(target, () -> subject);
  }

  /**
   * Creates a {@link NexusExecutorService} that binds the current {@link Subject} to platform threads.
   * <p>
   * This is suitable for CPU-bound tasks that should inherit the current security context.
   *
   * @param target the executor service to delegate to
   * @return a new executor service that associates the current subject with platform threads
   */
  public static NexusExecutorService forCurrentSubject(final ExecutorService target) {
    return new NexusExecutorService(target, new CurrentSubjectSupplier());
  }
  
  /**
   * Creates a {@link NexusExecutorService} that uses virtual threads with a fixed {@link Subject}.
   * <p>
   * Virtual threads are lightweight threads that are ideal for I/O-bound operations like network calls,
   * file operations, and database access. They have significantly lower overhead than platform threads
   * and allow for much higher concurrency with minimal resource usage.
   * <p>
   * This method creates an executor that spawns a new virtual thread for each submitted task,
   * eliminating the need for thread pool sizing and management.
   *
   * @param subject the fixed subject to associate with virtual threads
   * @return a new executor service that associates the given subject with virtual threads
   * @since 3.60
   */
  public static NexusExecutorService forVirtualThreads(final Subject subject) {
    return new NexusExecutorService(Executors.newVirtualThreadPerTaskExecutor(), () -> subject);
  }
  
  /**
   * Creates a {@link NexusExecutorService} that uses virtual threads with the current {@link Subject}.
   * <p>
   * Virtual threads are lightweight threads that are ideal for I/O-bound operations like network calls,
   * file operations, and database access. They have significantly lower overhead than platform threads
   * and allow for much higher concurrency with minimal resource usage.
   * <p>
   * This method creates an executor that spawns a new virtual thread for each submitted task,
   * eliminating the need for thread pool sizing and management.
   *
   * @return a new executor service that associates the current subject with virtual threads
   * @since 3.60
   */
  public static NexusExecutorService forCurrentSubjectVirtualThreads() {
    return new NexusExecutorService(Executors.newVirtualThreadPerTaskExecutor(), new CurrentSubjectSupplier());
  }
}