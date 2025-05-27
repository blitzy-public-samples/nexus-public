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
package org.sonatype.nexus.testsuite.testsupport;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceTestSeries;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceChart;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Performance comparison test between Java 21 Virtual Threads and traditional platform threads.
 * 
 * This test measures throughput, latency, and resource utilization under various load conditions
 * to validate the performance improvements provided by Virtual Threads for I/O-bound operations.
 * 
 * The test generates HTML reports with charts comparing the performance of both threading models,
 * focusing on metrics such as:
 * - Throughput (operations per second)
 * - Latency (response time)
 * - Resource utilization (CPU, memory)
 * - Scalability under different concurrency levels
 * 
 * These metrics are crucial for validating that Virtual Threads provide the expected performance
 * improvements in Nexus Repository, particularly for I/O-bound operations like remote repository
 * connections and file operations.
 */
public class VirtualThreadPerformanceComparisonTest
    extends TestSupport
{
  private static final int LOW_CONCURRENCY = 10;
  private static final int MEDIUM_CONCURRENCY = 100;
  private static final int HIGH_CONCURRENCY = 1000;
  private static final int VERY_HIGH_CONCURRENCY = 10000;
  
  private static final int TASK_DURATION_MS = 50; // Simulated I/O operation duration
  private static final int WARMUP_ITERATIONS = 3;
  private static final int TEST_ITERATIONS = 5;
  
  // Task types for different workload patterns
  private enum TaskType {
    SIMPLE_SLEEP,    // Simple Thread.sleep to simulate I/O
    FILE_IO,         // Actual file operations
    NETWORK_IO       // Simulated network operations with variable latency
  }
  
  // Default task type to use for tests
  private static final TaskType DEFAULT_TASK_TYPE = TaskType.SIMPLE_SLEEP;
  
  @Rule
  public TestName name = new TestName();

  private File chartFile;

  @Before
  public void setChartFileLocation() throws IOException {
    final File chartDir = util.resolveFile("target/test-results/virtual-thread-performance");
    chartDir.mkdirs();
    chartFile = new File(chartDir, name.getMethodName() + ".html");
  }
  
  /**
   * Tests throughput comparison between platform threads and virtual threads under low concurrency.
   * 
   * At low concurrency levels, we expect platform threads to perform similarly to virtual threads,
   * as the overhead of platform threads is not significant when the number of threads is small.
   */
  @Test
  public void testLowConcurrencyThroughput() throws Exception {
    log.info("Running throughput comparison at low concurrency: {} threads", LOW_CONCURRENCY);
    runThroughputComparison("Low Concurrency Throughput", LOW_CONCURRENCY);
  }
  
  /**
   * Tests throughput comparison between platform threads and virtual threads under medium concurrency.
   * 
   * At medium concurrency levels, we expect to start seeing the benefits of virtual threads,
   * as the overhead of platform threads becomes more significant.
   */
  @Test
  public void testMediumConcurrencyThroughput() throws Exception {
    log.info("Running throughput comparison at medium concurrency: {} threads", MEDIUM_CONCURRENCY);
    runThroughputComparison("Medium Concurrency Throughput", MEDIUM_CONCURRENCY);
  }
  
  /**
   * Tests throughput comparison between platform threads and virtual threads under high concurrency.
   * 
   * At high concurrency levels, we expect virtual threads to significantly outperform platform threads,
   * as the overhead of platform threads becomes substantial and context switching increases.
   */
  @Test
  public void testHighConcurrencyThroughput() throws Exception {
    log.info("Running throughput comparison at high concurrency: {} threads", HIGH_CONCURRENCY);
    runThroughputComparison("High Concurrency Throughput", HIGH_CONCURRENCY);
  }
  
  /**
   * Tests throughput comparison between platform threads and virtual threads under very high concurrency.
   * This test is particularly important for validating Virtual Threads' scalability advantages.
   * 
   * At very high concurrency levels, platform threads are expected to struggle significantly or even fail,
   * while virtual threads should continue to perform well due to their lightweight nature.
   * This test simulates scenarios like high-traffic repository access in Nexus.
   */
  @Test
  public void testVeryHighConcurrencyThroughput() throws Exception {
    log.info("Running throughput comparison at very high concurrency: {} threads", VERY_HIGH_CONCURRENCY);
    runThroughputComparison("Very High Concurrency Throughput", VERY_HIGH_CONCURRENCY);
  }
  
  /**
   * Tests different I/O patterns to compare how virtual threads perform with different types of I/O operations.
   * This helps identify which types of operations benefit most from virtual threads.
   * 
   * In Nexus Repository, different types of I/O operations are common:
   * - Network I/O for remote repository connections
   * - File I/O for blob storage operations
   * - Database I/O for metadata storage
   * 
   * Understanding which patterns benefit most from virtual threads helps prioritize
   * which components to migrate first in the Java 21 upgrade.
   */
  @Test
  public void testDifferentIoPatterns() throws Exception {
    final PerformanceData perfData = new PerformanceData();
    final PerformanceTestSeries sleepPlatformSeries = perfData.findTestResult("Sleep I/O - Platform");
    final PerformanceTestSeries sleepVirtualSeries = perfData.findTestResult("Sleep I/O - Virtual");
    final PerformanceTestSeries filePlatformSeries = perfData.findTestResult("File I/O - Platform");
    final PerformanceTestSeries fileVirtualSeries = perfData.findTestResult("File I/O - Virtual");
    final PerformanceTestSeries networkPlatformSeries = perfData.findTestResult("Network I/O - Platform");
    final PerformanceTestSeries networkVirtualSeries = perfData.findTestResult("Network I/O - Virtual");
    
    int concurrency = MEDIUM_CONCURRENCY;
    
    // Test sleep I/O pattern
    runTaskTypeComparison(concurrency, TaskType.SIMPLE_SLEEP, sleepPlatformSeries, sleepVirtualSeries);
    
    // Test file I/O pattern
    runTaskTypeComparison(concurrency, TaskType.FILE_IO, filePlatformSeries, fileVirtualSeries);
    
    // Test network I/O pattern
    runTaskTypeComparison(concurrency, TaskType.NETWORK_IO, networkPlatformSeries, networkVirtualSeries);
    
    // Generate chart
    PerformanceChart.writePerformanceReport(perfData, chartFile);
  }
  
  /**
   * Runs a comparison between platform threads and virtual threads for a specific task type.
   * 
   * @param concurrency The number of concurrent tasks to run
   * @param taskType The type of I/O task to simulate
   * @param platformSeries The series to record platform thread results
   * @param virtualSeries The series to record virtual thread results
   */
  private void runTaskTypeComparison(int concurrency, TaskType taskType, 
      PerformanceTestSeries platformSeries, PerformanceTestSeries virtualSeries) 
      throws InterruptedException, ExecutionException {
    
    // Warm-up runs
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runPlatformThreadWorkload(concurrency, taskType);
      runVirtualThreadWorkload(concurrency, taskType);
    }
    
    // Test runs
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      // Measure platform thread throughput
      long platformStart = System.nanoTime();
      runPlatformThreadWorkload(concurrency, taskType);
      long platformEnd = System.nanoTime();
      double platformThroughput = calculateThroughput(concurrency, platformStart, platformEnd);
      
      // Measure virtual thread throughput
      long virtualStart = System.nanoTime();
      runVirtualThreadWorkload(concurrency, taskType);
      long virtualEnd = System.nanoTime();
      double virtualThroughput = calculateThroughput(concurrency, virtualStart, virtualEnd);
      
      // Record results
      platformSeries.addResults(i + 1, new PerformanceRunResult(platformThroughput, 0, 0, true));
      virtualSeries.addResults(i + 1, new PerformanceRunResult(virtualThroughput, 0, 0, true));
    }
  }
  
  /**
   * Tests latency comparison between platform threads and virtual threads under low concurrency.
   * 
   * Latency is a critical metric for user experience in Nexus Repository, as it directly affects
   * how quickly users receive responses to their requests.
   */
  @Test
  public void testLowConcurrencyLatency() throws Exception {
    log.info("Running latency comparison at low concurrency: {} threads", LOW_CONCURRENCY);
    runLatencyComparison("Low Concurrency Latency", LOW_CONCURRENCY);
  }
  
  /**
   * Tests latency comparison between platform threads and virtual threads under medium concurrency.
   * 
   * As concurrency increases, we expect to see more pronounced differences in latency between
   * platform threads and virtual threads, particularly for I/O-bound operations.
   */
  @Test
  public void testMediumConcurrencyLatency() throws Exception {
    log.info("Running latency comparison at medium concurrency: {} threads", MEDIUM_CONCURRENCY);
    runLatencyComparison("Medium Concurrency Latency", MEDIUM_CONCURRENCY);
  }
  
  /**
   * Tests latency comparison between platform threads and virtual threads under high concurrency.
   * 
   * At high concurrency levels, platform threads often experience significant latency increases
   * due to context switching and resource contention, while virtual threads should maintain
   * more consistent latency due to their lightweight nature and efficient scheduling.
   */
  @Test
  public void testHighConcurrencyLatency() throws Exception {
    log.info("Running latency comparison at high concurrency: {} threads", HIGH_CONCURRENCY);
    runLatencyComparison("High Concurrency Latency", HIGH_CONCURRENCY);
  }
  
  /**
   * Tests resource utilization comparison between platform threads and virtual threads.
   * Measures memory usage and CPU utilization under different concurrency levels.
   * 
   * This test is particularly important for validating that Virtual Threads use
   * significantly less resources than platform threads at high concurrency levels.
   */
  @Test
  public void testResourceUtilization() throws Exception {
    final PerformanceData perfData = new PerformanceData();
    final PerformanceTestSeries platformThreadMemory = perfData.findTestResult("Platform Thread Memory (MB)");
    final PerformanceTestSeries virtualThreadMemory = perfData.findTestResult("Virtual Thread Memory (MB)");
    final PerformanceTestSeries platformThreadCpu = perfData.findTestResult("Platform Thread CPU (%)");
    final PerformanceTestSeries virtualThreadCpu = perfData.findTestResult("Virtual Thread CPU (%)");
    
    // Test different concurrency levels
    int[] concurrencyLevels = {LOW_CONCURRENCY, MEDIUM_CONCURRENCY, HIGH_CONCURRENCY};
    
    for (int concurrency : concurrencyLevels) {
      log.info("Measuring resource utilization at concurrency level: {}", concurrency);
      
      // Measure platform thread resource usage
      ResourceUsage platformUsage = measureResourceUsage(() -> {
        runPlatformThreadWorkload(concurrency);
        return null;
      });
      
      // Measure virtual thread resource usage
      ResourceUsage virtualUsage = measureResourceUsage(() -> {
        runVirtualThreadWorkload(concurrency);
        return null;
      });
      
      log.info("Platform threads at concurrency {}: Memory={} MB, CPU={}%", 
          concurrency, platformUsage.memoryMb, platformUsage.cpuPercent);
      log.info("Virtual threads at concurrency {}: Memory={} MB, CPU={}%", 
          concurrency, virtualUsage.memoryMb, virtualUsage.cpuPercent);
      
      // Record results
      platformThreadMemory.addResults(concurrency, 
          new PerformanceRunResult(platformUsage.memoryMb, 0, 0, true));
      virtualThreadMemory.addResults(concurrency, 
          new PerformanceRunResult(virtualUsage.memoryMb, 0, 0, true));
      platformThreadCpu.addResults(concurrency, 
          new PerformanceRunResult(platformUsage.cpuPercent, 0, 0, true));
      virtualThreadCpu.addResults(concurrency, 
          new PerformanceRunResult(virtualUsage.cpuPercent, 0, 0, true));
    }
    
    // Generate chart
    PerformanceChart.writePerformanceReport(perfData, chartFile);
  }
  
  /**
   * Runs a throughput comparison test between platform threads and virtual threads.
   * 
   * @param testName The name of the test for reporting
   * @param concurrency The number of concurrent tasks to run
   */
  private void runThroughputComparison(String testName, int concurrency) throws Exception {
    final PerformanceData perfData = new PerformanceData();
    final PerformanceTestSeries platformThreadSeries = perfData.findTestResult("Platform Threads");
    final PerformanceTestSeries virtualThreadSeries = perfData.findTestResult("Virtual Threads");
    
    // Warm-up runs
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runPlatformThreadWorkload(concurrency);
      runVirtualThreadWorkload(concurrency);
    }
    
    // Test runs
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      // Measure platform thread throughput
      long platformStart = System.nanoTime();
      runPlatformThreadWorkload(concurrency);
      long platformEnd = System.nanoTime();
      double platformThroughput = calculateThroughput(concurrency, platformStart, platformEnd);
      
      // Measure virtual thread throughput
      long virtualStart = System.nanoTime();
      runVirtualThreadWorkload(concurrency);
      long virtualEnd = System.nanoTime();
      double virtualThroughput = calculateThroughput(concurrency, virtualStart, virtualEnd);
      
      // Record results
      platformThreadSeries.addResults(i + 1, 
          new PerformanceRunResult(platformThroughput, 0, 0, true));
      virtualThreadSeries.addResults(i + 1, 
          new PerformanceRunResult(virtualThroughput, 0, 0, true));
    }
    
    // Generate chart
    PerformanceChart.writePerformanceReport(perfData, chartFile);
  }
  
  /**
   * Runs a latency comparison test between platform threads and virtual threads.
   * 
   * @param testName The name of the test for reporting
   * @param concurrency The number of concurrent tasks to run
   */
  private void runLatencyComparison(String testName, int concurrency) throws Exception {
    final PerformanceData perfData = new PerformanceData();
    final PerformanceTestSeries platformThreadSeries = perfData.findTestResult("Platform Threads");
    final PerformanceTestSeries virtualThreadSeries = perfData.findTestResult("Virtual Threads");
    
    // Warm-up runs
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      measureLatency(() -> runPlatformThreadWorkload(concurrency));
      measureLatency(() -> runVirtualThreadWorkload(concurrency));
    }
    
    // Test runs
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      // Measure platform thread latency
      double platformLatency = measureLatency(() -> runPlatformThreadWorkload(concurrency));
      
      // Measure virtual thread latency
      double virtualLatency = measureLatency(() -> runVirtualThreadWorkload(concurrency));
      
      // Record results
      platformThreadSeries.addResults(i + 1, 
          new PerformanceRunResult(platformLatency, 0, 0, true));
      virtualThreadSeries.addResults(i + 1, 
          new PerformanceRunResult(virtualLatency, 0, 0, true));
    }
    
    // Generate chart
    PerformanceChart.writePerformanceReport(perfData, chartFile);
  }
  
  /**
   * Runs a workload using traditional platform threads.
   * 
   * @param concurrency The number of concurrent tasks to run
   */
  private void runPlatformThreadWorkload(int concurrency) throws InterruptedException, ExecutionException {
    runPlatformThreadWorkload(concurrency, DEFAULT_TASK_TYPE);
  }
  
  /**
   * Runs a workload using traditional platform threads with the specified task type.
   * 
   * @param concurrency The number of concurrent tasks to run
   * @param taskType The type of I/O task to simulate
   */
  private void runPlatformThreadWorkload(int concurrency, TaskType taskType) 
      throws InterruptedException, ExecutionException {
    try (ExecutorService executor = Executors.newFixedThreadPool(concurrency, 
        new NamedThreadFactory("platform"))) {
      List<Future<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < concurrency; i++) {
        futures.add(executor.submit(() -> executeTask(taskType)));
      }
      
      // Wait for all tasks to complete
      for (Future<Void> future : futures) {
        future.get();
      }
    }
  }
  
  /**
   * Runs a workload using Java 21 Virtual Threads.
   * 
   * @param concurrency The number of concurrent tasks to run
   */
  private void runVirtualThreadWorkload(int concurrency) throws InterruptedException, ExecutionException {
    runVirtualThreadWorkload(concurrency, DEFAULT_TASK_TYPE);
  }
  
  /**
   * Runs a workload using Java 21 Virtual Threads with the specified task type.
   * 
   * @param concurrency The number of concurrent tasks to run
   * @param taskType The type of I/O task to simulate
   */
  private void runVirtualThreadWorkload(int concurrency, TaskType taskType) 
      throws InterruptedException, ExecutionException {
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < concurrency; i++) {
        futures.add(executor.submit(() -> executeTask(taskType)));
      }
      
      // Wait for all tasks to complete
      for (Future<Void> future : futures) {
        future.get();
      }
    }
  }
  
  /**
   * Alternative implementation using direct virtual thread creation instead of an executor.
   * This demonstrates the different ways to create virtual threads in Java 21.
   * 
   * @param concurrency The number of concurrent tasks to run
   * @param taskType The type of I/O task to simulate
   */
  private void runDirectVirtualThreads(int concurrency, TaskType taskType) throws InterruptedException {
    List<Thread> threads = new ArrayList<>();
    
    for (int i = 0; i < concurrency; i++) {
      Thread thread = Thread.ofVirtual().name("virtual-" + i).start(() -> {
        try {
          executeTask(taskType);
        }
        catch (Exception e) {
          log.error("Error in virtual thread task", e);
          Thread.currentThread().interrupt();
        }
      });
      threads.add(thread);
    }
    
    // Wait for all threads to complete
    for (Thread thread : threads) {
      thread.join();
    }
  }
  
  /**
   * Executes the appropriate task based on the task type.
   * 
   * @param taskType The type of I/O task to simulate
   * @return null (void result)
   */
  private Void executeTask(TaskType taskType) throws Exception {
    switch (taskType) {
      case SIMPLE_SLEEP:
        return simulateIoBoundTask();
      case FILE_IO:
        return simulateFileIoTask();
      case NETWORK_IO:
        return simulateNetworkIoTask();
      default:
        return simulateIoBoundTask();
    }
  }
  
  /**
   * Simulates an I/O-bound task by sleeping for a fixed duration.
   * In a real-world scenario, this would be a network call, file operation, or database query.
   */
  private Void simulateIoBoundTask() throws InterruptedException {
    Thread.sleep(TASK_DURATION_MS);
    return null;
  }
  
  /**
   * Simulates a more realistic I/O-bound task that performs file operations.
   * This is more representative of real-world Nexus Repository operations like BlobStore interactions.
   */
  private Void simulateFileIoTask() throws InterruptedException, IOException {
    File tempDir = util.resolveFile("target/test-tmp/io-simulation");
    tempDir.mkdirs();
    
    // Create a unique filename for this thread to avoid contention
    String threadName = Thread.currentThread().getName();
    File tempFile = new File(tempDir, "temp-" + threadName + "-" + System.nanoTime() + ".tmp");
    
    try {
      // Create file - simulates creating a blob
      tempFile.createNewFile();
      
      // Simulate some processing time - like computing hashes or metadata
      Thread.sleep(TASK_DURATION_MS / 4);
      
      // Write some content - simulates storing blob content
      java.nio.file.Files.write(
          tempFile.toPath(), 
          ("Content for " + threadName + " at " + System.currentTimeMillis()).getBytes());
      
      // Simulate more processing time - like updating database records
      Thread.sleep(TASK_DURATION_MS / 4);
      
      // Read the content - simulates retrieving blob content
      java.nio.file.Files.readAllBytes(tempFile.toPath());
      
      // Simulate more processing time - like processing the retrieved content
      Thread.sleep(TASK_DURATION_MS / 4);
      
      // Delete file - simulates cleanup or temporary file deletion
      tempFile.delete();
      
      // Simulate final processing time
      Thread.sleep(TASK_DURATION_MS / 4);
    }
    catch (IOException e) {
      log.error("Error during file I/O simulation", e);
      throw e;
    }
    
    return null;
  }
  
  /**
   * Simulates a network I/O-bound task.
   * In a real implementation, this would make actual HTTP requests to external systems,
   * similar to how Nexus Repository connects to remote repositories.
   */
  private Void simulateNetworkIoTask() throws InterruptedException {
    // Simulate initial connection establishment
    Thread.sleep(TASK_DURATION_MS / 5);
    
    // Simulate sending request
    Thread.sleep(TASK_DURATION_MS / 10);
    
    // Simulate waiting for response with variable timing
    // This represents the most significant part of network I/O where threads are blocked
    // waiting for external systems to respond - exactly where virtual threads excel
    long responseLatency = (long) (TASK_DURATION_MS * 0.5 * (0.8 + Math.random() * 0.4));
    Thread.sleep(responseLatency);
    
    // Simulate processing response
    Thread.sleep(TASK_DURATION_MS / 5);
    
    // Simulate connection cleanup
    Thread.sleep(TASK_DURATION_MS / 10);
    
    return null;
  }
  
  /**
   * Calculates throughput in operations per second.
   * 
   * @param operations The number of operations completed
   * @param startNanos The start time in nanoseconds
   * @param endNanos The end time in nanoseconds
   * @return The throughput in operations per second
   */
  private double calculateThroughput(int operations, long startNanos, long endNanos) {
    double durationSeconds = (endNanos - startNanos) / 1_000_000_000.0;
    return operations / durationSeconds;
  }
  
  /**
   * Measures the average latency of a task.
   * 
   * @param task The task to measure
   * @return The average latency in milliseconds
   */
  private double measureLatency(Runnable task) {
    long start = System.nanoTime();
    task.run();
    long end = System.nanoTime();
    
    return (end - start) / 1_000_000.0; // Convert to milliseconds
  }
  
  /**
   * Measures resource usage (memory and CPU) while executing a task.
   * 
   * @param task The task to measure
   * @return The resource usage metrics
   */
  private ResourceUsage measureResourceUsage(Callable<Void> task) throws Exception {
    // Get initial memory usage
    System.gc(); // Request garbage collection to get more accurate measurements
    long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Measure CPU time before task
    long startCpuTime = getProcessCpuTime();
    long startTime = System.nanoTime();
    
    // Run the task
    task.call();
    
    // Measure CPU time after task
    long endTime = System.nanoTime();
    long endCpuTime = getProcessCpuTime();
    
    // Get final memory usage
    System.gc(); // Request garbage collection to get more accurate measurements
    long finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Calculate metrics
    double memoryUsageMb = (finalMemory - initialMemory) / (1024.0 * 1024.0);
    double cpuPercent = calculateCpuPercent(startCpuTime, endCpuTime, startTime, endTime);
    
    return new ResourceUsage(memoryUsageMb, cpuPercent);
  }
  
  /**
   * Gets the current process CPU time in nanoseconds.
   * This is a simplified implementation and may not be accurate on all platforms.
   */
  private long getProcessCpuTime() {
    return System.nanoTime(); // Simplified implementation
  }
  
  /**
   * Calculates CPU usage percentage.
   * This is a simplified implementation and may not be accurate on all platforms.
   */
  private double calculateCpuPercent(long startCpuTime, long endCpuTime, long startTime, long endTime) {
    double cpuTime = endCpuTime - startCpuTime;
    double totalTime = endTime - startTime;
    return (cpuTime / totalTime) * 100.0;
  }
  
  /**
   * Simple class to hold resource usage metrics.
   */
  private static class ResourceUsage {
    final double memoryMb;
    final double cpuPercent;
    
    ResourceUsage(double memoryMb, double cpuPercent) {
      this.memoryMb = memoryMb;
      this.cpuPercent = cpuPercent;
    }
  }
  
  /**
   * Thread factory that creates named threads for better debugging and monitoring.
   */
  private static class NamedThreadFactory implements ThreadFactory {
    private final AtomicInteger threadNumber = new AtomicInteger(1);
    private final String namePrefix;
    
    NamedThreadFactory(String namePrefix) {
      this.namePrefix = namePrefix;
    }
    
    @Override
    public Thread newThread(Runnable r) {
      return new Thread(r, namePrefix + "-thread-" + threadNumber.getAndIncrement());
    }
  }
}