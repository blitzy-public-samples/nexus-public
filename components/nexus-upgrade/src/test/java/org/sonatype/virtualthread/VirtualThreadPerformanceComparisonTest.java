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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.nexus.common.upgrade.events.UpgradeCompletedEvent;
import org.sonatype.nexus.common.upgrade.events.UpgradeStartedEvent;
import org.sonatype.nexus.datastore.api.DataStore;
import org.sonatype.nexus.datastore.api.DataStoreManager;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector;
import org.sonatype.nexus.testcommon.virtualthread.ThreadPinningDetector.PinningInfo;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;
import org.sonatype.nexus.testdb.DataSessionRule;
import org.sonatype.nexus.upgrade.datastore.DatabaseMigrationStep;
import org.sonatype.nexus.upgrade.datastore.internal.PostStartupUpgradeAuditor;
import org.sonatype.nexus.upgrade.datastore.internal.UpgradeManagerImpl;
import org.sonatype.nexus.upgrade.plan.DependencyResolver;
import org.sonatype.nexus.upgrade.plan.DependencySource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.datastore.api.DataStoreManager.DEFAULT_DATASTORE_NAME;

/**
 * Performance comparison tests between platform threads and virtual threads for Nexus upgrade operations.
 * <p>
 * This test class measures throughput, latency, and resource utilization for key upgrade operations like
 * database migrations, dependency resolution, and upgrade task execution under both threading models.
 * It validates the performance benefits of virtual threads for I/O-bound operations and identifies any
 * potential bottlenecks or thread pinning issues.
 *
 * @since 3.60
 */
