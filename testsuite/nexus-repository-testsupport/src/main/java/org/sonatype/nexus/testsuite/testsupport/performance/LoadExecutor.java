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
package org.sonatype.nexus.testsuite.testsupport.performance;

import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import org.joda.time.DateTime;

import static com.google.common.base.Preconditions.checkState;

/**
 * Utility for using {@link Callable}s to generate performance-testing load.
 * 
 * <p>This implementation leverages Java 21 Virtual Threads for improved concurrency and
 * resource utilization during performance testing. Virtual threads are lightweight threads
 * that are managed by the JVM rather than the operating system, allowing for much higher
 * concurrency with minimal overhead.</p>
 * 
 * <p>Key characteristics of Java 21 virtual threads used in this implementation:</p>
 * <ul>
 *   <li>Lightweight - Each virtual thread requires only ~2KB of memory vs ~1MB for platform threads</li>
 *   <li>Managed by JVM - Virtual threads are scheduled by the JVM, not the operating system</li>
 *   <li>Automatic yielding - Virtual threads automatically yield during blocking operations</li>
 *   <li>Carrier thread multiplexing - Many virtual threads share a small pool of OS threads</li>
 *   <li>Compatible with existing APIs - Works with standard Java concurrency APIs</li>
 * </ul>
 * 
 * <p>This class supports both virtual threads and platform threads, allowing for performance
 * comparison between the two threading models.</p>
 */
public class LoadExecutor
{
  public static final int START_TIMEOUT_SECONDS = 15;

  private final CountDownLatch startSignal = new CountDownLatch(1);

  private volatile DateTime endtime;

  private final AtomicBoolean terminateEarly = new AtomicBoolean(false);

  private final AtomicInteger requestsStarted = new AtomicInteger(0);

  private final AtomicInteger requestsProcessed = new AtomicInteger(0);

  private final AtomicReference<Exception> taskException = new AtomicReference<>();

  private final AtomicReference<AssertionError> taskAssertionError = new AtomicReference<>();

  private final Iterator<Callable<?>> endlessTasks;

  private final int threads;

  private final int duration;
  
  private final boolean useVirtualThreads;

  /**
   * Creates a LoadExecutor that uses Java 21 virtual threads by default.
   *
   * @param externalTasks a group of tasks that will be repeatedly invoked to produce load
   * @param threads       the number of concurrent tasks to execute
   * @param duration      in seconds
   */
  public LoadExecutor(final Iterable<Callable<?>> externalTasks, final int threads, final int duration) {
    this(externalTasks, threads, duration, true);
  }
  
  /**
   * Creates a LoadExecutor with the option to use either virtual threads or platform threads.
   *
   * @param externalTasks a group of tasks that will be repeatedly invoked to produce load
   * @param threads       the number of concurrent tasks to execute
   * @param duration      in seconds
   * @param useVirtualThreads whether to use virtual threads (true) or platform threads (false)
   */
  public LoadExecutor(final Iterable<Callable<?>> externalTasks, final int threads, final int duration, final boolean useVirtualThreads) {
    Preconditions.checkNotNull(externalTasks);
    Preconditions.checkArgument(threads > 0);
    Preconditions.checkArgument(duration >= 0);

    endlessTasks = Iterables.cycle(externalTasks).iterator();
    this.threads = threads;
    this.duration = duration;
    this.useVirtualThreads = useVirtualThreads;
  }

  /**
   * Execute the tasks using the specified number of threads.
   * When using virtual threads (default), each task runs on its own virtual thread,
   * providing improved concurrency and resource utilization compared to platform threads.
   *
   * @throws Exception if any of the supplied tasks threw an exception
   * @throws AssertionError if any of the supplied tasks failed an assertion
   */
  public void callTasks()
      throws Exception
  {
    this.endtime = new DateTime().plusSeconds(duration);

    // Create an executor service based on the thread type configuration
    final ExecutorService executorService = createExecutorService(threads);

    for (int thread = 0; thread < threads; thread++) {
      final Callable<Void> callable = new VoidCallable();

      // the return value is not crucial
      executorService.submit(callable);
    }

    // Fire the start signal
    startSignal.countDown();

    executorService.shutdown();
    executorService.awaitTermination(duration + 60, TimeUnit.SECONDS);

    final Exception exception = taskException.get();
    if (exception != null) {
      throw exception;
    }

    final AssertionError assertionError = taskAssertionError.get();
    if (assertionError != null) {
      throw assertionError;
    }
  }
  
  /**
   * Creates an appropriate executor service based on the configuration.
   * 
   * <p>When using virtual threads, this method creates an executor service that uses
   * Java 21 virtual threads for improved concurrency and resource utilization. Virtual threads
   * are lightweight threads that are managed by the JVM rather than the operating system,
   * allowing for much higher concurrency with minimal overhead.</p>
   * 
   * <p>When using platform threads, this method creates a traditional thread pool with
   * the specified number of platform threads. This is useful for comparison purposes and
   * for testing code that may not be compatible with virtual threads.</p>
   * 
   * @param threadCount the number of threads to use
   * @return an ExecutorService using either virtual threads or platform threads
   */
  protected ExecutorService createExecutorService(int threadCount) {
    if (useVirtualThreads) {
      // Use Java 21 virtual threads for improved concurrency and resource utilization
      return Executors.newVirtualThreadPerTaskExecutor();
    } else {
      // Use traditional platform threads for comparison purposes
      return Executors.newFixedThreadPool(threadCount, Thread.ofPlatform().factory());
    }
  }

  private synchronized Callable<?> getNextTask() {
    final Callable<?> next = endlessTasks.next();
    Preconditions.checkState(next != null);
    return next;
  }

  /**
   * @return the total number of requests that have been started
   */
  public int getRequestsStarted() {
    return requestsStarted.get();
  }

  /**
   * @return the total number of requests that have been successfully processed
   */
  public int getRequestsProcessed() {
    return requestsProcessed.get();
  }

  /**
   * @return the number of concurrent threads (virtual threads) being used
   */
  public int getThreads() {
    return threads;
  }

  /**
   * Internal callable implementation that executes tasks until the test duration expires
   * or an error occurs. When using virtual threads, this provides significantly improved
   * performance and scalability compared to platform threads.
   */
  private class VoidCallable
      implements Callable<Void>
  {
    @Override
    public Void call() throws Exception {
      try {
        awaitStartSignal();

        while (new DateTime().isBefore(endtime) && !terminateEarly.get()) {
          performOneTask();
        }
        return null;
      }
      catch (AssertionError e) {
        // Record only the first assertion failed
        taskAssertionError.compareAndSet(null, e);
        terminateEarly.set(true);
        return null;
      }
      catch (Exception e) {
        // Record only the first exception thrown
        taskException.compareAndSet(null, e);
        terminateEarly.set(true);
        return null;
      }
    }

    private void awaitStartSignal() throws InterruptedException
    {
      checkState(startSignal.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS), 
          "Start signal not received within %s seconds", START_TIMEOUT_SECONDS);
    }

    private void performOneTask() throws Exception {
      requestsStarted.incrementAndGet();
      final Callable<?> next = getNextTask();
      next.call();
      requestsProcessed.incrementAndGet();
    }
  }
}