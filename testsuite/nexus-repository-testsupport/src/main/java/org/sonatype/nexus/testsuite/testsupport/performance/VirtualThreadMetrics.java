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

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects metrics about virtual thread execution for performance analysis.
 * 
 * <p>This class is designed to be used with Java 21's virtual threads to track various
 * performance characteristics and help identify bottlenecks or optimization opportunities.</p>
 *
 * @since 3.60
 */
public class VirtualThreadMetrics 
{
  private final LongAdder totalVirtualThreadsCreated = new LongAdder();
  private final LongAdder totalVirtualThreadsCompleted = new LongAdder();
  private final LongAdder pinnedThreadCount = new LongAdder();
  private final AtomicLong maxConcurrentVirtualThreads = new AtomicLong(0);
  private final AtomicLong currentVirtualThreads = new AtomicLong(0);
  private final LongAdder totalBlockedTime = new LongAdder();
  private final LongAdder totalRunningTime = new LongAdder();
  
  /**
   * Records the creation of a new virtual thread.
   */
  public void recordThreadCreated() {
    totalVirtualThreadsCreated.increment();
    long current = currentVirtualThreads.incrementAndGet();
    updateMaxConcurrent(current);
  }
  
  /**
   * Records the completion of a virtual thread.
   */
  public void recordThreadCompleted() {
    totalVirtualThreadsCompleted.increment();
    currentVirtualThreads.decrementAndGet();
  }
  
  /**
   * Records a thread pinning event.
   */
  public void recordThreadPinned() {
    pinnedThreadCount.increment();
  }
  
  /**
   * Records time spent in blocked state.
   * 
   * @param nanosBlocked nanoseconds spent blocked
   */
  public void recordBlockedTime(long nanosBlocked) {
    totalBlockedTime.add(nanosBlocked);
  }
  
  /**
   * Records time spent in running state.
   * 
   * @param nanosRunning nanoseconds spent running
   */
  public void recordRunningTime(long nanosRunning) {
    totalRunningTime.add(nanosRunning);
  }
  
  /**
   * Updates the maximum concurrent virtual threads count if necessary.
   * 
   * @param currentCount current count of virtual threads
   */
  private void updateMaxConcurrent(long currentCount) {
    long current;
    do {
      current = maxConcurrentVirtualThreads.get();
      if (currentCount <= current) {
        break;
      }
    } while (!maxConcurrentVirtualThreads.compareAndSet(current, currentCount));
  }
  
  /**
   * Gets the total number of virtual threads created.
   * 
   * @return total virtual threads created
   */
  public long getTotalVirtualThreadsCreated() {
    return totalVirtualThreadsCreated.sum();
  }
  
  /**
   * Gets the total number of virtual threads that completed execution.
   * 
   * @return total virtual threads completed
   */
  public long getTotalVirtualThreadsCompleted() {
    return totalVirtualThreadsCompleted.sum();
  }
  
  /**
   * Gets the number of thread pinning events detected.
   * Thread pinning occurs when a virtual thread performs an operation that
   * prevents it from yielding the carrier thread, which can impact performance.
   * 
   * @return count of thread pinning events
   */
  public long getPinnedThreadCount() {
    return pinnedThreadCount.sum();
  }
  
  /**
   * Gets the maximum number of concurrent virtual threads observed.
   * 
   * @return maximum concurrent virtual threads
   */
  public long getMaxConcurrentVirtualThreads() {
    return maxConcurrentVirtualThreads.get();
  }
  
  /**
   * Gets the current number of active virtual threads.
   * 
   * @return current virtual thread count
   */
  public long getCurrentVirtualThreads() {
    return currentVirtualThreads.get();
  }
  
  /**
   * Gets the total time virtual threads spent in blocked state.
   * 
   * @return total blocked time in nanoseconds
   */
  public long getTotalBlockedTimeNanos() {
    return totalBlockedTime.sum();
  }
  
  /**
   * Gets the total time virtual threads spent in running state.
   * 
   * @return total running time in nanoseconds
   */
  public long getTotalRunningTimeNanos() {
    return totalRunningTime.sum();
  }
  
  /**
   * Gets the efficiency ratio of virtual threads.
   * This is calculated as running time divided by (running time + blocked time).
   * Higher values indicate better efficiency.
   * 
   * @return efficiency ratio between 0.0 and 1.0
   */
  public double getEfficiencyRatio() {
    long running = totalRunningTime.sum();
    long blocked = totalBlockedTime.sum();
    if (running + blocked == 0) {
      return 0.0;
    }
    return (double) running / (running + blocked);
  }
  
  /**
   * Resets all metrics to zero.
   */
  public void reset() {
    totalVirtualThreadsCreated.reset();
    totalVirtualThreadsCompleted.reset();
    pinnedThreadCount.reset();
    maxConcurrentVirtualThreads.set(0);
    currentVirtualThreads.set(0);
    totalBlockedTime.reset();
    totalRunningTime.reset();
  }
}