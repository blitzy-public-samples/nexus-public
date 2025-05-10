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
 * A ScheduledExecutorService implementation that launches scheduled tasks as virtual threads,
 * enabling high-concurrency scheduled operations while maintaining security context and MDC propagation.
 * <p>
 * This implementation supports all standard scheduling operations with the performance benefits of virtual threads.
 * It leverages Java 21's virtual threads to significantly improve throughput for I/O-bound scheduled tasks
 * while maintaining the same security context and logging context as the original thread.
 *
 * @since 3.60
 */
public class VirtualThreadScheduledExecutorService
    implements ScheduledExecutorService
{
  private final ScheduledExecutorService delegate;
  private final Supplier<Subject> subjectSupplier;

  /**
   * Creates a new VirtualThreadScheduledExecutorService with the given delegate and subject supplier.
   *
   * @param delegate the underlying ScheduledExecutorService that will schedule tasks
   * @param subjectSupplier the supplier of Subject to bind to virtual threads
   */
  public VirtualThreadScheduledExecutorService(final ScheduledExecutorService delegate, 
                                              final Supplier<Subject> subjectSupplier) {
    this.delegate = checkNotNull(delegate);
    this.subjectSupplier = checkNotNull(subjectSupplier);
  }

  /**
   * Returns the Subject to be associated with virtual threads.
   */
  protected Subject getSubject() {
    return subjectSupplier.get();
  }

  /**
   * Wraps a Runnable to be executed as a virtual thread with proper Subject binding and MDC propagation.
   */
  protected Runnable wrapRunnable(Runnable task) {
    Subject subject = getSubject();
    Runnable mdcAwareTask = new MDCAwareRunnable(task);
    Runnable boundTask = subject.associateWith(mdcAwareTask);
    
    return () -> {
      // Launch the task in a new virtual thread and wait for it to complete
      try {
        Thread virtualThread = Thread.startVirtualThread(boundTask);
        virtualThread.join();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for virtual thread to complete", e);
      }
    };
  }

  /**
   * Wraps a Callable to be executed as a virtual thread with proper Subject binding and MDC propagation.
   */
  protected <V> Callable<V> wrapCallable(Callable<V> task) {
    Subject subject = getSubject();
    Callable<V> mdcAwareTask = new MDCAwareCallable<>(task);
    Callable<V> boundTask = subject.associateWith(mdcAwareTask);
    
    return () -> {
      // Create a holder for the result and any exception
      final Object[] resultHolder = new Object[1];
      final Exception[] exceptionHolder = new Exception[1];
      
      // Launch the task in a new virtual thread and wait for it to complete
      try {
        Thread virtualThread = Thread.startVirtualThread(() -> {
          try {
            resultHolder[0] = boundTask.call();
          }
          catch (Exception e) {
            exceptionHolder[0] = e;
          }
        });
        virtualThread.join();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Interrupted while waiting for virtual thread to complete", e);
      }
      
      // If there was an exception, rethrow it
      if (exceptionHolder[0] != null) {
        if (exceptionHolder[0] instanceof RuntimeException) {
          throw (RuntimeException) exceptionHolder[0];
        }
        else if (exceptionHolder[0] instanceof Exception) {
          throw new RuntimeException(exceptionHolder[0]);
        }
      }
      
      // Return the result
      @SuppressWarnings("unchecked")
      V result = (V) resultHolder[0];
      return result;
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
    return delegate.submit(wrapCallable(task));
  }

  @Override
  public <T> Future<T> submit(Runnable task, T result) {
    return delegate.submit(wrapRunnable(task), result);
  }

  @Override
  public Future<?> submit(Runnable task) {
    return delegate.submit(wrapRunnable(task));
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
    return delegate.invokeAll(tasks.stream().map(this::wrapCallable).toList());
  }

  @Override
  public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException {
    return delegate.invokeAll(tasks.stream().map(this::wrapCallable).toList(), timeout, unit);
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks)
      throws InterruptedException, ExecutionException {
    return delegate.invokeAny(tasks.stream().map(this::wrapCallable).toList());
  }

  @Override
  public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
      throws InterruptedException, ExecutionException, TimeoutException {
    return delegate.invokeAny(tasks.stream().map(this::wrapCallable).toList(), timeout, unit);
  }

  @Override
  public void execute(Runnable command) {
    delegate.execute(wrapRunnable(command));
  }

  //
  // Factory methods
  //

  /**
   * Creates a VirtualThreadScheduledExecutorService that associates a fixed Subject with all virtual threads.
   *
   * @param delegate the underlying ScheduledExecutorService
   * @param subject the Subject to associate with all virtual threads
   * @return a new VirtualThreadScheduledExecutorService
   */
  public static VirtualThreadScheduledExecutorService forFixedSubject(
      final ScheduledExecutorService delegate,
      final Subject subject)
  {
    return new VirtualThreadScheduledExecutorService(delegate, () -> subject);
  }

  /**
   * Creates a VirtualThreadScheduledExecutorService that associates the current Subject with all virtual threads.
   *
   * @param delegate the underlying ScheduledExecutorService
   * @return a new VirtualThreadScheduledExecutorService
   */
  public static VirtualThreadScheduledExecutorService forCurrentSubject(final ScheduledExecutorService delegate) {
    return new VirtualThreadScheduledExecutorService(delegate, new CurrentSubjectSupplier());
  }
  
  /**
   * Creates a new VirtualThreadScheduledExecutorService with a default scheduled thread pool and the current subject.
   * 
   * @param corePoolSize the number of threads to keep in the pool for scheduling tasks
   * @return a new VirtualThreadScheduledExecutorService
   */
  public static VirtualThreadScheduledExecutorService withScheduledPool(int corePoolSize) {
    return forCurrentSubject(Executors.newScheduledThreadPool(corePoolSize));
  }
  
  /**
   * Creates a new VirtualThreadScheduledExecutorService with a single-threaded scheduler and the current subject.
   * 
   * @return a new VirtualThreadScheduledExecutorService
   */
  public static VirtualThreadScheduledExecutorService withSingleThreadScheduler() {
    return forCurrentSubject(Executors.newSingleThreadScheduledExecutor());
  }
}