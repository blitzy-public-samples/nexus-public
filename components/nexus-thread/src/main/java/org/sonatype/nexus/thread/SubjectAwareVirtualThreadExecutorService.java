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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.sonatype.nexus.security.subject.CurrentSubjectSupplier;
import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An {@link ExecutorService} implementation that uses Java 21 Virtual Threads and ensures
 * proper propagation of Shiro {@link Subject} and MDC context across virtual thread boundaries.
 * <p>
 * This executor creates a new virtual thread for each submitted task, which is ideal for I/O-bound
 * operations where threads might block, such as network requests, database operations, or file I/O.
 * <p>
 * Unlike traditional thread pools, virtual threads are lightweight and managed by the JVM, allowing
 * for a much higher number of concurrent operations without the overhead of platform threads.
 *
 * @since 3.60
 */
public class SubjectAwareVirtualThreadExecutorService
    implements ExecutorService
{
  private final ExecutorService delegate;
  private final Supplier<Subject> subjectSupplier;

  /**
   * Creates a new executor service that uses virtual threads and associates tasks with the subject
   * provided by the given supplier.
   *
   * @param subjectSupplier the supplier of the subject to associate with submitted tasks
   */
  public SubjectAwareVirtualThreadExecutorService(final Supplier<Subject> subjectSupplier) {
    this.delegate = Executors.newVirtualThreadPerTaskExecutor();
    this.subjectSupplier = checkNotNull(subjectSupplier);
  }

  /**
   * Returns the subject to associate with submitted tasks.
   */
  protected Subject getSubject() {
    return subjectSupplier.get();
  }

  /**
   * Wraps the given runnable to ensure it executes with the proper subject and MDC context.
   */
  protected Runnable associateWithSubject(Runnable runnable) {
    Subject subject = getSubject();
    return subject.associateWith(new MDCAwareRunnable(runnable));
  }

  /**
   * Wraps the given callable to ensure it executes with the proper subject and MDC context.
   */
  protected <T> Callable<T> associateWithSubject(Callable<T> task) {
    Subject subject = getSubject();
    return subject.associateWith(new MDCAwareCallable<>(task));
  }

  @Override
  public void execute(Runnable command) {
    delegate.execute(associateWithSubject(command));
  }

  @Override
  public Future<?> submit(Runnable task) {
    return delegate.submit(associateWithSubject(task));
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return delegate.submit(associateWithSubject(task), result);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return delegate.submit(associateWithSubject(task));
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
    return delegate.invokeAll(tasks.stream().map(this::associateWithSubject).toList());
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException
  {
    return delegate.invokeAll(tasks.stream().map(this::associateWithSubject).toList(), timeout, unit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
    return delegate.invokeAny(tasks.stream().map(this::associateWithSubject).toList());
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException
  {
    return delegate.invokeAny(tasks.stream().map(this::associateWithSubject).toList(), timeout, unit);
  }

  @Override
  public void shutdown() {
    delegate.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    return delegate.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  //
  // Factory access
  //

  /**
   * Creates a new executor service that uses virtual threads and associates tasks with the given fixed subject.
   *
   * @param subject the subject to associate with submitted tasks
   * @return a new virtual thread executor service
   */
  public static SubjectAwareVirtualThreadExecutorService forFixedSubject(final Subject subject) {
    return new SubjectAwareVirtualThreadExecutorService(() -> subject);
  }

  /**
   * Creates a new executor service that uses virtual threads and associates tasks with the current subject.
   *
   * @return a new virtual thread executor service
   */
  public static SubjectAwareVirtualThreadExecutorService forCurrentSubject() {
    return new SubjectAwareVirtualThreadExecutorService(new CurrentSubjectSupplier());
  }
}