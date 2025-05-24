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
package org.sonatype.virtualthread;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.upgrade.events.UpgradeCompletedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeStartedEvent;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.internal.UpgradeManagerImpl;
import org.sonatype.nexus.upgrade.datastore.internal.TestMigrationStep;
import org.sonatype.nexus.upgrade.plan.DependencyResolver;
import org.sonatype.nexus.upgrade.plan.DependencySource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Performance comparison test between platform threads and virtual threads for Nexus upgrade operations.
 * This test measures throughput, latency, and resource utilization for key upgrade operations like
 * database migrations, dependency resolution, and upgrade task execution under both threading models.
 * 
 * @since 3.60
 */
public class VirtualThreadPerformanceComparisonTest
    extends TestSupport
{
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASUREMENT_ITERATIONS = 5;
  private static final int MAX_CONCURRENCY = 1000;
  private static final int STEP_SIZE = 100;
  private static final Duration TEST_TIMEOUT = Duration.ofMinutes(5);
  
  private AutoCloseable mocks;
  
  @Mock
  private DataStoreManager dataStoreManager;
  
  @Mock
  private PostStartupUpgradeAuditor auditor;
  
  @BeforeEach
  void setUp() {
    mocks = MockitoAnnotations.openMocks(this);
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }
  
  /**
   * Detects thread pinning events during test execution.
   * Thread pinning occurs when a virtual thread is pinned to its carrier thread,
   * preventing the carrier thread from being used by other virtual threads.
   */
  private static class ThreadPinningDetector {
    private final Map<Thread, StackTraceElement[]> pinnedThreads = new ConcurrentHashMap<>();
    private final Thread monitorThread;
    private volatile boolean running = true;
    
    public ThreadPinningDetector() {
      monitorThread = new Thread(() -> {
        while (running) {
          detectPinnedThreads();
          try {
            Thread.sleep(100);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }, "thread-pinning-detector");
      monitorThread.setDaemon(true);
    }
    
    public void start() {
      monitorThread.start();
    }
    
    public void stop() {
      running = false;
      monitorThread.interrupt();
    }
    
    private void detectPinnedThreads() {
      Thread.getAllStackTraces().forEach((thread, stackTrace) -> {
        if (thread.toString().contains("VirtualThread") && 
            thread.getState() == Thread.State.RUNNABLE) {
          for (StackTraceElement element : stackTrace) {
            // Check for common pinning causes
            if (element.getMethodName().contains("synchronized") ||
                element.getClassName().contains("native") ||
                element.getClassName().contains("jni")) {
              pinnedThreads.put(thread, stackTrace);
              break;
            }
          }
        }
      });
    }
    
    public Map<Thread, StackTraceElement[]> getPinnedThreads() {
      return pinnedThreads;
    }
    
    public String getPinningReport() {
      StringBuilder report = new StringBuilder("Thread Pinning Report:\n");
      if (pinnedThreads.isEmpty()) {
        report.append("No thread pinning detected.\n");
      } else {
        report.append("Detected ").append(pinnedThreads.size()).append(" pinned threads:\n");
        pinnedThreads.forEach((thread, stackTrace) -> {
          report.append("Thread: ").append(thread).append("\n");
          for (StackTraceElement element : stackTrace) {
            report.append("  at ").append(element).append("\n");
          }
          report.append("\n");
        });
      }
      return report.toString();
    }
  }
  
  /**
   * Performance measurement results for a specific test scenario.
   */
  private static class PerformanceResult {
    private final String name;
    private final int concurrency;
    private final long operationCount;
    private final long durationMs;
    private final double operationsPerSecond;
    private final double avgLatencyMs;
    private final long maxLatencyMs;
    private final long minLatencyMs;
    private final long p95LatencyMs;
    private final long p99LatencyMs;
    private final long memoryUsedBytes;
    private final int errorCount;
    
    public PerformanceResult(String name, int concurrency, long operationCount, long durationMs, 
                            List<Long> latencies, long memoryUsedBytes, int errorCount) {
      this.name = name;
      this.concurrency = concurrency;
      this.operationCount = operationCount;
      this.durationMs = durationMs;
      this.operationsPerSecond = operationCount * 1000.0 / durationMs;
      
      // Calculate latency statistics
      if (latencies.isEmpty()) {
        this.avgLatencyMs = 0;
        this.maxLatencyMs = 0;
        this.minLatencyMs = 0;
        this.p95LatencyMs = 0;
        this.p99LatencyMs = 0;
      } else {
        this.avgLatencyMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        this.maxLatencyMs = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        this.minLatencyMs = latencies.stream().mapToLong(Long::longValue).min().orElse(0);
        
        // Sort latencies for percentile calculations
        latencies.sort(Long::compare);
        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        int p99Index = (int) Math.ceil(latencies.size() * 0.99) - 1;
        this.p95LatencyMs = latencies.get(Math.max(0, p95Index));
        this.p99LatencyMs = latencies.get(Math.max(0, p99Index));
      }
      
      this.memoryUsedBytes = memoryUsedBytes;
      this.errorCount = errorCount;
    }
    
    public String getName() {
      return name;
    }
    
    public int getConcurrency() {
      return concurrency;
    }
    
    public long getOperationCount() {
      return operationCount;
    }
    
    public long getDurationMs() {
      return durationMs;
    }
    
    public double getOperationsPerSecond() {
      return operationsPerSecond;
    }
    
    public double getAvgLatencyMs() {
      return avgLatencyMs;
    }
    
    public long getMaxLatencyMs() {
      return maxLatencyMs;
    }
    
    public long getMinLatencyMs() {
      return minLatencyMs;
    }
    
    public long getP95LatencyMs() {
      return p95LatencyMs;
    }
    
    public long getP99LatencyMs() {
      return p99LatencyMs;
    }
    
    public long getMemoryUsedBytes() {
      return memoryUsedBytes;
    }
    
    public int getErrorCount() {
      return errorCount;
    }
    
    @Override
    public String toString() {
      return STR."""
          Performance Results for \{
ame} (Concurrency: \{concurrency}):
          - Operations: \{operationCount}
          - Duration: \{durationMs} ms
          - Throughput: \{String.format("%.2f", operationsPerSecond)} ops/sec
          - Avg Latency: \{String.format("%.2f", avgLatencyMs)} ms
          - Min Latency: \{minLatencyMs} ms
          - Max Latency: \{maxLatencyMs} ms
          - P95 Latency: \{p95LatencyMs} ms
          - P99 Latency: \{p99LatencyMs} ms
          - Memory Used: \{memoryUsedBytes / 1024} KB
          - Errors: \{errorCount}
          """;
    }
  }
  
  /**
   * Runs a performance test with increasing concurrency levels for both platform threads and virtual threads.
   * 
   * @param testName the name of the test
   * @param operation the operation to benchmark
   * @return a list of performance results for each concurrency level and thread type
   */
  private List<PerformanceResult> runScalabilityTest(String testName, Runnable operation) {
    List<PerformanceResult> results = new ArrayList<>();
    
    // Warm up
    log.info("Warming up {}...", testName);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      operation.run();
    }
    
    // Test with increasing concurrency using platform threads
    for (int concurrency = STEP_SIZE; concurrency <= MAX_CONCURRENCY; concurrency += STEP_SIZE) {
      log.info("Testing {} with {} platform threads", testName, concurrency);
      results.add(measurePerformance(testName + " (Platform Threads)", concurrency, false, operation));
    }
    
    // Test with increasing concurrency using virtual threads
    for (int concurrency = STEP_SIZE; concurrency <= MAX_CONCURRENCY; concurrency += STEP_SIZE) {
      log.info("Testing {} with {} virtual threads", testName, concurrency);
      results.add(measurePerformance(testName + " (Virtual Threads)", concurrency, true, operation));
    }
    
    return results;
  }
  
  /**
   * Measures the performance of an operation with a specific concurrency level and thread type.
   * 
   * @param name the name of the test
   * @param concurrency the number of concurrent operations
   * @param useVirtualThreads whether to use virtual threads
   * @param operation the operation to benchmark
   * @return the performance result
   */
  private PerformanceResult measurePerformance(String name, int concurrency, boolean useVirtualThreads, Runnable operation) {
    ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
    if (useVirtualThreads) {
      pinningDetector.start();
    }
    
    // Create appropriate executor service
    ExecutorService executor = useVirtualThreads ?
        Executors.newVirtualThreadPerTaskExecutor() :
        Executors.newFixedThreadPool(concurrency);
    
    try {
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicInteger completedCount = new AtomicInteger(0);
      List<Long> latencies = new ArrayList<>(concurrency);
      CountDownLatch latch = new CountDownLatch(concurrency);
      
      // Measure memory before test
      Runtime runtime = Runtime.getRuntime();
      System.gc(); // Request garbage collection to get more accurate memory measurement
      long memoryBefore = runtime.totalMemory() - runtime.freeMemory();
      
      // Start timing
      Instant start = Instant.now();
      
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            Instant operationStart = Instant.now();
            operation.run();
            Instant operationEnd = Instant.now();
            
            // Record latency
            synchronized (latencies) {
              latencies.add(Duration.between(operationStart, operationEnd).toMillis());
            }
            
            completedCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error during operation execution", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      latch.await(1, TimeUnit.MINUTES);
      
      // End timing
      Instant end = Instant.now();
      long durationMs = Duration.between(start, end).toMillis();
      
      // Measure memory after test
      System.gc(); // Request garbage collection to get more accurate memory measurement
      long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
      long memoryUsed = memoryAfter - memoryBefore;
      
      if (useVirtualThreads) {
        pinningDetector.stop();
        if (!pinningDetector.getPinnedThreads().isEmpty()) {
          log.warn(pinningDetector.getPinningReport());
        }
      }
      
      return new PerformanceResult(
          name,
          concurrency,
          completedCount.get(),
          durationMs,
          latencies,
          memoryUsed,
          errorCount.get()
      );
    }
    catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Performance test was interrupted", e);
    }
    finally {
      executor.shutdownNow();
      try {
        executor.awaitTermination(10, TimeUnit.SECONDS);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
  
  /**
   * Compares the performance of database migrations using platform threads vs virtual threads.
   */
  @Test
  @DisplayName("Compare database migration performance: Platform Threads vs Virtual Threads")
  void compareDatabaseMigrationPerformance() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Set up test database
      DataSessionRule dataSessionRule = new DataSessionRule();
      when(dataStoreManager.get(DEFAULT_DATASTORE_NAME)).thenReturn(dataSessionRule.getDataStore(DEFAULT_DATASTORE_NAME));
      
      // Create test migration step
      TestMigrationStep migrationStep = new TestMigrationStep();
      
      // Define the operation to benchmark
      Runnable migrationOperation = () -> {
        UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
        upgradeManager.migrate();
        
        // Verify migration was successful
        verify(auditor).post(any(UpgradeStartedEvent.class));
        verify(auditor).post(any(UpgradeCompletedEvent.class));
      };
      
      // Run the performance comparison
      List<PerformanceResult> results = runScalabilityTest("Database Migration", migrationOperation);
      
      // Log results
      results.forEach(result -> log.info(result.toString()));
      
      // Compare results at maximum concurrency
      PerformanceResult platformResult = results.stream()
          .filter(r -> r.getName().contains("Platform") && r.getConcurrency() == MAX_CONCURRENCY)
          .findFirst()
          .orElseThrow();
      
      PerformanceResult virtualResult = results.stream()
          .filter(r -> r.getName().contains("Virtual") && r.getConcurrency() == MAX_CONCURRENCY)
          .findFirst()
          .orElseThrow();
      
      // Assert performance expectations
      assertAll(
          () -> assertThat("Virtual threads should have zero errors", virtualResult.getErrorCount(), is(0)),
          () -> assertThat("Platform threads should have zero errors", platformResult.getErrorCount(), is(0)),
          () -> assertThat("Virtual threads should handle more operations per second", 
              virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond())),
          () -> assertThat("Virtual threads should use less memory", 
              virtualResult.getMemoryUsedBytes(), lessThan(platformResult.getMemoryUsedBytes()))
      );
    });
  }
  
  /**
   * Compares the performance of dependency resolution using platform threads vs virtual threads.
   */
  @Test
  @DisplayName("Compare dependency resolution performance: Platform Threads vs Virtual Threads")
  void compareDependencyResolutionPerformance() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Define the operation to benchmark
      Runnable dependencyResolutionOperation = () -> {
        // Create a complex dependency graph
        List<TestDependencySource> sources = createComplexDependencyGraph(100);
        
        // Resolve dependencies
        DependencyResolver<TestDependencySource> resolver = new DependencyResolver<>();
        sources.forEach(resolver::add);
        resolver.resolve();
      };
      
      // Run the performance comparison
      List<PerformanceResult> results = runScalabilityTest("Dependency Resolution", dependencyResolutionOperation);
      
      // Log results
      results.forEach(result -> log.info(result.toString()));
      
      // Compare results at maximum concurrency
      PerformanceResult platformResult = results.stream()
          .filter(r -> r.getName().contains("Platform") && r.getConcurrency() == MAX_CONCURRENCY)
          .findFirst()
          .orElseThrow();
      
      PerformanceResult virtualResult = results.stream()
          .filter(r -> r.getName().contains("Virtual") && r.getConcurrency() == MAX_CONCURRENCY)
          .findFirst()
          .orElseThrow();
      
      // Assert performance expectations
      assertAll(
          () -> assertThat("Virtual threads should have zero errors", virtualResult.getErrorCount(), is(0)),
          () -> assertThat("Platform threads should have zero errors", platformResult.getErrorCount(), is(0)),
          () -> assertThat("Virtual threads should have lower P95 latency", 
              virtualResult.getP95LatencyMs(), lessThanOrEqualTo(platformResult.getP95LatencyMs()))
      );
    });
  }
  
  /**
   * Tests the scalability of virtual threads with very high concurrency levels.
   */
  @ParameterizedTest
  @ValueSource(ints = {1000, 5000, 10000})
  @DisplayName("Test virtual thread scalability with high concurrency")
  void testVirtualThreadScalability(int concurrency) {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Create a simple I/O-bound operation that simulates network or disk I/O
      Runnable ioOperation = () -> {
        try {
          // Simulate I/O operation with sleep
          Thread.sleep(50);
        }
        catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      };
      
      // Measure performance with virtual threads
      PerformanceResult result = measurePerformance(
          "I/O Operation (Virtual Threads)", 
          concurrency, 
          true, 
          ioOperation
      );
      
      log.info(result.toString());
      
      // Assert that virtual threads can handle high concurrency
      assertAll(
          () -> assertThat("All operations should complete successfully", 
              result.getOperationCount(), is((long) concurrency)),
          () -> assertThat("There should be no errors", 
              result.getErrorCount(), is(0))
      );
    });
  }
  
  /**
   * Tests thread pinning detection during synchronized block execution.
   */
  @Test
  @DisplayName("Detect thread pinning during synchronized block execution")
  void detectThreadPinningDuringSynchronizedExecution() {
    assertTimeoutPreemptively(TEST_TIMEOUT, () -> {
      // Create an object for synchronization
      Object lock = new Object();
      
      // Create an operation that uses synchronized blocks (which cause pinning)
      Runnable pinnedOperation = () -> {
        synchronized (lock) {
          try {
            // Hold the lock for a while
            Thread.sleep(100);
          }
          catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
      };
      
      // Measure performance with virtual threads
      ThreadPinningDetector pinningDetector = new ThreadPinningDetector();
      pinningDetector.start();
      
      try {
        // Run the operation with virtual threads
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
          // Submit multiple tasks
          List<CompletableFuture<Void>> futures = new ArrayList<>();
          for (int i = 0; i < 10; i++) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(pinnedOperation, executor);
            futures.add(future);
          }
          
          // Wait for all tasks to complete
          CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
        finally {
          executor.shutdownNow();
          executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        
        // Check if pinning was detected
        pinningDetector.stop();
        Map<Thread, StackTraceElement[]> pinnedThreads = pinningDetector.getPinnedThreads();
        
        log.info(pinningDetector.getPinningReport());
        
        // We expect to detect at least one pinned thread
        assertThat("Thread pinning should be detected", !pinnedThreads.isEmpty());
      }
      finally {
        pinningDetector.stop();
      }
    });
  }
  
  /**
   * Creates a complex dependency graph for testing dependency resolution performance.
   * 
   * @param size the number of nodes in the graph
   * @return a list of dependency sources
   */
  private List<TestDependencySource> createComplexDependencyGraph(int size) {
    List<TestDependencySource> sources = new ArrayList<>(size);
    
    // Create nodes
    for (int i = 0; i < size; i++) {
      sources.add(new TestDependencySource("node-" + i));
    }
    
    // Create dependencies (each node depends on ~10% of other nodes)
    for (int i = 0; i < size; i++) {
      TestDependencySource source = sources.get(i);
      for (int j = 0; j < size / 10; j++) {
        int dependencyIndex = (i + j + 1) % size;
        source.addDependency(new TestDependency(sources.get(dependencyIndex).getId()));
      }
    }
    
    return sources;
  }
  
  /**
   * Simple dependency source implementation for testing.
   */
  private static class TestDependencySource implements DependencySource<TestDependencySource> {
    private final String id;
    private final List<DependencySource.Dependency<TestDependencySource>> dependencies = new ArrayList<>();
    
    public TestDependencySource(String id) {
      this.id = id;
    }
    
    public String getId() {
      return id;
    }
    
    public void addDependency(DependencySource.Dependency<TestDependencySource> dependency) {
      dependencies.add(dependency);
    }
    
    @Override
    public List<DependencySource.Dependency<TestDependencySource>> getDependencies() {
      return dependencies;
    }
    
    @Override
    public String toString() {
      return "TestDependencySource{id='" + id + "'}";
    }
  }
  
  /**
   * Simple dependency implementation for testing.
   */
  private static class TestDependency implements DependencySource.Dependency<TestDependencySource> {
    private final String targetId;
    
    public TestDependency(String targetId) {
      this.targetId = targetId;
    }
    
    @Override
    public boolean satisfiedBy(TestDependencySource source) {
      return source.getId().equals(targetId);
    }
    
    @Override
    public String toString() {
      return "TestDependency{targetId='" + targetId + "'}";
    }
  }
  
  /**
   * Mock implementation of PostStartupUpgradeAuditor for testing.
   */
  private interface PostStartupUpgradeAuditor {
    void post(Object event);
  }
}