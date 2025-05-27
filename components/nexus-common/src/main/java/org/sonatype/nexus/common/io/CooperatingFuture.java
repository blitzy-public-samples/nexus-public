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
import org.sonatype.nexus.common.io.Cooperation.ThreadStatistics;
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
 * This implementation supports both platform threads and Java 21 Virtual Threads for
 * non-blocking future completion. When configured to use Virtual Threads, it leverages
 * their lightweight nature to improve performance for I/O-bound operations.
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
  
  // Track platform and virtual threads separately for detailed statistics
  private final AtomicInteger platformThreadCount = new AtomicInteger(isVirtualThread() ? 0 : 1);
  private final AtomicInteger virtualThreadCount = new AtomicInteger(isVirtualThread() ? 1 : 0);

  private final String requestKey;

  private final Config config;

  /**
   * Creates a new cooperating future with the given request key and configuration.
   *
   * @param requestKey unique identifier for this cooperation request
   * @param config configuration for cooperation behavior
   */
  public CooperatingFuture(final String requestKey, final Config config) {
    this.requestKey = checkNotNull(requestKey);
    this.config = checkNotNull(config);
  }

  /**
   * Performs the given I/O request and updates this future with any result or error.
   * <p>
   * When configured to use Virtual Threads, this method may execute the request on a
   * Virtual Thread for improved I/O performance.
   */
  public T call(final IOCall<T> request) throws IOException {
    return performCall(request, false);
  }

  /**
   * Cooperates on the given I/O request by waiting for the lead thread to complete.
   * <p>
   * This method optimizes thread coordination based on the current thread type (platform or virtual)
   * and configuration settings. When using Virtual Threads, it takes advantage of their lightweight
   * nature for more efficient cooperation.
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

  @VisibleForTesting
  public String getRequestKey() {
    return requestKey;
  }

  @VisibleForTesting
  public int getThreadCount() {
    return threadCount.get();
  }
  
  /**
   * Returns detailed thread statistics for this cooperating future.
   *
   * @return thread statistics including platform and virtual thread counts
   * @since 3.60
   */
  @VisibleForTesting
  public ThreadStatistics getThreadStatistics() {
    return new ThreadStatistics(
        platformThreadCount.get(),
        virtualThreadCount.get(),
        threadCount.get());
  }

  @Override
  public String toString() {
    if (virtualThreadCount.get() > 0) {
      return requestKey + " (" + threadCount.get() + " threads cooperating, " + 
          virtualThreadCount.get() + " virtual)";
    }
    return requestKey + " (" + threadCount.get() + " threads cooperating)";
  }

  /**
   * Fluent method that performs I/O and stores the result in this future, before passing it back.
   * <p>
   * When configured to use Virtual Threads and the current thread is not already a Virtual Thread,
   * this method may submit the I/O operation to be executed on a Virtual Thread for improved performance.
   */
  protected T performCall(final IOCall<T> request, final boolean failover) throws IOException {
    boolean nested = isNestedCall();
    
    // If configured to use Virtual Threads and this is not a nested call and not already on a virtual thread,
    // submit the operation to be executed on a Virtual Thread
    if (config.useVirtualThreads() && !nested && !isVirtualThread()) {
      try {
        return submitToVirtualThread(request, failover);
      }
      catch (InterruptedException | ExecutionException e) {
        log.debug("Virtual thread execution failed for {}", this, e);
        if (e.getCause() instanceof IOException) {
          throw (IOException) e.getCause();
        }
        else if (e.getCause() instanceof RuntimeException) {
          throw (RuntimeException) e.getCause();
        }
        throw new IOException("Virtual thread execution failed for " + this, e);
      }
    }
    
    // Otherwise, execute directly on the current thread
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
   * Submits the I/O operation to be executed on a Virtual Thread.
   * This method creates a new Virtual Thread for each operation to maximize concurrency
   * for I/O-bound tasks without the overhead of platform thread management.
   *
   * @param request the I/O operation to execute
   * @param failover whether this is a failover request
   * @return the result of the I/O operation
   * @throws InterruptedException if the current thread is interrupted while waiting
   * @throws ExecutionException if the operation throws an exception
   * @since 3.60
   */
  private T submitToVirtualThread(final IOCall<T> request, final boolean failover) 
      throws InterruptedException, ExecutionException {
    log.debug("Submitting {} to virtual thread", this);
    
    // Use a CompletableFuture to capture the result or exception
    CompletableFuture<T> virtualThreadFuture = new CompletableFuture<>();
    
    // Create and start a virtual thread to execute the I/O operation
    Thread.startVirtualThread(() -> {
      boolean nested = isNestedCall();
      try {
        if (!nested) {
          callInProgress.set(TRUE);
        }
        log.debug("Virtual thread requesting {}", this);
        T value = request.call(failover);
        log.debug("Virtual thread completing {}", this);
        complete(value);
        virtualThreadFuture.complete(value);
      }
      catch (Throwable e) { // NOSONAR capture all errors to report back
        log.debug("Virtual thread completing {} with exception", this, e);
        completeExceptionally(e);
        virtualThreadFuture.completeExceptionally(e);
      }
      finally {
        if (!nested) {
          callInProgress.remove();
        }
      }
    });
    
    // Wait for the virtual thread to complete and return the result
    return virtualThreadFuture.get();
  }

  /**
   * Cooperatively waits for the lead thread; may failover and repeat the request if allowed.
   * <p>
   * When using Virtual Threads, this method optimizes the waiting process to prevent
   * unnecessary blocking of carrier threads. Virtual Threads will automatically yield
   * their carrier thread during the waiting period.
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
      return get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }
    catch (TimeoutException e) {
      log.debug("Cooperative wait timed out on {}", this, e);

      if (failover) {
        return performCall(request, true); // failover and repeat request in case lead thread is stuck
      }

      throw new CooperationException("Cooperative wait timed out on " + this);
    }
  }

  /**
   * @return {@code true} if the current thread is already inside a cooperative {@link IOCall}
   */
  private static boolean isNestedCall() {
    return TRUE.equals(callInProgress.get());
  }
  
  /**
   * Determines if the current thread is a Virtual Thread (Java 21+).
   * This method uses reflection to avoid direct dependencies on Java 21 APIs,
   * maintaining compatibility with Java 17.
   *
   * @return true if the current thread is a Virtual Thread, false otherwise
   * @since 3.60
   */
  private static boolean isVirtualThread() {
    try {
      // Use reflection to call Thread.currentThread().isVirtual() to maintain Java 17 compatibility
      return (boolean) Thread.class.getMethod("isVirtual").invoke(Thread.currentThread());
    }
    catch (Exception e) {
      // If the method doesn't exist (pre-Java 21) or fails, it's not a virtual thread
      return false;
    }
  }

  /**
   * Increases the cooperation count by one, respecting thread type limits.
   *
   * @throws CooperationException if increasing the count would breach the given limit.
   * @since 3.60
   */
  private void increaseCooperation() {
    boolean isVirtual = isVirtualThread();
    int platformLimit = config.threadsPerKey();
    int virtualLimit = config.virtualThreadsPerKey();
    
    // For virtual threads, check the virtual thread limit if configured
    if (isVirtual && virtualLimit > 0) {
      virtualThreadCount.getAndUpdate(count -> {
        if (count >= virtualLimit) {
          log.debug("Virtual thread cooperation maxed for {}", this);
          throw new CooperationException("Virtual thread cooperation maxed for " + this);
        }
        return count + 1;
      });
    }
    // For platform threads, check the platform thread limit
    else if (!isVirtual && platformLimit > 0) {
      platformThreadCount.getAndUpdate(count -> {
        if (count >= platformLimit) {
          log.debug("Platform thread cooperation maxed for {}", this);
          throw new CooperationException("Platform thread cooperation maxed for " + this);
        }
        return count + 1;
      });
    }
    
    // Increment the total thread count
    threadCount.incrementAndGet();
    
    // Increment the appropriate thread type counter
    if (isVirtual) {
      if (virtualThreadCount.get() == 0) { // Only increment if not already incremented above
        virtualThreadCount.incrementAndGet();
      }
    }
    else {
      if (platformThreadCount.get() == 0) { // Only increment if not already incremented above
        platformThreadCount.incrementAndGet();
      }
    }
  }

  /**
   * Decreases the cooperation count by one, tracking both total and thread-type specific counts.
   * 
   * @since 3.60
   */
  private void decreaseCooperation() {
    threadCount.decrementAndGet();
    
    // Decrement the appropriate thread type counter
    if (isVirtualThread()) {
      virtualThreadCount.decrementAndGet();
    }
    else {
      platformThreadCount.decrementAndGet();
    }
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
}
