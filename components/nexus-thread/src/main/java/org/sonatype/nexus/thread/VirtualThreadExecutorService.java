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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import org.apache.shiro.subject.Subject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An ExecutorService implementation that leverages Java 21 Virtual Threads for improved
 * performance with I/O-bound operations. This service ensures that MDC context and security
 * Subject are properly propagated to virtual threads.
 *
 * @since 3.60
 */
public class VirtualThreadExecutorService
    implements ExecutorService
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadExecutorService.class);
  
  private final ExecutorService delegate;
  private final Subject subject;

  /**
   * Creates a new VirtualThreadExecutorService with the specified delegate executor and
   * the current security Subject.
   *
   * @param delegate the underlying ExecutorService that should use virtual threads
   */
  public VirtualThreadExecutorService(final ExecutorService delegate) {
    this.delegate = checkNotNull(delegate);
    this.subject = org.apache.shiro.SecurityUtils.getSubject();
  }

  /**
   * Creates a new VirtualThreadExecutorService with the specified delegate executor and
   * security Subject.
   *
   * @param delegate the underlying ExecutorService that should use virtual threads
   * @param subject the security Subject to use for task execution
   */
  public VirtualThreadExecutorService(final ExecutorService delegate, final Subject subject) {
    this.delegate = checkNotNull(delegate);
    this.subject = checkNotNull(subject);
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
  public boolean awaitTermination(final long timeout, final TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public <T> Future<T> submit(final Callable<T> task) {
    checkNotNull(task);
    return delegate.submit(new MDCAwareCallable<>(subject, task));
  }

  @Override
  public <T> Future<T> submit(final Runnable task, final T result) {
    checkNotNull(task);
    return delegate.submit(new MDCAwareRunnable(subject, task), result);
  }

  @Override
  public Future<?> submit(final Runnable task) {
    checkNotNull(task);
    return delegate.submit(new MDCAwareRunnable(subject, task));
  }

  @Override
  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) throws InterruptedException {
    checkNotNull(tasks);
    return delegate.invokeAll(wrapTasks(tasks));
  }

  @Override
  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks,
                                      final long timeout,
                                      final TimeUnit unit) throws InterruptedException
  {
    checkNotNull(tasks);
    return delegate.invokeAll(wrapTasks(tasks), timeout, unit);
  }

  @Override
  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException
  {
    checkNotNull(tasks);
    return delegate.invokeAny(wrapTasks(tasks));
  }

  @Override
  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks,
                         final long timeout,
                         final TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException
  {
    checkNotNull(tasks);
    return delegate.invokeAny(wrapTasks(tasks), timeout, unit);
  }

  @Override
  public void execute(final Runnable command) {
    checkNotNull(command);
    delegate.execute(new MDCAwareRunnable(subject, command));
  }

  /**
   * Wraps a collection of Callable tasks with MDCAwareCallable to ensure proper context propagation.
   */
  private <T> Collection<? extends Callable<T>> wrapTasks(final Collection<? extends Callable<T>> tasks) {
    return tasks.stream()
        .map(task -> new MDCAwareCallable<T>(subject, task))
        .toList();
  }
}