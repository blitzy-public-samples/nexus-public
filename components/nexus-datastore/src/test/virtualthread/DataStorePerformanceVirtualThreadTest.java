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
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.sql.DataSource;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreConfiguration;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.datastore.api.DataStoreNotFoundException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.when;

/**
 * Performance comparison test for datastore operations using platform threads versus Virtual Threads.
 * This class conducts controlled benchmarks to measure throughput, latency, and resource utilization
 * differences between the two threading models.
 */
@ExtendWith(MockitoExtension.class)
public class DataStorePerformanceVirtualThreadTest
    extends TestSupport
{
  private static final String STORE_NAME = "test-performance";
  private static final String TEST_TABLE = "performance_test";
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  
  // Performance thresholds for Virtual Threads
  private static final int MAX_CONCURRENT_CONNECTIONS_PLATFORM = 1000;
  private static final int MAX_CONCURRENT_CONNECTIONS_VIRTUAL = 10000;
  private static final double P95_RESPONSE_TIME_THRESHOLD_VIRTUAL = 350.0; // ms
  private static final double P99_RESPONSE_TIME_THRESHOLD_VIRTUAL = 600.0; // ms
  private static final double THREAD_SCALING_EFFICIENCY_THRESHOLD = 0.9; // 90%
  
  @Mock
  private ApplicationDirectories directories;
  
  private DataStoreManager dataStoreManager;
  private DataStore dataStore;
  
  @BeforeEach
  void setUp() throws Exception {
    // Setup the datastore manager with an H2 in-memory database
    when(directories.getWorkDirectory("etc/fabric/datastore")).thenReturn(util.createTempDir());
    
    dataStoreManager = createDataStoreManager();
    
    // Create and initialize the test datastore
    DataStoreConfiguration config = new DataStoreConfiguration();
    config.setName(STORE_NAME);
    config.setType("h2");
    config.setAttributes(Map.of(
        "jdbcUrl", "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "username", "sa",
        "password", ""
    ));
    
    try {
      dataStore = dataStoreManager.get(STORE_NAME);
    }
    catch (DataStoreNotFoundException e) {
      dataStore = dataStoreManager.create(config);
    }
    
    // Create test table
    try (Connection conn = dataStore.getDataSource().getConnection()) {
      conn.createStatement().execute(
          "CREATE TABLE IF NOT EXISTS " + TEST_TABLE + " ("
              + "id INT PRIMARY KEY, "
              + "name VARCHAR(255), "
              + "value VARCHAR(1024), "
              + "created_at TIMESTAMP"
              + ")");
    }
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (dataStore != null) {
      // Clean up test data
      try (Connection conn = dataStore.getDataSource().getConnection()) {
        conn.createStatement().execute("DROP TABLE IF EXISTS " + TEST_TABLE);
      }
      
      dataStoreManager.delete(dataStore.getConfiguration());
    }
  }
  
  /**
   * Test comparing read performance between platform threads and Virtual Threads.
   * This test measures throughput and latency for database read operations under
   * varying concurrency levels.
   */
  @Test
  void testReadPerformanceComparison() throws Exception {
    // Populate test data
    populateTestData(1000);
    
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        this::readOperation,
        100, // Start with 100 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM, // Max concurrent operations
        100 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        this::readOperation,
        100, // Start with 100 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL, // Max concurrent operations
        500 // Step size
    );
    
    // Log results
    log.info("Platform Thread Results: {}", platformResult);
    log.info("Virtual Thread Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(MAX_CONCURRENT_CONNECTIONS_PLATFORM * 5));
    
    assertThat("Virtual thread P95 response time should be under threshold",
        virtualResult.getP95ResponseTime(), lessThan(P95_RESPONSE_TIME_THRESHOLD_VIRTUAL));
    
    assertThat("Virtual thread P99 response time should be under threshold",
        virtualResult.getP99ResponseTime(), lessThan(P99_RESPONSE_TIME_THRESHOLD_VIRTUAL));
    
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better throughput than platform threads",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.5));
    
    double scalingEfficiency = calculateScalingEfficiency(virtualResult);
    assertThat("Virtual thread scaling efficiency should exceed threshold",
        scalingEfficiency, greaterThanOrEqualTo(THREAD_SCALING_EFFICIENCY_THRESHOLD));
  }
  
  /**
   * Test comparing write performance between platform threads and Virtual Threads.
   * This test measures throughput and latency for database write operations under
   * varying concurrency levels.
   */
  @Test
  void testWritePerformanceComparison() throws Exception {
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        this::writeOperation,
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM / 2, // Max concurrent operations
        50 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        this::writeOperation,
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL / 2, // Max concurrent operations
        250 // Step size
    );
    
    // Log results
    log.info("Platform Thread Write Results: {}", platformResult);
    log.info("Virtual Thread Write Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency for writes",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(platformResult.getMaxConcurrency() * 5));
    
    assertThat("Virtual thread P95 response time for writes should be under threshold",
        virtualResult.getP95ResponseTime(), lessThan(P95_RESPONSE_TIME_THRESHOLD_VIRTUAL * 1.5)); // Allow higher threshold for writes
    
    assertThat("Virtual thread P99 response time for writes should be under threshold",
        virtualResult.getP99ResponseTime(), lessThan(P99_RESPONSE_TIME_THRESHOLD_VIRTUAL * 1.5)); // Allow higher threshold for writes
    
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better write throughput than platform threads",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.2)); // 20% improvement
    
    double scalingEfficiency = calculateScalingEfficiency(virtualResult);
    assertThat("Virtual thread scaling efficiency for writes should exceed threshold",
        scalingEfficiency, greaterThanOrEqualTo(THREAD_SCALING_EFFICIENCY_THRESHOLD));
  }
  
  /**
   * Test comparing mixed read/write performance between platform threads and Virtual Threads.
   * This test measures throughput and latency for a mix of database operations under
   * varying concurrency levels.
   */
  @Test
  void testMixedOperationsPerformanceComparison() throws Exception {
    // Populate initial test data
    populateTestData(500);
    
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        this::mixedOperation,
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM / 2, // Max concurrent operations
        50 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        this::mixedOperation,
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL / 2, // Max concurrent operations
        250 // Step size
    );
    
    // Log results
    log.info("Platform Thread Mixed Operation Results: {}", platformResult);
    log.info("Virtual Thread Mixed Operation Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency for mixed operations",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(platformResult.getMaxConcurrency() * 4));
    
    assertThat("Virtual thread P95 response time for mixed operations should be under threshold",
        virtualResult.getP95ResponseTime(), lessThan(P95_RESPONSE_TIME_THRESHOLD_VIRTUAL * 1.5));
    
    assertThat("Virtual thread P99 response time for mixed operations should be under threshold",
        virtualResult.getP99ResponseTime(), lessThan(P99_RESPONSE_TIME_THRESHOLD_VIRTUAL * 1.5));
    
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better throughput for mixed operations",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.3)); // 30% improvement
    
    double scalingEfficiency = calculateScalingEfficiency(virtualResult);
    assertThat("Virtual thread scaling efficiency for mixed operations should exceed threshold",
        scalingEfficiency, greaterThanOrEqualTo(THREAD_SCALING_EFFICIENCY_THRESHOLD));
  }
  
  /**
   * Test measuring memory consumption differences between platform threads and Virtual Threads
   * under high concurrency.
   */
  @Test
  void testMemoryConsumptionComparison() throws Exception {
    // Populate test data
    populateTestData(1000);
    
    // Measure memory before platform thread test
    long beforePlatformMemory = getUsedMemory();
    
    // Run platform thread test with high concurrency
    runConcurrentOperations(ThreadingModel.PLATFORM, this::readOperation, 500, 5);
    
    // Measure memory after platform thread test
    long afterPlatformMemory = getUsedMemory();
    long platformMemoryUsage = afterPlatformMemory - beforePlatformMemory;
    
    // Force GC to clean up before virtual thread test
    System.gc();
    Thread.sleep(1000);
    
    // Measure memory before virtual thread test
    long beforeVirtualMemory = getUsedMemory();
    
    // Run virtual thread test with high concurrency
    runConcurrentOperations(ThreadingModel.VIRTUAL, this::readOperation, 5000, 5);
    
    // Measure memory after virtual thread test
    long afterVirtualMemory = getUsedMemory();
    long virtualMemoryUsage = afterVirtualMemory - beforeVirtualMemory;
    
    // Log memory usage
    log.info("Platform Thread Memory Usage: {} MB for 500 threads", platformMemoryUsage / (1024 * 1024));
    log.info("Virtual Thread Memory Usage: {} MB for 5000 threads", virtualMemoryUsage / (1024 * 1024));
    
    // Calculate memory efficiency (memory per thread)
    double platformMemoryPerThread = (double) platformMemoryUsage / 500;
    double virtualMemoryPerThread = (double) virtualMemoryUsage / 5000;
    
    log.info("Platform Thread Memory Per Thread: {} KB", platformMemoryPerThread / 1024);
    log.info("Virtual Thread Memory Per Thread: {} KB", virtualMemoryPerThread / 1024);
    
    // Verify virtual threads use significantly less memory per thread
    assertThat("Virtual threads should use less memory per thread",
        virtualMemoryPerThread, lessThan(platformMemoryPerThread * 0.2)); // 80% reduction
  }
  
  /**
   * Creates a DataStoreManager for testing.
   */
  private DataStoreManager createDataStoreManager() {
    // For testing purposes, we're using a simplified DataStoreManager implementation
    return new DataStoreManager() {
      private final Map<String, DataStore> stores = new HashMap<>();
      
      @Override
      public DataStore create(DataStoreConfiguration configuration) {
        DataStore store = new TestDataStore(configuration);
        stores.put(configuration.getName(), store);
        return store;
      }
      
      @Override
      public DataStore get(String storeName) {
        if (!stores.containsKey(storeName)) {
          throw new DataStoreNotFoundException(storeName);
        }
        return stores.get(storeName);
      }
      
      @Override
      public boolean exists(String storeName) {
        return stores.containsKey(storeName);
      }
      
      @Override
      public void delete(DataStoreConfiguration configuration) {
        stores.remove(configuration.getName());
      }
      
      @Override
      public List<String> browseStoreNames() {
        return new ArrayList<>(stores.keySet());
      }
    };
  }
  
  /**
   * Simple DataStore implementation for testing.
   */
  private static class TestDataStore implements DataStore {
    private final DataStoreConfiguration configuration;
    private final DataSource dataSource;
    
    TestDataStore(DataStoreConfiguration configuration) {
      this.configuration = configuration;
      
      // Create an H2 in-memory datasource
      org.h2.jdbcx.JdbcDataSource h2DataSource = new org.h2.jdbcx.JdbcDataSource();
      h2DataSource.setURL((String) configuration.getAttributes().get("jdbcUrl"));
      h2DataSource.setUser((String) configuration.getAttributes().get("username"));
      h2DataSource.setPassword((String) configuration.getAttributes().get("password"));
      
      this.dataSource = h2DataSource;
    }
    
    @Override
    public DataStoreConfiguration getConfiguration() {
      return configuration;
    }
    
    @Override
    public DataSource getDataSource() {
      return dataSource;
    }
    
    @Override
    public void start() {
      // No-op for test implementation
    }
    
    @Override
    public void stop() {
      // No-op for test implementation
    }
    
    @Override
    public void shutdown() {
      // No-op for test implementation
    }
    
    @Override
    public boolean isStarted() {
      return true;
    }
  }
  
  /**
   * Enum representing the threading models to test.
   */
  private enum ThreadingModel {
    PLATFORM,
    VIRTUAL
  }
  
  /**
   * Class to hold performance test results.
   */
  private static class PerformanceResult {
    private final ThreadingModel threadingModel;
    private final int maxConcurrency;
    private final double throughput; // operations per second
    private final Map<Integer, List<Double>> responseTimes; // concurrency level -> list of response times in ms
    
    PerformanceResult(ThreadingModel threadingModel, int maxConcurrency, double throughput,
                      Map<Integer, List<Double>> responseTimes) {
      this.threadingModel = threadingModel;
      this.maxConcurrency = maxConcurrency;
      this.throughput = throughput;
      this.responseTimes = responseTimes;
    }
    
    public ThreadingModel getThreadingModel() {
      return threadingModel;
    }
    
    public int getMaxConcurrency() {
      return maxConcurrency;
    }
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getP95ResponseTime() {
      return getPercentile(95.0);
    }
    
    public double getP99ResponseTime() {
      return getPercentile(99.0);
    }
    
    public double getPercentile(double percentile) {
      List<Double> allTimes = responseTimes.values().stream()
          .flatMap(List::stream)
          .sorted()
          .collect(Collectors.toList());
      
      if (allTimes.isEmpty()) {
        return 0.0;
      }
      
      int index = (int) Math.ceil(percentile / 100.0 * allTimes.size()) - 1;
      return allTimes.get(Math.max(0, Math.min(index, allTimes.size() - 1)));
    }
    
    @Override
    public String toString() {
      return String.format(
          "%s Threads - Max Concurrency: %d, Throughput: %.2f ops/sec, P95: %.2f ms, P99: %.2f ms",
          threadingModel, maxConcurrency, throughput, getP95ResponseTime(), getP99ResponseTime());
    }
  }
  
  /**
   * Populates the test table with the specified number of records.
   */
  private void populateTestData(int count) throws SQLException {
    try (Connection conn = dataStore.getDataSource().getConnection()) {
      // Clear existing data
      conn.createStatement().execute("DELETE FROM " + TEST_TABLE);
      
      // Insert new data
      try (PreparedStatement stmt = conn.prepareStatement(
          "INSERT INTO " + TEST_TABLE + " (id, name, value, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP())")) {
        
        for (int i = 0; i < count; i++) {
          stmt.setInt(1, i);
          stmt.setString(2, "test-name-" + i);
          stmt.setString(3, "test-value-" + i + "-" + "x".repeat(100)); // Add some data volume
          stmt.addBatch();
          
          if (i % 100 == 0) {
            stmt.executeBatch();
          }
        }
        stmt.executeBatch();
      }
    }
  }
  
  /**
   * Performs a read operation against the database.
   */
  private void readOperation() throws SQLException {
    try (Connection conn = dataStore.getDataSource().getConnection()) {
      // Select a random record
      int id = (int) (Math.random() * 1000);
      
      try (PreparedStatement stmt = conn.prepareStatement(
          "SELECT * FROM " + TEST_TABLE + " WHERE id = ?")) {
        stmt.setInt(1, id);
        
        try (ResultSet rs = stmt.executeQuery()) {
          if (rs.next()) {
            // Read all columns to simulate real usage
            rs.getInt("id");
            rs.getString("name");
            rs.getString("value");
            rs.getTimestamp("created_at");
          }
        }
      }
    }
  }
  
  /**
   * Performs a write operation against the database.
   */
  private void writeOperation() throws SQLException {
    try (Connection conn = dataStore.getDataSource().getConnection()) {
      // Insert or update a record
      int id = (int) (Math.random() * 10000);
      
      try (PreparedStatement stmt = conn.prepareStatement(
          "MERGE INTO " + TEST_TABLE + " (id, name, value, created_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP())")) {
        stmt.setInt(1, id);
        stmt.setString(2, "name-" + id);
        stmt.setString(3, "value-" + id + "-" + System.currentTimeMillis());
        stmt.executeUpdate();
      }
    }
  }
  
  /**
   * Performs a mixed read/write operation against the database.
   */
  private void mixedOperation() throws SQLException {
    // 80% reads, 20% writes
    if (Math.random() < 0.8) {
      readOperation();
    }
    else {
      writeOperation();
    }
  }
  
  /**
   * Benchmarks an operation using the specified threading model and concurrency levels.
   */
  private PerformanceResult benchmarkOperation(
      ThreadingModel threadingModel,
      Callable<Void> operation,
      int startConcurrency,
      int maxConcurrency,
      int stepSize) throws Exception {
    
    Map<Integer, List<Double>> responseTimes = new ConcurrentHashMap<>();
    double maxThroughput = 0.0;
    int actualMaxConcurrency = 0;
    
    // Test with increasing concurrency levels
    for (int concurrency = startConcurrency; concurrency <= maxConcurrency; concurrency += stepSize) {
      // Warm-up phase
      runConcurrentOperations(threadingModel, operation, concurrency, WARMUP_ITERATIONS);
      
      // Measurement phase
      long startTime = System.nanoTime();
      List<Double> iterationResponseTimes = runConcurrentOperations(threadingModel, operation, concurrency, MEASUREMENT_ITERATIONS);
      long endTime = System.nanoTime();
      
      // Calculate throughput (operations per second)
      double durationSeconds = Duration.ofNanos(endTime - startTime).toMillis() / 1000.0;
      double iterationThroughput = (concurrency * MEASUREMENT_ITERATIONS) / durationSeconds;
      
      // Store response times for this concurrency level
      responseTimes.put(concurrency, iterationResponseTimes);
      
      // Update max throughput if this iteration was better
      if (iterationThroughput > maxThroughput) {
        maxThroughput = iterationThroughput;
        actualMaxConcurrency = concurrency;
      }
      
      log.info("{} Threads - Concurrency: {}, Throughput: {:.2f} ops/sec",
          threadingModel, concurrency, iterationThroughput);
      
      // If throughput starts decreasing significantly, we've reached the limit
      if (iterationThroughput < maxThroughput * 0.7 && concurrency > startConcurrency * 2) {
        log.info("Throughput decreased significantly, stopping benchmark at concurrency {}", concurrency);
        break;
      }
    }
    
    return new PerformanceResult(threadingModel, actualMaxConcurrency, maxThroughput, responseTimes);
  }
  
  /**
   * Runs concurrent operations using the specified threading model and concurrency level.
   * Returns a list of response times in milliseconds.
   */
  private List<Double> runConcurrentOperations(
      ThreadingModel threadingModel,
      Callable<Void> operation,
      int concurrency,
      int iterations) throws Exception {
    
    List<Double> responseTimes = new ArrayList<>();
    CountDownLatch latch = new CountDownLatch(concurrency * iterations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create appropriate thread factory based on threading model
    ThreadFactory threadFactory = threadingModel == ThreadingModel.VIRTUAL ?
        Thread.ofVirtual().name("virtual-test-", 0).factory() :
        Thread.ofPlatform().name("platform-test-", 0).factory();
    
    // Create executor service with the appropriate thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Submit tasks
      for (int i = 0; i < concurrency * iterations; i++) {
        executor.submit(() -> {
          try {
            long startTime = System.nanoTime();
            operation.call();
            long endTime = System.nanoTime();
            
            // Record response time in milliseconds
            double responseTime = Duration.ofNanos(endTime - startTime).toMillis();
            synchronized (responseTimes) {
              responseTimes.add(responseTime);
            }
          }
          catch (Exception e) {
            log.error("Error executing operation", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
          return null;
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(5, TimeUnit.MINUTES);
      
      if (!completed) {
        log.warn("Not all tasks completed within the timeout period");
      }
      
      if (errorCount.get() > 0) {
        log.warn("{} errors occurred during execution", errorCount.get());
      }
    }
    
    return responseTimes;
  }
  
  /**
   * Calculates the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Calculates the scaling efficiency of virtual threads.
   * This measures how well throughput scales with increased concurrency.
   */
  private double calculateScalingEfficiency(PerformanceResult result) {
    // Get throughput at different concurrency levels
    Map<Integer, List<Double>> responseTimes = result.responseTimes;
    if (responseTimes.size() < 2) {
      return 1.0; // Not enough data points
    }
    
    // Sort concurrency levels
    List<Integer> concurrencyLevels = new ArrayList<>(responseTimes.keySet());
    concurrencyLevels.sort(Integer::compareTo);
    
    // Calculate average response time at each concurrency level
    Map<Integer, Double> avgResponseTimes = new HashMap<>();
    for (Integer concurrency : concurrencyLevels) {
      List<Double> times = responseTimes.get(concurrency);
      double avgTime = times.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
      avgResponseTimes.put(concurrency, avgTime);
    }
    
    // Calculate ideal vs. actual scaling
    int lowestConcurrency = concurrencyLevels.get(0);
    int highestConcurrency = concurrencyLevels.get(concurrencyLevels.size() - 1);
    
    double baselineTime = avgResponseTimes.get(lowestConcurrency);
    double actualTime = avgResponseTimes.get(highestConcurrency);
    
    // Ideal scaling: response time stays constant regardless of concurrency
    // Actual scaling: response time typically increases with concurrency
    // Efficiency = baseline / actual (capped at 1.0)
    return Math.min(1.0, baselineTime / actualTime);
  }
}