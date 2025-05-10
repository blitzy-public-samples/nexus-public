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
package org.sonatype.nexus.transaction;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

import org.sonatype.goodies.testsupport.TestSupport;

import com.google.common.base.Stopwatch;
import com.google.common.base.Suppliers;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Performance comparison test between platform threads and virtual threads for transaction operations.
 * 
 * This test benchmarks the performance characteristics of Java 21 Virtual Threads vs traditional
 * platform threads when executing transactional operations under various loads and scenarios.
 * 
 * @since 3.60
 */
@Tag("Java21TestGroup")
public class TransactionPerformanceComparisonTest
    extends TestSupport
{
  private static final Logger log = LoggerFactory.getLogger(TransactionPerformanceComparisonTest.class);
  
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 3;
  private static final int OPERATIONS_PER_ITERATION = 1000;
  
  private static final int[] CONCURRENCY_LEVELS = {10, 50, 100, 500, 1000};
  
  private Injector injector;
  private ExampleMethods exampleMethods;
  private MockTransactionalStore store;
  private MemoryMXBean memoryMXBean;
  
  /**
   * Mock implementation of a transactional store for testing.
   */
  static class MockTransactionalStore implements TransactionalStore<TransactionalSession<?>>
  {
    private final AtomicInteger sessionCount = new AtomicInteger();
    private final AtomicInteger transactionCount = new AtomicInteger();
    private final AtomicInteger commitCount = new AtomicInteger();
    private final AtomicInteger rollbackCount = new AtomicInteger();
    private final ConcurrentHashMap<Thread, MockSession> sessions = new ConcurrentHashMap<>();
    
    @Override
    public TransactionalSession<?> openSession() {
      return openSession(TransactionIsolation.DEFAULT);
    }
    
    @Override
    public TransactionalSession<?> openSession(TransactionIsolation isolation) {
      sessionCount.incrementAndGet();
      MockSession session = new MockSession(this);
      sessions.put(Thread.currentThread(), session);
      return session;
    }
    
    public void reset() {
      sessionCount.set(0);
      transactionCount.set(0);
      commitCount.set(0);
      rollbackCount.set(0);
      sessions.clear();
    }
    
    public int getSessionCount() {
      return sessionCount.get();
    }
    
    public int getTransactionCount() {
      return transactionCount.get();
    }
    
    public int getCommitCount() {
      return commitCount.get();
    }
    
    public int getRollbackCount() {
      return rollbackCount.get();
    }
    
    public int getActiveSessionCount() {
      return sessions.size();
    }
  }
  
  /**
   * Mock implementation of a transactional session for testing.
   */
  static class MockSession implements TransactionalSession<MockTransaction>
  {
    private final MockTransactionalStore store;
    private MockTransaction transaction;
    private boolean closed;
    
    MockSession(MockTransactionalStore store) {
      this.store = store;
    }
    
    @Override
    public MockTransaction getTransaction() {
      if (transaction == null) {
        transaction = new MockTransaction(store);
      }
      return transaction;
    }
    
    @Override
    public void close() {
      if (!closed) {
        closed = true;
        store.sessions.remove(Thread.currentThread());
        if (transaction != null && !transaction.isFinished()) {
          transaction.rollback();
        }
      }
    }
  }
  
  /**
   * Mock implementation of a transaction for testing.
   */
  static class MockTransaction implements Transaction
  {
    private final MockTransactionalStore store;
    private boolean finished;
    
    MockTransaction(MockTransactionalStore store) {
      this.store = store;
      store.transactionCount.incrementAndGet();
    }
    
    @Override
    public void commit() {
      if (!finished) {
        finished = true;
        store.commitCount.incrementAndGet();
      }
    }
    
    @Override
    public void rollback() {
      if (!finished) {
        finished = true;
        store.rollbackCount.incrementAndGet();
      }
    }
    
    public boolean isFinished() {
      return finished;
    }
  }
  
  /**
   * Test module that provides the necessary components for transaction testing.
   */
  static class TestModule extends AbstractModule
  {
    private final MockTransactionalStore store;
    
    TestModule(MockTransactionalStore store) {
      this.store = store;
    }
    
    @Override
    protected void configure() {
      bind(MockTransactionalStore.class).toInstance(store);
      bind(ExampleMethods.ExampleNestedStore.class);
      bind(ExampleMethods.class);
    }
  }
  
  @BeforeEach
  public void setUp() {
    store = new MockTransactionalStore();
    injector = Guice.createInjector(new TransactionModule(), new TestModule(store));
    exampleMethods = injector.getInstance(ExampleMethods.class);
    memoryMXBean = ManagementFactory.getMemoryMXBean();
  }
  
  @AfterEach
  public void tearDown() {
    // Ensure we clean up any lingering UnitOfWork
    try {
      UnitOfWork.end();
    }
    catch (IllegalStateException e) {
      // Ignore - no active UnitOfWork
    }
  }
  
  /**
   * Benchmark results container class.
   */
  static class BenchmarkResult
  {
    final long operationsPerSecond;
    final double avgLatencyMs;
    final long maxLatencyMs;
    final long memoryUsedBytes;
    final int maxActiveSessions;
    
    BenchmarkResult(long operationsPerSecond, double avgLatencyMs, long maxLatencyMs, 
                    long memoryUsedBytes, int maxActiveSessions) {
      this.operationsPerSecond = operationsPerSecond;
      this.avgLatencyMs = avgLatencyMs;
      this.maxLatencyMs = maxLatencyMs;
      this.memoryUsedBytes = memoryUsedBytes;
      this.maxActiveSessions = maxActiveSessions;
    }
    
    @Override
    public String toString() {
      return String.format(
          "ops/sec: %d, avg latency: %.2f ms, max latency: %d ms, memory: %.2f MB, max sessions: %d",
          operationsPerSecond, avgLatencyMs, maxLatencyMs, memoryUsedBytes / (1024.0 * 1024.0), maxActiveSessions);
    }
  }
  
  /**
   * Runs a benchmark with the specified executor and concurrency level.
   */
  private BenchmarkResult runBenchmark(ExecutorService executor, int concurrency, boolean isVirtual) {
    // Reset metrics
    store.reset();
    System.gc(); // Request garbage collection to stabilize memory measurements
    
    long initialMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    AtomicInteger maxActiveSessions = new AtomicInteger(0);
    
    // Warmup
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runWorkload(executor, concurrency, OPERATIONS_PER_ITERATION / WARMUP_ITERATIONS);
    }
    
    // Reset after warmup
    store.reset();
    System.gc();
    
    // Actual benchmark
    List<Long> latencies = new ArrayList<>();
    Stopwatch stopwatch = Stopwatch.createStarted();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      List<Long> iterationLatencies = runWorkload(executor, concurrency, OPERATIONS_PER_ITERATION / BENCHMARK_ITERATIONS);
      latencies.addAll(iterationLatencies);
      
      // Track max active sessions
      int activeSessions = store.getActiveSessionCount();
      if (activeSessions > maxActiveSessions.get()) {
        maxActiveSessions.set(activeSessions);
      }
    }
    
    long totalOperations = OPERATIONS_PER_ITERATION;
    long elapsedNanos = stopwatch.elapsed(TimeUnit.NANOSECONDS);
    long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    long operationsPerSecond = elapsedMs > 0 ? (totalOperations * 1000) / elapsedMs : 0;
    
    // Calculate latency statistics
    double avgLatencyMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0) / 1_000_000.0;
    long maxLatencyMs = latencies.stream().mapToLong(Long::longValue).max().orElse(0) / 1_000_000;
    
    // Calculate memory usage
    long finalMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    long memoryUsed = Math.max(0, finalMemory - initialMemory); // Avoid negative values due to GC
    
    return new BenchmarkResult(operationsPerSecond, avgLatencyMs, maxLatencyMs, memoryUsed, maxActiveSessions.get());
  }
  
  /**
   * Runs a workload with the specified executor, concurrency level, and operation count.
   * 
   * @return List of operation latencies in nanoseconds
   */
  private List<Long> runWorkload(ExecutorService executor, int concurrency, int operations) {
    List<Long> latencies = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(operations);
    
    // Submit work
    for (int i = 0; i < operations; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          long start = System.nanoTime();
          
          // Perform transactional work
          UnitOfWork.begin(store::openSession);
          try {
            exampleMethods.transactional();
          }
          finally {
            UnitOfWork.end();
          }
          
          long end = System.nanoTime();
          latencies.add(end - start);
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }
    
    // Wait for completion
    try {
      latch.await(1, TimeUnit.MINUTES);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    return latencies;
  }
  
  /**
   * Tests transaction throughput comparison between platform threads and virtual threads
   * at different concurrency levels.
   */
  @Test
  public void testTransactionThroughputComparison() throws Exception {
    log.info("Starting transaction throughput comparison test");
    
    for (int concurrency : CONCURRENCY_LEVELS) {
      log.info("Testing with concurrency level: {}", concurrency);
      
      // Create executors
      ExecutorService platformExecutor = Executors.newFixedThreadPool(concurrency);
      ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
      
      try {
        // Run benchmarks
        log.info("Running platform thread benchmark...");
        BenchmarkResult platformResult = runBenchmark(platformExecutor, concurrency, false);
        log.info("Platform thread result: {}", platformResult);
        
        log.info("Running virtual thread benchmark...");
        BenchmarkResult virtualResult = runBenchmark(virtualExecutor, concurrency, true);
        log.info("Virtual thread result: {}", virtualResult);
        
        // Log comparison
        double throughputImprovement = ((double) virtualResult.operationsPerSecond / platformResult.operationsPerSecond) - 1.0;
        double latencyImprovement = (platformResult.avgLatencyMs / virtualResult.avgLatencyMs) - 1.0;
        double memoryEfficiency = (double) platformResult.memoryUsedBytes / virtualResult.memoryUsedBytes;
        
        log.info("Comparison at concurrency {}: Throughput: {}% improvement, Latency: {}% improvement, Memory efficiency: {}x",
            concurrency, String.format("%.2f", throughputImprovement * 100), 
            String.format("%.2f", latencyImprovement * 100), 
            String.format("%.2f", memoryEfficiency));
        
        // For higher concurrency levels, virtual threads should show significant benefits
        if (concurrency >= 100) {
          assertThat("Virtual threads should have higher throughput at high concurrency",
              virtualResult.operationsPerSecond, greaterThan(platformResult.operationsPerSecond));
          
          assertThat("Virtual threads should use less memory per thread at high concurrency",
              (double) virtualResult.memoryUsedBytes / virtualResult.maxActiveSessions,
              lessThan((double) platformResult.memoryUsedBytes / platformResult.maxActiveSessions));
        }
      }
      finally {
        platformExecutor.shutdown();
        virtualExecutor.shutdown();
      }
    }
  }
  
  /**
   * Tests transaction retry performance with platform threads vs virtual threads.
   */
  @Test
  public void testTransactionRetryPerformance() throws Exception {
    log.info("Starting transaction retry performance test");
    
    // Configure retry count
    final int retryCount = 3;
    final int concurrency = 100;
    final int operations = 100;
    
    // Create executors
    ExecutorService platformExecutor = Executors.newFixedThreadPool(concurrency);
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      // Test platform threads
      log.info("Testing retry performance with platform threads...");
      long platformTime = measureRetryPerformance(platformExecutor, operations, retryCount);
      log.info("Platform thread retry time: {} ms", platformTime);
      
      // Test virtual threads
      log.info("Testing retry performance with virtual threads...");
      long virtualTime = measureRetryPerformance(virtualExecutor, operations, retryCount);
      log.info("Virtual thread retry time: {} ms", virtualTime);
      
      // Log comparison
      double improvement = ((double) platformTime / virtualTime) - 1.0;
      log.info("Retry performance improvement with virtual threads: {}%", 
          String.format("%.2f", improvement * 100));
      
      // Virtual threads should handle retries more efficiently
      assertThat("Virtual threads should handle retries more efficiently",
          virtualTime, lessThan(platformTime));
    }
    finally {
      platformExecutor.shutdown();
      virtualExecutor.shutdown();
    }
  }
  
  /**
   * Measures the performance of transaction retries.
   * 
   * @param executor The executor to use
   * @param operations Number of operations to perform
   * @param retryCount Number of retries before success
   * @return Total time in milliseconds
   */
  private long measureRetryPerformance(ExecutorService executor, int operations, int retryCount) {
    CountDownLatch latch = new CountDownLatch(operations);
    Stopwatch stopwatch = Stopwatch.createStarted();
    
    for (int i = 0; i < operations; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          // Set up for retries
          exampleMethods.setCountdownToSuccess(retryCount);
          
          // Execute with retries
          UnitOfWork.begin(store::openSession);
          try {
            exampleMethods.retryOnUncheckedException();
          }
          catch (Exception e) {
            // Expected during retries
          }
          finally {
            UnitOfWork.end();
          }
        }
        finally {
          latch.countDown();
        }
      }, executor);
    }
    
    try {
      latch.await(1, TimeUnit.MINUTES);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    
    return stopwatch.elapsed(TimeUnit.MILLISECONDS);
  }
  
  /**
   * Tests high-concurrency transaction behavior with virtual threads.
   */
  @Test
  public void testHighConcurrencyTransactions() throws Exception {
    log.info("Starting high-concurrency transaction test");
    
    // Use a very high concurrency level that would be impractical with platform threads
    final int concurrency = 5000;
    final int operations = concurrency; // One operation per thread
    
    // Create virtual thread executor
    ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    try {
      log.info("Running {} concurrent transactions with virtual threads", concurrency);
      
      CountDownLatch latch = new CountDownLatch(operations);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger failureCount = new AtomicInteger(0);
      Stopwatch stopwatch = Stopwatch.createStarted();
      
      // Submit work
      for (int i = 0; i < operations; i++) {
        CompletableFuture.runAsync(() -> {
          try {
            UnitOfWork.begin(store::openSession);
            try {
              exampleMethods.transactional();
              successCount.incrementAndGet();
            }
            finally {
              UnitOfWork.end();
            }
          }
          catch (Exception e) {
            failureCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        }, virtualExecutor);
      }
      
      // Wait for completion
      boolean completed = latch.await(1, TimeUnit.MINUTES);
      long elapsedMs = stopwatch.elapsed(TimeUnit.MILLISECONDS);
      
      log.info("High-concurrency test completed in {} ms: {} successful, {} failed, completed: {}",
          elapsedMs, successCount.get(), failureCount.get(), completed);
      
      // Verify results
      assertThat("All operations should complete", completed, is(true));
      assertThat("Most operations should succeed", successCount.get(), greaterThanOrEqualTo((int)(operations * 0.95)));
      assertThat("Transaction count should match operations", store.getTransactionCount(), is(operations));
      assertThat("Commit count should match successful operations", store.getCommitCount(), is(successCount.get()));
    }
    finally {
      virtualExecutor.shutdown();
    }
  }
  
  /**
   * Tests memory utilization patterns for both thread types.
   */
  @Test
  public void testMemoryUtilizationPatterns() throws Exception {
    log.info("Starting memory utilization pattern test");
    
    final int threadCount = 1000;
    
    // Measure platform thread memory usage
    log.info("Measuring platform thread memory usage...");
    long platformMemory = measureThreadMemoryUsage(false, threadCount);
    log.info("Platform thread memory usage for {} threads: {} MB", 
        threadCount, String.format("%.2f", platformMemory / (1024.0 * 1024.0)));
    
    // Measure virtual thread memory usage
    log.info("Measuring virtual thread memory usage...");
    long virtualMemory = measureThreadMemoryUsage(true, threadCount);
    log.info("Virtual thread memory usage for {} threads: {} MB", 
        threadCount, String.format("%.2f", virtualMemory / (1024.0 * 1024.0)));
    
    // Calculate per-thread memory usage
    double platformMemoryPerThread = (double) platformMemory / threadCount;
    double virtualMemoryPerThread = (double) virtualMemory / threadCount;
    
    log.info("Memory per thread - Platform: {} KB, Virtual: {} KB", 
        String.format("%.2f", platformMemoryPerThread / 1024.0), 
        String.format("%.2f", virtualMemoryPerThread / 1024.0));
    
    // Virtual threads should use significantly less memory per thread
    assertThat("Virtual threads should use less memory per thread",
        virtualMemoryPerThread, lessThan(platformMemoryPerThread * 0.5)); // At least 50% less
  }
  
  /**
   * Measures memory usage for creating and using a specific number of threads.
   * 
   * @param useVirtualThreads Whether to use virtual threads
   * @param threadCount Number of threads to create
   * @return Memory usage in bytes
   */
  private long measureThreadMemoryUsage(boolean useVirtualThreads, int threadCount) throws Exception {
    // Force GC to stabilize memory measurements
    System.gc();
    Thread.sleep(1000);
    
    long initialMemory = memoryMXBean.getHeapMemoryUsage().getUsed();
    
    // Create threads
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(threadCount);
    
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(threadCount);
      
      // Create threads that will wait until signaled
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            // Wait for signal to start
            startLatch.await();
            
            // Do a simple transaction
            UnitOfWork.begin(store::openSession);
            try {
              exampleMethods.transactional();
            }
            finally {
              UnitOfWork.end();
            }
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          finally {
            completionLatch.countDown();
          }
        });
      }
      
      // Allow time for thread creation
      Thread.sleep(1000);
      
      // Measure memory with all threads created
      long memoryWithThreads = memoryMXBean.getHeapMemoryUsage().getUsed();
      
      // Signal threads to continue and finish
      startLatch.countDown();
      completionLatch.await(1, TimeUnit.MINUTES);
      
      return memoryWithThreads - initialMemory;
    }
    finally {
      executor.shutdown();
    }
  }
}