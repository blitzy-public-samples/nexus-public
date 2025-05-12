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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.sonatype.nexus.security.subject.CurrentSubjectSupplier;
import org.sonatype.nexus.thread.internal.MDCAwareCallable;
import org.sonatype.nexus.thread.internal.MDCAwareRunnable;

import org.apache.shiro.subject.Subject;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link ScheduledExecutorService} implementation that launches scheduled tasks as virtual threads.
 * This implementation delegates scheduling operations to a provided {@link ScheduledExecutorService}
 * but executes the actual tasks using virtual threads, enabling high-concurrency scheduled operations
 * while maintaining security context and MDC propagation.
 *
 * @since 3.60
 */
public class VirtualThreadScheduledExecutorService
    implements ScheduledExecutorService
{
  private final ScheduledExecutorService delegate;
  private final Supplier<Subject> subjectSupplier;
  private final ExecutorService virtualThreadExecutor;

  /**
   * Creates a new instance with the given delegate and subject supplier.
   *
   * @param delegate the {@link ScheduledExecutorService} to delegate scheduling operations to
   * @param subjectSupplier the supplier of {@link Subject} to bind to virtual threads
   */
  public VirtualThreadScheduledExecutorService(final ScheduledExecutorService delegate, 
                                              final Supplier<Subject> subjectSupplier) {
    this.delegate = checkNotNull(delegate);
    this.subjectSupplier = checkNotNull(subjectSupplier);
    this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }

  /**
   * Wraps the given {@link Runnable} to execute in a virtual thread with the current subject.
   */
  private Runnable wrapRunnable(final Runnable runnable) {
    return () -> {
      Subject subject = subjectSupplier.get();
      Runnable mdcAwareRunnable = new MDCAwareRunnable(runnable);
      Runnable subjectBoundRunnable = subject.associateWith(mdcAwareRunnable);
      virtualThreadExecutor.execute(subjectBoundRunnable);
    };
  }

  /**
   * Wraps the given {@link Callable} to execute in a virtual thread with the current subject.
   */
  private <V> Callable<V> wrapCallable(final Callable<V> callable) {
    return () -> {
      Subject subject = subjectSupplier.get();
      Callable<V> mdcAwareCallable = new MDCAwareCallable<>(callable);
      Callable<V> subjectBoundCallable = subject.associateWith(mdcAwareCallable);
      Future<V> future = virtualThreadExecutor.submit(subjectBoundCallable);
      return future.get();
    };
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return delegate.schedule(wrapRunnable(command), delay, unit);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    return delegate.schedule(wrapCallable(callable), delay, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
    return delegate.scheduleAtFixedRate(wrapRunnable(command), initialDelay, period, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return delegate.scheduleWithFixedDelay(wrapRunnable(command), initialDelay, delay, unit);
  }

  // ExecutorService methods delegated to the virtualThreadExecutor

  @Override
  public void execute(Runnable command) {
    Subject subject = subjectSupplier.get();
    Runnable mdcAwareRunnable = new MDCAwareRunnable(command);
    Runnable subjectBoundRunnable = subject.associateWith(mdcAwareRunnable);
    virtualThreadExecutor.execute(subjectBoundRunnable);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    Subject subject = subjectSupplier.get();
    Callable<T> mdcAwareCallable = new MDCAwareCallable<>(task);
    Callable<T> subjectBoundCallable = subject.associateWith(mdcAwareCallable);
    return virtualThreadExecutor.submit(subjectBoundCallable);
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    Subject subject = subjectSupplier.get();
    Runnable mdcAwareRunnable = new MDCAwareRunnable(task);
    Runnable subjectBoundRunnable = subject.associateWith(mdcAwareRunnable);
    return virtualThreadExecutor.submit(subjectBoundRunnable, result);
  }

  @Override
  public Future<?> submit(Runnable task) {
    Subject subject = subjectSupplier.get();
    Runnable mdcAwareRunnable = new MDCAwareRunnable(task);
    Runnable subjectBoundRunnable = subject.associateWith(mdcAwareRunnable);
    return virtualThreadExecutor.submit(subjectBoundRunnable);
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
    Collection<Callable<T>> wrappedTasks = tasks.stream()
        .map(this::wrapWithSubjectAndMdc)
        .toList();
    return virtualThreadExecutor.invokeAll(wrappedTasks);
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    Collection<Callable<T>> wrappedTasks = tasks.stream()
        .map(this::wrapWithSubjectAndMdc)
        .toList();
    return virtualThreadExecutor.invokeAll(wrappedTasks, timeout, unit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    Collection<Callable<T>> wrappedTasks = tasks.stream()
        .map(this::wrapWithSubjectAndMdc)
        .toList();
    return virtualThreadExecutor.invokeAny(wrappedTasks);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    Collection<Callable<T>> wrappedTasks = tasks.stream()
        .map(this::wrapWithSubjectAndMdc)
        .toList();
    return virtualThreadExecutor.invokeAny(wrappedTasks, timeout, unit);
  }

  private <T> Callable<T> wrapWithSubjectAndMdc(Callable<T> task) {
    Subject subject = subjectSupplier.get();
    Callable<T> mdcAwareCallable = new MDCAwareCallable<>(task);
    return subject.associateWith(mdcAwareCallable);
  }

  // Shutdown methods delegated to both executors

  @Override
  public void shutdown() {
    delegate.shutdown();
    virtualThreadExecutor.shutdown();
  }

  @Override
  public List<Runnable> shutdownNow() {
    delegate.shutdownNow();
    return virtualThreadExecutor.shutdownNow();
  }

  @Override
  public boolean isShutdown() {
    return delegate.isShutdown() && virtualThreadExecutor.isShutdown();
  }

  @Override
  public boolean isTerminated() {
    return delegate.isTerminated() && virtualThreadExecutor.isTerminated();
  }

  @Override
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    long startNanos = System.nanoTime();
    boolean delegateTerminated = delegate.awaitTermination(timeout, unit);
    long elapsedNanos = System.nanoTime() - startNanos;
    long remainingNanos = unit.toNanos(timeout) - elapsedNanos;
    if (remainingNanos <= 0) {
      return delegateTerminated && virtualThreadExecutor.isTerminated();
    }
    return delegateTerminated && virtualThreadExecutor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
  }

  //
  // Factory methods
  //

  /**
   * Creates a new {@link VirtualThreadScheduledExecutorService} that binds the fixed subject to all tasks.
   *
   * @param delegate the {@link ScheduledExecutorService} to delegate scheduling operations to
   * @param subject the fixed {@link Subject} to bind to all tasks
   * @return a new {@link VirtualThreadScheduledExecutorService}
   */
  public static VirtualThreadScheduledExecutorService forFixedSubject(
      final ScheduledExecutorService delegate,
      final Subject subject)
  {
    return new VirtualThreadScheduledExecutorService(delegate, () -> subject);
  }

  /**
   * Creates a new {@link VirtualThreadScheduledExecutorService} that binds the current subject to all tasks.
   *
   * @param delegate the {@link ScheduledExecutorService} to delegate scheduling operations to
   * @return a new {@link VirtualThreadScheduledExecutorService}
   */
  public static VirtualThreadScheduledExecutorService forCurrentSubject(final ScheduledExecutorService delegate) {
    return new VirtualThreadScheduledExecutorService(delegate, new CurrentSubjectSupplier());
  }