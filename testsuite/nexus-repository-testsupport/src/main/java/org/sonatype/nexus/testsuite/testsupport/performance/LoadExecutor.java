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

import java.time.Duration;
import java.time.Instant;
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

import static com.google.common.base.Preconditions.checkState;

/**
 * Utility for using {@link Callable}s to generate performance-testing load.
 * 
 * <p>This class is compatible with Java 21 and uses modern Java time APIs
 * instead of the deprecated Joda-Time library.</p>
 */
public class LoadExecutor
{
  public static final int START_TIMEOUT_SECONDS = 15;

  private final CountDownLatch startSignal = new CountDownLatch(1);

  private volatile Instant endTime;

  private final AtomicBoolean terminateEarly = new AtomicBoolean(false);

  private final AtomicInteger requestsStarted = new AtomicInteger(0);

  private final AtomicInteger requestsProcessed = new AtomicInteger(0);

  private final AtomicReference<Exception> taskException = new AtomicReference<>();

  private final AtomicReference<AssertionError> taskAssertionError = new AtomicReference<>();

  private final Iterator<Callable<?>> endlessTasks;

  private final int threads;

  private final int duration;

  /**
   * Creates a new load executor.
   *
   * @param externalTasks a group of tasks that will be repeatedly invoked to produce load
   * @param threads the number of threads used to execute the tasks
   * @param duration in seconds
   */
  public LoadExecutor(final Iterable<Callable<?>> externalTasks, final int threads, final int duration) {
    Preconditions.checkNotNull(externalTasks);
    Preconditions.checkArgument(threads > 0, "Thread count must be positive");
    Preconditions.checkArgument(duration >= 0, "Duration must be non-negative");

    endlessTasks = Iterables.cycle(externalTasks).iterator();
    this.threads = threads;
    this.duration = duration;
  }

  /**
   * Execute the tasks using the specified number of threads.
   *
   * @throws Exception if any of the supplied tasks threw an exception
   * @throws AssertionError if any of the supplied tasks failed an assertion
   */
  public void callTasks()
      throws Exception
  {
    // Use Java 21 time API instead of Joda-Time
    this.endTime = Instant.now().plus(Duration.ofSeconds(duration));

    // Create an executor service of the correct number of threads
    // In Java 21, we could use virtual threads for better scalability
    final ExecutorService executorService = Executors.newFixedThreadPool(threads);

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

  private synchronized Callable<?> getNextTask() {
    final Callable<?> next = endlessTasks.next();
    Preconditions.checkState(next != null, "Task iterator returned null");
    return next;
  }

  /**
   * Gets the number of requests that were started.
   *
   * @return the number of started requests
   */
  public int getRequestsStarted() {
    return requestsStarted.get();
  }

  /**
   * Gets the number of requests that were successfully processed.
   *
   * @return the number of processed requests
   */
  public int getRequestsProcessed() {
    return requestsProcessed.get();
  }

  /**
   * Gets the number of threads used for this load executor.
   *
   * @return the number of threads
   */
  public int getThreads() {
    return threads;
  }

  /**
   * A callable that executes tasks until the end time is reached or an error occurs.
   */
  private class VoidCallable
      implements Callable<Void>
  {
    @Override
    public Void call() throws Exception {
      try {
        awaitStartSignal();

        // Use Java 21 time API instead of Joda-Time
        while (Instant.now().isBefore(endTime) && !terminateEarly.get()) {
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
                "Start signal not received within timeout");
    }

    private void performOneTask() throws Exception {
      requestsStarted.incrementAndGet();
      final Callable<?> next = getNextTask();
      next.call();
      requestsProcessed.incrementAndGet();
    }
  }
}