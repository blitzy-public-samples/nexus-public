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
package org.sonatype.nexus.datastore.virtualthread;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.sonatype.nexus.common.app.ApplicationVersion;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.mybatis.MyBatisDataStore;
import org.sonatype.nexus.testcommon.validation.VirtualThreadTestGroup;

import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Performance test comparing MyBatis SQL sessions under platform threads versus Java 21 Virtual Threads.
 * 
 * This test validates that virtual threads provide better scalability and resource efficiency 
 * for I/O-bound database operations compared to platform threads.
 */
@Tag("virtual-threads")
public class SqlSessionVirtualThreadPerformanceTest
    extends TestSupport
{
  private static final String TEST_TABLE = "PERFORMANCE_TEST";
  private static final int LOW_CONCURRENCY = 10;
  private static final int MEDIUM_CONCURRENCY = 100;
  private static final int HIGH_CONCURRENCY = 1000;
  private static final int VERY_HIGH_CONCURRENCY = 5000;
  private static final int OPERATIONS_PER_THREAD = 10;
  private static final int WARMUP_ITERATIONS = 3;
  
  private DataSource dataSource;
  private SqlSessionFactory sqlSessionFactory;
  private MyBatisDataStore dataStore;
  
  @BeforeEach
  void setUp() throws Exception {
    // Set up an in-memory H2 database for testing
    JdbcDataSource jdbcDataSource = new JdbcDataSource();
    jdbcDataSource.setURL("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1");
    jdbcDataSource.setUser("sa");
    jdbcDataSource.setPassword("");
    this.dataSource = jdbcDataSource;
    
    // Create test table
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute(
          "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " ("
          + "id INT AUTO_INCREMENT PRIMARY KEY, "
          + "name VARCHAR(255), "
          + "value INT)");
    }
    
    // Set up MyBatis DataStore
    ApplicationVersion appVersion = mock(ApplicationVersion.class);
    when(appVersion.getVersion()).thenReturn("1.0.0");
    
    DataStore<?> ds = new MyBatisDataStore("test", dataSource, appVersion);
    this.dataStore = (MyBatisDataStore) ds;
    this.sqlSessionFactory = dataStore.getSqlSessionFactory();
    
    // Populate test data
    populateTestData(100);
    
    // Warm up to avoid JIT compilation affecting results
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runWithPlatformThreads(10, this::performQueryOperation);
      runWithVirtualThreads(10, this::performQueryOperation);
    }
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Clean up test table
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute("DROP TABLE IF EXISTS " + TEST_TABLE);
    }
  }
  
  /**
   * Tests query performance with low concurrency level.
   */
  @Test
  @DisplayName("Query performance with low concurrency")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testQueryPerformanceWithLowConcurrency() throws Exception {
    PerformanceResult platformResult = runWithPlatformThreads(LOW_CONCURRENCY, this::performQueryOperation);
    PerformanceResult virtualResult = runWithVirtualThreads(LOW_CONCURRENCY, this::performQueryOperation);
    
    log.info("Platform threads - Low concurrency: {} ops/sec, avg latency: {} ms", 
        platformResult.getThroughput(), platformResult.getAverageLatency());
    log.info("Virtual threads - Low concurrency: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // At low concurrency, performance should be similar
    assertThat("Virtual threads should have comparable or better throughput at low concurrency",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 0.9));
  }
  
  /**
   * Tests query performance with medium concurrency level.
   */
  @Test
  @DisplayName("Query performance with medium concurrency")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testQueryPerformanceWithMediumConcurrency() throws Exception {
    PerformanceResult platformResult = runWithPlatformThreads(MEDIUM_CONCURRENCY, this::performQueryOperation);
    PerformanceResult virtualResult = runWithVirtualThreads(MEDIUM_CONCURRENCY, this::performQueryOperation);
    
    log.info("Platform threads - Medium concurrency: {} ops/sec, avg latency: {} ms", 
        platformResult.getThroughput(), platformResult.getAverageLatency());
    log.info("Virtual threads - Medium concurrency: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // At medium concurrency, virtual threads should start showing benefits
    assertThat("Virtual threads should have better throughput at medium concurrency",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.1));
  }
  
  /**
   * Tests query performance with high concurrency level.
   */
  @Test
  @DisplayName("Query performance with high concurrency")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testQueryPerformanceWithHighConcurrency() throws Exception {
    PerformanceResult platformResult = runWithPlatformThreads(HIGH_CONCURRENCY, this::performQueryOperation);
    PerformanceResult virtualResult = runWithVirtualThreads(HIGH_CONCURRENCY, this::performQueryOperation);
    
    log.info("Platform threads - High concurrency: {} ops/sec, avg latency: {} ms", 
        platformResult.getThroughput(), platformResult.getAverageLatency());
    log.info("Virtual threads - High concurrency: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // At high concurrency, virtual threads should show significant benefits
    assertThat("Virtual threads should have significantly better throughput at high concurrency",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.3));
    assertThat("Virtual threads should have lower latency at high concurrency",
        virtualResult.getAverageLatency(), lessThan(platformResult.getAverageLatency() * 0.8));
  }
  
  /**
   * Tests query performance with very high concurrency level.
   * This test demonstrates the scalability advantage of virtual threads.
   */
  @Test
  @DisplayName("Query performance with very high concurrency")
  @Timeout(value = 120, unit = TimeUnit.SECONDS)
  void testQueryPerformanceWithVeryHighConcurrency() throws Exception {
    PerformanceResult virtualResult = runWithVirtualThreads(VERY_HIGH_CONCURRENCY, this::performQueryOperation);
    
    log.info("Virtual threads - Very high concurrency: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // Just verify that virtual threads can handle this level of concurrency
    assertThat("Virtual threads should maintain throughput at very high concurrency",
        virtualResult.getThroughput(), greaterThan(0.0));
    assertThat("Virtual threads should have reasonable latency at very high concurrency",
        virtualResult.getAverageLatency(), lessThan(5000.0));
  }
  
  /**
   * Tests update performance with medium concurrency level.
   */
  @Test
  @DisplayName("Update performance with medium concurrency")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testUpdatePerformanceWithMediumConcurrency() throws Exception {
    PerformanceResult platformResult = runWithPlatformThreads(MEDIUM_CONCURRENCY, this::performUpdateOperation);
    PerformanceResult virtualResult = runWithVirtualThreads(MEDIUM_CONCURRENCY, this::performUpdateOperation);
    
    log.info("Platform threads - Update with medium concurrency: {} ops/sec, avg latency: {} ms", 
        platformResult.getThroughput(), platformResult.getAverageLatency());
    log.info("Virtual threads - Update with medium concurrency: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // For updates, virtual threads should also show benefits
    assertThat("Virtual threads should have better update throughput",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.1));
  }
  
  /**
   * Tests batch operation performance with medium concurrency level.
   */
  @Test
  @DisplayName("Batch operation performance with medium concurrency")
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testBatchOperationPerformance() throws Exception {
    PerformanceResult platformResult = runWithPlatformThreads(MEDIUM_CONCURRENCY, this::performBatchOperation);
    PerformanceResult virtualResult = runWithVirtualThreads(MEDIUM_CONCURRENCY, this::performBatchOperation);
    
    log.info("Platform threads - Batch operations: {} ops/sec, avg latency: {} ms", 
        platformResult.getThroughput(), platformResult.getAverageLatency());
    log.info("Virtual threads - Batch operations: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // For batch operations, virtual threads should also show benefits
    assertThat("Virtual threads should have better batch operation throughput",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.05));
  }
  
  /**
   * Tests mixed workload performance with high concurrency level.
   */
  @Test
  @DisplayName("Mixed workload performance with high concurrency")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testMixedWorkloadPerformance() throws Exception {
    PerformanceResult platformResult = runWithPlatformThreads(HIGH_CONCURRENCY, this::performMixedOperation);
    PerformanceResult virtualResult = runWithVirtualThreads(HIGH_CONCURRENCY, this::performMixedOperation);
    
    log.info("Platform threads - Mixed workload: {} ops/sec, avg latency: {} ms", 
        platformResult.getThroughput(), platformResult.getAverageLatency());
    log.info("Virtual threads - Mixed workload: {} ops/sec, avg latency: {} ms", 
        virtualResult.getThroughput(), virtualResult.getAverageLatency());
    
    // For mixed workloads, virtual threads should show significant benefits
    assertThat("Virtual threads should have better mixed workload throughput",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.2));
    assertThat("Virtual threads should have lower mixed workload latency",
        virtualResult.getAverageLatency(), lessThan(platformResult.getAverageLatency() * 0.8));
  }
  
  /**
   * Tests memory efficiency of virtual threads vs platform threads.
   */
  @Test
  @DisplayName("Memory efficiency of virtual threads vs platform threads")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testMemoryEfficiency() throws Exception {
    // Force GC to get a clean baseline
    System.gc();
    long beforePlatformMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Run with platform threads and measure memory
    runWithPlatformThreads(HIGH_CONCURRENCY, this::performQueryOperation);
    System.gc();
    long afterPlatformMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long platformMemUsage = afterPlatformMem - beforePlatformMem;
    
    // Force GC again to get a clean baseline
    System.gc();
    long beforeVirtualMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Run with virtual threads and measure memory
    runWithVirtualThreads(HIGH_CONCURRENCY, this::performQueryOperation);
    System.gc();
    long afterVirtualMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    long virtualMemUsage = afterVirtualMem - beforeVirtualMem;
    
    log.info("Platform threads memory usage: {} bytes", platformMemUsage);
    log.info("Virtual threads memory usage: {} bytes", virtualMemUsage);
    
    // Virtual threads should use less memory per thread
    assertThat("Virtual threads should use less memory than platform threads",
        virtualMemUsage, lessThan(platformMemUsage));
  }
  
  /**
   * Tests ACID compliance under high virtual thread load.
   */
  @Test
  @DisplayName("ACID compliance under high virtual thread load")
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void testAcidComplianceUnderLoad() throws Exception {
    // Initial value
    final int initialValue = 1000;
    final int expectedFinalValue = initialValue + HIGH_CONCURRENCY;
    
    // Set up a single record with initial value
    try (Connection conn = dataSource.getConnection()) {
      conn.createStatement().execute("DELETE FROM " + TEST_TABLE);
      PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO " + TEST_TABLE + " (id, name, value) VALUES (1, 'test', ?)");
      ps.setInt(1, initialValue);
      ps.executeUpdate();
      conn.commit();
    }
    
    // Run concurrent increment operations with virtual threads
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch latch = new CountDownLatch(HIGH_CONCURRENCY);
      
      for (int i = 0; i < HIGH_CONCURRENCY; i++) {
        executor.submit(() -> {
          try {
            incrementValueWithTransaction();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      latch.await();
    }
    
    // Verify final value
    int finalValue;
    try (Connection conn = dataSource.getConnection();
         ResultSet rs = conn.createStatement().executeQuery(
             "SELECT value FROM " + TEST_TABLE + " WHERE id = 1")) {
      rs.next();
      finalValue = rs.getInt(1);
    }
    
    log.info("Initial value: {}, Expected final value: {}, Actual final value: {}", 
        initialValue, expectedFinalValue, finalValue);
    
    // If ACID properties are maintained, the final value should match expected
    assertThat("ACID properties should be maintained under high virtual thread load",
        finalValue, is(expectedFinalValue));
  }
  
  /**
   * Runs a performance test with platform threads.
   *
   * @param concurrency the number of concurrent threads
   * @param operation the database operation to perform
   * @return performance metrics
   */
  private PerformanceResult runWithPlatformThreads(int concurrency, Runnable operation) throws Exception {
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    return runPerformanceTest(concurrency, operation, 
        () -> Executors.newFixedThreadPool(concurrency, platformThreadFactory));
  }
  
  /**
   * Runs a performance test with virtual threads.
   *
   * @param concurrency the number of concurrent threads
   * @param operation the database operation to perform
   * @return performance metrics
   */
  private PerformanceResult runWithVirtualThreads(int concurrency, Runnable operation) throws Exception {
    return runPerformanceTest(concurrency, operation, Executors::newVirtualThreadPerTaskExecutor);
  }
  
  /**
   * Runs a performance test with the given executor and operation.
   *
   * @param concurrency the number of concurrent threads
   * @param operation the database operation to perform
   * @param executorSupplier supplier for the executor service
   * @return performance metrics
   */
  private PerformanceResult runPerformanceTest(
      int concurrency, 
      Runnable operation,
      Supplier<ExecutorService> executorSupplier) throws Exception {
    
    AtomicInteger completedOperations = new AtomicInteger(0);
    AtomicLong totalLatency = new AtomicLong(0);
    ConcurrentHashMap<Integer, Long> latencies = new ConcurrentHashMap<>();
    CountDownLatch latch = new CountDownLatch(concurrency);
    
    long startTime = System.currentTimeMillis();
    
    try (ExecutorService executor = executorSupplier.get()) {
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < concurrency; i++) {
        final int threadId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            for (int j = 0; j < OPERATIONS_PER_THREAD; j++) {
              long opStart = System.nanoTime();
              operation.run();
              long opEnd = System.nanoTime();
              long latency = TimeUnit.NANOSECONDS.toMillis(opEnd - opStart);
              
              totalLatency.addAndGet(latency);
              latencies.put(completedOperations.incrementAndGet(), latency);
            }
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all operations to complete
      latch.await();
      
      // Wait for all futures to complete (should be done already due to latch)
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
    
    long endTime = System.currentTimeMillis();
    long duration = endTime - startTime;
    
    // Calculate metrics
    int totalOps = completedOperations.get();
    double throughput = (totalOps * 1000.0) / duration; // ops/sec
    double avgLatency = totalLatency.get() / (double) totalOps;
    
    // Calculate percentiles
    List<Long> sortedLatencies = new ArrayList<>(latencies.values());
    sortedLatencies.sort(Long::compareTo);
    
    long p50 = percentile(sortedLatencies, 50);
    long p95 = percentile(sortedLatencies, 95);
    long p99 = percentile(sortedLatencies, 99);
    
    return new PerformanceResult(throughput, avgLatency, p50, p95, p99, duration);
  }
  
  /**
   * Calculates the specified percentile from a sorted list of values.
   *
   * @param sortedValues the sorted list of values
   * @param percentile the percentile to calculate (0-100)
   * @return the percentile value
   */
  private long percentile(List<Long> sortedValues, int percentile) {
    if (sortedValues.isEmpty()) {
      return 0;
    }
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    return sortedValues.get(Math.max(0, Math.min(sortedValues.size() - 1, index)));
  }
  
  /**
   * Populates the test table with sample data.
   *
   * @param count the number of records to insert
   */
  private void populateTestData(int count) throws SQLException {
    try (Connection conn = dataSource.getConnection()) {
      conn.setAutoCommit(false);
      PreparedStatement ps = conn.prepareStatement(
          "INSERT INTO " + TEST_TABLE + " (name, value) VALUES (?, ?)");
      
      for (int i = 0; i < count; i++) {
        ps.setString(1, "test-" + i);
        ps.setInt(2, i);
        ps.addBatch();
        
        if (i % 100 == 0) {
          ps.executeBatch();
        }
      }
      
      ps.executeBatch();
      conn.commit();
    }
  }
  
  /**
   * Performs a query operation using MyBatis SqlSession.
   */
  private void performQueryOperation() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      List<Object> results = session.selectList("SELECT * FROM " + TEST_TABLE + " LIMIT 10");
      assertThat(results, notNullValue());
    }
  }
  
  /**
   * Performs an update operation using MyBatis SqlSession.
   */
  private void performUpdateOperation() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      int id = (int) (Math.random() * 100) + 1;
      int value = (int) (Math.random() * 1000);
      
      int updated = session.update("UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?", 
          new Object[] { value, id });
      
      session.commit();
    }
  }
  
  /**
   * Performs a batch operation using MyBatis SqlSession.
   */
  private void performBatchOperation() {
    try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
      for (int i = 0; i < 5; i++) {
        int id = (int) (Math.random() * 100) + 1;
        int value = (int) (Math.random() * 1000);
        
        session.update("UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?", 
            new Object[] { value, id });
      }
      
      session.commit();
    }
  }
  
  /**
   * Performs a mixed operation (query + update) using MyBatis SqlSession.
   */
  private void performMixedOperation() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      // First query
      List<Object> results = session.selectList("SELECT * FROM " + TEST_TABLE + " LIMIT 5");
      assertThat(results, notNullValue());
      
      // Then update based on query results
      if (!results.isEmpty()) {
        int id = (int) (Math.random() * 100) + 1;
        int value = (int) (Math.random() * 1000);
        
        session.update("UPDATE " + TEST_TABLE + " SET value = ? WHERE id = ?", 
            new Object[] { value, id });
      }
      
      session.commit();
    }
  }
  
  /**
   * Increments a value in the database with proper transaction handling.
   */
  private void incrementValueWithTransaction() {
    try (SqlSession session = sqlSessionFactory.openSession()) {
      try {
        // Read current value
        Integer currentValue = session.selectOne("SELECT value FROM " + TEST_TABLE + " WHERE id = 1");
        
        // Increment value
        int newValue = currentValue + 1;
        
        // Update with new value
        session.update("UPDATE " + TEST_TABLE + " SET value = ? WHERE id = 1", newValue);
        
        // Commit transaction
        session.commit();
      } 
      catch (Exception e) {
        session.rollback();
        throw e;
      }
    }
  }
  
  /**
   * Class to hold performance test results.
   */
  private static class PerformanceResult {
    private final double throughput; // ops/sec
    private final double averageLatency; // ms
    private final long p50Latency; // ms
    private final long p95Latency; // ms
    private final long p99Latency; // ms
    private final long duration; // ms
    
    public PerformanceResult(double throughput, double averageLatency, 
                            long p50Latency, long p95Latency, long p99Latency, long duration) {
      this.throughput = throughput;
      this.averageLatency = averageLatency;
      this.p50Latency = p50Latency;
      this.p95Latency = p95Latency;
      this.p99Latency = p99Latency;
      this.duration = duration;
    }
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getAverageLatency() {
      return averageLatency;
    }
    
    public long getP50Latency() {
      return p50Latency;
    }
    
    public long getP95Latency() {
      return p95Latency;
    }
    
    public long getP99Latency() {
      return p99Latency;
    }
    
    public long getDuration() {
      return duration;
    }
  }
}