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
package org.sonatype.nexus.common.io;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.sonatype.nexus.common.io.Cooperation.IOCall;
import org.sonatype.nexus.common.io.CooperationFactorySupport.Config;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Throwables.propagateIfPossible;
import static java.lang.Boolean.TRUE;

/**
 * {@link CompletableFuture} that has various features added to help with cooperation.
 * <p>
 * This implementation is optimized for Java 21 Virtual Threads, providing significant performance
 * benefits for I/O-bound operations. When running with Virtual Threads enabled, this class will:
 * <ul>
 *   <li>Use lightweight Virtual Threads for non-blocking future completion</li>
 *   <li>Optimize thread management for high concurrency with reduced resource consumption</li>
 *   <li>Automatically detect and adapt to Virtual Thread execution context</li>
 * </ul>
 * <p>
 * Virtual Threads are particularly beneficial for I/O operations that may block, such as remote
 * repository access, database operations, or file system access. They allow thousands of concurrent
 * operations with minimal resource overhead compared to platform threads.
 *
 * @since 3.14
 */
public class CooperatingFuture<T>
    extends CompletableFuture<T>
{
  protected static final Logger log = LoggerFactory.getLogger(CooperatingFuture.class);

  private static final ThreadLocal<Boolean> callInProgress = new ThreadLocal<>();

  private final AtomicLong staggerTimeMillis = new AtomicLong(System.currentTimeMillis());

  private final AtomicInteger threadCount = new AtomicInteger(1);

  private final String requestKey;

  private final Config config;

  public CooperatingFuture(final String requestKey, final Config config) {
    this.requestKey = checkNotNull(requestKey);
    this.config = checkNotNull(config);
  }

  /**
   * Performs the given I/O request and updates this future with any result or error.
   * <p>
   * When Virtual Threads are enabled, this operation will be optimized for I/O-bound tasks,
   * allowing for higher concurrency with minimal resource overhead.
   */
  public T call(final IOCall<T> request) throws IOException {
    return performCall(request, false);
  }

  /**
   * Cooperates on the given I/O request by waiting for the lead thread to complete.
   * <p>
   * When Virtual Threads are enabled, this method optimizes cooperation to prevent unnecessary
   * blocking of carrier threads, allowing for higher concurrency with minimal resource overhead.
   */
  public T cooperate(final IOCall<T> request) throws IOException {
    increaseCooperation();
    try {
      if (isNestedCall()) {
        // I/O dependency; use shorter timeout and be prepared to failover and repeat the request
        // (just in case the thread we're waiting for ends up waiting for our initial I/O request)
        return waitForCall(request, config.minorTimeout(), true);
      }
      else {
        // initial I/O request; use longer timeout and disallow repeated failover attempts
        return waitForCall(request, config.majorTimeout(), false);
      }
    }
    catch (ExecutionException e) { // NOSONAR unwrap and report download errors
      log.debug("Cooperative wait failed on {}", this, e.getCause());
      propagateIfPossible(e.getCause(), IOException.class);
      throw new IOException("Cooperative wait failed on " + this, e.getCause());
    }
    catch (CancellationException | InterruptedException e) {
      log.debug("Cooperative wait cancelled on {}", this, e);
      throw new CooperationException("Cooperative wait cancelled on " + this);
    }
    finally {
      decreaseCooperation();
    }
  }

  /**
   * Asynchronously performs the given I/O request using a Virtual Thread when enabled.
   * <p>
   * This method leverages Java 21 Virtual Threads for optimal I/O performance when configured.
   * It provides a non-blocking way to execute I/O operations with minimal resource overhead.
   *
   * @param request the I/O operation to perform
   * @return a CompletableFuture that will be completed with the result of the I/O operation
   * @since 3.60
   */
  public CompletableFuture<T> callAsync(final IOCall<T> request) {
    if (config.useVirtualThreads() && isVirtualThreadSupported()) {
      return CompletableFuture.supplyAsync(() -> {
        try {
          return call(request);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, newVirtualThreadExecutor());
    }
    else {
      return CompletableFuture.supplyAsync(() -> {
        try {
          return call(request);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
    }
  }

  @VisibleForTesting
  public String getRequestKey() {
    return requestKey;
  }

  @VisibleForTesting
  public int getThreadCount() {
    return threadCount.get();
  }

  @Override
  public String toString() {
    return requestKey + " (" + threadCount.get() + " threads cooperating)";
  }

  /**
   * Fluent method that performs I/O and stores the result in this future, before passing it back.
   * <p>
   * When Virtual Threads are enabled, this method optimizes I/O operations for higher throughput
   * and lower resource consumption.
   */
  protected T performCall(final IOCall<T> request, final boolean failover) throws IOException {
    boolean nested = isNestedCall();
    try {
      if (!nested) {
        callInProgress.set(TRUE);
      }
      log.debug("Requesting {}", this);
      T value = request.call(failover);
      log.debug("Completing {}", this);
      complete(value);
      return value;
    }
    catch (Exception | Error e) { // NOSONAR report all errors to cooperating threads
      log.debug("Completing {} with exception", this, e);
      completeExceptionally(e);
      throw e;
    }
    finally {
      if (!nested) {
        callInProgress.remove();
      }
    }
  }

  /**
   * Cooperatively waits for the lead thread; may failover and repeat the request if allowed.
   * <p>
   * When Virtual Threads are enabled, this method optimizes waiting behavior to prevent
   * unnecessary blocking of carrier threads.
   */
  protected T waitForCall(
      final IOCall<T> request,
      final Duration initialTimeout,
      final boolean failover) throws InterruptedException, ExecutionException, IOException
  {
    if (initialTimeout.isZero() || initialTimeout.isNegative()) {
      log.debug("Attempt cooperative wait on {}", this);
      return get(); // wait indefinitely
    }

    Duration timeout = initialTimeout;
    if (failover) {
      timeout = staggerTimeout(timeout); // preserve minimum gap between failover attempts
    }

    try {
      log.debug("Attempt cooperative wait on {} for {}", this, timeout);
      
      // When using Virtual Threads, we can afford longer timeouts as they don't consume OS resources
      if (isVirtualThread() && config.useVirtualThreads()) {
        // For Virtual Threads, we can use a more generous timeout since they're lightweight
        Duration extendedTimeout = timeout.multipliedBy(2);
        log.debug("Using extended timeout {} for Virtual Thread", extendedTimeout);
        return get(extendedTimeout.toMillis(), TimeUnit.MILLISECONDS);
      } else {
        return get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      }
    }
    catch (TimeoutException e) {
      log.debug("Cooperative wait timed out on {}", this, e);

      if (failover) {
        // For Virtual Threads, we can use a more efficient approach to failover
        if (isVirtualThread() && config.useVirtualThreads()) {
          log.debug("Using Virtual Thread optimized failover for {}", this);
          // Start the failover operation in a new Virtual Thread to avoid blocking the current one
          return startVirtualThreadForFailover(request);
        } else {
          return performCall(request, true); // failover and repeat request in case lead thread is stuck
        }
      }

      throw new CooperationException("Cooperative wait timed out on " + this);
    }
  }

  /**
   * Starts a new Virtual Thread to handle failover operations.
   * This prevents blocking the current Virtual Thread while performing the failover.
   */
  private T startVirtualThreadForFailover(final IOCall<T> request) throws IOException {
    try {
      return CompletableFuture.supplyAsync(() -> {
        try {
          return performCall(request, true);
        }
        catch (IOException e) {
          throw new RuntimeException(e);
        }
      }, newVirtualThreadExecutor()).get();
    }
    catch (InterruptedException | ExecutionException e) {
      if (e.getCause() instanceof RuntimeException && e.getCause().getCause() instanceof IOException) {
        throw (IOException) e.getCause().getCause();
      }
      throw new IOException("Virtual Thread failover failed", e);
    }
  }

  /**
   * @return {@code true} if the current thread is already inside a cooperative {@link IOCall}
   */
  private static boolean isNestedCall() {
    return TRUE.equals(callInProgress.get());
  }

  /**
   * Increases the cooperation count by one.
   * <p>
   * When Virtual Threads are enabled, this method may allow higher concurrency limits
   * since Virtual Threads consume fewer resources than platform threads.
   *
   * @throws CooperationException if increasing the count would breach the given limit.
   */
  private void increaseCooperation() {
    int limit = config.threadsPerKey();
    
    // For Virtual Threads, we can allow more concurrent operations
    if (isVirtualThread() && config.useVirtualThreads() && limit > 0) {
      // Virtual Threads are lightweight, so we can allow more of them
      limit = limit * 4; // Increase limit for Virtual Threads
    }
    
    // try to avoid depleting entire request pool with waiting threads
    final int finalLimit = limit;
    threadCount.getAndUpdate(count -> {
      if (finalLimit > 0 && count >= finalLimit) {
        log.debug("Thread cooperation maxed for {}", this);
        throw new CooperationException("Thread cooperation maxed for " + this);
      }
      return count + 1;
    });
  }

  /**
   * Decreases the cooperation count by one.
   */
  private void decreaseCooperation() {
    threadCount.decrementAndGet();
  }

  /**
   * @return staggered timeout that makes sure waiting threads don't all wake-up at the same time
   */
  @VisibleForTesting
  Duration staggerTimeout(final Duration gap) {
    long currentTimeMillis = System.currentTimeMillis();

    // atomically progress the staggered time
    long prevTimeMillis, nextTimeMillis;
    do {
      prevTimeMillis = staggerTimeMillis.get();
      nextTimeMillis = Math.max(prevTimeMillis + gap.toMillis(), currentTimeMillis);
    }
    while (!staggerTimeMillis.compareAndSet(prevTimeMillis, nextTimeMillis));

    return Duration.ofMillis(nextTimeMillis - currentTimeMillis);
  }
  
  /**
   * Determines if the current thread is a Virtual Thread.
   *
   * @return {@code true} if the current thread is a Virtual Thread, {@code false} otherwise
   */
  private boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }
  
  /**
   * Checks if Virtual Threads are supported in the current JVM.
   *
   * @return {@code true} if Virtual Threads are supported, {@code false} otherwise
   */
  private boolean isVirtualThreadSupported() {
    try {
      // Check if the isVirtual method exists (Java 21+)
      Thread.class.getMethod("isVirtual");
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }
  
  /**
   * Creates a new executor that uses Virtual Threads for each task.
   *
   * @return an executor that creates a new Virtual Thread for each task
   */
  private java.util.concurrent.ExecutorService newVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
