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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.apache.commons.mail.Email;
import org.apache.commons.mail.EmailException;
import org.apache.commons.mail.SimpleEmail;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests to evaluate the performance and scalability benefits of Java 21 virtual threads
 * for email operations compared to traditional platform threads.
 *
 * @since 3.60
 */
@DisplayName("Email Concurrency Performance Test")
public class EmailConcurrencyTest
{
  private static final Logger log = LoggerFactory.getLogger(EmailConcurrencyTest.class);

  private static final int[] THREAD_COUNTS = {100, 500, 1000, 5000};
  
  private static final int WARMUP_ITERATIONS = 3;
  
  private static final int TEST_ITERATIONS = 5;
  
  private static final int EMAIL_OPERATION_DELAY_MS = 50; // Simulate I/O delay

  private static final String TEST_EMAIL_SUBJECT = "Test Email";
  
  private static final String TEST_EMAIL_BODY = "This is a test email for concurrency testing.";

  private AutoCloseable mocks;

  @Mock
  private EmailManager emailManager;

  @Mock
  private EmailConfiguration emailConfiguration;

  @TempDir
  Path tempDir;

  private Path resultsFile;

  @BeforeEach
  void setup(TestInfo testInfo) throws IOException {
    mocks = MockitoAnnotations.openMocks(this);
    
    // Setup mock behavior for email operations
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);
    when(emailConfiguration.isEnabled()).thenReturn(true);
    when(emailConfiguration.getHost()).thenReturn("localhost");
    when(emailConfiguration.getPort()).thenReturn(25);
    when(emailConfiguration.getFromAddress()).thenReturn("test@example.com");
    
    // Simulate I/O-bound email sending with a delay
    doAnswer(invocation -> {
      Thread.sleep(EMAIL_OPERATION_DELAY_MS); // Simulate network I/O
      return null;
    }).when(emailManager).send(any(Email.class));
    
