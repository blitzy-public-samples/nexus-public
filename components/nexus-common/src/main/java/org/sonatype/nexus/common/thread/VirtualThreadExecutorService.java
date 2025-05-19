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
package org.sonatype.nexus.common.thread;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * An {@link ExecutorService} implementation that uses Java 21 Virtual Threads for improved scalability
 * and reduced resource consumption for I/O-bound operations.
 *
 * @since 3.60
 */
public class VirtualThreadExecutorService
    implements ExecutorService
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadExecutorService.class);

  private final ExecutorService delegate;

  /**
   * Creates a new {@link VirtualThreadExecutorService} that delegates to the provided executor service.
   *
   * @param delegate the executor service to delegate to, typically created with
   *                 {@code Executors.newVirtualThreadPerTaskExecutor()}
   */
  public VirtualThreadExecutorService(final ExecutorService delegate) {
    this.delegate = checkNotNull(delegate);
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
    return delegate.submit(task);
  }

  @Override
  public <T> Future<T> submit(final Runnable task, final T result) {
    return delegate.submit(task, result);
  }

  @Override
  public Future<?> submit(final Runnable task) {
    return delegate.submit(task);
  }

  @Override
  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks) throws InterruptedException {
    return delegate.invokeAll(tasks);
  }

  @Override
  public <T> List<Future<T>> invokeAll(final Collection<? extends Callable<T>> tasks, final long timeout,
                                      final TimeUnit unit) throws InterruptedException
  {
    return delegate.invokeAll(tasks, timeout, unit);
  }

  @Override
  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException
  {
    return delegate.invokeAny(tasks);
  }

  @Override
  public <T> T invokeAny(final Collection<? extends Callable<T>> tasks, final long timeout, final TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException
  {
    return delegate.invokeAny(tasks, timeout, unit);
  }

  @Override
  public void execute(final Runnable command) {
    delegate.execute(command);
  }

  /**
   * Executes the given supplier asynchronously using a virtual thread.
   *
   * @param supplier the supplier to execute
   * @param <T> the type of the result
   * @return a CompletableFuture that will complete with the result of the supplier
   */
  public <T> CompletableFuture<T> supplyAsync(final Supplier<T> supplier) {
    return CompletableFuture.supplyAsync(supplier, delegate);
  }

  /**
   * Executes the given runnable asynchronously using a virtual thread.
   *
   * @param runnable the runnable to execute
   * @return a CompletableFuture that will complete when the runnable completes
   */
  public CompletableFuture<Void> runAsync(final Runnable runnable) {
    return CompletableFuture.runAsync(runnable, delegate);
  }
}