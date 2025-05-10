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
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An adapter that wraps a {@link ScheduledExecutorService} to execute tasks on virtual threads.
 * <p>
 * This adapter delegates scheduling to the underlying executor service but executes each task
 * on a new virtual thread using {@link Executors#newVirtualThreadPerTaskExecutor()}.
 * <p>
 * This approach combines the scheduling capabilities of a traditional {@link ScheduledExecutorService}
 * with the efficiency of virtual threads for task execution, making it ideal for I/O-bound
 * scheduled tasks.
 *
 * @since 3.60
 */
public class ScheduledExecutorServiceVirtualThreadAdapter
    implements ScheduledExecutorService
{
  private final ScheduledExecutorService delegate;

  /**
   * Creates a new adapter that wraps the given scheduled executor service.
   *
   * @param delegate the underlying scheduled executor service
   */
  public ScheduledExecutorServiceVirtualThreadAdapter(final ScheduledExecutorService delegate) {
    this.delegate = checkNotNull(delegate);
  }

  @Override
  public ScheduledFuture<?> schedule(final Runnable command, final long delay, final TimeUnit unit) {
    return delegate.schedule(() -> {
      Executors.newVirtualThreadPerTaskExecutor().submit(command).get();
      return null;
    }, delay, unit);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(final Callable<V> callable, final long delay, final TimeUnit unit) {
    return delegate.schedule(() -> {
      return Executors.newVirtualThreadPerTaskExecutor().submit(callable).get();
    }, delay, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(final Runnable command, final long initialDelay, final long period,
                                               final TimeUnit unit)
  {
    return delegate.scheduleAtFixedRate(() -> {
      try {
        Executors.newVirtualThreadPerTaskExecutor().submit(command).get();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException(e.getCause());
      }
    }, initialDelay, period, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay, final long delay,
                                                  final TimeUnit unit)
  {
    return delegate.scheduleWithFixedDelay(() -> {
      try {
        Executors.newVirtualThreadPerTaskExecutor().submit(command).get();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException(e.getCause());
      }
    }, initialDelay, delay, unit);
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
    return delegate.submit(() -> {
      return Executors.newVirtualThreadPerTaskExecutor().submit(task).get();
    });
  }

  @Override
  public <T> Future<T> submit(final Runnable task, final T result) {
    return delegate.submit(() -> {
      Executors.newVirtualThreadPerTaskExecutor().submit(task).get();
      return result;
    });
  }

  @Override
  public Future<?> submit(final Runnable task) {
    return delegate.submit(() -> {
      Executors.newVirtualThreadPerTaskExecutor().submit(task).get();
      return null;
    });
  }

  @Override
  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) throws InterruptedException {
    return delegate.invokeAll(tasks.stream()
        .map(task -> (Callable<T>) () -> Executors.newVirtualThreadPerTaskExecutor().submit(task).get())
        .toList());
  }

  @Override
  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout,
                                      final TimeUnit unit)
      throws InterruptedException
  {
    return delegate.invokeAll(
        tasks.stream()
            .map(task -> (Callable<T>) () -> Executors.newVirtualThreadPerTaskExecutor().submit(task).get())
            .toList(),
        timeout, unit);
  }

  @Override
  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException
  {
    return delegate.invokeAny(tasks.stream()
        .map(task -> (Callable<T>) () -> Executors.newVirtualThreadPerTaskExecutor().submit(task).get())
        .toList());
  }

  @Override
  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException
  {
    return delegate.invokeAny(
        tasks.stream()
            .map(task -> (Callable<T>) () -> Executors.newVirtualThreadPerTaskExecutor().submit(task).get())
            .toList(),
        timeout, unit);
  }

  @Override
  public void execute(final Runnable command) {
    delegate.execute(() -> {
      try {
        Executors.newVirtualThreadPerTaskExecutor().submit(command).get();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      catch (ExecutionException e) {
        if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new RuntimeException(e.getCause());
      }
    });
  }
}