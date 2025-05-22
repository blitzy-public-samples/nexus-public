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
package virtualthread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

import org.sonatype.goodies.testsupport.TestSupport;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Support class for testing MyBatis datastore operations with Java 21 Virtual Threads.
 * <p>
 * This class provides utilities for:
 * <ul>
 *   <li>Creating thread factories for both platform and virtual threads</li>
 *   <li>Detecting thread pinning issues with JFR events</li>
 *   <li>Comparing performance between platform and virtual threads</li>
 *   <li>Validating thread behavior and execution</li>
 * </ul>
 * 
 * @since 3.60
 */
public class VirtualThreadTestSupport
    extends TestSupport
{
  private static final String VIRTUAL_THREAD_PINNED_EVENT = "jdk.VirtualThreadPinned";
  
  private static final long DEFAULT_PINNING_THRESHOLD_MS = 20; // Default JFR threshold
  
  /**
   * Creates a {@link ThreadFactory} that produces platform threads with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a thread factory for platform threads
   */
  public static ThreadFactory platformThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = Thread.ofPlatform()
          .name(namePrefix + "-" + counter.getAndIncrement())
          .daemon(true)
          .unstarted(r);
      return thread;
    };
  }

  /**
   * Creates a {@link ThreadFactory} that produces virtual threads with the specified name prefix.
   *
   * @param namePrefix the prefix to use for thread names
   * @return a thread factory for virtual threads
   */
  public static ThreadFactory virtualThreadFactory(final String namePrefix) {
    AtomicInteger counter = new AtomicInteger();
    return r -> {
      Thread thread = Thread.ofVirtual()
          .name(namePrefix + "-" + counter.getAndIncrement())
          .unstarted(r);
      return thread;
    };
  }

  /**
   * Creates an {@link ExecutorService} that creates a new virtual thread for each task.
   *
   * @param namePrefix the prefix to use for thread names
   * @return an executor service using virtual threads
   */
  public static ExecutorService newVirtualThreadExecutor(final String namePrefix) {
    return Executors.newThreadPerTaskExecutor(virtualThreadFactory(namePrefix));
  }

  /**
   * Creates an {@link ExecutorService} that creates a new platform thread for each task.
   *
   * @param namePrefix the prefix to use for thread names
   * @return an executor service using platform threads
   */
  public static ExecutorService newPlatformThreadExecutor(final String namePrefix) {
    return Executors.newThreadPerTaskExecutor(platformThreadFactory(namePrefix));
  }

  /**
   * Detects thread pinning issues by monitoring JFR events.
   * <p>
   * This method starts a JFR recording stream that listens for virtual thread pinning events
   * and reports them to the provided listener.
   *
   * @param listener the listener to receive pinning events
   * @param thresholdMs the minimum duration in milliseconds for a pinning event to be reported
   * @return the recording stream (caller should close this when done)
   */
  public static RecordingStream detectThreadPinning(PinningEventListener listener, long thresholdMs) {
    RecordingStream recordingStream = new RecordingStream();
    recordingStream.enable(VIRTUAL_THREAD_PINNED_EVENT)
        .withThreshold(Duration.ofMillis(thresholdMs))
        .withStackTrace();
    
    recordingStream.onEvent(VIRTUAL_THREAD_PINNED_EVENT, event -> {
      listener.onPinningEvent(event);
    });
    
    recordingStream.startAsync();
    return recordingStream;
  }

  /**
   * Detects thread pinning issues with the default threshold.
   *
   * @param listener the listener to receive pinning events
   * @return the recording stream (caller should close this when done)
   */
  public static RecordingStream detectThreadPinning(PinningEventListener listener) {
    return detectThreadPinning(listener, DEFAULT_PINNING_THRESHOLD_MS);
  }

  /**
   * Interface for receiving thread pinning events.
   */
  public interface PinningEventListener {
    /**
     * Called when a thread pinning event is detected.
     *
     * @param event the JFR event containing pinning details
     */
    void onPinningEvent(RecordedEvent event);
  }

  /**
   * A simple implementation of {@link PinningEventListener} that counts pinning events.
   */
  public static class PinningEventCounter implements PinningEventListener {
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicLong totalDurationMs = new AtomicLong();
    private final List<String> stackTraces = new ArrayList<>();

    @Override
    public void onPinningEvent(RecordedEvent event) {
      count.incrementAndGet();
      totalDurationMs.addAndGet(event.getDuration().toMillis());
      if (event.hasStackTrace()) {
        stackTraces.add(event.getStackTrace().toString());
      }
    }

    /**
     * Gets the number of pinning events detected.
     *
     * @return the count of pinning events
     */
    public int getCount() {
      return count.get();
    }

    /**
     * Gets the total duration of all pinning events in milliseconds.
     *
     * @return the total duration in ms
     */
    public long getTotalDurationMs() {
      return totalDurationMs.get();
    }

    /**
     * Gets the stack traces from pinning events.
     *
     * @return the list of stack traces
     */
    public List<String> getStackTraces() {
      return new ArrayList<>(stackTraces);
    }

    /**
     * Resets the counter.
     */
    public void reset() {
      count.set(0);
      totalDurationMs.set(0);
      stackTraces.clear();
    }
  }

  /**
   * Compares the performance of executing a task with platform threads versus virtual threads.
   * <p>
   * This method runs the same task multiple times with both thread types and measures execution time.
   *
   * @param <T> the return type of the task
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param concurrency the number of concurrent executions
   * @return a {@link PerformanceResult} containing the comparison metrics
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if the task throws an exception
   */
  public static <T> PerformanceResult<T> compareThreadPerformance(
      Callable<T> task,
      int iterations,
      int concurrency) throws InterruptedException, ExecutionException {
    
    // Run with platform threads
    long platformStartTime = System.nanoTime();
    List<T> platformResults = executeWithThreads(
        task,
        iterations,
        concurrency,
        () -> newPlatformThreadExecutor("platform-perf"));
    long platformEndTime = System.nanoTime();
    long platformDurationMs = TimeUnit.NANOSECONDS.toMillis(platformEndTime - platformStartTime);
    
    // Run with virtual threads
    long virtualStartTime = System.nanoTime();
    List<T> virtualResults = executeWithThreads(
        task,
        iterations,
        concurrency,
        () -> newVirtualThreadExecutor("virtual-perf"));
    long virtualEndTime = System.nanoTime();
    long virtualDurationMs = TimeUnit.NANOSECONDS.toMillis(virtualEndTime - virtualStartTime);
    
    return new PerformanceResult<>(
        platformResults,
        virtualResults,
        platformDurationMs,
        virtualDurationMs);
  }

  /**
   * Executes a task multiple times using the provided executor service factory.
   *
   * @param <T> the return type of the task
   * @param task the task to execute
   * @param iterations the number of times to execute the task
   * @param concurrency the number of concurrent executions
   * @param executorFactory factory to create the executor service
   * @return a list of task results
   * @throws InterruptedException if the execution is interrupted
   * @throws ExecutionException if the task throws an exception
   */
  private static <T> List<T> executeWithThreads(
      Callable<T> task,
      int iterations,
      int concurrency,
      Supplier<ExecutorService> executorFactory) throws InterruptedException, ExecutionException {
    
    List<T> results = new ArrayList<>(iterations);
    int batchSize = Math.min(concurrency, iterations);
    int remainingTasks = iterations;
    
    while (remainingTasks > 0) {
      int tasksToRun = Math.min(batchSize, remainingTasks);
      try (ExecutorService executor = executorFactory.get()) {
        List<Future<T>> futures = new ArrayList<>(tasksToRun);
        for (int i = 0; i < tasksToRun; i++) {
          futures.add(executor.submit(task));
        }
        
        for (Future<T> future : futures) {
          results.add(future.get());
        }
      }
      remainingTasks -= tasksToRun;
    }
    
    return results;
  }

  /**
   * Result class for performance comparison between platform and virtual threads.
   *
   * @param <T> the type of task results
   */
  public static class PerformanceResult<T> {
    private final List<T> platformResults;
    private final List<T> virtualResults;
    private final long platformDurationMs;
    private final long virtualDurationMs;

    public PerformanceResult(
        List<T> platformResults,
        List<T> virtualResults,
        long platformDurationMs,
        long virtualDurationMs) {
      this.platformResults = platformResults;
      this.virtualResults = virtualResults;
      this.platformDurationMs = platformDurationMs;
      this.virtualDurationMs = virtualDurationMs;
    }

    /**
     * Gets the results from platform thread execution.
     *
     * @return the list of results
     */
    public List<T> getPlatformResults() {
      return platformResults;
    }

    /**
     * Gets the results from virtual thread execution.
     *
     * @return the list of results
     */
    public List<T> getVirtualResults() {
      return virtualResults;
    }

    /**
     * Gets the total execution time for platform threads in milliseconds.
     *
     * @return the platform thread execution time
     */
    public long getPlatformDurationMs() {
      return platformDurationMs;
    }

    /**
     * Gets the total execution time for virtual threads in milliseconds.
     *
     * @return the virtual thread execution time
     */
    public long getVirtualDurationMs() {
      return virtualDurationMs;
    }

    /**
     * Gets the performance improvement ratio of virtual threads compared to platform threads.
     * <p>
     * A value greater than 1.0 indicates virtual threads were faster.
     *
     * @return the performance improvement ratio
     */
    public double getImprovementRatio() {
      return (double) platformDurationMs / virtualDurationMs;
    }

    /**
     * Asserts that virtual threads performed better than platform threads.
     *
     * @return this result object for method chaining
     */
    public PerformanceResult<T> assertVirtualThreadsPerformedBetter() {
      assertThat("Virtual threads should be faster than platform threads",
          virtualDurationMs, is(lessThan(platformDurationMs)));
      return this;
    }

    /**
     * Asserts that virtual threads performed better than platform threads by at least the specified factor.
     *
     * @param minImprovementFactor the minimum improvement factor expected
     * @return this result object for method chaining
     */
    public PerformanceResult<T> assertVirtualThreadsPerformedBetterByFactor(double minImprovementFactor) {
      double actualImprovement = getImprovementRatio();
      assertThat("Virtual threads should be faster than platform threads by a factor of at least " + minImprovementFactor,
          actualImprovement, is(greaterThan(minImprovementFactor)));
      return this;
    }
  }

  /**
   * Executes a task with the specified number of concurrent virtual threads and waits for completion.
   *
   * @param task the task to execute
   * @param concurrency the number of concurrent threads
   * @throws InterruptedException if the execution is interrupted
   */
  public static void executeWithVirtualThreads(Runnable task, int concurrency) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(concurrency);
    
    try (ExecutorService executor = newVirtualThreadExecutor("vt-test")) {
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            task.run();
          } finally {
            latch.countDown();
          }
        });
      }
      
      latch.await();
    }
  }

  /**
   * Checks if the current thread is a virtual thread.
   *
   * @return true if the current thread is a virtual thread, false otherwise
   */
  public static boolean isVirtualThread() {
    return Thread.currentThread().isVirtual();
  }

  /**
   * Asserts that the current thread is a virtual thread.
   */
  public static void assertIsVirtualThread() {
    assertThat("Current thread should be a virtual thread", isVirtualThread(), is(true));
  }

  /**
   * Asserts that the current thread is a platform thread.
   */
  public static void assertIsPlatformThread() {
    assertThat("Current thread should be a platform thread", isVirtualThread(), is(false));
  }
}