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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
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

import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.FreezeService;
import org.sonatype.nexus.common.stateguard.StateGuardModule;
import org.sonatype.nexus.datastore.DataStoreSupport;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;

import com.google.common.collect.ImmutableMap;
import com.google.inject.Injector;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.google.inject.Guice.createInjector;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.mockito.Mockito.mock;

/**
 * Performance test comparing Virtual Threads vs Platform Threads for database operations.
 * 
 * This test measures throughput, latency, and resource utilization under various load conditions
 * to verify the performance improvements when using Java 21 Virtual Threads for I/O-bound
 * database operations.
 */
public class VirtualThreadPerformanceTest extends TestSupport
{
  private static final String DB_URL = "jdbc:h2:mem:virtualthread;DB_CLOSE_DELAY=-1";
  
  private static final String CREATE_TABLE_SQL = 
      "CREATE TABLE IF NOT EXISTS test_data (id INT PRIMARY KEY, name VARCHAR(255))";
  
  private static final String INSERT_SQL = "INSERT INTO test_data (id, name) VALUES (?, ?)";
  
  private static final String SELECT_SQL = "SELECT name FROM test_data WHERE id = ?";
  
  private static final String DELETE_SQL = "DELETE FROM test_data WHERE id = ?";
  
  private static final int WARMUP_COUNT = 100;
  
  private static final int MEASUREMENT_ITERATIONS = 5;
  
  private DataSource dataSource;
  
  private TestDataStore dataStore;
  
  /**
   * Test data store implementation for performance testing.
   */
  static class TestDataStore extends DataStoreSupport<Connection>
  {
    private final DataSource dataSource;
    
    public TestDataStore(DataSource dataSource) {
      this.dataSource = dataSource;
    }
    
    @Override
    public void register(final Class<?> accessType) {
      // no-op
    }

    @Override
    public void unregister(final Class<?> accessType) {
      // no-op
    }

    @Override
    public Connection openSession() {
      try {
        return dataSource.getConnection();
      }
      catch (SQLException e) {
        throw new RuntimeException("Failed to open connection", e);
      }
    }

    @Override
    public Connection openConnection() {
      return openSession();
    }

    @Override
    public DataSource getDataSource() {
      return dataSource;
    }

    @Override
    protected void doStart(final String storeName, final Map<String, String> attributes) throws Exception {
      // no-op
    }

    @Override
    public void freeze() {
      // no-op
    }

    @Override
    public void unfreeze() {
      // no-op
    }

    @Override
    public boolean isFrozen() {
      return false;
    }

    @Override
    public void backup(final String location) throws SQLException {
      // no-op
    }
  }
  
  /**
   * Performance result data container.
   */
  static class PerformanceResult
  {
    private final String threadType;
    private final int concurrentOperations;
    private final long totalOperations;
    private final long durationMs;
    private final double operationsPerSecond;
    private final double avgLatencyMs;
    private final double p95LatencyMs;
    private final double p99LatencyMs;
    private final long maxMemoryUsed;
    
    public PerformanceResult(String threadType, 
                            int concurrentOperations,
                            long totalOperations, 
                            long durationMs, 
                            List<Long> latencies,
                            long maxMemoryUsed) {
      this.threadType = threadType;
      this.concurrentOperations = concurrentOperations;
      this.totalOperations = totalOperations;
      this.durationMs = durationMs;
      this.operationsPerSecond = (double) totalOperations / (durationMs / 1000.0);
      
      // Calculate latency statistics
      latencies.sort(Long::compare);
      this.avgLatencyMs = latencies.stream().mapToLong(Long::longValue).average().orElse(0);
      this.p95LatencyMs = percentile(latencies, 95);
      this.p99LatencyMs = percentile(latencies, 99);
      this.maxMemoryUsed = maxMemoryUsed;
    }
    
    private double percentile(List<Long> sortedLatencies, int percentile) {
      int index = (int) Math.ceil(percentile / 100.0 * sortedLatencies.size()) - 1;
      return sortedLatencies.get(Math.max(0, Math.min(index, sortedLatencies.size() - 1)));
    }
    
