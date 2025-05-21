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
 * This class supports both platform threads and virtual threads (Java 21+). Virtual threads are lightweight
 * threads that are managed by the JVM rather than the operating system, allowing for much higher concurrency
 * with minimal resource overhead. They are particularly beneficial for I/O-bound operations where threads
 * spend most of their time waiting.
 * <p>
 * Use the {@link #forFixedSubjectVirtual(Subject)} or {@link #forCurrentSubjectVirtual()} factory methods
 * to create executor services backed by virtual threads.
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
   * Creates a {@link NexusExecutorService} with a fixed subject using the provided executor service.
   *
   * @param target the executor service to delegate to
   * @param subject the fixed subject to associate with all tasks
   * @return a new {@link NexusExecutorService}
   */
  public static NexusExecutorService forFixedSubject(final ExecutorService target, final Subject subject) {
    return new NexusExecutorService(target, () -> subject);
  }

  /**
   * Creates a {@link NexusExecutorService} that uses the current subject for each task using the provided executor service.
   *
   * @param target the executor service to delegate to
   * @return a new {@link NexusExecutorService}
   */
  public static NexusExecutorService forCurrentSubject(final ExecutorService target) {
    return new NexusExecutorService(target, new CurrentSubjectSupplier());
  }
  
  /**
   * Creates a {@link NexusExecutorService} with a fixed subject using a virtual thread per task executor.
   * <p>
   * This method creates an executor service that spawns a new virtual thread for each submitted task.
   * Virtual threads are lightweight threads that are managed by the JVM rather than the operating system,
   * allowing for much higher concurrency with minimal resource overhead.
   * <p>
   * This is particularly useful for I/O-bound operations where threads spend most of their time waiting,
   * such as network operations, file operations, or database queries.
   *
   * @param subject the fixed subject to associate with all tasks
   * @return a new {@link NexusExecutorService} backed by virtual threads
   * @since 3.60
   */
  public static NexusExecutorService forFixedSubjectVirtual(final Subject subject) {
    return new NexusExecutorService(Executors.newVirtualThreadPerTaskExecutor(), () -> subject);
  }

  /**
   * Creates a {@link NexusExecutorService} that uses the current subject for each task using a virtual thread per task executor.
   * <p>
   * This method creates an executor service that spawns a new virtual thread for each submitted task.
   * Virtual threads are lightweight threads that are managed by the JVM rather than the operating system,
   * allowing for much higher concurrency with minimal resource overhead.
   * <p>
   * This is particularly useful for I/O-bound operations where threads spend most of their time waiting,
   * such as network operations, file operations, or database queries.
   *
   * @return a new {@link NexusExecutorService} backed by virtual threads
   * @since 3.60
   */
  public static NexusExecutorService forCurrentSubjectVirtual() {
    return new NexusExecutorService(Executors.newVirtualThreadPerTaskExecutor(), new CurrentSubjectSupplier());
  }
}