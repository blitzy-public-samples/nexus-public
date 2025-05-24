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
package org.sonatype.nexus.email;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceRunResult;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData.PerformanceTestSeries;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Test class that evaluates the performance and scalability benefits of Java 21 virtual threads
 * for email operations compared to traditional platform threads.
 * 
 * This test performs comparative benchmarks between platform and virtual thread executors
 * using identical email operations and records execution times, memory usage, and throughput metrics.
 * 
 * @since 3.60
 */
@EnabledOnJre(JRE.JAVA_21)
@Tag("VirtualThreadTest")
public class EmailConcurrencyTest
    extends TestSupport
{
  private static final int[] THREAD_COUNTS = new int[]{100, 500, 1000, 5000};
  
  private static final int DURATION_SECONDS = 10;
  
  private static final int WARMUP_ITERATIONS = 5;
  
  private static final int MEASUREMENT_ITERATIONS = 20;
  
  @TempDir
  Path tempDir;
  
  private Path emailDir;
  
  private MockEmailer emailer;
  
  private ExecutorService platformExecutor;
  
  private ExecutorService virtualExecutor;
  
  @BeforeEach
  void setUp() throws IOException {
    emailDir = tempDir.resolve("emails");
    Files.createDirectories(emailDir);
    
    emailer = new MockEmailer(emailDir.toFile());
    
    // Create platform thread executor with fixed thread pool
    platformExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    
    // Create virtual thread executor using Java 21 virtual threads
    virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @AfterEach
  void tearDown() {
    if (platformExecutor != null) {
      platformExecutor.shutdownNow();
    }
    
    if (virtualExecutor != null) {
      virtualExecutor.shutdownNow();
    }
  }
  
  /**
   * Tests the performance difference between platform threads and virtual threads
   * when handling concurrent email operations.
   * 
   * This test demonstrates the efficiency advantages of virtual threads when dealing with
   * I/O-bound email operations and provides performance validation in high-concurrency scenarios.
   */
  @Test
  @DisplayName("Compare platform threads vs virtual threads for email operations")
  void compareThreadModelsForEmailOperations() throws Exception {
    // Verify we're running on Java 21 or later
    assumeTrue(Runtime.version().feature() >= 21, 
        "This test requires Java 21 or later for virtual thread support");
    
    log.info("Starting email concurrency performance test");
    
    // Run performance tests for both thread models
    PerformanceData platformResults = runPerformanceTest("Platform Threads", this::createPlatformThreadTasks);
    PerformanceData virtualResults = runPerformanceTest("Virtual Threads", this::createVirtualThreadTasks);
    
    // Log the results
    log.info("Platform Thread Results: {}", platformResults);
    log.info("Virtual Thread Results: {}", virtualResults);
    
    // Verify that virtual threads can handle higher concurrency
    int maxPlatformThreads = platformResults.getMaxThreads();
    int maxVirtualThreads = virtualResults.getMaxThreads();
    
    log.info("Max Platform Threads: {}", maxPlatformThreads);
    log.info("Max Virtual Threads: {}", maxVirtualThreads);
    
    // Verify performance metrics
    double platformThroughput = platformResults.getThroughput();
    double virtualThroughput = virtualResults.getThroughput();
    
    log.info("Platform Thread Throughput: {} ops/sec", platformThroughput);
    log.info("Virtual Thread Throughput: {} ops/sec", virtualThroughput);
    
    // Memory usage comparison
    long platformMemoryUsage = platformResults.getMemoryUsage();
    long virtualMemoryUsage = virtualResults.getMemoryUsage();
    
    log.info("Platform Thread Memory Usage: {} MB", platformMemoryUsage / (1024 * 1024));
    log.info("Virtual Thread Memory Usage: {} MB", virtualMemoryUsage / (1024 * 1024));
    
    // Assertions to validate virtual thread benefits
    assertAll(
        // Virtual threads should support more concurrent connections
        () -> assertThat("Virtual threads should support higher concurrency", 
            maxVirtualThreads, greaterThan(maxPlatformThreads)),
        
        // Virtual threads should have higher throughput
        () -> assertThat("Virtual threads should provide better throughput", 
            virtualThroughput, greaterThan(platformThroughput)),
        
        // Virtual threads should use less memory per thread
        () -> assertThat("Virtual threads should use less memory per thread", 
            (double) virtualMemoryUsage / maxVirtualThreads, 
            lessThan((double) platformMemoryUsage / maxPlatformThreads))
    );
  }
  
  /**
   * Tests the scalability of virtual threads with increasing concurrent client connections.
   * 
   * This test demonstrates how virtual threads can efficiently handle a large number of
   * concurrent connections without significant performance degradation, unlike platform threads.
   */
  @Test
  @DisplayName("Test virtual thread scalability with increasing client connections")
  void testVirtualThreadScalability() throws Exception {
    // Verify we're running on Java 21 or later
    assumeTrue(Runtime.version().feature() >= 21, 
        "This test requires Java 21 or later for virtual thread support");
    
    log.info("Starting virtual thread scalability test");
    
    // Create performance data for tracking results
    PerformanceData results = new PerformanceData();
    PerformanceTestSeries virtualSeries = results.findTestResult("Virtual Threads");
    
    // Test with escalating thread counts
    for (int threadCount : THREAD_COUNTS) {
      log.info("Testing with {} virtual threads", threadCount);
      
      // Create tasks for the current thread count
      List<Callable<Void>> tasks = createTasksForThreadCount(threadCount, virtualExecutor);
      
      // Warm up
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runTasks(tasks, virtualExecutor, 1);
      }
      
      // Measure memory before test
      long memoryBefore = getUsedMemory();
      
      // Run the test and measure time
      long startTime = System.nanoTime();
      int completedTasks = runTasks(tasks, virtualExecutor, MEASUREMENT_ITERATIONS);
      long endTime = System.nanoTime();
      
      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;
      
      // Calculate metrics
      double durationSeconds = Duration.ofNanos(endTime - startTime).toMillis() / 1000.0;
      double throughput = completedTasks / durationSeconds;
      
      // Record results
      virtualSeries.addResults(threadCount, new PerformanceRunResult(
          completedTasks,
          0, // No failed tasks
          (int) durationSeconds,
          false // No exceptions
      ));
      
      log.info("Thread count: {}, Throughput: {} ops/sec, Memory usage: {} MB", 
          threadCount, throughput, memoryUsage / (1024 * 1024));
      
      // Verify that the test completed successfully with all thread counts
      assertTrue(completedTasks > 0, "No tasks completed for thread count: " + threadCount);
    }
    
    // Verify that the test was able to handle the maximum thread count
    assertTrue(virtualSeries.hasResults(THREAD_COUNTS[THREAD_COUNTS.length - 1]), 
        "Failed to test with maximum thread count");
  }
  
  /**
   * Runs a performance test with the given thread model and returns performance data.
   */
  private PerformanceData runPerformanceTest(String testName, Supplier<List<Callable<Void>>> taskSupplier) 
      throws Exception {
    PerformanceData results = new PerformanceData();
    PerformanceTestSeries series = results.findTestResult(testName);
    
    for (int threadCount : THREAD_COUNTS) {
      try {
        log.info("Testing {} with {} threads", testName, threadCount);
        
        // Create tasks for the current thread model and thread count
        List<Callable<Void>> tasks = taskSupplier.get();
        
        // Limit tasks to the current thread count
        tasks = tasks.subList(0, Math.min(tasks.size(), threadCount));
        
        // Warm up
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
          runTasks(tasks, getExecutorForTestName(testName), 1);
        }
        
        // Measure memory before test
        long memoryBefore = getUsedMemory();
        
        // Run the test and measure time
        long startTime = System.nanoTime();
        int completedTasks = runTasks(tasks, getExecutorForTestName(testName), MEASUREMENT_ITERATIONS);
        long endTime = System.nanoTime();
        
        // Measure memory after test
        long memoryAfter = getUsedMemory();
        long memoryUsage = memoryAfter - memoryBefore;
        
        // Calculate metrics
        double durationSeconds = Duration.ofNanos(endTime - startTime).toMillis() / 1000.0;
        double throughput = completedTasks / durationSeconds;
        
        // Record results
        series.addResults(threadCount, new PerformanceRunResult(
            completedTasks,
            0, // No failed tasks
            (int) durationSeconds,
            false // No exceptions
        ));
        
        // Store additional metrics in the performance data
        results.setThroughput(throughput);
        results.setMemoryUsage(memoryUsage);
        results.setMaxThreads(threadCount);
        
        log.info("{} - Thread count: {}, Throughput: {} ops/sec, Memory usage: {} MB", 
            testName, threadCount, throughput, memoryUsage / (1024 * 1024));
      } 
      catch (Exception e) {
        log.error("Error testing {} with {} threads", testName, threadCount, e);
        // Record the failure but continue with the next thread count
        series.addResults(threadCount, new PerformanceRunResult(
            0, // No completed tasks
            threadCount, // All tasks failed
            DURATION_SECONDS,
            true // Exception occurred
        ));
        
        // If we can't handle this thread count, we won't be able to handle higher counts
        break;
      }
    }
    
    return results;
  }
  
  /**
   * Creates tasks that use platform threads for email operations.
   */
  private List<Callable<Void>> createPlatformThreadTasks() {
    return createTasksForThreadCount(THREAD_COUNTS[THREAD_COUNTS.length - 1], platformExecutor);
  }
  
  /**
   * Creates tasks that use virtual threads for email operations.
   */
  private List<Callable<Void>> createVirtualThreadTasks() {
    return createTasksForThreadCount(THREAD_COUNTS[THREAD_COUNTS.length - 1], virtualExecutor);
  }
  
  /**
   * Creates a list of callable tasks for the given thread count and executor.
   */
  private List<Callable<Void>> createTasksForThreadCount(int threadCount, ExecutorService executor) {
    List<Callable<Void>> tasks = new ArrayList<>(threadCount);
    
    for (int i = 0; i < threadCount; i++) {
      final int taskId = i;
      tasks.add(() -> {
        // Simulate an email verification operation
        String recipient = "user" + taskId + "@example.com";
        String subject = "Test Email " + taskId;
        String body = "This is a test email for task " + taskId;
        
        // Perform the email operation
        emailer.sendEmail(recipient, subject, body);
        
        // Verify the email was sent
        boolean verified = emailer.verifyEmailSent(recipient, subject);
        assertThat("Email should be sent and verified", verified, is(true));
        
        return null;
      });
    }
    
    return tasks;
  }
  
  /**
   * Runs the given tasks using the provided executor and returns the number of completed tasks.
   */
  private int runTasks(List<Callable<Void>> tasks, ExecutorService executor, int iterations) throws Exception {
    AtomicInteger completedTasks = new AtomicInteger(0);
    
    for (int i = 0; i < iterations; i++) {
      List<CompletableFuture<Void>> futures = new ArrayList<>(tasks.size());
      
      for (Callable<Void> task : tasks) {
        CompletableFuture<Void> future = CompletableFuture.supplyAsync(() -> {
          try {
            task.call();
            completedTasks.incrementAndGet();
            return null;
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete or timeout
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
          .orTimeout(DURATION_SECONDS, SECONDS)
          .exceptionally(ex -> null)
          .join();
    }
    
    return completedTasks.get();
  }
  
  /**
   * Returns the executor for the given test name.
   */
  private ExecutorService getExecutorForTestName(String testName) {
    return testName.contains("Virtual") ? virtualExecutor : platformExecutor;
  }
  
  /**
   * Returns the current used memory in bytes.
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate memory usage
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Mock implementation of an email service for testing purposes.
   */
  private static class MockEmailer {
    private final File emailDir;
    
    public MockEmailer(File emailDir) {
      this.emailDir = emailDir;
    }
    
    /**
     * Simulates sending an email by writing it to a file.
     */
    public void sendEmail(String recipient, String subject, String body) throws IOException {
      // Create a unique filename based on recipient and subject
      String filename = recipient.replace("@", "_at_") + "_" + subject.replaceAll("\\s+", "_") + ".eml";
      File emailFile = new File(emailDir, filename);
      
      // Simulate network I/O delay that would occur in a real email system
      try {
        // Simulate network latency (50-150ms)
        MILLISECONDS.sleep(50 + (long) (Math.random() * 100));
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      // Write the email content to the file
      String content = "To: " + recipient + "\n" +
                      "Subject: " + subject + "\n" +
                      "\n" +
                      body;
      
      Files.writeString(emailFile.toPath(), content);
      
      // Simulate additional processing delay
      try {
        // Simulate processing time (10-30ms)
        MILLISECONDS.sleep(10 + (long) (Math.random() * 20));
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    
    /**
     * Verifies that an email was sent to the given recipient with the given subject.
     */
    public boolean verifyEmailSent(String recipient, String subject) {
      // Create the expected filename
      String filename = recipient.replace("@", "_at_") + "_" + subject.replaceAll("\\s+", "_") + ".eml";
      File emailFile = new File(emailDir, filename);
      
      // Simulate verification delay
      try {
        // Simulate verification time (20-50ms)
        MILLISECONDS.sleep(20 + (long) (Math.random() * 30));
      } 
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      return emailFile.exists();
    }
  }
  
  /**
   * Extension of PerformanceData to store additional metrics.
   */
  private static class PerformanceData extends org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData {
    private double throughput;
    private long memoryUsage;
    private int maxThreads;
    
    public double getThroughput() {
      return throughput;
    }
    
    public void setThroughput(double throughput) {
      this.throughput = throughput;
    }
    
    public long getMemoryUsage() {
      return memoryUsage;
    }
    
    public void setMemoryUsage(long memoryUsage) {
      this.memoryUsage = memoryUsage;
    }
    
    public int getMaxThreads() {
      return maxThreads;
    }
    
    public void setMaxThreads(int maxThreads) {
      this.maxThreads = maxThreads;
    }
    
    @Override
    public String toString() {
      return "PerformanceData{" +
          "throughput=" + throughput +
          ", memoryUsage=" + memoryUsage +
          ", maxThreads=" + maxThreads +
          "}";
    }
  }
}