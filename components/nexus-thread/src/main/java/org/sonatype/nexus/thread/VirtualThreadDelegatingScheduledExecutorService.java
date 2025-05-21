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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A {@link ScheduledExecutorService} implementation that delegates scheduling to a provided executor
 * but executes the actual tasks using Virtual Threads.
 * <p>
 * This implementation follows the "one virtual thread per task" principle recommended for Java 21 Virtual Threads.
 * It uses a single platform thread for scheduling but creates a new virtual thread for each task execution.
 * <p>
 * This approach is ideal for I/O-bound tasks that would otherwise block platform threads, such as:
 * <ul>
 *   <li>Network operations (HTTP requests, remote repository access)</li>
 *   <li>Database operations</li>
 *   <li>File system operations</li>
 * </ul>
 *
 * @since 3.60
 */
public class VirtualThreadDelegatingScheduledExecutorService
    implements ScheduledExecutorService
{
  private final ScheduledExecutorService delegate;
  private final ThreadFactory virtualThreadFactory;

  /**
   * Creates a new {@link VirtualThreadDelegatingScheduledExecutorService} with the specified delegate executor
   * and virtual thread factory.
   *
   * @param delegate the underlying {@link ScheduledExecutorService} to delegate scheduling to
   * @param virtualThreadFactory the factory to create virtual threads for task execution
   */
  public VirtualThreadDelegatingScheduledExecutorService(
      final ScheduledExecutorService delegate,
      final ThreadFactory virtualThreadFactory)
  {
    this.delegate = checkNotNull(delegate);
    this.virtualThreadFactory = checkNotNull(virtualThreadFactory);
  }

  @Override
  public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
    return delegate.schedule(() -> {
      // Create and start a new virtual thread for each task
      Thread virtualThread = virtualThreadFactory.newThread(command);
      virtualThread.start();
      try {
        virtualThread.join(); // Wait for the virtual thread to complete
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, delay, unit);
  }

  @Override
  public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
    // We need to wrap the callable in a task that will be executed by a virtual thread
    // and then return the result to the original future
    return delegate.schedule(() -> {
      // Create a wrapper that will execute the callable on a virtual thread
      FutureTask<V> futureTask = new FutureTask<>(callable);
      Thread virtualThread = virtualThreadFactory.newThread(futureTask);
      virtualThread.start();
      try {
        virtualThread.join(); // Wait for the virtual thread to complete
        return futureTask.get(); // Get the result from the future task
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      catch (ExecutionException e) {
        throw new RuntimeException(e.getCause());
      }
    }, delay, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
    return delegate.scheduleAtFixedRate(() -> {
      // Create and start a new virtual thread for each execution
      Thread virtualThread = virtualThreadFactory.newThread(command);
      virtualThread.start();
      try {
        virtualThread.join(); // Wait for the virtual thread to complete
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }, initialDelay, period, unit);
  }

  @Override
  public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
    return delegate.scheduleWithFixedDelay(() -> {
      // Create and start a new virtual thread for each execution
      Thread virtualThread = virtualThreadFactory.newThread(command);
      virtualThread.start();
      try {
        virtualThread.join(); // Wait for the virtual thread to complete
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
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
  public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
    return delegate.awaitTermination(timeout, unit);
  }

  @Override
  public <T> Future<T> submit(Callable<T> task) {
    return schedule(task, 0, TimeUnit.MILLISECONDS);
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return submit(() -> {
      task.run();
      return result;
    });
  }

  @Override
  public Future<?> submit(Runnable task) {
    return schedule(task, 0, TimeUnit.MILLISECONDS);
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public void execute(Runnable command) {
    submit(command);
  }

  /**
   * A simple implementation of a FutureTask that wraps a Callable and provides access to its result.
   *
   * @param <V> the result type
   */
  private static class FutureTask<V> implements Runnable {
    private final Callable<V> callable;
    private V result;
    private Throwable exception;

    FutureTask(Callable<V> callable) {
      this.callable = callable;
    }

    @Override
    public void run() {
      try {
        result = callable.call();
      }
      catch (Throwable e) {
        exception = e;
      }
    }

    public V get() throws ExecutionException {
      if (exception != null) {
        throw new ExecutionException(exception);
      }
      return result;
    }
  }
}