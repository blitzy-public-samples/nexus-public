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
 * Supports both platform threads (via traditional thread pools) and virtual threads (via Java 21's
 * virtual thread per task executor). Virtual threads are recommended for I/O-bound operations to achieve
 * higher throughput with minimal resource overhead.
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
   * Creates a {@link NexusExecutorService} that binds a fixed subject to tasks executed by the target executor.
   *
   * @param target the underlying executor service
   * @param subject the subject to bind to all tasks
   * @return a new executor service that binds the given subject to all tasks
   */
  public static NexusExecutorService forFixedSubject(final ExecutorService target, final Subject subject) {
    return new NexusExecutorService(target, () -> subject);
  }

  /**
   * Creates a {@link NexusExecutorService} that binds the current subject to tasks executed by the target executor.
   *
   * @param target the underlying executor service
   * @return a new executor service that binds the current subject to all tasks
   */
  public static NexusExecutorService forCurrentSubject(final ExecutorService target) {
    return new NexusExecutorService(target, new CurrentSubjectSupplier());
  }
  
  /**
   * Creates a {@link NexusExecutorService} that uses Java 21 virtual threads and binds a fixed subject to all tasks.
   * <p>
   * Virtual threads are lightweight threads that are well-suited for I/O-bound operations. They have minimal
   * overhead compared to platform threads, allowing for much higher concurrency with fewer resources.
   * <p>
   * This executor is ideal for operations like remote repository access, file operations, and database queries.
   *
   * @param subject the subject to bind to all tasks
   * @return a new executor service using virtual threads that binds the given subject to all tasks
   * @since 3.60
   */
  public static NexusExecutorService forVirtualThreads(final Subject subject) {
    return new NexusExecutorService(Executors.newVirtualThreadPerTaskExecutor(), () -> subject);
  }
  
  /**
   * Creates a {@link NexusExecutorService} that uses Java 21 virtual threads and binds the current subject to all tasks.
   * <p>
   * Virtual threads are lightweight threads that are well-suited for I/O-bound operations. They have minimal
   * overhead compared to platform threads, allowing for much higher concurrency with fewer resources.
   * <p>
   * This executor is ideal for operations like remote repository access, file operations, and database queries.
   *
   * @return a new executor service using virtual threads that binds the current subject to all tasks
   * @since 3.60
   */
  public static NexusExecutorService forVirtualThreads() {
    return new NexusExecutorService(Executors.newVirtualThreadPerTaskExecutor(), new CurrentSubjectSupplier());
  }
}
