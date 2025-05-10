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
package org.sonatype.nexus.scheduling.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.goodies.common.ComponentSupport;

/**
 * Collects and provides statistics about virtual thread usage in the scheduler.
 * 
 * @since 3.60
 */
@Named
@Singleton
public class VirtualThreadStatistics
    extends ComponentSupport
{
  private final AtomicInteger virtualThreadsCreated = new AtomicInteger(0);
  private final AtomicInteger virtualThreadsTerminated = new AtomicInteger(0);
  private final AtomicInteger pinnedThreadsCount = new AtomicInteger(0);
  private final AtomicLong totalPinningDurationMs = new AtomicLong(0);
  private final AtomicLong maxPinningDurationMs = new AtomicLong(0);
  private final List<ThreadPinningEvent> recentPinningEvents = Collections.synchronizedList(new ArrayList<>());
  private final int MAX_RECENT_EVENTS = 100;
  
  /**
   * Records a new virtual thread creation.
   */
  public void recordThreadCreated() {
    virtualThreadsCreated.incrementAndGet();
  }
  
  /**
   * Records a virtual thread termination.
   */
  public void recordThreadTerminated() {
    virtualThreadsTerminated.incrementAndGet();
  }
  
  /**
   * Records a thread pinning event with the specified duration.
   * 
   * @param threadName the name of the pinned thread
   * @param durationMs the duration of the pinning in milliseconds
   * @param stackTrace the stack trace at the time of pinning
   */
  public void recordThreadPinning(String threadName, long durationMs, String stackTrace) {
    pinnedThreadsCount.incrementAndGet();
    totalPinningDurationMs.addAndGet(durationMs);
    maxPinningDurationMs.updateAndGet(current -> Math.max(current, durationMs));
    
    ThreadPinningEvent event = new ThreadPinningEvent(threadName, durationMs, stackTrace, Instant.now());
    
    synchronized (recentPinningEvents) {
      recentPinningEvents.add(event);
      if (recentPinningEvents.size() > MAX_RECENT_EVENTS) {
        recentPinningEvents.remove(0);
      }
    }
    
    if (durationMs > 1000) { // Log long pinning events (> 1 second)
      log.warn("Virtual thread pinning detected: {} was pinned for {}ms", threadName, durationMs);
      log.debug("Pinning stack trace: {}", stackTrace);
    }
  }
  
  /**
   * @return the total number of virtual threads created
   */
  public int getVirtualThreadsCreated() {
    return virtualThreadsCreated.get();
  }
  
  /**
   * @return the total number of virtual threads terminated
   */
  public int getVirtualThreadsTerminated() {
    return virtualThreadsTerminated.get();
  }
  
  /**
   * @return the current number of active virtual threads
   */
  public int getActiveVirtualThreads() {
    return virtualThreadsCreated.get() - virtualThreadsTerminated.get();
  }
  
  /**
   * @return the total number of thread pinning events detected
   */
  public int getPinnedThreadsCount() {
    return pinnedThreadsCount.get();
  }
  
  /**
   * @return the total duration of all pinning events in milliseconds
   */
  public long getTotalPinningDurationMs() {
    return totalPinningDurationMs.get();
  }
  
  /**
   * @return the maximum duration of any pinning event in milliseconds
   */
  public long getMaxPinningDurationMs() {
    return maxPinningDurationMs.get();
  }
  
  /**
   * @return the average duration of pinning events in milliseconds, or 0 if no events
   */
  public double getAveragePinningDurationMs() {
    int count = pinnedThreadsCount.get();
    return count > 0 ? (double) totalPinningDurationMs.get() / count : 0;
  }
  
  /**
   * @return a list of recent thread pinning events
   */
  public List<ThreadPinningEvent> getRecentPinningEvents() {
    synchronized (recentPinningEvents) {
      return new ArrayList<>(recentPinningEvents);
    }
  }
  
  /**
   * Resets all statistics.
   */
  public void reset() {
    virtualThreadsCreated.set(0);
    virtualThreadsTerminated.set(0);
    pinnedThreadsCount.set(0);
    totalPinningDurationMs.set(0);
    maxPinningDurationMs.set(0);
    synchronized (recentPinningEvents) {
      recentPinningEvents.clear();
    }
  }
  
  /**
   * Represents a thread pinning event with relevant details.
   */
  public static class ThreadPinningEvent {
    private final String threadName;
    private final long durationMs;
    private final String stackTrace;
    private final Instant timestamp;
    
    public ThreadPinningEvent(String threadName, long durationMs, String stackTrace, Instant timestamp) {
      this.threadName = threadName;
      this.durationMs = durationMs;
      this.stackTrace = stackTrace;
      this.timestamp = timestamp;
    }
    
    public String getThreadName() {
      return threadName;
    }
    
    public long getDurationMs() {
      return durationMs;
    }
    
    public String getStackTrace() {
      return stackTrace;
    }
    
    public Instant getTimestamp() {
      return timestamp;
    }
    
    public Duration getAge() {
      return Duration.between(timestamp, Instant.now());
    }
    
    @Override
    public String toString() {
      return String.format("ThreadPinningEvent[thread=%s, duration=%dms, age=%s]", 
          threadName, durationMs, getAge());
    }
  }
}