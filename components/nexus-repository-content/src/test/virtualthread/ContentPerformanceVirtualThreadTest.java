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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
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

import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.AssetBlob;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.store.AssetData;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.AssetStoreTestSupport;
import org.sonatype.nexus.repository.content.store.ComponentData;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.content.store.ContentRepositoryData;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Performance comparison test for repository content operations using platform threads versus Virtual Threads.
 * This class conducts controlled benchmarks to measure throughput, latency, and resource utilization differences
 * between the two threading models. It validates the performance improvements achieved by migrating to Java 21
 * Virtual Threads for I/O-bound content operations like asset browsing, component retrieval, and content streaming.
 */
@Category(VirtualThreadTestGroup.class)
public class ContentPerformanceVirtualThreadTest
    extends AssetStoreTestSupport
{
  private static final int COMPONENT_COUNT = 1000;
  private static final int ASSET_COUNT = 5000;
  private static final int CONCURRENT_OPERATIONS = 500;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  
  private static final double VIRTUAL_THREAD_THROUGHPUT_IMPROVEMENT_THRESHOLD = 1.3; // 30% improvement
  private static final double VIRTUAL_THREAD_LATENCY_IMPROVEMENT_THRESHOLD = 0.7; // 30% reduction
  
  private Random random;
  
  @Before
  public void setUp() {
    initialiseStores(true); // Use versioned entities
    random = new Random(42); // Fixed seed for reproducibility
    
    // Create test data
    createTestData();
  }
  
  /**
   * Creates test components and assets for performance testing.
   */
  private void createTestData() {
    inTx(() -> {
      // Create components
      for (int i = 0; i < COMPONENT_COUNT; i++) {
        ComponentData component = randomComponent(repositoryId);
        component.setNamespace("namespace-" + (i % 10));
        component.setName("component-" + i);
        component.setVersion("1.0." + i);
        componentStore.createComponent(component);
      }
      
      // Create assets
      for (int i = 0; i < ASSET_COUNT; i++) {
        AssetData asset = randomAsset(repositoryId);
        asset.setPath("/path/to/asset-" + i + ".jar");
        
        // Assign some assets to components
        if (i % 5 == 0) {
          ComponentData component = randomComponent(repositoryId);
          component.setComponentId(random.nextInt(COMPONENT_COUNT) + 1);
          asset.setComponent(component);
        }
        
        underTest.createAsset(asset);
      }
    });
  }
  
  /**
   * Tests the performance of browsing assets using platform threads vs virtual threads.
   * This test measures throughput and latency for concurrent asset browsing operations.
   */
  @Test
  public void testAssetBrowsingPerformance() throws Exception {
    // Create thread factories
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Warm up
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentAssetBrowsing(platformThreadFactory, 50);
      runConcurrentAssetBrowsing(virtualThreadFactory, 50);
    }
    
    // Measure platform threads performance
    System.out.println("\nMeasuring platform threads performance...");
    PerformanceResult platformResult = measurePerformance(() -> {
      try {
        return runConcurrentAssetBrowsing(platformThreadFactory, CONCURRENT_OPERATIONS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Measure virtual threads performance
    System.out.println("\nMeasuring virtual threads performance...");
    PerformanceResult virtualResult = measurePerformance(() -> {
      try {
        return runConcurrentAssetBrowsing(virtualThreadFactory, CONCURRENT_OPERATIONS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Print results
    printPerformanceComparison("Asset Browsing", platformResult, virtualResult);
    
    // Verify performance improvements
    double throughputImprovement = virtualResult.getThroughput() / platformResult.getThroughput();
    double p95LatencyImprovement = virtualResult.getP95Latency() / platformResult.getP95Latency();
    
    assertThat("Virtual threads should provide higher throughput", 
        throughputImprovement, greaterThan(VIRTUAL_THREAD_THROUGHPUT_IMPROVEMENT_THRESHOLD));
    assertThat("Virtual threads should provide lower P95 latency", 
        p95LatencyImprovement, lessThan(VIRTUAL_THREAD_LATENCY_IMPROVEMENT_THRESHOLD));
  }
  
  /**
   * Tests the performance of component retrieval using platform threads vs virtual threads.
   * This test measures throughput and latency for concurrent component retrieval operations.
   */
  @Test
  public void testComponentRetrievalPerformance() throws Exception {
    // Create thread factories
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Warm up
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentComponentRetrieval(platformThreadFactory, 50);
      runConcurrentComponentRetrieval(virtualThreadFactory, 50);
    }
    
    // Measure platform threads performance
    System.out.println("\nMeasuring platform threads performance...");
    PerformanceResult platformResult = measurePerformance(() -> {
      try {
        return runConcurrentComponentRetrieval(platformThreadFactory, CONCURRENT_OPERATIONS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Measure virtual threads performance
    System.out.println("\nMeasuring virtual threads performance...");
    PerformanceResult virtualResult = measurePerformance(() -> {
      try {
        return runConcurrentComponentRetrieval(virtualThreadFactory, CONCURRENT_OPERATIONS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Print results
    printPerformanceComparison("Component Retrieval", platformResult, virtualResult);
    
    // Verify performance improvements
    double throughputImprovement = virtualResult.getThroughput() / platformResult.getThroughput();
    double p95LatencyImprovement = virtualResult.getP95Latency() / platformResult.getP95Latency();
    
    assertThat("Virtual threads should provide higher throughput", 
        throughputImprovement, greaterThan(VIRTUAL_THREAD_THROUGHPUT_IMPROVEMENT_THRESHOLD));
    assertThat("Virtual threads should provide lower P95 latency", 
        p95LatencyImprovement, lessThan(VIRTUAL_THREAD_LATENCY_IMPROVEMENT_THRESHOLD));
  }
  
  /**
   * Tests the performance of asset search operations using platform threads vs virtual threads.
   * This test measures throughput and latency for concurrent asset search operations.
   */
  @Test
  public void testAssetSearchPerformance() throws Exception {
    // Create thread factories
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Warm up
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runConcurrentAssetSearch(platformThreadFactory, 50);
      runConcurrentAssetSearch(virtualThreadFactory, 50);
    }
    
    // Measure platform threads performance
    System.out.println("\nMeasuring platform threads performance...");
    PerformanceResult platformResult = measurePerformance(() -> {
      try {
        return runConcurrentAssetSearch(platformThreadFactory, CONCURRENT_OPERATIONS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Measure virtual threads performance
    System.out.println("\nMeasuring virtual threads performance...");
    PerformanceResult virtualResult = measurePerformance(() -> {
      try {
        return runConcurrentAssetSearch(virtualThreadFactory, CONCURRENT_OPERATIONS);
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
    
    // Print results
    printPerformanceComparison("Asset Search", platformResult, virtualResult);
    
    // Verify performance improvements
    double throughputImprovement = virtualResult.getThroughput() / platformResult.getThroughput();
    double p95LatencyImprovement = virtualResult.getP95Latency() / platformResult.getP95Latency();
    
    assertThat("Virtual threads should provide higher throughput", 
        throughputImprovement, greaterThan(VIRTUAL_THREAD_THROUGHPUT_IMPROVEMENT_THRESHOLD));
    assertThat("Virtual threads should provide lower P95 latency", 
        p95LatencyImprovement, lessThan(VIRTUAL_THREAD_LATENCY_IMPROVEMENT_THRESHOLD));
  }
  
  /**
   * Tests the performance of scaling with increasing concurrent operations using platform threads vs virtual threads.
   * This test measures how well each threading model scales as concurrency increases.
   */
  @Test
  public void testScalingPerformance() throws Exception {
    // Create thread factories
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Test concurrency levels
    int[] concurrencyLevels = {100, 500, 1000, 2000, 5000};
    
    System.out.println("\nScaling Performance Test:");
    System.out.println("Concurrency | Platform Throughput | Virtual Throughput | Improvement");
    System.out.println("-----------|-------------------|-------------------|------------");
    
    for (int concurrency : concurrencyLevels) {
      // Measure platform threads performance
      PerformanceResult platformResult = measurePerformance(() -> {
        try {
          return runConcurrentAssetBrowsing(platformThreadFactory, concurrency);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      // Measure virtual threads performance
      PerformanceResult virtualResult = measurePerformance(() -> {
        try {
          return runConcurrentAssetBrowsing(virtualThreadFactory, concurrency);
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      
      double improvement = virtualResult.getThroughput() / platformResult.getThroughput();
      System.out.printf("%10d | %19.2f | %19.2f | %10.2fx%n", 
          concurrency, platformResult.getThroughput(), virtualResult.getThroughput(), improvement);
      
      // For higher concurrency levels, virtual threads should show even greater improvement
      if (concurrency >= 1000) {
        assertThat("Virtual threads should scale better at high concurrency", 
            improvement, greaterThan(VIRTUAL_THREAD_THROUGHPUT_IMPROVEMENT_THRESHOLD));
      }
    }
  }
  
  /**
   * Runs concurrent asset browsing operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use
   * @param concurrentOperations the number of concurrent operations to run
   * @return the operation results containing latency measurements
   */
  private OperationResults runConcurrentAssetBrowsing(ThreadFactory threadFactory, int concurrentOperations) 
      throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    List<Long> latencies = new ArrayList<>(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>(concurrentOperations);
      
      for (int i = 0; i < concurrentOperations; i++) {
        final int limit = 10 + random.nextInt(20); // Random limit between 10-30
        final int offset = random.nextInt(ASSET_COUNT - limit);
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          long startTime = System.nanoTime();
          try {
            inTx(() -> {
              Continuation<Asset> assets = underTest.browseAssets(repositoryId, limit, null);
              assertThat(assets, notNullValue());
              assertThat(assets.size(), greaterThan(0));
            });
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            long endTime = System.nanoTime();
            synchronized (latencies) {
              latencies.add((endTime - startTime) / 1_000_000); // Convert to milliseconds
            }
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(2, TimeUnit.MINUTES);
      
      // Ensure all futures are completed
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      return new OperationResults(latencies, errorCount.get());
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Runs concurrent component retrieval operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use
   * @param concurrentOperations the number of concurrent operations to run
   * @return the operation results containing latency measurements
   */
  private OperationResults runConcurrentComponentRetrieval(ThreadFactory threadFactory, int concurrentOperations) 
      throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    List<Long> latencies = new ArrayList<>(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>(concurrentOperations);
      
      for (int i = 0; i < concurrentOperations; i++) {
        final int componentId = random.nextInt(COMPONENT_COUNT) + 1;
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          long startTime = System.nanoTime();
          try {
            inTx(() -> {
              Component component = componentStore.readComponent(componentId).orElse(null);
              assertThat(component, notNullValue());
              assertThat(component.componentId(), is(componentId));
            });
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            long endTime = System.nanoTime();
            synchronized (latencies) {
              latencies.add((endTime - startTime) / 1_000_000); // Convert to milliseconds
            }
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(2, TimeUnit.MINUTES);
      
      // Ensure all futures are completed
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      return new OperationResults(latencies, errorCount.get());
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Runs concurrent asset search operations using the specified thread factory.
   *
   * @param threadFactory the thread factory to use
   * @param concurrentOperations the number of concurrent operations to run
   * @return the operation results containing latency measurements
   */
  private OperationResults runConcurrentAssetSearch(ThreadFactory threadFactory, int concurrentOperations) 
      throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory);
    CountDownLatch latch = new CountDownLatch(concurrentOperations);
    List<Long> latencies = new ArrayList<>(concurrentOperations);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    try {
      // Submit tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>(concurrentOperations);
      
      for (int i = 0; i < concurrentOperations; i++) {
        final String searchPattern = "/path/to/asset-" + (random.nextInt(50) * 100) + "*.jar";
        
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          long startTime = System.nanoTime();
          try {
            inTx(() -> {
              List<String> patterns = List.of(searchPattern);
              Continuation<Asset> assets = underTest.findAssetsByPattern(repositoryId, patterns, 20, null);
              assertThat(assets, notNullValue());
            });
          }
          catch (Exception e) {
            errorCount.incrementAndGet();
          }
          finally {
            long endTime = System.nanoTime();
            synchronized (latencies) {
              latencies.add((endTime - startTime) / 1_000_000); // Convert to milliseconds
            }
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      latch.await(2, TimeUnit.MINUTES);
      
      // Ensure all futures are completed
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      return new OperationResults(latencies, errorCount.get());
    }
    finally {
      executor.shutdown();
    }
  }
  
  /**
   * Measures the performance of an operation over multiple iterations.
   *
   * @param operation the operation to measure
   * @return the performance result
   */
  private PerformanceResult measurePerformance(Supplier<OperationResults> operation) {
    List<Double> throughputs = new ArrayList<>(MEASUREMENT_ITERATIONS);
    List<Double> p95Latencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
    List<Double> p99Latencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
    List<Double> avgLatencies = new ArrayList<>(MEASUREMENT_ITERATIONS);
    AtomicLong totalErrors = new AtomicLong(0);
    
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
      Instant start = Instant.now();
      OperationResults results = operation.get();
      Instant end = Instant.now();
      
      // Calculate metrics
      double durationSeconds = Duration.between(start, end).toMillis() / 1000.0;
      double throughput = CONCURRENT_OPERATIONS / durationSeconds;
      
      // Sort latencies for percentile calculation
      List<Long> sortedLatencies = new ArrayList<>(results.getLatencies());
      sortedLatencies.sort(Long::compareTo);
      
      // Calculate percentiles
      int p95Index = (int) Math.ceil(0.95 * sortedLatencies.size()) - 1;
      int p99Index = (int) Math.ceil(0.99 * sortedLatencies.size()) - 1;
      double p95Latency = sortedLatencies.get(p95Index);
      double p99Latency = sortedLatencies.get(p99Index);
      
      // Calculate average latency
      double avgLatency = sortedLatencies.stream().mapToLong(Long::longValue).average().orElse(0);
      
      // Record metrics
      throughputs.add(throughput);
      p95Latencies.add(p95Latency);
      p99Latencies.add(p99Latency);
      avgLatencies.add(avgLatency);
      totalErrors.addAndGet(results.getErrorCount());
      
      System.out.printf("Iteration %d: Throughput=%.2f ops/s, Avg=%.2fms, P95=%.2fms, P99=%.2fms, Errors=%d%n",
          i + 1, throughput, avgLatency, p95Latency, p99Latency, results.getErrorCount());
    }
    
    // Calculate averages
    double avgThroughput = throughputs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double avgP95Latency = p95Latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double avgP99Latency = p99Latencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    double avgAvgLatency = avgLatencies.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    
    return new PerformanceResult(avgThroughput, avgAvgLatency, avgP95Latency, avgP99Latency, totalErrors.get());
  }
  
  /**
   * Prints a comparison of performance results between platform threads and virtual threads.
   *
   * @param operationName the name of the operation being compared
   * @param platformResult the performance result for platform threads
   * @param virtualResult the performance result for virtual threads
   */
  private void printPerformanceComparison(String operationName, PerformanceResult platformResult, 
      PerformanceResult virtualResult) {
    System.out.println("\n" + operationName + " Performance Comparison:");
    System.out.println("Metric       | Platform Threads | Virtual Threads | Improvement");
    System.out.println("-------------|-----------------|----------------|------------");
    
    double throughputImprovement = virtualResult.getThroughput() / platformResult.getThroughput();
    double avgLatencyImprovement = platformResult.getAvgLatency() / virtualResult.getAvgLatency();
    double p95LatencyImprovement = platformResult.getP95Latency() / virtualResult.getP95Latency();
    double p99LatencyImprovement = platformResult.getP99Latency() / virtualResult.getP99Latency();
    
    System.out.printf("Throughput   | %14.2f | %14.2f | %10.2fx%n", 
        platformResult.getThroughput(), virtualResult.getThroughput(), throughputImprovement);
    System.out.printf("Avg Latency  | %14.2f | %14.2f | %10.2fx%n", 
        platformResult.getAvgLatency(), virtualResult.getAvgLatency(), avgLatencyImprovement);
    System.out.printf("P95 Latency  | %14.2f | %14.2f | %10.2fx%n", 
        platformResult.getP95Latency(), virtualResult.getP95Latency(), p95LatencyImprovement);
    System.out.printf("P99 Latency  | %14.2f | %14.2f | %10.2fx%n", 
        platformResult.getP99Latency(), virtualResult.getP99Latency(), p99LatencyImprovement);
    System.out.printf("Error Count  | %14d | %14d | %n", 
        platformResult.getErrorCount(), virtualResult.getErrorCount());
  }
  
  /**
   * Class representing the results of a performance operation, including latency measurements and error count.
   */
  private static class OperationResults {
    private final List<Long> latencies;
    private final int errorCount;
    
    public OperationResults(List<Long> latencies, int errorCount) {
      this.latencies = latencies;
      this.errorCount = errorCount;
    }
    
    public List<Long> getLatencies() {
      return latencies;
    }
    
    public int getErrorCount() {
      return errorCount;
    }
  }
  
  /**
   * Class representing the performance result of an operation, including throughput and latency metrics.
   */
  private static class PerformanceResult {
    private final double throughput;
    private final double avgLatency;
    private final double p95Latency;
    private final double p99Latency;
    private final long errorCount;
    
    public PerformanceResult(double throughput, double avgLatency, double p95Latency, double p99Latency, 
        long errorCount) {
      this.throughput = throughput;
      this.avgLatency = avgLatency;
      this.p95Latency = p95Latency;
      this.p99Latency = p99Latency;
      this.errorCount = errorCount;
    }
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getAvgLatency() {
      return avgLatency;
    }
    
    public double getP95Latency() {
      return p95Latency;
    }
    
    public double getP99Latency() {
      return p99Latency;
    }
    
    public long getErrorCount() {
      return errorCount;
    }
  }
}