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
package org.sonatype.nexus.email.virtualthread;

import java.util.ArrayList;
import java.util.HashMap;
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

import org.apache.commons.mail.Email;
import org.apache.commons.mail.SimpleEmail;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.email.EmailConfiguration;
import org.sonatype.nexus.email.EmailManager;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Tests high-concurrency email operations using Java 21 Virtual Threads versus traditional platform threads.
 * Measures and compares performance metrics, validates correctness under load, and verifies that
 * Virtual Threads significantly improve throughput and resource utilization in email sending operations.
 *
 * @since 3.60
 */
@Category(VirtualThreadTestGroup.class)
public class EmailConcurrencyTest
    extends TestSupport
{
  private static final int[] CONCURRENT_CLIENT_COUNTS = {100, 500, 1000, 5000, 10000};
  private static final int WARMUP_ITERATIONS = 5;
  private static final int TEST_ITERATIONS = 10;
  private static final long OPERATION_TIMEOUT_MS = 30000; // 30 seconds

  @Mock
  private EmailManager emailManager;

  @Mock
  private EmailConfiguration emailConfiguration;

  private AutoCloseable mocks;

  @Before
  public void setup() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);
    
    // Configure email manager mock
    when(emailManager.getConfiguration()).thenReturn(emailConfiguration);
    when(emailConfiguration.isEnabled()).thenReturn(true);
    when(emailConfiguration.getHost()).thenReturn("localhost");
    when(emailConfiguration.getPort()).thenReturn(25);
    when(emailConfiguration.getFromAddress()).thenReturn("test@example.com");
    
    // Simulate email sending with a small delay to mimic network I/O
    doAnswer(invocation -> {
      // Simulate network I/O with a small delay
      Thread.sleep(5);
      return null;
    }).when(emailManager).send(any(Email.class));
  }

  @After
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  public void testEmailConcurrencyWithVirtualThreads() throws Exception {
    log.info("Starting email concurrency test with Virtual Threads vs Platform Threads");
    
    // Create thread factories for both types
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Store performance results for comparison
    Map<String, Map<Integer, PerformanceMetrics>> allResults = new HashMap<>();
    allResults.put("virtual", new HashMap<>());
    allResults.put("platform", new HashMap<>());
    
    // Run tests with increasing concurrency
    for (int clientCount : CONCURRENT_CLIENT_COUNTS) {
      log.info("Testing with {} concurrent clients", clientCount);
      
      // Test with virtual threads
      PerformanceMetrics virtualMetrics = runConcurrencyTest("Virtual", virtualThreadFactory, clientCount);
      allResults.get("virtual").put(clientCount, virtualMetrics);
      
      // Test with platform threads (skip higher concurrency levels that would cause resource issues)
      if (clientCount <= 1000) {
        PerformanceMetrics platformMetrics = runConcurrencyTest("Platform", platformThreadFactory, clientCount);
        allResults.get("platform").put(clientCount, platformMetrics);
      }
    }
    
    // Log and validate results
    logResults(allResults);
    validateResults(allResults);
  }

  private PerformanceMetrics runConcurrencyTest(String threadType, ThreadFactory threadFactory, int clientCount) 
      throws Exception {
    log.info("Running {} thread test with {} concurrent clients", threadType, clientCount);
    
    // Create executor with the specified thread factory
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    
    try {
      // Warmup phase
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runIteration(executor, clientCount, false);
      }
      
      // Measurement phase
      PerformanceMetrics metrics = new PerformanceMetrics();
      
      for (int i = 0; i < TEST_ITERATIONS; i++) {
        IterationResult result = runIteration(executor, clientCount, true);
        metrics.addResult(result);
      }
      
      log.info("{} thread test with {} clients completed. Avg time: {}ms, Throughput: {} emails/sec, Errors: {}", 
          threadType, clientCount, metrics.getAverageResponseTime(), metrics.getThroughput(), metrics.getErrorCount());
      
      return metrics;
    } finally {
      executor.shutdown();
      executor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }

  private IterationResult runIteration(ExecutorService executor, int clientCount, boolean measure) 
      throws Exception {
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(clientCount);
    AtomicInteger errorCount = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Long> responseTimes = new ConcurrentHashMap<>();
    
    // Track memory before test
    long memoryBefore = 0;
    if (measure) {
      System.gc(); // Request garbage collection to get more accurate measurements
      memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks
    List<CompletableFuture<Void>> futures = new ArrayList<>(clientCount);
    for (int i = 0; i < clientCount; i++) {
      final int clientId = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          long clientStartTime = System.nanoTime();
          
          // Perform email operation
          SimpleEmail email = new SimpleEmail();
          email.setSubject("Test email " + clientId);
          email.addTo("recipient" + clientId + "@example.com");
          email.setMsg("This is a test email from client " + clientId);
          
          emailManager.send(email);
          
          if (measure) {
            long clientEndTime = System.nanoTime();
            responseTimes.put(clientId, (clientEndTime - clientStartTime) / 1_000_000); // Convert to ms
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
          log.error("Error in client {}: {}", clientId, e.getMessage(), e);
        } finally {
          completionLatch.countDown();
        }
      }, executor);
      
      futures.add(future);
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for completion with timeout
    boolean completed = completionLatch.await(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long endTime = System.currentTimeMillis();
    long totalTime = endTime - startTime;
    
    // Track memory after test
    long memoryAfter = 0;
    long memoryUsed = 0;
    if (measure) {
      System.gc(); // Request garbage collection to get more accurate measurements
      memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      memoryUsed = memoryAfter - memoryBefore;
    }
    
    // Cancel any remaining futures if we timed out
    if (!completed) {
      log.warn("Test timed out after {} ms", OPERATION_TIMEOUT_MS);
      futures.forEach(f -> f.cancel(true));
    }
    
    return new IterationResult(totalTime, clientCount, errorCount.get(), responseTimes, memoryUsed);
  }

  private void logResults(Map<String, Map<Integer, PerformanceMetrics>> allResults) {
    log.info("\nPerformance Comparison Results:");
    log.info("---------------------------------");
    log.info("Concurrent Clients | Thread Type | Avg Response Time (ms) | Throughput (emails/sec) | Memory Usage (MB) | Errors");
    log.info("---------------------------------");
    
    for (int clientCount : CONCURRENT_CLIENT_COUNTS) {
      PerformanceMetrics virtualMetrics = allResults.get("virtual").get(clientCount);
      if (virtualMetrics != null) {
        log.info("{} | Virtual | {} | {} | {} | {}", 
            clientCount, 
            String.format("%.2f", virtualMetrics.getAverageResponseTime()),
            String.format("%.2f", virtualMetrics.getThroughput()),
            String.format("%.2f", virtualMetrics.getAverageMemoryUsage() / (1024.0 * 1024.0)),
            virtualMetrics.getErrorCount());
      }
      
      PerformanceMetrics platformMetrics = allResults.get("platform").get(clientCount);
      if (platformMetrics != null) {
        log.info("{} | Platform | {} | {} | {} | {}", 
            clientCount, 
            String.format("%.2f", platformMetrics.getAverageResponseTime()),
            String.format("%.2f", platformMetrics.getThroughput()),
            String.format("%.2f", platformMetrics.getAverageMemoryUsage() / (1024.0 * 1024.0)),
            platformMetrics.getErrorCount());
      }
    }
  }

  private void validateResults(Map<String, Map<Integer, PerformanceMetrics>> allResults) {
    // Validate that all tests completed successfully
    for (Map<Integer, PerformanceMetrics> results : allResults.values()) {
      for (PerformanceMetrics metrics : results.values()) {
        assertThat("No errors should occur during test", metrics.getErrorCount(), is(0));
      }
    }
    
    // For comparable concurrency levels, virtual threads should outperform platform threads
    for (int clientCount : CONCURRENT_CLIENT_COUNTS) {
      if (clientCount <= 1000) { // We only have platform thread data up to 1000 clients
        PerformanceMetrics virtualMetrics = allResults.get("virtual").get(clientCount);
        PerformanceMetrics platformMetrics = allResults.get("platform").get(clientCount);
        
        if (virtualMetrics != null && platformMetrics != null) {
          // At higher concurrency, virtual threads should have better throughput
          if (clientCount >= 500) {
            assertThat("Virtual threads should have higher throughput at high concurrency",
                virtualMetrics.getThroughput(), greaterThan(platformMetrics.getThroughput()));
          }
          
          // Virtual threads should use less memory per thread
          assertThat("Virtual threads should use less memory",
              virtualMetrics.getAverageMemoryUsage(), lessThan(platformMetrics.getAverageMemoryUsage()));
        }
      }
    }
    
    // Verify that virtual threads can handle high concurrency
    PerformanceMetrics highConcurrencyMetrics = allResults.get("virtual").get(CONCURRENT_CLIENT_COUNTS[CONCURRENT_CLIENT_COUNTS.length - 1]);
    assertThat("Virtual threads should handle high concurrency", highConcurrencyMetrics.getErrorCount(), is(0));
  }

  /**
   * Represents the result of a single test iteration
   */
  private static class IterationResult
  {
    private final long totalTime;
    private final int clientCount;
    private final int errorCount;
    private final Map<Integer, Long> responseTimes;
    private final long memoryUsed;

    public IterationResult(long totalTime, int clientCount, int errorCount, Map<Integer, Long> responseTimes, long memoryUsed) {
      this.totalTime = totalTime;
      this.clientCount = clientCount;
      this.errorCount = errorCount;
      this.responseTimes = responseTimes;
      this.memoryUsed = memoryUsed;
    }

    public long getTotalTime() {
      return totalTime;
    }

    public int getClientCount() {
      return clientCount;
    }

    public int getErrorCount() {
      return errorCount;
    }

    public Map<Integer, Long> getResponseTimes() {
      return responseTimes;
    }

    public long getMemoryUsed() {
      return memoryUsed;
    }

    public double getThroughput() {
      return (clientCount - errorCount) * 1000.0 / totalTime;
    }
  }

  /**
   * Aggregates performance metrics across multiple test iterations
   */
  private static class PerformanceMetrics
  {
    private final List<IterationResult> results = new ArrayList<>();
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong responseCount = new AtomicLong(0);
    private final AtomicInteger totalErrors = new AtomicInteger(0);
    private final AtomicLong totalMemoryUsed = new AtomicLong(0);

    public void addResult(IterationResult result) {
      results.add(result);
      totalErrors.addAndGet(result.getErrorCount());
      totalMemoryUsed.addAndGet(result.getMemoryUsed());
      
      // Add individual response times
      for (Long responseTime : result.getResponseTimes().values()) {
        totalResponseTime.addAndGet(responseTime);
        responseCount.incrementAndGet();
      }
    }

    public double getAverageResponseTime() {
      return responseCount.get() > 0 ? totalResponseTime.get() / (double) responseCount.get() : 0;
    }

    public double getThroughput() {
      double totalOperations = 0;
      double totalTimeMs = 0;
      
      for (IterationResult result : results) {
        totalOperations += result.getClientCount() - result.getErrorCount();
        totalTimeMs += result.getTotalTime();
      }
      
      return totalTimeMs > 0 ? (totalOperations * 1000.0) / totalTimeMs : 0;
    }

    public int getErrorCount() {
      return totalErrors.get();
    }

    public double getAverageMemoryUsage() {
      return results.isEmpty() ? 0 : totalMemoryUsed.get() / (double) results.size();
    }
  }
}