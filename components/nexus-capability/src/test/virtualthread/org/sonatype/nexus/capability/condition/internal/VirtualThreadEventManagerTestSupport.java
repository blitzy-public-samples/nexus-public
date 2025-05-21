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
package org.sonatype.nexus.capability.condition.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.sonatype.nexus.capability.Condition;
import org.sonatype.nexus.capability.condition.EventManagerTestSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension of {@link EventManagerTestSupport} that provides virtual thread-specific testing capabilities.
 * This class enables testing of capability conditions under virtual thread execution to validate
 * behavior with Java 21's Virtual Threads feature.
 *
 * @since 3.60
 */
public class VirtualThreadEventManagerTestSupport
    extends EventManagerTestSupport
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadEventManagerTestSupport.class);
  
  private static final int DEFAULT_THREAD_COUNT = 100;
  private static final int DEFAULT_TIMEOUT_SECONDS = 10;
  
  private final ThreadFactory virtualThreadFactory;
  private final ConcurrentHashMap<String, ThreadExecutionMetrics> metricsMap = new ConcurrentHashMap<>();
  
  /**
   * Creates a new instance with default virtual thread factory.
   */
  public VirtualThreadEventManagerTestSupport() {
    this.virtualThreadFactory = Thread.ofVirtual().factory();
  }
  
  /**
   * Creates a new instance with a custom virtual thread factory.
   *
   * @param virtualThreadFactory the thread factory to use for creating virtual threads
   */
  public VirtualThreadEventManagerTestSupport(ThreadFactory virtualThreadFactory) {
    this.virtualThreadFactory = virtualThreadFactory;
  }
  
  /**
   * Fires multiple events concurrently using virtual threads.
   *
   * @param eventSupplier the supplier function that creates events to fire
   * @param threadCount the number of virtual threads to use
   * @return true if all events were fired successfully, false otherwise
   */
  public boolean fireConcurrentEvents(Consumer<Integer> eventSupplier, int threadCount) {
    CountDownLatch latch = new CountDownLatch(threadCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      String testName = "concurrent-event-" + System.currentTimeMillis();
      ThreadExecutionMetrics metrics = new ThreadExecutionMetrics(threadCount);
      metricsMap.put(testName, metrics);
      
      for (int i = 0; i < threadCount; i++) {
        final int index = i;
        executor.submit(() -> {
          Thread currentThread = Thread.currentThread();
          String threadName = currentThread.getName();
          boolean isVirtual = currentThread.isVirtual();
          
          Instant start = Instant.now();
          try {
            log.debug("Firing event on {} thread: {}", isVirtual ? "virtual" : "platform", threadName);
            eventSupplier.accept(index);
            metrics.recordSuccess(threadName, isVirtual, Duration.between(start, Instant.now()));
          }
          catch (Exception e) {
            log.error("Error firing event on thread {}: {}", threadName, e.getMessage(), e);
            errorCount.incrementAndGet();
            metrics.recordFailure(threadName, isVirtual, Duration.between(start, Instant.now()), e);
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      try {
        boolean completed = latch.await(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
          log.warn("Timeout waiting for all events to complete");
          return false;
        }
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        log.warn("Interrupted while waiting for events to complete", e);
        return false;
      }
      
      return errorCount.get() == 0;
    }
  }
  
  /**
   * Fires multiple events concurrently using the default number of virtual threads.
   *
   * @param eventSupplier the supplier function that creates events to fire
   * @return true if all events were fired successfully, false otherwise
   */
  public boolean fireConcurrentEvents(Consumer<Integer> eventSupplier) {
    return fireConcurrentEvents(eventSupplier, DEFAULT_THREAD_COUNT);
  }
  
  /**
   * Fires a condition satisfied event on multiple virtual threads concurrently.
   *
   * @param condition the condition to mark as satisfied
   * @param threadCount the number of virtual threads to use
   * @return true if all events were fired successfully, false otherwise
   */
  public boolean fireConcurrentSatisfiedEvents(Condition condition, int threadCount) {
    return fireConcurrentEvents(i -> eventManager.post(new Condition.Satisfied(condition)), threadCount);
  }
  
  /**
   * Fires a condition unsatisfied event on multiple virtual threads concurrently.
   *
   * @param condition the condition to mark as unsatisfied
   * @param threadCount the number of virtual threads to use
   * @return true if all events were fired successfully, false otherwise
   */
  public boolean fireConcurrentUnsatisfiedEvents(Condition condition, int threadCount) {
    return fireConcurrentEvents(i -> eventManager.post(new Condition.Unsatisfied(condition)), threadCount);
  }
  
  /**
   * Detects if the current thread is pinned (unable to yield to other virtual threads).
   * This is useful for identifying synchronization issues that prevent virtual threads from
   * being efficiently scheduled.
   *
   * @return true if the current thread is pinned, false otherwise
   */
  public boolean isThreadPinned() {
    if (!Thread.currentThread().isVirtual()) {
      // Platform threads are always "pinned" by definition
      return true;
    }
    
    // Create a latch that will be counted down by another virtual thread
    CountDownLatch latch = new CountDownLatch(1);
    
    // Start another virtual thread that will count down the latch after a short delay
    Thread signalThread = virtualThreadFactory.newThread(() -> {
      try {
        // Small delay to ensure the main thread is waiting
        Thread.sleep(50);
        latch.countDown();
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });
    signalThread.start();
    
    try {
      // If the current thread is pinned, it won't be able to yield to the signal thread,
      // and this will time out
      return !latch.await(500, TimeUnit.MILLISECONDS);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true; // Conservatively assume pinned if interrupted
    }
  }
  
  /**
   * Gets the execution metrics for a specific test run.
   *
   * @param testName the name of the test run
   * @return the execution metrics, or null if not found
   */
  public ThreadExecutionMetrics getMetrics(String testName) {
    return metricsMap.get(testName);
  }
  
  /**
   * Gets all execution metrics collected so far.
   *
   * @return a map of test names to execution metrics
   */
  public ConcurrentHashMap<String, ThreadExecutionMetrics> getAllMetrics() {
    return metricsMap;
  }
  
  /**
   * Clears all collected metrics.
   */
  public void clearMetrics() {
    metricsMap.clear();
  }
  
  /**
   * Class that collects and stores execution metrics for thread operations.
   */
  public static class ThreadExecutionMetrics {
    private final int totalThreads;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger virtualThreadCount = new AtomicInteger(0);
    private final AtomicInteger platformThreadCount = new AtomicInteger(0);
    private final List<ThreadExecutionRecord> records = new ArrayList<>();
    private volatile Duration totalDuration = Duration.ZERO;
    private volatile Duration minDuration = Duration.ofDays(1); // Start with a large value
    private volatile Duration maxDuration = Duration.ZERO;
    
    public ThreadExecutionMetrics(int totalThreads) {
      this.totalThreads = totalThreads;
    }
    
    /**
     * Records a successful thread execution.
     *
     * @param threadName the name of the thread
     * @param isVirtual whether the thread is a virtual thread
     * @param duration the execution duration
     */
    public synchronized void recordSuccess(String threadName, boolean isVirtual, Duration duration) {
      successCount.incrementAndGet();
      if (isVirtual) {
        virtualThreadCount.incrementAndGet();
      } else {
        platformThreadCount.incrementAndGet();
      }
      
      records.add(new ThreadExecutionRecord(threadName, isVirtual, duration, null));
      updateDurationStats(duration);
    }
    
    /**
     * Records a failed thread execution.
     *
     * @param threadName the name of the thread
     * @param isVirtual whether the thread is a virtual thread
     * @param duration the execution duration
     * @param error the error that occurred
     */
    public synchronized void recordFailure(String threadName, boolean isVirtual, Duration duration, Exception error) {
      failureCount.incrementAndGet();
      if (isVirtual) {
        virtualThreadCount.incrementAndGet();
      } else {
        platformThreadCount.incrementAndGet();
      }
      
      records.add(new ThreadExecutionRecord(threadName, isVirtual, duration, error));
      updateDurationStats(duration);
    }
    
    private void updateDurationStats(Duration duration) {
      totalDuration = totalDuration.plus(duration);
      if (duration.compareTo(minDuration) < 0) {
        minDuration = duration;
      }
      if (duration.compareTo(maxDuration) > 0) {
        maxDuration = duration;
      }
    }
    
    /**
     * Gets the total number of threads that were expected to execute.
     *
     * @return the total thread count
     */
    public int getTotalThreads() {
      return totalThreads;
    }
    
    /**
     * Gets the number of successful thread executions.
     *
     * @return the success count
     */
    public int getSuccessCount() {
      return successCount.get();
    }
    
    /**
     * Gets the number of failed thread executions.
     *
     * @return the failure count
     */
    public int getFailureCount() {
      return failureCount.get();
    }
    
    /**
     * Gets the number of virtual threads used.
     *
     * @return the virtual thread count
     */
    public int getVirtualThreadCount() {
      return virtualThreadCount.get();
    }
    
    /**
     * Gets the number of platform threads used.
     *
     * @return the platform thread count
     */
    public int getPlatformThreadCount() {
      return platformThreadCount.get();
    }
    
    /**
     * Gets the total duration of all thread executions.
     *
     * @return the total duration
     */
    public Duration getTotalDuration() {
      return totalDuration;
    }
    
    /**
     * Gets the average duration of thread executions.
     *
     * @return the average duration, or Duration.ZERO if no executions
     */
    public Duration getAverageDuration() {
      int total = successCount.get() + failureCount.get();
      if (total == 0) {
        return Duration.ZERO;
      }
      return totalDuration.dividedBy(total);
    }
    
    /**
     * Gets the minimum duration of any thread execution.
     *
     * @return the minimum duration
     */
    public Duration getMinDuration() {
      return minDuration;
    }
    
    /**
     * Gets the maximum duration of any thread execution.
     *
     * @return the maximum duration
     */
    public Duration getMaxDuration() {
      return maxDuration;
    }
    
    /**
     * Gets all execution records.
     *
     * @return the list of execution records
     */
    public List<ThreadExecutionRecord> getRecords() {
      return new ArrayList<>(records);
    }
    
    /**
     * Gets a summary of the execution metrics as a string.
     *
     * @return a summary string
     */
    public String getSummary() {
      return String.format(
          "Executed %d/%d threads (%d success, %d failure) - %d virtual, %d platform - " +
              "Avg: %s, Min: %s, Max: %s",
          successCount.get() + failureCount.get(), totalThreads,
          successCount.get(), failureCount.get(),
          virtualThreadCount.get(), platformThreadCount.get(),
          getAverageDuration(), minDuration, maxDuration);
    }
  }
  
  /**
   * Record of a single thread execution.
   */
  public static class ThreadExecutionRecord {
    private final String threadName;
    private final boolean virtual;
    private final Duration duration;
    private final Exception error;
    
    public ThreadExecutionRecord(String threadName, boolean virtual, Duration duration, Exception error) {
      this.threadName = threadName;
      this.virtual = virtual;
      this.duration = duration;
      this.error = error;
    }
    
    /**
     * Gets the name of the thread.
     *
     * @return the thread name
     */
    public String getThreadName() {
      return threadName;
    }
    
    /**
     * Checks if the thread was a virtual thread.
     *
     * @return true if virtual, false if platform
     */
    public boolean isVirtual() {
      return virtual;
    }
    
    /**
     * Gets the execution duration.
     *
     * @return the duration
     */
    public Duration getDuration() {
      return duration;
    }
    
    /**
     * Gets the error that occurred, if any.
     *
     * @return the error, or null if successful
     */
    public Exception getError() {
      return error;
    }
    
    /**
     * Checks if the execution was successful.
     *
     * @return true if successful, false if an error occurred
     */
    public boolean isSuccess() {
      return error == null;
    }
  }
}