    @Override
    public String toString() {
      return String.format("%s (Concurrent: %d) - Ops: %d, Duration: %d ms, Throughput: %.2f ops/sec, " +
          "Avg Latency: %.2f ms, P95: %.2f ms, P99: %.2f ms, Max Memory: %d MB",
          threadType, concurrentOperations, totalOperations, durationMs, operationsPerSecond, 
          avgLatencyMs, p95LatencyMs, p99LatencyMs, maxMemoryUsed / (1024 * 1024));
    }
    
    public String getThreadType() {
      return threadType;
    }
    
    public int getConcurrentOperations() {
      return concurrentOperations;
    }
    
    public long getTotalOperations() {
      return totalOperations;
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
    
    public double getP95LatencyMs() {
      return p95LatencyMs;
    }
    
    public double getP99LatencyMs() {
      return p99LatencyMs;
    }
    
    public long getMaxMemoryUsed() {
      return maxMemoryUsed;
    }
  }
  
  @BeforeEach
  void setUp(TestInfo testInfo) throws Exception {
    log.info("Setting up test: {}", testInfo.getDisplayName());
    
    // Initialize H2 in-memory database
    JdbcDataSource h2DataSource = new JdbcDataSource();
    h2DataSource.setURL(DB_URL);
    h2DataSource.setUser("sa");
    h2DataSource.setPassword("");
    this.dataSource = h2DataSource;
    
    // Create test data store
    Injector injector = createInjector(new StateGuardModule());
    dataStore = injector.getInstance(TestDataStore.class);
    dataStore.setDataSource(dataSource);
    
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName("virtualthread-test");
    config.setType("h2");
    config.setSource("local");
    config.setAttributes(ImmutableMap.of("jdbcUrl", DB_URL));
    dataStore.setConfiguration(config);
    dataStore.setFreezeService(mock(FreezeService.class));
    dataStore.start();
    
    // Initialize database schema
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(CREATE_TABLE_SQL)) {
      stmt.execute();
    }
    
    // Perform warmup to stabilize JVM
    warmup();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (dataStore != null) {
      dataStore.stop();
    }
  }
  
  /**
   * Warm up the JVM to stabilize performance measurements.
   */
  private void warmup() throws Exception {
    log.info("Warming up JVM with {} operations", WARMUP_COUNT);
    
    // Insert test data
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      try (PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
        for (int i = 0; i < WARMUP_COUNT; i++) {
          stmt.setInt(1, i);
          stmt.setString(2, "test-" + i);
          stmt.addBatch();
          
          if (i % 100 == 0) {
            stmt.executeBatch();
          }
        }
        stmt.executeBatch();
        conn.commit();
      }
    }
    
