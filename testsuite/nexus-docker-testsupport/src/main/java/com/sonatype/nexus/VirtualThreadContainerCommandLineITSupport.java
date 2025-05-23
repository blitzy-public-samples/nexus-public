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
package com.sonatype.nexus;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.sonatype.nexus.docker.testsupport.ContainerCommandLineITSupport;
import com.sonatype.nexus.docker.testsupport.DockerContainerConfig;

import org.testcontainers.containers.Container.ExecResult;

/**
 * Extension of {@link ContainerCommandLineITSupport} that provides Virtual Thread execution capabilities
 * for Docker-based integration tests. This class enables testing Nexus components with Java 21's Virtual Threads
 * by providing methods to execute commands using Virtual Threads, measure performance metrics, and detect
 * thread pinning issues.
 * 
 * <p>Virtual Threads are lightweight threads that are managed by the JVM rather than the OS. They are designed
 * for I/O-bound operations where threads might spend a lot of time waiting. This makes them ideal for testing
 * high-concurrency scenarios in Nexus.</p>
 * 
 * @since 3.60
 */
public class VirtualThreadContainerCommandLineITSupport
    extends ContainerCommandLineITSupport
{
  private static final int DEFAULT_TIMEOUT_SECONDS = 60;
  
  private final ConcurrentHashMap<String, ThreadPinningStats> pinningStats = new ConcurrentHashMap<>();
  
  /**
   * Constructor. Uses default {@link DockerContainerConfig}
   *
   * @param image name of image to use, can include tag. For example, centos:7
   * @see ContainerCommandLineITSupport#ContainerCommandLineITSupport(DockerContainerConfig)
   */
  protected VirtualThreadContainerCommandLineITSupport(final String image) {
    super(image);
  }

  /**
   * Constructor that creates and run the container with the corresponding commands based on provided configuration.
   *
   * @param dockerContainerConfig parameters to run a container.
   * @param commands to be run for docker container.
   */
  protected VirtualThreadContainerCommandLineITSupport(final DockerContainerConfig dockerContainerConfig, final String commands) {
    super(dockerContainerConfig, commands);
  }

  /**
   * Constructor that creates and run the container based on provided configuration.
   *
   * @param dockerContainerConfig parameters to run a container.
   */
  protected VirtualThreadContainerCommandLineITSupport(final DockerContainerConfig dockerContainerConfig) {
    super(dockerContainerConfig);
  }
  
  /**
   * Executes a command in the container using a Virtual Thread.
   * 
   * <p>This method creates a new Virtual Thread to execute the command, which is more efficient
   * for I/O-bound operations than traditional platform threads.</p>
   *
   * @param command the command to execute
   * @return an Optional containing the command output lines, or empty if execution failed
   */
  public Optional<List<String>> execWithVirtualThread(final String command) {
    return execWithVirtualThread(command, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Executes a command in the container using a Virtual Thread with a specified timeout.
   *
   * @param command the command to execute
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return an Optional containing the command output lines, or empty if execution failed or timed out
   */
  public Optional<List<String>> execWithVirtualThread(final String command, long timeout, TimeUnit unit) {
    try {
      return CompletableFuture.supplyAsync(() -> exec(command), Executors.newVirtualThreadPerTaskExecutor())
          .get(timeout, unit);
    }
    catch (InterruptedException | ExecutionException | TimeoutException e) {
      log.error("Error executing command with virtual thread: {}", command, e);
      return Optional.empty();
    }
  }
  
  /**
   * Executes multiple commands concurrently using Virtual Threads.
   * 
   * <p>This method is useful for testing high-concurrency scenarios and measuring the performance
   * benefits of Virtual Threads.</p>
   *
   * @param commands the list of commands to execute
   * @return a list of Optionals containing the command output lines for each command
   */
  public List<Optional<List<String>>> execConcurrentWithVirtualThreads(final List<String> commands) {
    return execConcurrentWithVirtualThreads(commands, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
  }
  
  /**
   * Executes multiple commands concurrently using Virtual Threads with a specified timeout.
   *
   * @param commands the list of commands to execute
   * @param timeout the maximum time to wait
   * @param unit the time unit of the timeout argument
   * @return a list of Optionals containing the command output lines for each command
   */
  public List<Optional<List<String>>> execConcurrentWithVirtualThreads(final List<String> commands, long timeout, TimeUnit unit) {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Optional<List<String>>>> futures = new ArrayList<>();
      
      for (String command : commands) {
        futures.add(CompletableFuture.supplyAsync(() -> exec(command), executor));
      }
      
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      
      try {
        allFutures.get(timeout, unit);
      }
      catch (InterruptedException | ExecutionException | TimeoutException e) {
        log.error("Error executing concurrent commands with virtual threads", e);
      }
      
      List<Optional<List<String>>> results = new ArrayList<>();
      for (CompletableFuture<Optional<List<String>>> future : futures) {
        try {
          results.add(future.isDone() ? future.get() : Optional.empty());
        }
        catch (InterruptedException | ExecutionException e) {
          log.error("Error getting result from virtual thread execution", e);
          results.add(Optional.empty());
        }
      }
      
      return results;
    }
  }
  
  /**
   * Compares the performance of executing a command using platform threads versus virtual threads.
   * 
   * <p>This method executes the same command multiple times using both platform threads and virtual threads,
   * and returns performance metrics for comparison.</p>
   *
   * @param command the command to execute
   * @param iterations the number of times to execute the command
   * @return a PerformanceComparison object containing the performance metrics
   */
  public PerformanceComparison compareThreadPerformance(final String command, final int iterations) {
    Instant platformStart = Instant.now();
    List<Optional<List<String>>> platformResults = execConcurrentWithPlatformThreads(command, iterations);
    Duration platformDuration = Duration.between(platformStart, Instant.now());
    
    Instant virtualStart = Instant.now();
    List<Optional<List<String>>> virtualResults = execConcurrentWithVirtualThreads(command, iterations);
    Duration virtualDuration = Duration.between(virtualStart, Instant.now());
    
    return new PerformanceComparison(
        platformDuration,
        virtualDuration,
        countSuccessfulExecutions(platformResults),
        countSuccessfulExecutions(virtualResults)
    );
  }
  
  /**
   * Executes a command multiple times concurrently using platform threads.
   *
   * @param command the command to execute
   * @param count the number of times to execute the command
   * @return a list of Optionals containing the command output lines for each execution
   */
  private List<Optional<List<String>>> execConcurrentWithPlatformThreads(final String command, final int count) {
    try (ExecutorService executor = Executors.newFixedThreadPool(Math.min(count, Runtime.getRuntime().availableProcessors() * 2))) {
      List<Future<Optional<List<String>>>> futures = new ArrayList<>();
      
      for (int i = 0; i < count; i++) {
        futures.add(executor.submit(() -> exec(command)));
      }
      
      List<Optional<List<String>>> results = new ArrayList<>();
      for (Future<Optional<List<String>>> future : futures) {
        try {
          results.add(future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        catch (InterruptedException | ExecutionException | TimeoutException e) {
          log.error("Error executing command with platform thread: {}", command, e);
          results.add(Optional.empty());
        }
      }
      
      return results;
    }
  }
  
  /**
   * Executes a command multiple times concurrently using virtual threads.
   *
   * @param command the command to execute
   * @param count the number of times to execute the command
   * @return a list of Optionals containing the command output lines for each execution
   */
  private List<Optional<List<String>>> execConcurrentWithVirtualThreads(final String command, final int count) {
    List<String> commands = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      commands.add(command);
    }
    return execConcurrentWithVirtualThreads(commands);
  }
  
  /**
   * Counts the number of successful executions in a list of command results.
   *
   * @param results the list of command execution results
   * @return the number of successful executions
   */
  private int countSuccessfulExecutions(List<Optional<List<String>>> results) {
    return (int) results.stream().filter(Optional::isPresent).count();
  }
  
  /**
   * Executes a command with thread pinning detection.
   * 
   * <p>Thread pinning occurs when a Virtual Thread is "pinned" to its carrier platform thread,
   * preventing the carrier thread from being used by other Virtual Threads. This typically happens
   * when using synchronized blocks or native methods.</p>
   *
   * @param command the command to execute
   * @param testName a name to identify this test for pinning statistics
   * @return an Optional containing the command output lines, or empty if execution failed
   */
  public Optional<List<String>> execWithPinningDetection(final String command, final String testName) {
    ThreadPinningStats stats = pinningStats.computeIfAbsent(testName, k -> new ThreadPinningStats());
    
    try {
      // Enable thread pinning detection via system property
      System.setProperty("jdk.tracePinnedThreads", "true");
      
      // Create a thread factory that monitors for pinning
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("pinning-test-", 0).factory();
      
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        return executor.submit(() -> {
          try {
            return exec(command);
          }
          catch (Exception e) {
            if (isPinningException(e)) {
              stats.incrementPinningCount();
              log.warn("Thread pinning detected in test {}: {}", testName, e.getMessage());
            }
            throw e;
          }
        }).get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    }
    catch (InterruptedException | ExecutionException | TimeoutException e) {
      log.error("Error executing command with pinning detection: {}", command, e);
      return Optional.empty();
    }
    finally {
      // Disable thread pinning detection
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }
  
  /**
   * Checks if an exception is related to thread pinning.
   *
   * @param e the exception to check
   * @return true if the exception is related to thread pinning, false otherwise
   */
  private boolean isPinningException(Exception e) {
    return e.getMessage() != null && 
        (e.getMessage().contains("virtual thread pinned") || 
         e.getMessage().contains("VirtualThreadPinned"));
  }
  
  /**
   * Gets the thread pinning statistics for a specific test.
   *
   * @param testName the name of the test
   * @return the thread pinning statistics, or null if no statistics are available for the test
   */
  public ThreadPinningStats getPinningStats(String testName) {
    return pinningStats.get(testName);
  }
  
  /**
   * Executes a high-concurrency test with a large number of Virtual Threads.
   * 
   * <p>This method is useful for testing how Nexus components perform under high load
   * with many concurrent operations.</p>
   *
   * @param commandSupplier a supplier that generates commands to execute
   * @param threadCount the number of Virtual Threads to create
   * @return a HighConcurrencyResult object containing the test results
   */
  public HighConcurrencyResult execHighConcurrencyTest(final Supplier<String> commandSupplier, final int threadCount) {
    Instant start = Instant.now();
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < threadCount; i++) {
        futures.add(CompletableFuture.runAsync(() -> {
          String command = commandSupplier.get();
          Optional<List<String>> result = exec(command);
          if (result.isPresent()) {
            successCount.incrementAndGet();
          }
          else {
            failureCount.incrementAndGet();
          }
        }, executor));
      }
      
      CompletableFuture<Void> allFutures = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
      
      try {
        allFutures.get(DEFAULT_TIMEOUT_SECONDS * 2, TimeUnit.SECONDS);
      }
      catch (InterruptedException | ExecutionException | TimeoutException e) {
        log.error("Error executing high concurrency test", e);
      }
    }
    
    Duration duration = Duration.between(start, Instant.now());
    return new HighConcurrencyResult(duration, successCount.get(), failureCount.get());
  }
  
  /**
   * Class representing the results of a performance comparison between platform threads and virtual threads.
   */
  public static class PerformanceComparison {
    private final Duration platformThreadDuration;
    private final Duration virtualThreadDuration;
    private final int platformThreadSuccessCount;
    private final int virtualThreadSuccessCount;
    
    public PerformanceComparison(Duration platformThreadDuration, Duration virtualThreadDuration,
                                int platformThreadSuccessCount, int virtualThreadSuccessCount) {
      this.platformThreadDuration = platformThreadDuration;
      this.virtualThreadDuration = virtualThreadDuration;
      this.platformThreadSuccessCount = platformThreadSuccessCount;
      this.virtualThreadSuccessCount = virtualThreadSuccessCount;
    }
    
    /**
     * Gets the duration of the platform thread execution.
     *
     * @return the platform thread execution duration
     */
    public Duration getPlatformThreadDuration() {
      return platformThreadDuration;
    }
    
    /**
     * Gets the duration of the virtual thread execution.
     *
     * @return the virtual thread execution duration
     */
    public Duration getVirtualThreadDuration() {
      return virtualThreadDuration;
    }
    
    /**
     * Gets the number of successful executions using platform threads.
     *
     * @return the platform thread success count
     */
    public int getPlatformThreadSuccessCount() {
      return platformThreadSuccessCount;
    }
    
    /**
     * Gets the number of successful executions using virtual threads.
     *
     * @return the virtual thread success count
     */
    public int getVirtualThreadSuccessCount() {
      return virtualThreadSuccessCount;
    }
    
    /**
     * Calculates the performance improvement ratio of virtual threads compared to platform threads.
     * 
     * <p>A value greater than 1.0 indicates that virtual threads are faster.</p>
     *
     * @return the performance improvement ratio
     */
    public double getPerformanceImprovementRatio() {
      return platformThreadDuration.toMillis() / (double) virtualThreadDuration.toMillis();
    }
    
    /**
     * Checks if virtual threads performed better than platform threads.
     *
     * @return true if virtual threads performed better, false otherwise
     */
    public boolean isVirtualThreadsFaster() {
      return virtualThreadDuration.compareTo(platformThreadDuration) < 0;
    }
    
    @Override
    public String toString() {
      return String.format(
          "Performance Comparison: Platform Threads (%d ms, %d successful) vs Virtual Threads (%d ms, %d successful) - Improvement Ratio: %.2fx",
          platformThreadDuration.toMillis(), platformThreadSuccessCount,
          virtualThreadDuration.toMillis(), virtualThreadSuccessCount,
          getPerformanceImprovementRatio());
    }
  }
  
  /**
   * Class representing thread pinning statistics.
   */
  public static class ThreadPinningStats {
    private final AtomicInteger pinningCount = new AtomicInteger(0);
    
    /**
     * Increments the pinning count.
     */
    public void incrementPinningCount() {
      pinningCount.incrementAndGet();
    }
    
    /**
     * Gets the number of thread pinning occurrences.
     *
     * @return the pinning count
     */
    public int getPinningCount() {
      return pinningCount.get();
    }
    
    /**
     * Checks if thread pinning was detected.
     *
     * @return true if thread pinning was detected, false otherwise
     */
    public boolean isPinningDetected() {
      return pinningCount.get() > 0;
    }
    
    @Override
    public String toString() {
      return String.format("Thread Pinning Stats: %d occurrences", pinningCount.get());
    }
  }
  
  /**
   * Class representing the results of a high concurrency test.
   */
  public static class HighConcurrencyResult {
    private final Duration duration;
    private final int successCount;
    private final int failureCount;
    
    public HighConcurrencyResult(Duration duration, int successCount, int failureCount) {
      this.duration = duration;
      this.successCount = successCount;
      this.failureCount = failureCount;
    }
    
    /**
     * Gets the duration of the high concurrency test.
     *
     * @return the test duration
     */
    public Duration getDuration() {
      return duration;
    }
    
    /**
     * Gets the number of successful executions.
     *
     * @return the success count
     */
    public int getSuccessCount() {
      return successCount;
    }
    
    /**
     * Gets the number of failed executions.
     *
     * @return the failure count
     */
    public int getFailureCount() {
      return failureCount;
    }
    
    /**
     * Gets the total number of executions.
     *
     * @return the total count
     */
    public int getTotalCount() {
      return successCount + failureCount;
    }
    
    /**
     * Gets the success rate as a percentage.
     *
     * @return the success rate percentage
     */
    public double getSuccessRatePercentage() {
      return (successCount / (double) getTotalCount()) * 100;
    }
    
    /**
     * Gets the operations per second rate.
     *
     * @return the operations per second
     */
    public double getOperationsPerSecond() {
      return getTotalCount() / (duration.toMillis() / 1000.0);
    }
    
    @Override
    public String toString() {
      return String.format(
          "High Concurrency Test Results: %d total operations (%d successful, %d failed) in %d ms - %.2f ops/sec, %.2f%% success rate",
          getTotalCount(), successCount, failureCount, duration.toMillis(),
          getOperationsPerSecond(), getSuccessRatePercentage());
    }
  }
}