    // Create results file
    resultsFile = tempDir.resolve("email-concurrency-results.csv");
    Files.writeString(resultsFile, "ThreadType,ThreadCount,TotalEmails,SuccessfulEmails,FailedEmails," +
        "TotalDurationMs,AverageResponseTimeMs,MaxResponseTimeMs,MinResponseTimeMs," +
        "MemoryUsedMB\n");
  }

  @AfterEach
  void cleanup() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
    
    log.info("Test results written to: {}", resultsFile.toAbsolutePath());
  }

  @Test
  @DisplayName("Compare platform threads vs virtual threads with escalating client counts")
  void compareThreadModelsWithEscalatingLoad() throws Exception {
    // Skip test if not running on Java 21+
    String javaVersion = System.getProperty("java.version");
    Assumptions.assumeTrue(javaVersion != null && javaVersion.startsWith("21"),
        "Test requires Java 21 or higher, but found: " + javaVersion);

    // Run warmup iterations to stabilize JVM
    log.info("Running {} warmup iterations...", WARMUP_ITERATIONS);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runEmailBenchmark("Warmup", 100, Thread.ofPlatform().factory());
      runEmailBenchmark("Warmup", 100, Thread.ofVirtual().factory());
    }

    // Run actual benchmarks with different thread counts
    for (int threadCount : THREAD_COUNTS) {
      log.info("\nRunning benchmark with {} threads...", threadCount);
      
      // Test with platform threads
      ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
      BenchmarkResult platformResult = runEmailBenchmark("Platform", threadCount, platformThreadFactory);
      logAndSaveResults("Platform", threadCount, platformResult);
      
      // Test with virtual threads
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      BenchmarkResult virtualResult = runEmailBenchmark("Virtual", threadCount, virtualThreadFactory);
      logAndSaveResults("Virtual", threadCount, virtualResult);
      
      // Verify virtual threads perform better at higher concurrency
      if (threadCount >= 1000) {
        assertAll(
            () -> assertTrue(virtualResult.averageResponseTimeMs < platformResult.averageResponseTimeMs,
                "Virtual threads should have lower average response time than platform threads"),
            () -> assertTrue(virtualResult.memoryUsedMB < platformResult.memoryUsedMB,
                "Virtual threads should use less memory than platform threads")
        );
      }
      
      // Allow system to recover between tests
      Thread.sleep(2000);
    }
  }

  /**
   * Runs a benchmark with the specified thread count and thread factory.
   */
  private BenchmarkResult runEmailBenchmark(String threadType, int threadCount, ThreadFactory threadFactory) 
      throws Exception {
    log.info("Running {} thread benchmark with {} threads...", threadType, threadCount);
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      List<BenchmarkResult> iterationResults = new ArrayList<>();
      
      // Run multiple iterations to get stable results
      for (int i = 0; i < TEST_ITERATIONS; i++) {
        BenchmarkResult result = runSingleIteration(executor, threadCount);
        iterationResults.add(result);
        log.debug("Iteration {} completed: {} ms avg response time", i + 1, result.averageResponseTimeMs);
      }
      
      // Calculate average results across all iterations
      return calculateAverageResult(iterationResults);
    } 
    finally {
      executor.shutdown();
      boolean terminated = executor.awaitTermination(30, SECONDS);
      if (!terminated) {
        log.warn("Executor did not terminate within timeout");
        executor.shutdownNow();
      }
    }
  }

  /**
   * Runs a single benchmark iteration with the specified executor and thread count.
   */
  private BenchmarkResult runSingleIteration(ExecutorService executor, int threadCount) throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(threadCount);
    
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    // Track response times
    AtomicLong totalResponseTime = new AtomicLong(0);
    AtomicLong maxResponseTime = new AtomicLong(0);
    AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    
    // Measure memory usage before test
    System.gc(); // Request garbage collection to get more accurate measurements
    long memoryBefore = getUsedMemory();
    
    // Start timing
    long startTime = System.nanoTime();
    
    // Submit tasks to the executor
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      final int emailId = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          long taskStartTime = System.nanoTime();
          
          // Simulate email verification request
          assertDoesNotThrow(() -> {
            Email email = createTestEmail(emailId);
            emailManager.send(email);
          });
          
          // Record response time
          long responseTime = MILLISECONDS.convert(System.nanoTime() - taskStartTime, TimeUnit.NANOSECONDS);
          totalResponseTime.addAndGet(responseTime);
          updateMax(maxResponseTime, responseTime);
          updateMin(minResponseTime, responseTime);
          
          successCount.incrementAndGet();
        } 
        catch (Exception e) {
          failureCount.incrementAndGet();
          log.error("Error in email task {}: {}", emailId, e.getMessage(), e);
        } 
        finally {
          completionLatch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    boolean completed = completionLatch.await(2, TimeUnit.MINUTES);
    if (!completed) {
      log.warn("Not all tasks completed within timeout");
    }
    
    // Calculate total duration
    long totalDurationMs = MILLISECONDS.convert(System.nanoTime() - startTime, TimeUnit.NANOSECONDS);
    
    // Measure memory after test
    System.gc(); // Request garbage collection to get more accurate measurements
    long memoryAfter = getUsedMemory();
    long memoryUsed = memoryAfter - memoryBefore;
    
    // Calculate average response time
    long totalEmails = successCount.get() + failureCount.get();
    double avgResponseTime = totalEmails > 0 ? (double) totalResponseTime.get() / totalEmails : 0;
    
    return new BenchmarkResult(
        totalEmails,
        successCount.get(),
        failureCount.get(),
        totalDurationMs,
        avgResponseTime,
        maxResponseTime.get(),
        minResponseTime.get() == Long.MAX_VALUE ? 0 : minResponseTime.get(),
        memoryUsed / (1024 * 1024) // Convert to MB
    );
  }

  /**
   * Creates a test email with the specified ID.
   */
  private Email createTestEmail(int id) throws EmailException {
    SimpleEmail email = new SimpleEmail();
    email.setSubject(TEST_EMAIL_SUBJECT + " #" + id);
    email.setMsg(TEST_EMAIL_BODY);
    email.addTo("recipient" + id + "@example.com");
    return email;
  }

  /**
   * Logs and saves benchmark results to the results file.
   */
  private void logAndSaveResults(String threadType, int threadCount, BenchmarkResult result) throws IOException {
    log.info("{} Thread Results ({}): {} emails, {} successful, {} failed, {} ms total, {} ms avg response time, {} MB memory used",
        threadType, threadCount, result.totalEmails, result.successfulEmails, result.failedEmails,
        result.totalDurationMs, String.format("%.2f", result.averageResponseTimeMs), result.memoryUsedMB);
    
    // Append results to CSV file
    String resultLine = String.format("%s,%d,%d,%d,%d,%d,%.2f,%d,%d,%d\n",
        threadType, threadCount, result.totalEmails, result.successfulEmails, result.failedEmails,
        result.totalDurationMs, result.averageResponseTimeMs, result.maxResponseTimeMs, result.minResponseTimeMs,
        result.memoryUsedMB);
    
    Files.writeString(resultsFile, resultLine, java.nio.file.StandardOpenOption.APPEND);
  }

  /**
   * Calculates the average result across multiple benchmark iterations.
   */
  private BenchmarkResult calculateAverageResult(List<BenchmarkResult> results) {
    int size = results.size();
    if (size == 0) {
      return new BenchmarkResult(0, 0, 0, 0, 0, 0, 0, 0);
    }
    
    long totalEmails = 0;
    long successfulEmails = 0;
    long failedEmails = 0;
    long totalDuration = 0;
    double totalAvgResponseTime = 0;
    long totalMaxResponseTime = 0;
    long totalMinResponseTime = 0;
    long totalMemoryUsed = 0;
    
    for (BenchmarkResult result : results) {
      totalEmails += result.totalEmails;
      successfulEmails += result.successfulEmails;
      failedEmails += result.failedEmails;
      totalDuration += result.totalDurationMs;
      totalAvgResponseTime += result.averageResponseTimeMs;
      totalMaxResponseTime += result.maxResponseTimeMs;
      totalMinResponseTime += result.minResponseTimeMs;
      totalMemoryUsed += result.memoryUsedMB;
    }
    
    return new BenchmarkResult(
        totalEmails / size,
        successfulEmails / size,
        failedEmails / size,
        totalDuration / size,
        totalAvgResponseTime / size,
        totalMaxResponseTime / size,
        totalMinResponseTime / size,
        totalMemoryUsed / size
    );
  }

  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  /**
   * Atomically updates the maximum value.
   */
  private void updateMax(AtomicLong maxValue, long newValue) {
    long currentMax = maxValue.get();
    while (newValue > currentMax) {
      if (maxValue.compareAndSet(currentMax, newValue)) {
        break;
      }
      currentMax = maxValue.get();
    }
  }

  /**
   * Atomically updates the minimum value.
   */
  private void updateMin(AtomicLong minValue, long newValue) {
    long currentMin = minValue.get();
    while (newValue < currentMin) {
      if (minValue.compareAndSet(currentMin, newValue)) {
        break;
      }
      currentMin = minValue.get();
    }
  }

  /**
   * Class to hold benchmark results.
   */
  private static class BenchmarkResult {
    final long totalEmails;
    final long successfulEmails;
    final long failedEmails;
    final long totalDurationMs;
    final double averageResponseTimeMs;
    final long maxResponseTimeMs;
    final long minResponseTimeMs;
    final long memoryUsedMB;

    BenchmarkResult(long totalEmails, long successfulEmails, long failedEmails, long totalDurationMs,
                    double averageResponseTimeMs, long maxResponseTimeMs, long minResponseTimeMs, long memoryUsedMB) {
      this.totalEmails = totalEmails;
      this.successfulEmails = successfulEmails;
      this.failedEmails = failedEmails;
      this.totalDurationMs = totalDurationMs;
      this.averageResponseTimeMs = averageResponseTimeMs;
      this.maxResponseTimeMs = maxResponseTimeMs;
      this.minResponseTimeMs = minResponseTimeMs;
      this.memoryUsedMB = memoryUsedMB;
    }
  }
}