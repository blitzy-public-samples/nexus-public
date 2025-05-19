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
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.datastore.internal.DataStoreManagerImpl;

import com.google.common.collect.ImmutableMap;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Performance test comparing Virtual Threads vs Platform Threads for database operations.
 * 
 * This test measures throughput, latency, and resource utilization under various load conditions
 * to verify performance improvements when using Virtual Threads for I/O-bound database operations.
 * 
 * @since 3.60
 */
@RunWith(Parameterized.class)
public class VirtualThreadPerformanceTest
    extends TestSupport
{
  private static final String TEST_DB_NAME = "virtualthread-performance-test";
  
  private static final String CREATE_TEST_TABLE = 
      "CREATE TABLE IF NOT EXISTS performance_test (" +
      "  id VARCHAR(36) PRIMARY KEY, " +
      "  name VARCHAR(100), " +
      "  value VARCHAR(1000), " +
      "  created_at TIMESTAMP " +
      ")";
  
  private static final String INSERT_TEST_RECORD = 
      "INSERT INTO performance_test (id, name, value, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)";
  
  private static final String SELECT_TEST_RECORD = 
      "SELECT id, name, value, created_at FROM performance_test WHERE id = ?";
  
  private static final String UPDATE_TEST_RECORD = 
      "UPDATE performance_test SET value = ? WHERE id = ?";
  
  private static final String DELETE_TEST_RECORD = 
      "DELETE FROM performance_test WHERE id = ?";
  
  private static final String COUNT_TEST_RECORDS = 
      "SELECT COUNT(*) FROM performance_test";
  
  private static final int WARMUP_ITERATIONS = 10;
  private static final int TEST_ITERATIONS = 3;
  private static final int OPERATION_TIMEOUT_SECONDS = 60;
  
  /**
   * Test parameters for different concurrency levels.
   */
  @Parameters(name = "{0} concurrent operations")
  public static Object[] concurrencyLevels() {
    return new Object[] { 10, 100, 1000, 10000 };
  }
  
  @Parameter
  public int concurrencyLevel;
  
  @Mock
  private ApplicationDirectories directories;
  
  private DataStoreManagerImpl dataStoreManager;
  
  private DataStore dataStore;
  
  private DataSource dataSource;
  
  @Before
  public void setUp() throws Exception {
    // Setup mock directories
    when(directories.getWorkDirectory("db")).thenReturn(util.createTempDir("db"));
    when(directories.getTemporaryDirectory()).thenReturn(util.createTempDir("tmp"));
    
    // Create and initialize the DataStoreManager
    dataStoreManager = new DataStoreManagerImpl(directories, null, null, null, null);
    dataStoreManager.start();
    
    // Create a test datastore
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName(TEST_DB_NAME);
    config.setType("h2");
    config.setSource("local");
    config.setAttributes(ImmutableMap.of(
        "jdbcUrl", "jdbc:h2:file:${karaf.data}/db/" + TEST_DB_NAME,
        "username", "sa",
        "password", ""
    ));
    
    dataStore = dataStoreManager.create(config);
    dataSource = dataStore.getDataSource();
    
    // Create test table
    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement()) {
      stmt.execute(CREATE_TEST_TABLE);
    }
  }
  
  @After
  public void tearDown() throws Exception {
    if (dataStoreManager != null) {
      dataStoreManager.delete(TEST_DB_NAME);
      dataStoreManager.stop();
    }
  }
  
  /**
   * Test CRUD operations using platform threads vs virtual threads.
   */
  @Test
  public void testCrudOperationsPerformance() throws Exception {
    // Run warmup iterations
    log.info("Running {} warmup iterations with {} concurrent operations", WARMUP_ITERATIONS, concurrencyLevel);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runCrudOperations(createPlatformThreadExecutor(), "Platform Thread Warmup");
      runCrudOperations(createVirtualThreadExecutor(), "Virtual Thread Warmup");
    }
    
    // Run test iterations and collect metrics
    log.info("Running {} test iterations with {} concurrent operations", TEST_ITERATIONS, concurrencyLevel);
    
    List<PerformanceResult> platformResults = new ArrayList<>();
    List<PerformanceResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResults.add(runCrudOperations(createPlatformThreadExecutor(), "Platform Thread Test"));
      virtualResults.add(runCrudOperations(createVirtualThreadExecutor(), "Virtual Thread Test"));
    }
    
    // Calculate average metrics
    PerformanceResult avgPlatformResult = calculateAverageResult(platformResults);
    PerformanceResult avgVirtualResult = calculateAverageResult(virtualResults);
    
    // Log results
    log.info("\nPerformance comparison for {} concurrent operations:", concurrencyLevel);
    log.info("Platform Threads - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgPlatformResult.throughput, avgPlatformResult.avgLatency, avgPlatformResult.p95Latency, 
        avgPlatformResult.p99Latency, avgPlatformResult.memoryUsageMb);
    log.info("Virtual Threads  - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgVirtualResult.throughput, avgVirtualResult.avgLatency, avgVirtualResult.p95Latency, 
        avgVirtualResult.p99Latency, avgVirtualResult.memoryUsageMb);
    
    // Calculate improvement percentages
    double throughputImprovement = ((double) avgVirtualResult.throughput / avgPlatformResult.throughput - 1) * 100;
    double latencyImprovement = (1 - (double) avgVirtualResult.avgLatency / avgPlatformResult.avgLatency) * 100;
    double p95LatencyImprovement = (1 - (double) avgVirtualResult.p95Latency / avgPlatformResult.p95Latency) * 100;
    double p99LatencyImprovement = (1 - (double) avgVirtualResult.p99Latency / avgPlatformResult.p99Latency) * 100;
    double memoryImprovement = (1 - (double) avgVirtualResult.memoryUsageMb / avgPlatformResult.memoryUsageMb) * 100;
    
    log.info("Improvements with Virtual Threads:");
    log.info("  Throughput: {}{}", throughputImprovement > 0 ? "+" : "", String.format("%.2f%%", throughputImprovement));
    log.info("  Avg Latency: {}{}", latencyImprovement > 0 ? "+" : "", String.format("%.2f%%", latencyImprovement));
    log.info("  P95 Latency: {}{}", p95LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p95LatencyImprovement));
    log.info("  P99 Latency: {}{}", p99LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p99LatencyImprovement));
    log.info("  Memory Usage: {}{}", memoryImprovement > 0 ? "+" : "", String.format("%.2f%%", memoryImprovement));
    
    // Verify that Virtual Threads provide better performance for high concurrency
    if (concurrencyLevel >= 1000) {
      // For high concurrency, Virtual Threads should provide significant improvements
      assertThat("Virtual Threads should provide higher throughput for high concurrency",
          avgVirtualResult.throughput, greaterThan(avgPlatformResult.throughput));
      
      assertThat("Virtual Threads should provide lower latency for high concurrency",
          avgVirtualResult.avgLatency, lessThan(avgPlatformResult.avgLatency));
      
      assertThat("Virtual Threads should use less memory for high concurrency",
          avgVirtualResult.memoryUsageMb, lessThanOrEqualTo(avgPlatformResult.memoryUsageMb));
    }
  }
  
  /**
   * Test read-heavy operations using platform threads vs virtual threads.
   */
  @Test
  public void testReadOperationsPerformance() throws Exception {
    // Prepare test data - insert records that will be read during the test
    prepareTestData(1000);
    
    // Run warmup iterations
    log.info("Running {} warmup iterations with {} concurrent read operations", WARMUP_ITERATIONS, concurrencyLevel);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runReadOperations(createPlatformThreadExecutor(), "Platform Thread Warmup");
      runReadOperations(createVirtualThreadExecutor(), "Virtual Thread Warmup");
    }
    
    // Run test iterations and collect metrics
    log.info("Running {} test iterations with {} concurrent read operations", TEST_ITERATIONS, concurrencyLevel);
    
    List<PerformanceResult> platformResults = new ArrayList<>();
    List<PerformanceResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResults.add(runReadOperations(createPlatformThreadExecutor(), "Platform Thread Test"));
      virtualResults.add(runReadOperations(createVirtualThreadExecutor(), "Virtual Thread Test"));
    }
    
    // Calculate average metrics
    PerformanceResult avgPlatformResult = calculateAverageResult(platformResults);
    PerformanceResult avgVirtualResult = calculateAverageResult(virtualResults);
    
    // Log results
    log.info("\nRead Performance comparison for {} concurrent operations:", concurrencyLevel);
    log.info("Platform Threads - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgPlatformResult.throughput, avgPlatformResult.avgLatency, avgPlatformResult.p95Latency, 
        avgPlatformResult.p99Latency, avgPlatformResult.memoryUsageMb);
    log.info("Virtual Threads  - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgVirtualResult.throughput, avgVirtualResult.avgLatency, avgVirtualResult.p95Latency, 
        avgVirtualResult.p99Latency, avgVirtualResult.memoryUsageMb);
    
    // Calculate improvement percentages
    double throughputImprovement = ((double) avgVirtualResult.throughput / avgPlatformResult.throughput - 1) * 100;
    double latencyImprovement = (1 - (double) avgVirtualResult.avgLatency / avgPlatformResult.avgLatency) * 100;
    double p95LatencyImprovement = (1 - (double) avgVirtualResult.p95Latency / avgPlatformResult.p95Latency) * 100;
    double p99LatencyImprovement = (1 - (double) avgVirtualResult.p99Latency / avgPlatformResult.p99Latency) * 100;
    double memoryImprovement = (1 - (double) avgVirtualResult.memoryUsageMb / avgPlatformResult.memoryUsageMb) * 100;
    
    log.info("Improvements with Virtual Threads for read operations:");
    log.info("  Throughput: {}{}", throughputImprovement > 0 ? "+" : "", String.format("%.2f%%", throughputImprovement));
    log.info("  Avg Latency: {}{}", latencyImprovement > 0 ? "+" : "", String.format("%.2f%%", latencyImprovement));
    log.info("  P95 Latency: {}{}", p95LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p95LatencyImprovement));
    log.info("  P99 Latency: {}{}", p99LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p99LatencyImprovement));
    log.info("  Memory Usage: {}{}", memoryImprovement > 0 ? "+" : "", String.format("%.2f%%", memoryImprovement));
    
    // Verify that Virtual Threads provide better performance for high concurrency read operations
    if (concurrencyLevel >= 1000) {
      assertThat("Virtual Threads should provide higher read throughput for high concurrency",
          avgVirtualResult.throughput, greaterThan(avgPlatformResult.throughput));
      
      assertThat("Virtual Threads should provide lower read latency for high concurrency",
          avgVirtualResult.avgLatency, lessThan(avgPlatformResult.avgLatency));
    }
  }
  
  /**
   * Test write-heavy operations using platform threads vs virtual threads.
   */
  @Test
  public void testWriteOperationsPerformance() throws Exception {
    // Run warmup iterations
    log.info("Running {} warmup iterations with {} concurrent write operations", WARMUP_ITERATIONS, concurrencyLevel);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runWriteOperations(createPlatformThreadExecutor(), "Platform Thread Warmup");
      runWriteOperations(createVirtualThreadExecutor(), "Virtual Thread Warmup");
      
      // Clean up after warmup
      cleanupTestData();
    }
    
    // Run test iterations and collect metrics
    log.info("Running {} test iterations with {} concurrent write operations", TEST_ITERATIONS, concurrencyLevel);
    
    List<PerformanceResult> platformResults = new ArrayList<>();
    List<PerformanceResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResults.add(runWriteOperations(createPlatformThreadExecutor(), "Platform Thread Test"));
      cleanupTestData();
      
      virtualResults.add(runWriteOperations(createVirtualThreadExecutor(), "Virtual Thread Test"));
      cleanupTestData();
    }
    
    // Calculate average metrics
    PerformanceResult avgPlatformResult = calculateAverageResult(platformResults);
    PerformanceResult avgVirtualResult = calculateAverageResult(virtualResults);
    
    // Log results
    log.info("\nWrite Performance comparison for {} concurrent operations:", concurrencyLevel);
    log.info("Platform Threads - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgPlatformResult.throughput, avgPlatformResult.avgLatency, avgPlatformResult.p95Latency, 
        avgPlatformResult.p99Latency, avgPlatformResult.memoryUsageMb);
    log.info("Virtual Threads  - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgVirtualResult.throughput, avgVirtualResult.avgLatency, avgVirtualResult.p95Latency, 
        avgVirtualResult.p99Latency, avgVirtualResult.memoryUsageMb);
    
    // Calculate improvement percentages
    double throughputImprovement = ((double) avgVirtualResult.throughput / avgPlatformResult.throughput - 1) * 100;
    double latencyImprovement = (1 - (double) avgVirtualResult.avgLatency / avgPlatformResult.avgLatency) * 100;
    double p95LatencyImprovement = (1 - (double) avgVirtualResult.p95Latency / avgPlatformResult.p95Latency) * 100;
    double p99LatencyImprovement = (1 - (double) avgVirtualResult.p99Latency / avgPlatformResult.p99Latency) * 100;
    double memoryImprovement = (1 - (double) avgVirtualResult.memoryUsageMb / avgPlatformResult.memoryUsageMb) * 100;
    
    log.info("Improvements with Virtual Threads for write operations:");
    log.info("  Throughput: {}{}", throughputImprovement > 0 ? "+" : "", String.format("%.2f%%", throughputImprovement));
    log.info("  Avg Latency: {}{}", latencyImprovement > 0 ? "+" : "", String.format("%.2f%%", latencyImprovement));
    log.info("  P95 Latency: {}{}", p95LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p95LatencyImprovement));
    log.info("  P99 Latency: {}{}", p99LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p99LatencyImprovement));
    log.info("  Memory Usage: {}{}", memoryImprovement > 0 ? "+" : "", String.format("%.2f%%", memoryImprovement));
    
    // Verify that Virtual Threads provide better performance for high concurrency write operations
    if (concurrencyLevel >= 1000) {
      assertThat("Virtual Threads should provide higher write throughput for high concurrency",
          avgVirtualResult.throughput, greaterThan(avgPlatformResult.throughput));
      
      assertThat("Virtual Threads should provide lower write latency for high concurrency",
          avgVirtualResult.avgLatency, lessThan(avgPlatformResult.avgLatency));
    }
  }
  
  /**
   * Test mixed read/write operations using platform threads vs virtual threads.
   */
  @Test
  public void testMixedOperationsPerformance() throws Exception {
    // Prepare some initial test data
    prepareTestData(500);
    
    // Run warmup iterations
    log.info("Running {} warmup iterations with {} concurrent mixed operations", WARMUP_ITERATIONS, concurrencyLevel);
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runMixedOperations(createPlatformThreadExecutor(), "Platform Thread Warmup");
      runMixedOperations(createVirtualThreadExecutor(), "Virtual Thread Warmup");
      
      // Reset data between runs
      cleanupTestData();
      prepareTestData(500);
    }
    
    // Run test iterations and collect metrics
    log.info("Running {} test iterations with {} concurrent mixed operations", TEST_ITERATIONS, concurrencyLevel);
    
    List<PerformanceResult> platformResults = new ArrayList<>();
    List<PerformanceResult> virtualResults = new ArrayList<>();
    
    for (int i = 0; i < TEST_ITERATIONS; i++) {
      platformResults.add(runMixedOperations(createPlatformThreadExecutor(), "Platform Thread Test"));
      
      // Reset data between runs
      cleanupTestData();
      prepareTestData(500);
      
      virtualResults.add(runMixedOperations(createVirtualThreadExecutor(), "Virtual Thread Test"));
      
      // Reset data between runs
      cleanupTestData();
      prepareTestData(500);
    }
    
    // Calculate average metrics
    PerformanceResult avgPlatformResult = calculateAverageResult(platformResults);
    PerformanceResult avgVirtualResult = calculateAverageResult(virtualResults);
    
    // Log results
    log.info("\nMixed Operations Performance comparison for {} concurrent operations:", concurrencyLevel);
    log.info("Platform Threads - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgPlatformResult.throughput, avgPlatformResult.avgLatency, avgPlatformResult.p95Latency, 
        avgPlatformResult.p99Latency, avgPlatformResult.memoryUsageMb);
    log.info("Virtual Threads  - Throughput: {}/s, Avg Latency: {} ms, P95 Latency: {} ms, P99 Latency: {} ms, Memory: {} MB",
        avgVirtualResult.throughput, avgVirtualResult.avgLatency, avgVirtualResult.p95Latency, 
        avgVirtualResult.p99Latency, avgVirtualResult.memoryUsageMb);
    
    // Calculate improvement percentages
    double throughputImprovement = ((double) avgVirtualResult.throughput / avgPlatformResult.throughput - 1) * 100;
    double latencyImprovement = (1 - (double) avgVirtualResult.avgLatency / avgPlatformResult.avgLatency) * 100;
    double p95LatencyImprovement = (1 - (double) avgVirtualResult.p95Latency / avgPlatformResult.p95Latency) * 100;
    double p99LatencyImprovement = (1 - (double) avgVirtualResult.p99Latency / avgPlatformResult.p99Latency) * 100;
    double memoryImprovement = (1 - (double) avgVirtualResult.memoryUsageMb / avgPlatformResult.memoryUsageMb) * 100;
    
    log.info("Improvements with Virtual Threads for mixed operations:");
    log.info("  Throughput: {}{}", throughputImprovement > 0 ? "+" : "", String.format("%.2f%%", throughputImprovement));
    log.info("  Avg Latency: {}{}", latencyImprovement > 0 ? "+" : "", String.format("%.2f%%", latencyImprovement));
    log.info("  P95 Latency: {}{}", p95LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p95LatencyImprovement));
    log.info("  P99 Latency: {}{}", p99LatencyImprovement > 0 ? "+" : "", String.format("%.2f%%", p99LatencyImprovement));
    log.info("  Memory Usage: {}{}", memoryImprovement > 0 ? "+" : "", String.format("%.2f%%", memoryImprovement));
    
    // Verify that Virtual Threads provide better performance for high concurrency mixed operations
    if (concurrencyLevel >= 1000) {
      assertThat("Virtual Threads should provide higher mixed operation throughput for high concurrency",
          avgVirtualResult.throughput, greaterThan(avgPlatformResult.throughput));
      
      assertThat("Virtual Threads should provide lower mixed operation latency for high concurrency",
          avgVirtualResult.avgLatency, lessThan(avgPlatformResult.avgLatency));
    }
  }
  
  /**
   * Create a platform thread executor with a fixed thread pool.
   */
  private ExecutorService createPlatformThreadExecutor() {
    return Executors.newFixedThreadPool(Math.min(concurrencyLevel, 200), new ThreadFactory() {
      private final AtomicInteger counter = new AtomicInteger();
      
      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName("platform-thread-" + counter.incrementAndGet());
        return t;
      }
    });
  }
  
  /**
   * Create a virtual thread executor using Java 21's virtual thread per task executor.
   */
  private ExecutorService createVirtualThreadExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
  
  /**
   * Run CRUD operations using the provided executor service.
   */
  private PerformanceResult runCrudOperations(ExecutorService executor, String testName) throws Exception {
    log.info("Running CRUD operations with {} ({})", testName, concurrencyLevel);
    
    // Prepare latency tracking
    ConcurrentHashMap<String, Long> operationLatencies = new ConcurrentHashMap<>();
    CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
    
    // Record start metrics
    long startMemory = getUsedMemoryMb();
    Instant startTime = Instant.now();
    
    // Submit tasks
    for (int i = 0; i < concurrencyLevel; i++) {
      executor.submit(() -> {
        try {
          String id = UUID.randomUUID().toString();
          Instant opStart = Instant.now();
          
          // Create
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(INSERT_TEST_RECORD)) {
            stmt.setString(1, id);
            stmt.setString(2, "Test Name " + id);
            stmt.setString(3, "Test Value " + id);
            stmt.executeUpdate();
          }
          
          // Read
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(SELECT_TEST_RECORD)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
              assertThat(rs.next(), is(true));
              assertThat(rs.getString("id"), is(id));
              assertThat(rs.getString("name"), is("Test Name " + id));
              assertThat(rs.getString("value"), is("Test Value " + id));
              assertThat(rs.getTimestamp("created_at"), is(notNullValue()));
            }
          }
          
          // Update
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(UPDATE_TEST_RECORD)) {
            stmt.setString(1, "Updated Value " + id);
            stmt.setString(2, id);
            stmt.executeUpdate();
          }
          
          // Verify update
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(SELECT_TEST_RECORD)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
              assertThat(rs.next(), is(true));
              assertThat(rs.getString("value"), is("Updated Value " + id));
            }
          }
          
          // Delete
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(DELETE_TEST_RECORD)) {
            stmt.setString(1, id);
            stmt.executeUpdate();
          }
          
          // Record operation latency
          long latencyMs = Duration.between(opStart, Instant.now()).toMillis();
          operationLatencies.put(id, latencyMs);
          
        } catch (Exception e) {
          log.error("Error in CRUD operation", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for completion with timeout
    boolean completed = completionLatch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
    Instant endTime = Instant.now();
    long endMemory = getUsedMemoryMb();
    
    // Shutdown executor
    executor.shutdown();
    executor.awaitTermination(5, SECONDS);
    
    // Calculate metrics
    if (!completed) {
      log.warn("Test did not complete within timeout period of {} seconds", OPERATION_TIMEOUT_SECONDS);
    }
    
    long durationMs = Duration.between(startTime, endTime).toMillis();
    long operationsCompleted = concurrencyLevel - completionLatch.getCount();
    long throughput = operationsCompleted * 1000 / Math.max(durationMs, 1);
    
    // Calculate latency statistics
    List<Long> latencies = new ArrayList<>(operationLatencies.values());
    LongSummaryStatistics latencyStats = latencies.stream().collect(Collectors.summarizingLong(Long::longValue));
    
    // Calculate percentiles
    long p95Latency = calculatePercentile(latencies, 95);
    long p99Latency = calculatePercentile(latencies, 99);
    
    // Create result
    return new PerformanceResult(
        throughput,
        (long) latencyStats.getAverage(),
        p95Latency,
        p99Latency,
        endMemory - startMemory
    );
  }
  
  /**
   * Run read operations using the provided executor service.
   */
  private PerformanceResult runReadOperations(ExecutorService executor, String testName) throws Exception {
    log.info("Running read operations with {} ({})", testName, concurrencyLevel);
    
    // Get list of existing IDs
    List<String> existingIds = getExistingIds();
    if (existingIds.isEmpty()) {
      throw new IllegalStateException("No test data available for read operations");
    }
    
    // Prepare latency tracking
    ConcurrentHashMap<String, Long> operationLatencies = new ConcurrentHashMap<>();
    CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
    
    // Record start metrics
    long startMemory = getUsedMemoryMb();
    Instant startTime = Instant.now();
    
    // Submit tasks
    for (int i = 0; i < concurrencyLevel; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Select a random ID from existing data
          String id = existingIds.get(index % existingIds.size());
          Instant opStart = Instant.now();
          
          // Read operation
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(SELECT_TEST_RECORD)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
              assertThat(rs.next(), is(true));
              assertThat(rs.getString("id"), is(id));
              assertThat(rs.getString("name"), is(notNullValue()));
              assertThat(rs.getString("value"), is(notNullValue()));
              assertThat(rs.getTimestamp("created_at"), is(notNullValue()));
            }
          }
          
          // Record operation latency
          long latencyMs = Duration.between(opStart, Instant.now()).toMillis();
          operationLatencies.put(id + "-" + index, latencyMs);
          
        } catch (Exception e) {
          log.error("Error in read operation", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for completion with timeout
    boolean completed = completionLatch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
    Instant endTime = Instant.now();
    long endMemory = getUsedMemoryMb();
    
    // Shutdown executor
    executor.shutdown();
    executor.awaitTermination(5, SECONDS);
    
    // Calculate metrics
    if (!completed) {
      log.warn("Test did not complete within timeout period of {} seconds", OPERATION_TIMEOUT_SECONDS);
    }
    
    long durationMs = Duration.between(startTime, endTime).toMillis();
    long operationsCompleted = concurrencyLevel - completionLatch.getCount();
    long throughput = operationsCompleted * 1000 / Math.max(durationMs, 1);
    
    // Calculate latency statistics
    List<Long> latencies = new ArrayList<>(operationLatencies.values());
    LongSummaryStatistics latencyStats = latencies.stream().collect(Collectors.summarizingLong(Long::longValue));
    
    // Calculate percentiles
    long p95Latency = calculatePercentile(latencies, 95);
    long p99Latency = calculatePercentile(latencies, 99);
    
    // Create result
    return new PerformanceResult(
        throughput,
        (long) latencyStats.getAverage(),
        p95Latency,
        p99Latency,
        endMemory - startMemory
    );
  }
  
  /**
   * Run write operations using the provided executor service.
   */
  private PerformanceResult runWriteOperations(ExecutorService executor, String testName) throws Exception {
    log.info("Running write operations with {} ({})", testName, concurrencyLevel);
    
    // Prepare latency tracking
    ConcurrentHashMap<String, Long> operationLatencies = new ConcurrentHashMap<>();
    CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
    
    // Record start metrics
    long startMemory = getUsedMemoryMb();
    Instant startTime = Instant.now();
    
    // Submit tasks
    for (int i = 0; i < concurrencyLevel; i++) {
      executor.submit(() -> {
        try {
          String id = UUID.randomUUID().toString();
          Instant opStart = Instant.now();
          
          // Write operation (insert)
          try (Connection conn = dataSource.getConnection();
               PreparedStatement stmt = conn.prepareStatement(INSERT_TEST_RECORD)) {
            stmt.setString(1, id);
            stmt.setString(2, "Test Name " + id);
            stmt.setString(3, "Test Value " + id);
            stmt.executeUpdate();
          }
          
          // Record operation latency
          long latencyMs = Duration.between(opStart, Instant.now()).toMillis();
          operationLatencies.put(id, latencyMs);
          
        } catch (Exception e) {
          log.error("Error in write operation", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for completion with timeout
    boolean completed = completionLatch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
    Instant endTime = Instant.now();
    long endMemory = getUsedMemoryMb();
    
    // Shutdown executor
    executor.shutdown();
    executor.awaitTermination(5, SECONDS);
    
    // Calculate metrics
    if (!completed) {
      log.warn("Test did not complete within timeout period of {} seconds", OPERATION_TIMEOUT_SECONDS);
    }
    
    long durationMs = Duration.between(startTime, endTime).toMillis();
    long operationsCompleted = concurrencyLevel - completionLatch.getCount();
    long throughput = operationsCompleted * 1000 / Math.max(durationMs, 1);
    
    // Calculate latency statistics
    List<Long> latencies = new ArrayList<>(operationLatencies.values());
    LongSummaryStatistics latencyStats = latencies.stream().collect(Collectors.summarizingLong(Long::longValue));
    
    // Calculate percentiles
    long p95Latency = calculatePercentile(latencies, 95);
    long p99Latency = calculatePercentile(latencies, 99);
    
    // Create result
    return new PerformanceResult(
        throughput,
        (long) latencyStats.getAverage(),
        p95Latency,
        p99Latency,
        endMemory - startMemory
    );
  }
  
  /**
   * Run mixed read/write operations using the provided executor service.
   */
  private PerformanceResult runMixedOperations(ExecutorService executor, String testName) throws Exception {
    log.info("Running mixed operations with {} ({})", testName, concurrencyLevel);
    
    // Get list of existing IDs
    List<String> existingIds = getExistingIds();
    if (existingIds.isEmpty()) {
      throw new IllegalStateException("No test data available for mixed operations");
    }
    
    // Prepare latency tracking
    ConcurrentHashMap<String, Long> operationLatencies = new ConcurrentHashMap<>();
    CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
    
    // Record start metrics
    long startMemory = getUsedMemoryMb();
    Instant startTime = Instant.now();
    
    // Submit tasks
    for (int i = 0; i < concurrencyLevel; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          Instant opStart = Instant.now();
          String operationId = UUID.randomUUID().toString();
          
          // Determine operation type: 70% read, 20% write, 10% update
          int operationType = ThreadLocalRandom.current().nextInt(10);
          
          if (operationType < 7) {
            // Read operation (70%)
            String id = existingIds.get(index % existingIds.size());
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(SELECT_TEST_RECORD)) {
              stmt.setString(1, id);
              try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                  // Just read the data
                  rs.getString("name");
                  rs.getString("value");
                  rs.getTimestamp("created_at");
                }
              }
            }
          } else if (operationType < 9) {
            // Write operation (20%)
            String id = UUID.randomUUID().toString();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(INSERT_TEST_RECORD)) {
              stmt.setString(1, id);
              stmt.setString(2, "Mixed Test Name " + id);
              stmt.setString(3, "Mixed Test Value " + id);
              stmt.executeUpdate();
            }
          } else {
            // Update operation (10%)
            String id = existingIds.get(index % existingIds.size());
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(UPDATE_TEST_RECORD)) {
              stmt.setString(1, "Updated in mixed test " + operationId);
              stmt.setString(2, id);
              stmt.executeUpdate();
            }
          }
          
          // Record operation latency
          long latencyMs = Duration.between(opStart, Instant.now()).toMillis();
          operationLatencies.put(operationId, latencyMs);
          
        } catch (Exception e) {
          log.error("Error in mixed operation", e);
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Wait for completion with timeout
    boolean completed = completionLatch.await(OPERATION_TIMEOUT_SECONDS, SECONDS);
    Instant endTime = Instant.now();
    long endMemory = getUsedMemoryMb();
    
    // Shutdown executor
    executor.shutdown();
    executor.awaitTermination(5, SECONDS);
    
    // Calculate metrics
    if (!completed) {
      log.warn("Test did not complete within timeout period of {} seconds", OPERATION_TIMEOUT_SECONDS);
    }
    
    long durationMs = Duration.between(startTime, endTime).toMillis();
    long operationsCompleted = concurrencyLevel - completionLatch.getCount();
    long throughput = operationsCompleted * 1000 / Math.max(durationMs, 1);
    
    // Calculate latency statistics
    List<Long> latencies = new ArrayList<>(operationLatencies.values());
    LongSummaryStatistics latencyStats = latencies.stream().collect(Collectors.summarizingLong(Long::longValue));
    
    // Calculate percentiles
    long p95Latency = calculatePercentile(latencies, 95);
    long p99Latency = calculatePercentile(latencies, 99);
    
    // Create result
    return new PerformanceResult(
        throughput,
        (long) latencyStats.getAverage(),
        p95Latency,
        p99Latency,
        endMemory - startMemory
    );
  }
  
  /**
   * Prepare test data by inserting records.
   */
  private void prepareTestData(int count) throws SQLException {
    log.info("Preparing {} test records", count);
    
    try (Connection conn = dataSource.getConnection();
         PreparedStatement stmt = conn.prepareStatement(INSERT_TEST_RECORD)) {
      
      for (int i = 0; i < count; i++) {
        String id = "test-" + i;
        stmt.setString(1, id);
        stmt.setString(2, "Test Name " + id);
        stmt.setString(3, "Test Value " + id);
        stmt.addBatch();
        
        if (i % 100 == 0) {
          stmt.executeBatch();
        }
      }
      
      stmt.executeBatch();
    }
  }
  
  /**
   * Clean up test data by deleting all records.
   */
  private void cleanupTestData() throws SQLException {
    log.info("Cleaning up test data");
    
    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement()) {
      stmt.execute("DELETE FROM performance_test");
    }
  }
  
  /**
   * Get a list of existing record IDs.
   */
  private List<String> getExistingIds() throws SQLException {
    List<String> ids = new ArrayList<>();
    
    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT id FROM performance_test")) {
      
      while (rs.next()) {
        ids.add(rs.getString("id"));
      }
    }
    
    return ids;
  }
  
  /**
   * Calculate the used memory in MB.
   */
  private long getUsedMemoryMb() {
    System.gc(); // Request garbage collection to get more accurate memory usage
    Runtime runtime = Runtime.getRuntime();
    return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
  }
  
  /**
   * Calculate the nth percentile from a list of values.
   */
  private long calculatePercentile(List<Long> values, int percentile) {
    if (values.isEmpty()) {
      return 0;
    }
    
    List<Long> sortedValues = new ArrayList<>(values);
    sortedValues.sort(Long::compare);
    
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
  }
  
  /**
   * Calculate the average performance result from multiple test runs.
   */
  private PerformanceResult calculateAverageResult(List<PerformanceResult> results) {
    if (results.isEmpty()) {
      return new PerformanceResult(0, 0, 0, 0, 0);
    }
    
    long totalThroughput = 0;
    long totalAvgLatency = 0;
    long totalP95Latency = 0;
    long totalP99Latency = 0;
    long totalMemoryUsage = 0;
    
    for (PerformanceResult result : results) {
      totalThroughput += result.throughput;
      totalAvgLatency += result.avgLatency;
      totalP95Latency += result.p95Latency;
      totalP99Latency += result.p99Latency;
      totalMemoryUsage += result.memoryUsageMb;
    }
    
    return new PerformanceResult(
        totalThroughput / results.size(),
        totalAvgLatency / results.size(),
        totalP95Latency / results.size(),
        totalP99Latency / results.size(),
        totalMemoryUsage / results.size()
    );
  }
  
  /**
   * Class to hold performance test results.
   */
  private static class PerformanceResult {
    final long throughput;       // Operations per second
    final long avgLatency;       // Average latency in ms
    final long p95Latency;       // 95th percentile latency in ms
    final long p99Latency;       // 99th percentile latency in ms
    final long memoryUsageMb;    // Memory usage in MB
    
    PerformanceResult(long throughput, long avgLatency, long p95Latency, long p99Latency, long memoryUsageMb) {
      this.throughput = throughput;
      this.avgLatency = avgLatency;
      this.p95Latency = p95Latency;
      this.p99Latency = p99Latency;
      this.memoryUsageMb = memoryUsageMb;
    }
  }
}