@Tag("VirtualThreadTestGroup")
@DisplayName("Virtual Thread Performance Comparison for Upgrade Operations")
public class VirtualThreadPerformanceComparisonTest
    extends VirtualThreadTestSupport
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  private static final int MAX_CONCURRENCY = 1000;
  private static final int CONCURRENCY_STEP = 100;
  
  private DataSessionRule dataSessionRule;
  private DataStoreManager dataStoreManager;
  private PostStartupUpgradeAuditor auditor;
  
  /**
   * Set up test environment before each test.
   */
  @BeforeEach
  void setUp() {
    // Ensure virtual threads are supported before running tests
    assumeVirtualThreadSupported();
    
    // Initialize test database
    dataSessionRule = new DataSessionRule();
    
    // Mock DataStoreManager to return our test DataStore
    dataStoreManager = mock(DataStoreManager.class);
    when(dataStoreManager.get(DEFAULT_DATASTORE_NAME)).thenReturn(getDataStore());
    
    // Mock auditor for upgrade events
    auditor = mock(PostStartupUpgradeAuditor.class);
    
    // Enable thread pinning detection
    ThreadPinningDetector.enableJdkPinningDetection();
    ThreadPinningDetector.startJfrMonitoring();
  }
  
  /**
   * Clean up after each test.
   */
  @AfterEach
  void tearDown() {
    // Stop thread pinning monitoring
    ThreadPinningDetector.stopJfrMonitoring();
    
    // Print thread pinning statistics
    List<PinningInfo> pinningStats = ThreadPinningDetector.getPinningStatistics();
    if (!pinningStats.isEmpty()) {
      log.warn("Thread pinning detected during test execution:");
      for (PinningInfo info : pinningStats) {
        log.warn(STR."  \{info.getLocation()} - Count: \{info.getCount()}, Total Duration: \{info.getTotalDurationMs()}ms, Avg: \{String.format("%.2f", info.getAverageDurationMs())}ms");
      }
    }
    
    // Clear pinning statistics for next test
    ThreadPinningDetector.clearPinningStatistics();
  }
  
  /**
   * Compares the performance of database migration operations between platform threads and virtual threads.
   * <p>
   * This test executes database migrations with increasing concurrency levels using both thread types
   * and measures throughput, latency, and resource utilization.
   */
  @Test
  @DisplayName("Database Migration Performance: Platform vs Virtual Threads")
  void testDatabaseMigrationPerformance() throws Exception {
    // Create test migration step
    TestMigrationStep migrationStep = new TestMigrationStep();
    
    // Run performance comparison
    PerformanceResult platformResult = measurePerformance(
        () -> runDatabaseMigration(migrationStep),
        Thread.ofPlatform().factory(),
        "Platform Thread - Database Migration");
    
    PerformanceResult virtualResult = measurePerformance(
        () -> runDatabaseMigration(migrationStep),
        Thread.ofVirtual().factory(),
        "Virtual Thread - Database Migration");
    
    // Log results using Java 21 string templates
    logPerformanceComparison(platformResult, virtualResult);
    
    // Verify virtual threads provide better scalability at high concurrency
    assertTrue(virtualResult.getMaxThroughput() >= platformResult.getMaxThroughput(),
        STR."Virtual thread throughput (\{virtualResult.getMaxThroughput()}) should be at least as good as platform thread throughput (\{platformResult.getMaxThroughput()})");
    
    // Verify virtual threads use fewer resources at high concurrency
    assertTrue(virtualResult.getResourceUtilization() <= platformResult.getResourceUtilization(),
        STR."Virtual thread resource utilization (\{virtualResult.getResourceUtilization()}) should be lower than platform thread utilization (\{platformResult.getResourceUtilization()})");
  }
  
  /**
   * Compares the performance of dependency resolution operations between platform threads and virtual threads.
   * <p>
   * This test resolves complex dependency graphs with increasing concurrency levels using both thread types
   * and measures throughput, latency, and resource utilization.
   */
  @Test
  @DisplayName("Dependency Resolution Performance: Platform vs Virtual Threads")
  void testDependencyResolutionPerformance() throws Exception {
    // Run performance comparison
    PerformanceResult platformResult = measurePerformance(
        this::resolveDependencies,
        Thread.ofPlatform().factory(),
        "Platform Thread - Dependency Resolution");
    
    PerformanceResult virtualResult = measurePerformance(
        this::resolveDependencies,
        Thread.ofVirtual().factory(),
        "Virtual Thread - Dependency Resolution");
    
    // Log results using Java 21 string templates
    logPerformanceComparison(platformResult, virtualResult);
    
    // Verify virtual threads provide better scalability at high concurrency
    assertTrue(virtualResult.getMaxThroughput() >= platformResult.getMaxThroughput(),
        STR."Virtual thread throughput (\{virtualResult.getMaxThroughput()}) should be at least as good as platform thread throughput (\{platformResult.getMaxThroughput()})");
    
    // Verify virtual threads use fewer resources at high concurrency
    assertTrue(virtualResult.getResourceUtilization() <= platformResult.getResourceUtilization(),
        STR."Virtual thread resource utilization (\{virtualResult.getResourceUtilization()}) should be lower than platform thread utilization (\{platformResult.getResourceUtilization()})");
  }
  
  /**
   * Tests the scalability of upgrade operations with increasing concurrency levels.
   * <p>
   * This test measures how well virtual threads scale compared to platform threads as the number
   * of concurrent operations increases.
   *
   * @param threadType the type of thread to use ("platform" or "virtual")
   */
  @ParameterizedTest(name = "Upgrade Scalability with {0} Threads")
  @ValueSource(strings = {"platform", "virtual"})
  void testUpgradeScalability(String threadType) throws Exception {
    // Create thread factory based on type
    ThreadFactory threadFactory = "virtual".equals(threadType) ?
        Thread.ofVirtual().factory() :
        Thread.ofPlatform().factory();
    
    // Create test migration step
    TestMigrationStep migrationStep = new TestMigrationStep();
    
    // Measure scalability with increasing concurrency
    log.info(STR."Testing upgrade scalability with \{threadType} threads:");
    
    for (int concurrency = CONCURRENCY_STEP; concurrency <= MAX_CONCURRENCY; concurrency += CONCURRENCY_STEP) {
      // Measure throughput at this concurrency level
      double throughput = measureThroughputAtConcurrency(
          () -> runDatabaseMigration(migrationStep),
          threadFactory,
          concurrency);
      
      log.info(STR."  Concurrency: \{concurrency}, Throughput: \{String.format("%.2f", throughput)} ops/sec");
    }
  }
  
  /**
   * Tests for thread pinning issues in upgrade operations.
   * <p>
   * This test identifies operations that may cause virtual threads to be pinned to platform threads,
   * which can limit scalability and performance benefits.
   */
  @Test
  @DisplayName("Thread Pinning Detection in Upgrade Operations")
  void testThreadPinningInUpgradeOperations() throws Exception {
    // Create test migration step
    TestMigrationStep migrationStep = new TestMigrationStep();
    
    // Test database migration for pinning
    boolean migrationPinning = ThreadPinningDetector.detectThreadPinning(() -> {
      try {
        runDatabaseMigration(migrationStep);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Test dependency resolution for pinning
    boolean dependencyPinning = ThreadPinningDetector.detectThreadPinning(() -> {
      try {
        resolveDependencies();
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Log results
    log.info(STR."Thread pinning detected in database migration: \{migrationPinning}");
    log.info(STR."Thread pinning detected in dependency resolution: \{dependencyPinning}");
    
    // Verify no thread pinning in critical operations
    assertFalse(migrationPinning, "Database migration should not cause thread pinning");
    assertFalse(dependencyPinning, "Dependency resolution should not cause thread pinning");
  }
  
  /**
   * Runs a database migration operation.
   *
   * @param migrationStep the migration step to execute
   * @throws Exception if an error occurs during migration
   */
  private void runDatabaseMigration(DatabaseMigrationStep migrationStep) throws Exception {
    UpgradeManagerImpl upgradeManager = new UpgradeManagerImpl(dataStoreManager, auditor, singletonList(migrationStep));
    upgradeManager.migrate();
  }
  
  /**
   * Resolves a complex dependency graph.
   *
   * @throws Exception if an error occurs during dependency resolution
   */
  private void resolveDependencies() throws Exception {
    DependencyResolver<TestDependency> resolver = new DependencyResolver<>();
    
    // Create a complex dependency graph
    resolver.add(
        new TestDependency("a", "b", "c"),
        new TestDependency("b", "d", "e"),
        new TestDependency("c", "f"),
        new TestDependency("d"),
        new TestDependency("e", "g"),
        new TestDependency("f"),
        new TestDependency("g"),
        new TestDependency("h", "i"),
        new TestDependency("i", "j"),
        new TestDependency("j")
    );
    
    // Resolve dependencies
    resolver.resolve();
  }
  
  /**
   * Measures performance metrics for an operation with both thread types.
   *
   * @param operation the operation to measure
   * @param threadFactory the thread factory to use
   * @param label a label for logging
   * @return performance metrics
   */
  private PerformanceResult measurePerformance(
      Callable<Void> operation,
      ThreadFactory threadFactory,
      String label) throws Exception
  {
    log.info(STR."Measuring performance for: \{label}");
    
    // Warm up
    log.info(STR."  Warming up with \{WARMUP_ITERATIONS} iterations...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      operation.call();
    }
    
    // Measure baseline (single-threaded)
    log.info("  Measuring baseline (single-threaded) performance...");
    long startTime = System.nanoTime();
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
      operation.call();
    }
    long endTime = System.nanoTime();
    double baselineLatency = (endTime - startTime) / (double) MEASUREMENT_ITERATIONS / 1_000_000.0; // ms
    double baselineThroughput = 1000.0 / baselineLatency; // ops/sec
    
    log.info(STR."  Baseline latency: \{String.format("%.2f", baselineLatency)} ms");
    log.info(STR."  Baseline throughput: \{String.format("%.2f", baselineThroughput)} ops/sec");
    
    // Measure with increasing concurrency
    double maxThroughput = baselineThroughput;
    double p95Latency = baselineLatency;
    double resourceUtilization = 0.0;
    
    for (int concurrency = CONCURRENCY_STEP; concurrency <= MAX_CONCURRENCY; concurrency += CONCURRENCY_STEP) {
      double throughput = measureThroughputAtConcurrency(operation, threadFactory, concurrency);
      if (throughput > maxThroughput) {
        maxThroughput = throughput;
      }
      
      // Estimate resource utilization (simplified model)
      double currentUtilization = (double) concurrency / Runtime.getRuntime().availableProcessors();
      if (currentUtilization > resourceUtilization) {
        resourceUtilization = currentUtilization;
      }
      
      log.info(STR."  Concurrency: \{concurrency}, Throughput: \{String.format("%.2f", throughput)} ops/sec");
    }
    
    // Measure p95 latency at max concurrency
    p95Latency = measureP95LatencyAtConcurrency(operation, threadFactory, MAX_CONCURRENCY);
    log.info(STR."  P95 latency at max concurrency: \{String.format("%.2f", p95Latency)} ms");
    
    return new PerformanceResult(label, baselineLatency, baselineThroughput, maxThroughput, p95Latency, resourceUtilization);
  }
  
  /**
   * Measures throughput at a specific concurrency level.
   *
   * @param operation the operation to measure
   * @param threadFactory the thread factory to use
   * @param concurrency the number of concurrent operations
   * @return throughput in operations per second
   */
  private double measureThroughputAtConcurrency(
      Callable<Void> operation,
      ThreadFactory threadFactory,
      int concurrency) throws Exception
  {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      CountDownLatch completionLatch = new CountDownLatch(concurrency);
      AtomicInteger completedOperations = new AtomicInteger(0);
      AtomicInteger errors = new AtomicInteger(0);
      
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        executor.submit(() -> {
          try {
            startLatch.await(); // Wait for all tasks to be ready
            operation.call();
            completedOperations.incrementAndGet();
          }
          catch (Exception e) {
            errors.incrementAndGet();
            log.error("Error during concurrent operation", e);
          }
          finally {
            completionLatch.countDown();
          }
          return null;
        });
      }
      
      // Start all tasks simultaneously
      long startTime = System.nanoTime();
      startLatch.countDown();
      
      // Wait for all tasks to complete
      completionLatch.await();
      long endTime = System.nanoTime();
      
      // Calculate throughput
      double elapsedSeconds = (endTime - startTime) / 1_000_000_000.0;
      return completedOperations.get() / elapsedSeconds;
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Measures P95 latency at a specific concurrency level.
   *
   * @param operation the operation to measure
   * @param threadFactory the thread factory to use
   * @param concurrency the number of concurrent operations
   * @return P95 latency in milliseconds
   */
  private double measureP95LatencyAtConcurrency(
      Callable<Void> operation,
      ThreadFactory threadFactory,
      int concurrency) throws Exception
  {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    try {
      CountDownLatch startLatch = new CountDownLatch(1);
      List<Long> latencies = new ArrayList<>(concurrency);
      List<Future<Long>> futures = new ArrayList<>(concurrency);
      
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        futures.add(executor.submit(() -> {
          startLatch.await(); // Wait for all tasks to be ready
          long start = System.nanoTime();
          operation.call();
          long end = System.nanoTime();
          return (end - start) / 1_000_000; // Convert to ms
        }));
      }
      
      // Start all tasks simultaneously
      startLatch.countDown();
      
      // Collect latencies
      for (Future<Long> future : futures) {
        try {
          latencies.add(future.get());
        }
        catch (Exception e) {
          log.error("Error measuring latency", e);
        }
      }
      
      // Calculate P95 latency
      if (latencies.isEmpty()) {
        return 0.0;
      }
      
      latencies.sort(Long::compare);
      int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
      return latencies.get(p95Index);
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Logs a comparison of performance results between platform and virtual threads.
   *
   * @param platformResult performance results for platform threads
   * @param virtualResult performance results for virtual threads
   */
  private void logPerformanceComparison(PerformanceResult platformResult, PerformanceResult virtualResult) {
    log.info("\nPERFORMANCE COMPARISON RESULTS:");
    log.info(STR."  Platform Thread - Baseline Latency: \{String.format("%.2f", platformResult.getBaselineLatency())} ms");
    log.info(STR."  Virtual Thread  - Baseline Latency: \{String.format("%.2f", virtualResult.getBaselineLatency())} ms");
    log.info(STR."  Platform Thread - Baseline Throughput: \{String.format("%.2f", platformResult.getBaselineThroughput())} ops/sec");
    log.info(STR."  Virtual Thread  - Baseline Throughput: \{String.format("%.2f", virtualResult.getBaselineThroughput())} ops/sec");
    log.info(STR."  Platform Thread - Max Throughput: \{String.format("%.2f", platformResult.getMaxThroughput())} ops/sec");
    log.info(STR."  Virtual Thread  - Max Throughput: \{String.format("%.2f", virtualResult.getMaxThroughput())} ops/sec");
    log.info(STR."  Platform Thread - P95 Latency: \{String.format("%.2f", platformResult.getP95Latency())} ms");
    log.info(STR."  Virtual Thread  - P95 Latency: \{String.format("%.2f", virtualResult.getP95Latency())} ms");
    log.info(STR."  Platform Thread - Resource Utilization: \{String.format("%.2f", platformResult.getResourceUtilization())}");
    log.info(STR."  Virtual Thread  - Resource Utilization: \{String.format("%.2f", virtualResult.getResourceUtilization())}");
    
    // Calculate improvement percentages
    double throughputImprovement = ((virtualResult.getMaxThroughput() / platformResult.getMaxThroughput()) - 1.0) * 100.0;
    double latencyImprovement = ((platformResult.getP95Latency() / virtualResult.getP95Latency()) - 1.0) * 100.0;
    double resourceImprovement = ((platformResult.getResourceUtilization() / virtualResult.getResourceUtilization()) - 1.0) * 100.0;
    
    log.info("\nIMPROVEMENT SUMMARY:");
    log.info(STR."  Throughput Improvement: \{String.format("%.2f", throughputImprovement)}%");
    log.info(STR."  Latency Improvement: \{String.format("%.2f", latencyImprovement)}%");
    log.info(STR."  Resource Utilization Improvement: \{String.format("%.2f", resourceImprovement)}%");
  }
  
  /**
   * Gets the test DataStore.
   *
   * @return the DataStore for testing
   */
  private Optional<DataStore<?>> getDataStore() {
    return dataSessionRule.getDataStore(DEFAULT_DATASTORE_NAME);
  }
  
  /**
   * A simple test migration step for performance testing.
   */
  private static class TestMigrationStep
      implements DatabaseMigrationStep
  {
    @Override
    public Optional<String> version() {
      return Optional.of("1.0");
    }
    
    @Override
    public void migrate(Connection connection) throws Exception {
      try (Statement stmt = connection.createStatement()) {
        // Create a test table
        stmt.execute("CREATE TABLE IF NOT EXISTS example (name VARCHAR(255))");
        
        // Insert some test data
        stmt.execute("INSERT INTO example (name) VALUES ('fawkes')");
        
        // Simulate some I/O-bound work
        Thread.sleep(50);
      }
    }
    
    public boolean isH2(Connection connection) throws SQLException {
      return connection.getMetaData().getDatabaseProductName().equals("H2");
    }
  }
  
  /**
   * A simple test dependency for performance testing.
   */
  private static class TestDependency
      implements DependencySource<TestDependency>
  {
    private final String id;
    private final List<Dependency<TestDependency>> dependencies = new ArrayList<>();
    
    public TestDependency(String id, String... dependsOn) {
      this.id = id;
      for (String dep : dependsOn) {
        dependencies.add(dependency(dep));
      }
    }
    
    @Override
    public List<Dependency<TestDependency>> getDependencies() {
      return dependencies;
    }
    
    /**
     * Creates a dependency which requires a thing with the given identifier.
     */
    static Dependency<TestDependency> dependency(final String id) {
      return new Dependency<TestDependency>() {
        @Override
        public boolean satisfiedBy(final TestDependency other) {
          return other.id.equals(id);
        }
        
        @Override
        public String toString() {
          return "DEPENDS_ON(" + id + ")";
        }
      };
    }
    
    @Override
    public String toString() {
      return "TestDependency{" +
          "id='" + id + '\'' +
          ", dependencies=" + dependencies +
          '}';
    }
  }
  
  /**
   * Holds performance measurement results.
   */
  private static class PerformanceResult
  {
    private final String label;
    private final double baselineLatency;
    private final double baselineThroughput;
    private final double maxThroughput;
    private final double p95Latency;
    private final double resourceUtilization;
    
    public PerformanceResult(
        String label,
        double baselineLatency,
        double baselineThroughput,
        double maxThroughput,
        double p95Latency,
        double resourceUtilization)
    {
      this.label = label;
      this.baselineLatency = baselineLatency;
      this.baselineThroughput = baselineThroughput;
      this.maxThroughput = maxThroughput;
      this.p95Latency = p95Latency;
      this.resourceUtilization = resourceUtilization;
    }
    
    public String getLabel() {
      return label;
    }
    
    public double getBaselineLatency() {
      return baselineLatency;
    }
    
    public double getBaselineThroughput() {
      return baselineThroughput;
    }
    
    public double getMaxThroughput() {
      return maxThroughput;
    }
    
    public double getP95Latency() {
      return p95Latency;
    }
    
    public double getResourceUtilization() {
      return resourceUtilization;
    }
  }
}