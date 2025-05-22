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
package org.junit;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

/**
 * JUnit Jupiter extension for testing with Java 21's Virtual Threads.
 * <p>
 * This extension enables tests to run with virtual threads instead of platform threads,
 * facilitating the validation of code that utilizes Java 21's lightweight threading model.
 * It provides the following features:
 * <ul>
 *   <li>Conditional test execution based on Java version and system properties</li>
 *   <li>Automatic setup of virtual thread infrastructure</li>
 *   <li>Detection and reporting of thread pinning issues</li>
 *   <li>Performance comparison between platform threads and virtual threads</li>
 * </ul>
 * <p>
 * Usage example:
 * <pre>
 * {@code
 * @ExtendWith(VirtualThreadExtension.class)
 * class MyVirtualThreadTest {
 *     @Test
 *     void testWithVirtualThreads() {
 *         // Test code that will run with virtual threads
 *     }
 * }
 * }
 * </pre>
 *
 * @since 3.60
 */
public class VirtualThreadExtension
    implements ExecutionCondition, BeforeAllCallback, TestExecutionExceptionHandler
{
  private static final Logger log = LoggerFactory.getLogger(VirtualThreadExtension.class);

  private static final String VIRTUAL_THREAD_ENABLED_PROPERTY = "nexus.test.virtualthread.enabled";
  private static final String VIRTUAL_THREAD_PINNING_DETECTION_PROPERTY = "nexus.test.virtualthread.pinning.detection";
  private static final String VIRTUAL_THREAD_PERFORMANCE_COMPARISON_PROPERTY = "nexus.test.virtualthread.performance.comparison";

  private static final AtomicBoolean initialized = new AtomicBoolean(false);
  private static final AtomicReference<ExecutorService> virtualThreadExecutor = new AtomicReference<>();

  /**
   * Evaluates if the test should be executed based on Java version and system properties.
   * <p>
   * The test will be disabled if:
   * <ul>
   *   <li>The JVM doesn't support virtual threads (Java version < 21)</li>
   *   <li>The {@code nexus.test.virtualthread.enabled} system property is set to {@code false}</li>
   * </ul>
   *
   * @param context the extension context
   * @return the result of the evaluation
   */
  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(final ExtensionContext context) {
    // Check if virtual threads are supported by the JVM
    if (!VirtualThreadTestSupport.isVirtualThreadSupported()) {
      return ConditionEvaluationResult.disabled("Virtual threads are not supported by this JVM (requires Java 21+)");
    }

    // Check if virtual thread tests are explicitly disabled
    String enabled = System.getProperty(VIRTUAL_THREAD_ENABLED_PROPERTY, "true");
    if (!Boolean.parseBoolean(enabled)) {
      return ConditionEvaluationResult.disabled("Virtual thread tests are disabled by system property: " 
          + VIRTUAL_THREAD_ENABLED_PROPERTY + "=false");
    }

    return ConditionEvaluationResult.enabled("Virtual threads are supported and enabled");
  }

  /**
   * Sets up the virtual thread infrastructure before all tests are executed.
   * <p>
   * This method initializes the virtual thread executor and configures thread pinning detection
   * if enabled via system properties.
   *
   * @param context the extension context
   * @throws Exception if an error occurs during setup
   */
  @Override
  public void beforeAll(final ExtensionContext context) throws Exception {
    if (initialized.compareAndSet(false, true)) {
      log.info("Initializing virtual thread infrastructure for tests");
      
      // Create a virtual thread executor for tests to use
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      virtualThreadExecutor.set(executor);
      
      // Register a shutdown hook to clean up the executor
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        ExecutorService exec = virtualThreadExecutor.getAndSet(null);
        if (exec != null && !exec.isShutdown()) {
          exec.shutdown();
        }
      }));
      
      // Configure thread pinning detection if enabled
      String pinningDetection = System.getProperty(VIRTUAL_THREAD_PINNING_DETECTION_PROPERTY, "true");
      if (Boolean.parseBoolean(pinningDetection)) {
        ThreadPinningDetector.startJfrMonitoring(event -> {
          log.warn("Virtual Thread pinning detected in test: {} - Duration: {} ms\nLocation: {}\nCauses: {}\nStack trace:\n{}", 
              event.getThreadName(), event.getDurationMs(), event.getLocation(), 
              event.getCauses(), event.getStackTrace());
        });
      }
      
      log.info("Virtual thread infrastructure initialized");
    }
  }

  /**
   * Handles exceptions thrown during test execution, with special handling for thread pinning issues.
   * <p>
   * This method captures and reports thread pinning issues, providing detailed information to help
   * developers identify and fix the root cause.
   *
   * @param context the extension context
   * @param throwable the exception thrown during test execution
   * @throws Throwable the original or wrapped exception
   */
  @Override
  public void handleTestExecutionException(final ExtensionContext context, final Throwable throwable) throws Throwable {
    // Check if the exception might be related to thread pinning
    if (isPotentialThreadPinningIssue(throwable)) {
      Method testMethod = context.getRequiredTestMethod();
      log.error("Potential thread pinning issue detected in test: {}#{}", 
          testMethod.getDeclaringClass().getSimpleName(), testMethod.getName());
      
      // Get thread pinning statistics if available
      if (ThreadPinningDetector.isJfrMonitoringActive()) {
        log.error("Thread pinning statistics:\n{}", formatPinningStatistics());
      }
      
      // Provide recommendations
      log.error("Recommendations to fix thread pinning issues:\n" +
          "1. Replace synchronized blocks/methods with ReentrantLock\n" +
          "2. Avoid native methods in virtual threads\n" +
          "3. Use thread-local variables without initial values\n" +
          "4. Consider using structured concurrency for better thread management");
    }
    
    // Rethrow the original exception
    throw throwable;
  }

  /**
   * Checks if an exception might be related to thread pinning issues.
   * <p>
   * This method analyzes the exception and its cause chain to determine if it might be
   * caused by thread pinning.
   *
   * @param throwable the exception to analyze
   * @return true if the exception might be related to thread pinning, false otherwise
   */
  private boolean isPotentialThreadPinningIssue(final Throwable throwable) {
    if (throwable == null) {
      return false;
    }
    
    // Check for common symptoms of thread pinning issues
    String message = throwable.getMessage();
    if (message != null) {
      if (message.contains("timeout") || 
          message.contains("deadlock") || 
          message.contains("blocked") || 
          message.contains("stuck")) {
        return true;
      }
    }
    
    // Check the cause chain
    return isPotentialThreadPinningIssue(throwable.getCause());
  }

  /**
   * Formats thread pinning statistics for logging.
   *
   * @return a formatted string containing thread pinning statistics
   */
  private String formatPinningStatistics() {
    StringBuilder sb = new StringBuilder();
    ThreadPinningDetector.getPinningStatistics().forEach(info -> {
      sb.append(String.format("Location: %s\n", info.getLocation()))
        .append(String.format("  Count: %d\n", info.getCount()))
        .append(String.format("  Total Duration: %d ms\n", info.getTotalDurationMs()))
        .append(String.format("  Average Duration: %.2f ms\n", info.getAverageDurationMs()))
        .append("\n");
    });
    return sb.toString();
  }

  /**
   * Gets the virtual thread executor that can be used by tests.
   * <p>
   * This executor creates a new virtual thread for each submitted task, making it suitable
   * for testing code that needs to run on virtual threads.
   *
   * @return the virtual thread executor, or empty if not initialized
   */
  public static Optional<ExecutorService> getVirtualThreadExecutor() {
    return Optional.ofNullable(virtualThreadExecutor.get());
  }

  /**
   * Compares the performance of a task running on platform threads versus virtual threads.
   * <p>
   * This method executes the same task on both platform threads and virtual threads,
   * and returns a comparison of their execution times.
   *
   * @param task the task to execute
   * @param iterations the number of iterations to run
   * @return a performance comparison result
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static PerformanceComparison comparePerformance(final Runnable task, final int iterations) 
      throws InterruptedException 
  {
    // Run on platform threads
    long platformStart = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
      task.run();
    }
    long platformTime = System.currentTimeMillis() - platformStart;
    
    // Run on virtual threads
    long virtualStart = System.currentTimeMillis();
    for (int i = 0; i < iterations; i++) {
      VirtualThreadTestSupport.runVirtual(task);
    }
    long virtualTime = System.currentTimeMillis() - virtualStart;
    
    return new PerformanceComparison(platformTime, virtualTime, iterations);
  }

  /**
   * Detects if a task experiences thread pinning when run on a virtual thread.
   * <p>
   * This method uses the ThreadPinningDetector to check if the task causes thread pinning.
   *
   * @param task the task to check
   * @return true if thread pinning was detected, false otherwise
   * @throws InterruptedException if the thread is interrupted while waiting
   */
  public static boolean detectThreadPinning(final Runnable task) throws InterruptedException {
    return VirtualThreadTestSupport.detectThreadPinning(task);
  }

  /**
   * Represents a performance comparison between platform threads and virtual threads.
   */
  public static class PerformanceComparison
  {
    private final long platformThreadTimeMs;
    private final long virtualThreadTimeMs;
    private final int iterations;

    public PerformanceComparison(long platformThreadTimeMs, long virtualThreadTimeMs, int iterations) {
      this.platformThreadTimeMs = platformThreadTimeMs;
      this.virtualThreadTimeMs = virtualThreadTimeMs;
      this.iterations = iterations;
    }

    /**
     * Gets the total execution time on platform threads in milliseconds.
     *
     * @return the platform thread execution time
     */
    public long getPlatformThreadTimeMs() {
      return platformThreadTimeMs;
    }

    /**
     * Gets the total execution time on virtual threads in milliseconds.
     *
     * @return the virtual thread execution time
     */
    public long getVirtualThreadTimeMs() {
      return virtualThreadTimeMs;
    }

    /**
     * Gets the number of iterations that were executed.
     *
     * @return the number of iterations
     */
    public int getIterations() {
      return iterations;
    }

    /**
     * Gets the average execution time per iteration on platform threads in milliseconds.
     *
     * @return the average platform thread execution time per iteration
     */
    public double getAveragePlatformThreadTimeMs() {
      return (double) platformThreadTimeMs / iterations;
    }

    /**
     * Gets the average execution time per iteration on virtual threads in milliseconds.
     *
     * @return the average virtual thread execution time per iteration
     */
    public double getAverageVirtualThreadTimeMs() {
      return (double) virtualThreadTimeMs / iterations;
    }

    /**
     * Gets the performance improvement factor of virtual threads over platform threads.
     * <p>
     * A value greater than 1.0 indicates that virtual threads are faster.
     * A value less than 1.0 indicates that platform threads are faster.
     *
     * @return the performance improvement factor
     */
    public double getImprovementFactor() {
      return (double) platformThreadTimeMs / virtualThreadTimeMs;
    }

    /**
     * Gets a formatted summary of the performance comparison.
     *
     * @return a formatted summary string
     */
    public String getSummary() {
      return String.format(
          "Performance Comparison (%d iterations):\n" +
          "  Platform Threads: %d ms (%.2f ms per iteration)\n" +
          "  Virtual Threads: %d ms (%.2f ms per iteration)\n" +
          "  Improvement Factor: %.2fx (%s)",
          iterations,
          platformThreadTimeMs, getAveragePlatformThreadTimeMs(),
          virtualThreadTimeMs, getAverageVirtualThreadTimeMs(),
          getImprovementFactor(),
          getImprovementFactor() > 1.0 ? "Virtual threads are faster" : "Platform threads are faster"
      );
    }

    @Override
    public String toString() {
      return getSummary();
    }
  }
}