    // Perform some reads to warm up the JVM
    ExecutorService warmupExecutor = Executors.newFixedThreadPool(10);
    try {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < WARMUP_COUNT; i++) {
        final int id = i;
        futures.add(CompletableFuture.runAsync(() -> {
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(SELECT_SQL)) {
            stmt.setInt(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
              if (rs.next()) {
                rs.getString(1);
              }
            }
          }
          catch (SQLException e) {
            throw new RuntimeException(e);
          }
        }, warmupExecutor));
      }
      
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
    finally {
      warmupExecutor.shutdown();
      warmupExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
    
    log.info("Warmup completed");
  }
  
  /**
   * Creates a platform thread executor with the specified number of threads.
   */
  private ExecutorService createPlatformThreadExecutor(int threads) {
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    return Executors.newFixedThreadPool(threads, platformThreadFactory);
  }
  
  /**
   * Creates a virtual thread executor.
   */
  private ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Runs a database operation benchmark with the specified executor and concurrency level.
   */
  private PerformanceResult runBenchmark(String threadType, ExecutorService executor, int concurrentOperations) 
      throws Exception {
    log.info("Running benchmark with {} threads, concurrency: {}", threadType, concurrentOperations);
    
    // Reset memory counters
    System.gc();
    long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    AtomicLong maxMemoryUsed = new AtomicLong(0);
    
    // Setup counters and latches
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(concurrentOperations);
    AtomicInteger completedOperations = new AtomicInteger(0);
    ConcurrentHashMap<Integer, Long> operationLatencies = new ConcurrentHashMap<>();
    
    // Prepare database operations
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < concurrentOperations; i++) {
      final int operationId = i;
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          // Wait for all threads to be ready
          startLatch.await();
          
          // Record start time
          long startTime = System.nanoTime();
          
          // Perform database operation (read-write cycle)
          performDatabaseOperation(operationId);
          
          // Record latency
          long latencyNanos = System.nanoTime() - startTime;
          operationLatencies.put(operationId, TimeUnit.NANOSECONDS.toMillis(latencyNanos));
          
          // Update counters
          completedOperations.incrementAndGet();
          
          // Track memory usage
          long currentMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
          long memoryUsed = currentMemory - initialMemory;
          maxMemoryUsed.updateAndGet(current -> Math.max(current, memoryUsed));
        }
        catch (Exception e) {
          log.error("Error in database operation", e);
        }
        finally {
          completionLatch.countDown();
        }
      }, executor));
    }
    
    // Start the benchmark
    Instant startTime = Instant.now();
    startLatch.countDown();
    
    // Wait for completion
    boolean completed = completionLatch.await(5, TimeUnit.MINUTES);
    Instant endTime = Instant.now();
    
    if (!completed) {
      log.warn("Benchmark did not complete within timeout");
    }
    
    // Calculate results
    long durationMs = Duration.between(startTime, endTime).toMillis();
    List<Long> latencies = new ArrayList<>(operationLatencies.values());
    
    return new PerformanceResult(
        threadType,
        concurrentOperations,
        completedOperations.get(),
        durationMs,
        latencies,
        maxMemoryUsed.get()
    );
  }
  
  /**
   * Performs a database read-write operation cycle.
   */
  private void performDatabaseOperation(int id) throws SQLException {
    // Simulate a realistic database operation with both reads and writes
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      
      // First read the data
      try (PreparedStatement selectStmt = conn.prepareStatement(SELECT_SQL)) {
        selectStmt.setInt(1, id % WARMUP_COUNT);
        try (ResultSet rs = selectStmt.executeQuery()) {
          if (rs.next()) {
            String name = rs.getString(1);
            // Update the data
            try (PreparedStatement updateStmt = conn.prepareStatement(INSERT_SQL)) {
              updateStmt.setInt(1, id % WARMUP_COUNT);
              updateStmt.setString(2, name + "-updated");
              updateStmt.executeUpdate();
            }
          }
        }
      }
      
      // Simulate some processing time
      try {
        Thread.sleep(10);
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      conn.commit();
    }
  }
  
  /**
   * Runs a benchmark comparing platform threads and virtual threads at the specified concurrency level.
   */
  private void runComparativeBenchmark(int concurrentOperations) throws Exception {
    // Run platform thread benchmark
    ExecutorService platformExecutor = createPlatformThreadExecutor(
        Math.min(concurrentOperations, Runtime.getRuntime().availableProcessors() * 2));
    try {
      PerformanceResult platformResult = runBenchmark("Platform Threads", platformExecutor, concurrentOperations);
      log.info("Platform Thread Result: {}", platformResult);
      
      // Run virtual thread benchmark
      ExecutorService virtualExecutor = createVirtualThreadExecutor();
      try {
        PerformanceResult virtualResult = runBenchmark("Virtual Threads", virtualExecutor, concurrentOperations);
        log.info("Virtual Thread Result: {}", virtualResult);
        
        // Compare results
        compareResults(platformResult, virtualResult);
      }
      finally {
        virtualExecutor.shutdown();
        virtualExecutor.awaitTermination(1, TimeUnit.MINUTES);
      }
    }
    finally {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Compares performance results between platform threads and virtual threads.
   */
  private void compareResults(PerformanceResult platformResult, PerformanceResult virtualResult) {
    log.info("Performance Comparison ({}):")
        .add("Concurrent Operations", platformResult.getConcurrentOperations())
        .add("Platform Thread Throughput", String.format("%.2f ops/sec", platformResult.getOperationsPerSecond()))
        .add("Virtual Thread Throughput", String.format("%.2f ops/sec", virtualResult.getOperationsPerSecond()))
        .add("Throughput Improvement", String.format("%.2f%%", 
            (virtualResult.getOperationsPerSecond() / platformResult.getOperationsPerSecond() - 1) * 100))
        .add("Platform Thread Avg Latency", String.format("%.2f ms", platformResult.getAvgLatencyMs()))
        .add("Virtual Thread Avg Latency", String.format("%.2f ms", virtualResult.getAvgLatencyMs()))
        .add("Latency Improvement", String.format("%.2f%%", 
            (1 - virtualResult.getAvgLatencyMs() / platformResult.getAvgLatencyMs()) * 100))
        .add("Platform Thread P95 Latency", String.format("%.2f ms", platformResult.getP95LatencyMs()))
        .add("Virtual Thread P95 Latency", String.format("%.2f ms", virtualResult.getP95LatencyMs()))
        .add("Platform Thread Memory", String.format("%d MB", platformResult.getMaxMemoryUsed() / (1024 * 1024)))
        .add("Virtual Thread Memory", String.format("%d MB", virtualResult.getMaxMemoryUsed() / (1024 * 1024)))
        .log();
    
    // For high concurrency operations, virtual threads should show better performance
    if (platformResult.getConcurrentOperations() >= 100) {
      assertThat("Virtual threads should have higher throughput for I/O-bound operations with high concurrency",
          virtualResult.getOperationsPerSecond(), greaterThan(platformResult.getOperationsPerSecond()));
      
      assertThat("Virtual threads should have lower average latency for I/O-bound operations",
          virtualResult.getAvgLatencyMs(), lessThan(platformResult.getAvgLatencyMs()));
      
      assertThat("Virtual threads should have lower P95 latency for I/O-bound operations",
          virtualResult.getP95LatencyMs(), lessThan(platformResult.getP95LatencyMs()));
      
      assertThat("Virtual threads should use less memory per thread",
          (double) virtualResult.getMaxMemoryUsed() / virtualResult.getConcurrentOperations(),
          lessThan((double) platformResult.getMaxMemoryUsed() / platformResult.getConcurrentOperations()));
    }
  }
  
  /**
   * Tests performance with a low concurrency level (10 operations).
   */
  @Test
  void testLowConcurrencyPerformance() throws Exception {
    runComparativeBenchmark(10);
  }
  
  /**
   * Tests performance with a medium concurrency level (100 operations).
   */
  @Test
  void testMediumConcurrencyPerformance() throws Exception {
    runComparativeBenchmark(100);
  }
  
  /**
   * Tests performance with a high concurrency level (1000 operations).
   */
  @Test
  void testHighConcurrencyPerformance() throws Exception {
    runComparativeBenchmark(1000);
  }
  
  /**
   * Tests performance with various concurrency levels to identify scaling characteristics.
   */
  @ParameterizedTest
  @ValueSource(ints = {10, 100, 500, 1000, 5000, 10000})
  void testScalabilityWithIncreasingConcurrency(int concurrentOperations) throws Exception {
    runComparativeBenchmark(concurrentOperations);
  }
  
  /**
   * Tests memory efficiency of virtual threads compared to platform threads under high load.
   */
  @Test
  void testMemoryEfficiency() throws Exception {
    // Use a high concurrency level to highlight memory differences
    int concurrentOperations = 5000;
    
    // Run platform thread benchmark with limited threads
    ExecutorService platformExecutor = createPlatformThreadExecutor(
        Math.min(concurrentOperations, Runtime.getRuntime().availableProcessors() * 2));
    try {
      System.gc();
      long beforePlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      PerformanceResult platformResult = runBenchmark("Platform Threads", platformExecutor, concurrentOperations);
      System.gc();
      long afterPlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      long platformMemoryUsed = afterPlatform - beforePlatform;
      
      log.info("Platform Thread Memory Usage: {} MB", platformMemoryUsed / (1024 * 1024));
      
      // Run virtual thread benchmark
      ExecutorService virtualExecutor = createVirtualThreadExecutor();
      try {
        System.gc();
        long beforeVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        PerformanceResult virtualResult = runBenchmark("Virtual Threads", virtualExecutor, concurrentOperations);
        System.gc();
        long afterVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long virtualMemoryUsed = afterVirtual - beforeVirtual;
        
        log.info("Virtual Thread Memory Usage: {} MB", virtualMemoryUsed / (1024 * 1024));
        
        // Virtual threads should use significantly less memory per concurrent operation
        double platformMemoryPerOperation = (double) platformMemoryUsed / concurrentOperations;
        double virtualMemoryPerOperation = (double) virtualMemoryUsed / concurrentOperations;
        
        log.info("Memory per operation - Platform: {} KB, Virtual: {} KB",
            platformMemoryPerOperation / 1024, virtualMemoryPerOperation / 1024);
        
        assertThat("Virtual threads should use less memory per concurrent operation",
            virtualMemoryPerOperation, lessThan(platformMemoryPerOperation));
      }
      finally {
        virtualExecutor.shutdown();
        virtualExecutor.awaitTermination(1, TimeUnit.MINUTES);
      }
    }
    finally {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Tests throughput under sustained load over time to verify stability.
   */
  @Test
  void testSustainedLoadThroughput() throws Exception {
    int concurrentOperations = 500;
    int durationSeconds = 30;
    
    log.info("Testing sustained load throughput for {} seconds with {} concurrent operations",
        durationSeconds, concurrentOperations);
    
    // Run platform thread benchmark
    ExecutorService platformExecutor = createPlatformThreadExecutor(
        Math.min(concurrentOperations, Runtime.getRuntime().availableProcessors() * 2));
    try {
      Supplier<PerformanceResult> platformBenchmark = () -> {
        try {
          return runBenchmark("Platform Threads", platformExecutor, concurrentOperations);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      };
      
      List<PerformanceResult> platformResults = new ArrayList<>();
      Instant platformStart = Instant.now();
      while (Duration.between(platformStart, Instant.now()).getSeconds() < durationSeconds) {
        platformResults.add(platformBenchmark.get());
      }
      
      double platformAvgThroughput = platformResults.stream()
          .mapToDouble(PerformanceResult::getOperationsPerSecond)
          .average()
          .orElse(0);
      
      // Run virtual thread benchmark
      ExecutorService virtualExecutor = createVirtualThreadExecutor();
      try {
        Supplier<PerformanceResult> virtualBenchmark = () -> {
          try {
            return runBenchmark("Virtual Threads", virtualExecutor, concurrentOperations);
          }
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        };
        
        List<PerformanceResult> virtualResults = new ArrayList<>();
        Instant virtualStart = Instant.now();
        while (Duration.between(virtualStart, Instant.now()).getSeconds() < durationSeconds) {
          virtualResults.add(virtualBenchmark.get());
        }
        
        double virtualAvgThroughput = virtualResults.stream()
            .mapToDouble(PerformanceResult::getOperationsPerSecond)
            .average()
            .orElse(0);
        
        log.info("Sustained Load Results:")
            .add("Platform Thread Avg Throughput", String.format("%.2f ops/sec", platformAvgThroughput))
            .add("Virtual Thread Avg Throughput", String.format("%.2f ops/sec", virtualAvgThroughput))
            .add("Throughput Improvement", String.format("%.2f%%", 
                (virtualAvgThroughput / platformAvgThroughput - 1) * 100))
            .log();
        
        assertThat("Virtual threads should maintain higher throughput under sustained load",
            virtualAvgThroughput, greaterThan(platformAvgThroughput));
      }
      finally {
        virtualExecutor.shutdown();
        virtualExecutor.awaitTermination(1, TimeUnit.MINUTES);
      }
    }
    finally {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
}