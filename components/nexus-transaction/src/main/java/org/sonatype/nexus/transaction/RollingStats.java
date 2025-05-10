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
package org.sonatype.nexus.transaction;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.System.currentTimeMillis;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.IntStream.range;
import static java.util.stream.LongStream.rangeClosed;

/**
 * Maintains a rolling window of counts for the given time period.
 * Optimized for high-concurrency environments with Java 21 Virtual Threads.
 *
 * @since 3.16
 */
class RollingStats
{
  private final AtomicIntegerArray buckets;

  private final TimeUnit tickUnit;
  
  // Using a lock instead of synchronized block for more fine-grained control
  private final Lock windowShiftLock = new ReentrantLock();

  private volatile long tick;

  public RollingStats(final int size, final TimeUnit tickUnit) {
    this.buckets = new AtomicIntegerArray(size);
    this.tickUnit = checkNotNull(tickUnit);
    this.tick = currentTick();
  }

  /**
   * Adds a count to the window at the latest tick.
   */
  public void mark() {
    maybeShiftWindow();
    buckets.getAndIncrement(index(tick));
  }

  /**
   * Returns a sum of all counts in the window.
   */
  public int sum() {
    maybeShiftWindow();
    return range(0, buckets.length()).map(buckets::get).sum();
  }

  /**
   * Returns the current tick based on the system clock.
   */
  private long currentTick() {
    return tickUnit.convert(currentTimeMillis(), MILLISECONDS);
  }

  /**
   * Converts the given tick to an index into the window.
   */
  private int index(final long _tick) {
    return (int) (_tick % buckets.length());
  }

  /**
   * Compares the last tick to the current tick and shifts the window accordingly, zeroing out stale elements.
   * Optimized for Virtual Threads by minimizing the synchronized block scope.
   */
  private void maybeShiftWindow() {
    // Get current tick outside of any lock to reduce contention
    long newTick = currentTick();
    
    // Fast path: if no shift is needed, return immediately without locking
    if (tick >= newTick) {
      return;
    }
    
    // Only try to acquire the lock if we might need to shift the window
    // This reduces contention in high-concurrency environments
    boolean lockAcquired = windowShiftLock.tryLock();
    if (lockAcquired) {
      try {
        // Re-check condition after acquiring the lock
        // Another thread might have already shifted the window
        if (tick < newTick) {
          // Move round circular window: any elements after the last tick, up to and including current tick, is now stale
          rangeClosed(tick + 1, newTick)
              .limit(buckets.length())
              .mapToInt(this::index)
              .forEach(i -> buckets.set(i, 0));
          
          // Update tick with volatile write to ensure visibility across threads
          tick = newTick;
        }
      } finally {
        windowShiftLock.unlock();
      }
    }
    // If we couldn't acquire the lock, another thread is already shifting the window
    // We can continue with our operation as the other thread will update the tick value
  }
}