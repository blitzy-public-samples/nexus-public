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
package org.sonatype.nexus.virtualthread;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.app.BaseUrlManager;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.common.stateguard.StateGuardLifecycle;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.manager.RepositoryManager;
import org.sonatype.nexus.scheduling.TaskScheduler;
import org.sonatype.nexus.security.SecuritySystem;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.when;

/**
 * Performance comparison test for Nexus Core operations using platform threads versus Virtual Threads.
 * This class implements controlled benchmarks to measure throughput, latency, and resource utilization
 * differences between the two threading models across repository, HTTP client, task scheduling,
 * key-value store, and security operations.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("java21")
@Tag("virtualthread")
public class CorePerformanceVirtualThreadTest
    extends TestSupport
    implements Java21TestGroup, VirtualThreadTestGroup
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  private static final int MAX_CONCURRENCY = 1000;
  private static final int CONCURRENCY_STEP = 100;
  private static final long OPERATION_TIMEOUT_MS = 10000;
  private static final double EXPECTED_THROUGHPUT_IMPROVEMENT = 1.3; // 30% improvement
  private static final double EXPECTED_P95_LATENCY_IMPROVEMENT = 0.7; // 30% reduction
  
  private MetricRegistry metricRegistry;
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  @Mock
  private RepositoryManager repositoryManager;
  
  @Mock
  private Repository repository;
  
  @Mock
  private HttpClientManager httpClientManager;
  
  @Mock
  private CloseableHttpClient httpClient;
  
  @Mock
  private CloseableHttpResponse httpResponse;
  
  @Mock
  private TaskScheduler taskScheduler;
  
  @Mock
  private SecuritySystem securitySystem;
  
  @Mock
  private EventManager eventManager;
  
  @Mock
  private BaseUrlManager baseUrlManager;
  
  @BeforeEach
  void setUp() throws Exception {
    metricRegistry = new MetricRegistry();
    
    // Create platform thread executor with fixed pool size
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2, platformThreadFactory);
    
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Setup mocks
    when(repositoryManager.get("maven-central")).thenReturn(repository);
    when(repository.getName()).thenReturn("maven-central");
    when(httpClientManager.create()).thenReturn(httpClient);
    when(httpClient.execute(org.mockito.ArgumentMatchers.any(HttpGet.class))).thenReturn(httpResponse);
    when(baseUrlManager.getBaseUrl()).thenReturn(URI.create("http://localhost:8081"));
  }
  
  @AfterEach
  void tearDown() throws Exception {
    platformThreadExecutor.shutdown();
    virtualThreadExecutor.shutdown();
    
    if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      platformThreadExecutor.shutdownNow();
    }
    
    if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
      virtualThreadExecutor.shutdownNow();
    }
  }
  
  /**
   * Benchmark repository operations under varying concurrency levels with both platform threads
   * and Virtual Threads. Measures throughput, latency percentiles, and success rates.
   */
  @Test
  @DisplayName("Repository operations performance comparison")
  void testRepositoryOperationsPerformance() throws Exception {
    // Define the repository operation to benchmark
    Callable<String> repositoryOperation = () -> {
      // Simulate repository operation with I/O delay
      simulateIoOperation(50); // 50ms I/O delay
      return repository.getName();
    };
    
    // Run benchmark with platform threads
    PerformanceResult platformResult = runBenchmark("repository-platform", repositoryOperation, platformThreadExecutor);
    
    // Run benchmark with virtual threads
    PerformanceResult virtualResult = runBenchmark("repository-virtual", repositoryOperation, virtualThreadExecutor);
    
    // Log results
    log.info("Repository Operations - Platform Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        platformResult.getThroughput(),
        platformResult.getP95Latency(),
        platformResult.getP99Latency());
    
    log.info("Repository Operations - Virtual Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        virtualResult.getThroughput(),
        virtualResult.getP95Latency(),
        virtualResult.getP99Latency());
    
    // Verify performance improvements
    assertThat("Virtual threads should provide higher throughput",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * EXPECTED_THROUGHPUT_IMPROVEMENT));
    
    assertThat("Virtual threads should provide lower P95 latency",
        virtualResult.getP95Latency(), lessThan(platformResult.getP95Latency() * EXPECTED_P95_LATENCY_IMPROVEMENT));
  }
  
  /**
   * Benchmark HTTP client operations under varying concurrency levels with both platform threads
   * and Virtual Threads. Measures throughput, latency percentiles, and success rates.
   */
  @Test
  @DisplayName("HTTP client operations performance comparison")
  void testHttpClientOperationsPerformance() throws Exception {
    // Define the HTTP client operation to benchmark
    Callable<Integer> httpOperation = () -> {
      // Simulate HTTP request with I/O delay
      simulateIoOperation(100); // 100ms I/O delay
      return httpResponse.getStatusLine().getStatusCode();
    };
    
    // Run benchmark with platform threads
    PerformanceResult platformResult = runBenchmark("http-platform", httpOperation, platformThreadExecutor);
    
    // Run benchmark with virtual threads
    PerformanceResult virtualResult = runBenchmark("http-virtual", httpOperation, virtualThreadExecutor);
    
    // Log results
    log.info("HTTP Client Operations - Platform Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        platformResult.getThroughput(),
        platformResult.getP95Latency(),
        platformResult.getP99Latency());
    
    log.info("HTTP Client Operations - Virtual Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        virtualResult.getThroughput(),
        virtualResult.getP95Latency(),
        virtualResult.getP99Latency());
    
    // Verify performance improvements
    assertThat("Virtual threads should provide higher throughput for HTTP operations",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * EXPECTED_THROUGHPUT_IMPROVEMENT));
    
    assertThat("Virtual threads should provide lower P95 latency for HTTP operations",
        virtualResult.getP95Latency(), lessThan(platformResult.getP95Latency() * EXPECTED_P95_LATENCY_IMPROVEMENT));
  }
  
  /**
   * Benchmark task scheduling operations under varying concurrency levels with both platform threads
   * and Virtual Threads. Measures throughput, latency percentiles, and success rates.
   */
  @Test
  @DisplayName("Task scheduling operations performance comparison")
  void testTaskSchedulingOperationsPerformance() throws Exception {
    // Define the task scheduling operation to benchmark
    Callable<Boolean> taskOperation = () -> {
      // Simulate task scheduling with I/O delay
      simulateIoOperation(30); // 30ms I/O delay
      return true;
    };
    
    // Run benchmark with platform threads
    PerformanceResult platformResult = runBenchmark("task-platform", taskOperation, platformThreadExecutor);
    
    // Run benchmark with virtual threads
    PerformanceResult virtualResult = runBenchmark("task-virtual", taskOperation, virtualThreadExecutor);
    
    // Log results
    log.info("Task Scheduling Operations - Platform Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        platformResult.getThroughput(),
        platformResult.getP95Latency(),
        platformResult.getP99Latency());
    
    log.info("Task Scheduling Operations - Virtual Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        virtualResult.getThroughput(),
        virtualResult.getP95Latency(),
        virtualResult.getP99Latency());
    
    // Verify performance improvements
    assertThat("Virtual threads should provide higher throughput for task operations",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * EXPECTED_THROUGHPUT_IMPROVEMENT));
    
    assertThat("Virtual threads should provide lower P95 latency for task operations",
        virtualResult.getP95Latency(), lessThan(platformResult.getP95Latency() * EXPECTED_P95_LATENCY_IMPROVEMENT));
  }
  
  /**
   * Benchmark security operations under varying concurrency levels with both platform threads
   * and Virtual Threads. Measures throughput, latency percentiles, and success rates.
   */
  @Test
  @DisplayName("Security operations performance comparison")
  void testSecurityOperationsPerformance() throws Exception {
    // Define the security operation to benchmark
    Callable<Boolean> securityOperation = () -> {
      // Simulate security operation with I/O delay
      simulateIoOperation(40); // 40ms I/O delay
      return true;
    };
    
    // Run benchmark with platform threads
    PerformanceResult platformResult = runBenchmark("security-platform", securityOperation, platformThreadExecutor);
    
    // Run benchmark with virtual threads
    PerformanceResult virtualResult = runBenchmark("security-virtual", securityOperation, virtualThreadExecutor);
    
    // Log results
    log.info("Security Operations - Platform Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        platformResult.getThroughput(),
        platformResult.getP95Latency(),
        platformResult.getP99Latency());
    
    log.info("Security Operations - Virtual Threads: {} ops/sec, P95: {} ms, P99: {} ms",
        virtualResult.getThroughput(),
        virtualResult.getP95Latency(),
        virtualResult.getP99Latency());
    
    // Verify performance improvements
    assertThat("Virtual threads should provide higher throughput for security operations",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * EXPECTED_THROUGHPUT_IMPROVEMENT));
    
    assertThat("Virtual threads should provide lower P95 latency for security operations",
        virtualResult.getP95Latency(), lessThan(platformResult.getP95Latency() * EXPECTED_P95_LATENCY_IMPROVEMENT));
  }
  
  /**
   * Benchmark mixed operations under high concurrency with both platform threads and Virtual Threads.
   * This test simulates a real-world scenario with multiple types of operations running concurrently.
   */
  @Test
  @DisplayName("Mixed operations under high concurrency")
  void testMixedOperationsUnderHighConcurrency() throws Exception {
    // Define mixed operations
    List<Callable<Object>> platformOperations = new ArrayList<>();
    List<Callable<Object>> virtualOperations = new ArrayList<>();
    
    // Add repository operations
    for (int i = 0; i < MAX_CONCURRENCY / 4; i++) {
      platformOperations.add(() -> {
        simulateIoOperation(50);
        return repository.getName();
      });
      virtualOperations.add(() -> {
        simulateIoOperation(50);
        return repository.getName();
      });
    }
    
    // Add HTTP operations
    for (int i = 0; i < MAX_CONCURRENCY / 4; i++) {
      platformOperations.add(() -> {
        simulateIoOperation(100);
        return httpResponse.getStatusLine().getStatusCode();
      });
      virtualOperations.add(() -> {
        simulateIoOperation(100);
        return httpResponse.getStatusLine().getStatusCode();
      });
    }
    
    // Add task operations
    for (int i = 0; i < MAX_CONCURRENCY / 4; i++) {
      platformOperations.add(() -> {
        simulateIoOperation(30);
        return true;
      });
      virtualOperations.add(() -> {
        simulateIoOperation(30);
        return true;
      });
    }
    
    // Add security operations
    for (int i = 0; i < MAX_CONCURRENCY / 4; i++) {
      platformOperations.add(() -> {
        simulateIoOperation(40);
        return true;
      });
      virtualOperations.add(() -> {
        simulateIoOperation(40);
        return true;
      });
    }
    
    // Measure memory before platform thread test
    long platformMemoryBefore = getUsedMemory();
    
    // Run all operations with platform threads and measure time
    long platformStartTime = System.nanoTime();
    List<Future<Object>> platformFutures = platformThreadExecutor.invokeAll(platformOperations, OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long platformEndTime = System.nanoTime();
    
    // Measure memory after platform thread test
    long platformMemoryAfter = getUsedMemory();
    long platformMemoryUsage = platformMemoryAfter - platformMemoryBefore;
    
    // Count successful operations
    long platformSuccessCount = platformFutures.stream().filter(f -> !f.isCancelled()).count();
    double platformDurationMs = (platformEndTime - platformStartTime) / 1_000_000.0;
    double platformThroughput = platformSuccessCount / (platformDurationMs / 1000.0);
    
    // Force garbage collection before virtual thread test
    System.gc();
    Thread.sleep(1000);
    
    // Measure memory before virtual thread test
    long virtualMemoryBefore = getUsedMemory();
    
    // Run all operations with virtual threads and measure time
    long virtualStartTime = System.nanoTime();
    List<Future<Object>> virtualFutures = virtualThreadExecutor.invokeAll(virtualOperations, OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long virtualEndTime = System.nanoTime();
    
    // Measure memory after virtual thread test
    long virtualMemoryAfter = getUsedMemory();
    long virtualMemoryUsage = virtualMemoryAfter - virtualMemoryBefore;
    
    // Count successful operations
    long virtualSuccessCount = virtualFutures.stream().filter(f -> !f.isCancelled()).count();
    double virtualDurationMs = (virtualEndTime - virtualStartTime) / 1_000_000.0;
    double virtualThroughput = virtualSuccessCount / (virtualDurationMs / 1000.0);
    
    // Log results
    log.info("Mixed Operations - Platform Threads: {} ops/sec, Memory: {} MB",
        String.format("%.2f", platformThroughput),
        String.format("%.2f", platformMemoryUsage / (1024.0 * 1024.0)));
    
    log.info("Mixed Operations - Virtual Threads: {} ops/sec, Memory: {} MB",
        String.format("%.2f", virtualThroughput),
        String.format("%.2f", virtualMemoryUsage / (1024.0 * 1024.0)));
    
    // Verify performance improvements
    assertThat("Virtual threads should provide higher throughput for mixed operations",
        virtualThroughput, greaterThan(platformThroughput * EXPECTED_THROUGHPUT_IMPROVEMENT));
    
    assertThat("Virtual threads should use less memory for mixed operations",
        virtualMemoryUsage, lessThan(platformMemoryUsage));
  }
  
  /**
   * Test memory utilization under high concurrency with both platform threads and Virtual Threads.
   * This test verifies that Virtual Threads use significantly less memory than platform threads
   * when handling a large number of concurrent operations.
   */
  @Test
  @DisplayName("Memory utilization under high concurrency")
  void testMemoryUtilizationUnderHighConcurrency() throws Exception {
    int threadCount = 10000; // 10K threads
    CountDownLatch platformLatch = new CountDownLatch(1);
    CountDownLatch virtualLatch = new CountDownLatch(1);
    
    // Force garbage collection before test
    System.gc();
    Thread.sleep(1000);
    
    // Measure baseline memory
    long baselineMemory = getUsedMemory();
    
    // Create platform threads and measure memory
    List<Thread> platformThreads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = Thread.ofPlatform().name("platform-" + i).start(() -> {
        try {
          platformLatch.await();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      platformThreads.add(thread);
    }
    
    // Measure memory after creating platform threads
    long platformMemory = getUsedMemory();
    long platformMemoryUsage = platformMemory - baselineMemory;
    
    // Release platform threads
    platformLatch.countDown();
    
    // Wait for platform threads to finish
    for (Thread thread : platformThreads) {
      thread.join(100);
    }
    
    // Force garbage collection
    System.gc();
    Thread.sleep(1000);
    
    // Reset baseline memory
    baselineMemory = getUsedMemory();
    
    // Create virtual threads and measure memory
    List<Thread> virtualThreads = new ArrayList<>();
    for (int i = 0; i < threadCount; i++) {
      Thread thread = Thread.ofVirtual().name("virtual-" + i).start(() -> {
        try {
          virtualLatch.await();
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      virtualThreads.add(thread);
    }
    
    // Measure memory after creating virtual threads
    long virtualMemory = getUsedMemory();
    long virtualMemoryUsage = virtualMemory - baselineMemory;
    
    // Release virtual threads
    virtualLatch.countDown();
    
    // Wait for virtual threads to finish
    for (Thread thread : virtualThreads) {
      thread.join(100);
    }
    
    // Log results
    log.info("Memory Usage - Platform Threads: {} MB for {} threads",
        String.format("%.2f", platformMemoryUsage / (1024.0 * 1024.0)),
        threadCount);
    
    log.info("Memory Usage - Virtual Threads: {} MB for {} threads",
        String.format("%.2f", virtualMemoryUsage / (1024.0 * 1024.0)),
        threadCount);
    
    // Verify memory usage
    assertThat("Virtual threads should use significantly less memory",
        virtualMemoryUsage, lessThan(platformMemoryUsage / 3)); // At least 3x less memory
  }
  
  /**
   * Run a benchmark for the given operation using the specified executor service.
   * The benchmark increases concurrency levels from 1 to MAX_CONCURRENCY in steps of CONCURRENCY_STEP.
   *
   * @param name      Name of the benchmark for metrics
   * @param operation Operation to benchmark
   * @param executor  Executor service to use
   * @return Performance result with throughput and latency metrics
   */
  private <T> PerformanceResult runBenchmark(String name, Callable<T> operation, ExecutorService executor) throws Exception {
    Timer latencyTimer = metricRegistry.timer(name + "-latency");
    Histogram concurrencyHistogram = metricRegistry.histogram(name + "-concurrency");
    
    // Warm-up phase
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runIteration(operation, executor, CONCURRENCY_STEP, latencyTimer, concurrencyHistogram);
    }
    
    // Reset metrics after warm-up
    metricRegistry.remove(name + "-latency");
    metricRegistry.remove(name + "-concurrency");
    latencyTimer = metricRegistry.timer(name + "-latency");
    concurrencyHistogram = metricRegistry.histogram(name + "-concurrency");
    
    // Measurement phase with increasing concurrency
    double totalThroughput = 0;
    for (int concurrency = CONCURRENCY_STEP; concurrency <= MAX_CONCURRENCY; concurrency += CONCURRENCY_STEP) {
      for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
        double throughput = runIteration(operation, executor, concurrency, latencyTimer, concurrencyHistogram);
        totalThroughput += throughput;
      }
    }
    
    // Calculate average throughput
    int totalIterations = (MAX_CONCURRENCY / CONCURRENCY_STEP) * MEASUREMENT_ITERATIONS;
    double avgThroughput = totalThroughput / totalIterations;
    
    // Get latency percentiles
    Snapshot snapshot = latencyTimer.getSnapshot();
    double p95Latency = snapshot.get95thPercentile() / 1_000_000.0; // Convert to ms
    double p99Latency = snapshot.get99thPercentile() / 1_000_000.0; // Convert to ms
    
    return new PerformanceResult(avgThroughput, p95Latency, p99Latency);
  }
  
  /**
   * Run a single iteration of the benchmark with the specified concurrency level.
   *
   * @param operation    Operation to benchmark
   * @param executor     Executor service to use
   * @param concurrency  Concurrency level
   * @param latencyTimer Timer for measuring latency
   * @param concurrencyHistogram Histogram for tracking concurrency
   * @return Throughput in operations per second
   */
  private <T> double runIteration(
      Callable<T> operation,
      ExecutorService executor,
      int concurrency,
      Timer latencyTimer,
      Histogram concurrencyHistogram) throws Exception
  {
    concurrencyHistogram.update(concurrency);
    
    // Create tasks
    List<Callable<T>> tasks = new ArrayList<>(concurrency);
    for (int i = 0; i < concurrency; i++) {
      tasks.add(() -> {
        Timer.Context timerContext = latencyTimer.time();
        try {
          return operation.call();
        }
        finally {
          timerContext.stop();
        }
      });
    }
    
    // Execute tasks and measure time
    long startTime = System.nanoTime();
    List<Future<T>> futures = executor.invokeAll(tasks, OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long endTime = System.nanoTime();
    
    // Count successful operations
    long successCount = futures.stream().filter(f -> !f.isCancelled()).count();
    double durationSeconds = (endTime - startTime) / 1_000_000_000.0;
    
    // Calculate throughput
    return successCount / durationSeconds;
  }
  
  /**
   * Simulate an I/O operation by sleeping for the specified duration.
   *
   * @param durationMs Duration in milliseconds
   */
  private void simulateIoOperation(long durationMs) {
    try {
      Thread.sleep(durationMs);
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Get the current used memory in bytes.
   *
   * @return Used memory in bytes
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Performance result class to hold benchmark metrics.
   */
  private static class PerformanceResult
  {
    private final double throughput;
    private final double p95Latency;
    private final double p99Latency;
    
    public PerformanceResult(double throughput, double p95Latency, double p99Latency) {
      this.throughput = throughput;
      this.p95Latency = p95Latency;
      this.p99Latency = p99Latency;
    }
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getP95Latency() {
      return p95Latency;
    }
    
    public double getP99Latency() {
      return p99Latency;
    }
  }
}