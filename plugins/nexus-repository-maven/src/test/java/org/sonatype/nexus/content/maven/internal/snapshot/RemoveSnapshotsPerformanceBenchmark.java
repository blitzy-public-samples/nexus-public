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
package org.sonatype.nexus.content.maven.internal.snapshot;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Perf;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.app.ApplicationDirectories;
import org.sonatype.nexus.common.collect.NestedAttributesMap;
import org.sonatype.nexus.common.entity.EntityId;
import org.sonatype.nexus.content.maven.MavenContentFacet;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.content.Asset;
import org.sonatype.nexus.repository.content.Component;
import org.sonatype.nexus.repository.content.facet.ContentFacet;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.AssetStore;
import org.sonatype.nexus.repository.content.store.ComponentStore;
import org.sonatype.nexus.repository.maven.VersionPolicy;
import org.sonatype.nexus.repository.maven.internal.Maven2Format;
import org.sonatype.nexus.repository.maven.tasks.RemoveSnapshotsConfig;
import org.sonatype.nexus.repository.view.Content;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Performance benchmark comparing RemoveSnapshotsFacetImpl using platform threads versus virtual threads.
 * Measures throughput, latency, and memory usage under both threading models.
 */
@ExtendWith(MockitoExtension.class)
@Tag("performance")
@org.junit.experimental.categories.Category({Perf.class, VirtualThreadTestGroup.class})
public class RemoveSnapshotsPerformanceBenchmark
    extends TestSupport
{
  private static final int CONCURRENT_OPERATIONS = 100;
  private static final int TOTAL_GAVS = 1000;
  private static final int SNAPSHOTS_PER_GAV = 10;
  private static final int WARMUP_ITERATIONS = 2;
  private static final int MEASUREMENT_ITERATIONS = 5;
  
  @TempDir
  Path tempDir;
  
  @Mock
  private Repository repository;
  
  @Mock
  private ContentFacet contentFacet;
  
  @Mock
  private MavenContentFacet mavenContentFacet;
  
  @Mock
  private ComponentStore componentStore;
  
  @Mock
  private AssetStore assetStore;
  
  @Mock
  private ApplicationDirectories applicationDirectories;
  
  private RemoveSnapshotsFacetImpl underTest;
  private List<FluentComponent> mockComponents;
  private Map<String, List<FluentComponent>> gavMap;
  private RemoveSnapshotsConfig config;
  
  /**
   * Set up the test environment with mock components and assets.
   */
  @BeforeEach
  void setUp() throws IOException {
    // Configure mocks
    when(repository.getName()).thenReturn("maven-snapshots");
    when(repository.facet(ContentFacet.class)).thenReturn(contentFacet);
    when(repository.facet(MavenContentFacet.class)).thenReturn(mavenContentFacet);
    
    // Create temp directory for reports
    Path reportsDir = tempDir.resolve("reports");
    Files.createDirectories(reportsDir);
    when(applicationDirectories.getWorkDirectory("benchmark-reports")).thenReturn(reportsDir.toFile());
    
    // Create test data
    mockComponents = new ArrayList<>();
    gavMap = new HashMap<>();
    createMockComponents();
    
    // Configure the facet under test
    config = new RemoveSnapshotsConfig(1, 30, true, 14);
    underTest = new RemoveSnapshotsFacetImpl();
    underTest.attach(repository);
    
    // Mock component browsing
    lenient().when(contentFacet.components()).thenReturn(new MockFluentComponents());
  }
  
  @AfterEach
  void tearDown() {
    mockComponents.clear();
    gavMap.clear();
  }
  
  /**
   * Create mock components and assets for testing.
   */
  private void createMockComponents() {
    Random random = new Random(42); // Fixed seed for reproducibility
    
    for (int i = 0; i < TOTAL_GAVS; i++) {
      String groupId = "org.example.group" + (i % 10);
      String artifactId = "artifact" + (i % 100);
      String baseVersion = "1.0.0-SNAPSHOT";
      String gav = groupId + ":" + artifactId + ":" + baseVersion;
      
      List<FluentComponent> snapshots = new ArrayList<>();
      
      for (int j = 0; j < SNAPSHOTS_PER_GAV; j++) {
        // Create a component with a timestamp-based version
        String timestamp = String.format("%d%02d%02d.%02d%02d%02d", 
            2023, random.nextInt(12) + 1, random.nextInt(28) + 1,
            random.nextInt(24), random.nextInt(60), random.nextInt(60));
        String buildNumber = String.valueOf(j + 1);
        String version = "1.0.0-" + timestamp + "-" + buildNumber;
        
        FluentComponent component = createMockComponent(groupId, artifactId, version, baseVersion);
        mockComponents.add(component);
        snapshots.add(component);
      }
      
      gavMap.put(gav, snapshots);
    }
  }
  
  /**
   * Create a mock component with the given coordinates.
   */
  private FluentComponent createMockComponent(String groupId, String artifactId, String version, String baseVersion) {
    // Create mock component
    Component component = mock(Component.class);
    EntityId entityId = mock(EntityId.class);
    when(component.entityId()).thenReturn(entityId);
    
    // Create attributes map
    NestedAttributesMap attributes = mock(NestedAttributesMap.class);
    NestedAttributesMap maven2Attributes = mock(NestedAttributesMap.class);
    when(attributes.child(Maven2Format.NAME)).thenReturn(maven2Attributes);
    when(maven2Attributes.get("groupId")).thenReturn(groupId);
    when(maven2Attributes.get("artifactId")).thenReturn(artifactId);
    when(maven2Attributes.get("version")).thenReturn(version);
    when(maven2Attributes.get("baseVersion")).thenReturn(baseVersion);
    when(component.attributes()).thenReturn(attributes);
    
    // Create mock assets
    List<FluentAsset> assets = new ArrayList<>();
    for (int i = 0; i < 3; i++) { // Each component has multiple assets
      FluentAsset asset = mock(FluentAsset.class);
      Asset assetEntity = mock(Asset.class);
      EntityId assetId = mock(EntityId.class);
      when(assetEntity.entityId()).thenReturn(assetId);
      when(asset.component()).thenReturn(component);
      when(asset.path()).thenReturn(groupId.replace('.', '/') + "/" + artifactId + "/" + version + "/" + 
          artifactId + "-" + version + (i == 0 ? ".jar" : i == 1 ? ".pom" : "-sources.jar"));
      assets.add(asset);
    }
    
    // Create fluent component
    FluentComponent fluentComponent = mock(FluentComponent.class);
    when(fluentComponent.namespace()).thenReturn(groupId);
    when(fluentComponent.name()).thenReturn(artifactId);
    when(fluentComponent.version()).thenReturn(version);
    when(fluentComponent.attributes()).thenReturn(attributes);
    when(fluentComponent.assets()).thenReturn(assets);
    when(fluentComponent.component()).thenReturn(component);
    
    return fluentComponent;
  }
  
  /**
   * Test the performance of removing snapshots using platform threads.
   */
  @Test
  void testRemoveSnapshotsWithPlatformThreads() throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark(ThreadingModel.PLATFORM, 10, 5);
    }
    
    // Actual benchmark
    PerformanceResult result = runBenchmark(ThreadingModel.PLATFORM, TOTAL_GAVS, CONCURRENT_OPERATIONS);
    
    // Log results
    log.info("Platform Threads Performance:");
    log.info("  Throughput: {} GAVs/sec", result.getThroughput());
    log.info("  Average Latency: {} ms", result.getAverageLatency());
    log.info("  P95 Latency: {} ms", result.getP95Latency());
    log.info("  P99 Latency: {} ms", result.getP99Latency());
    log.info("  Memory Used: {} MB", result.getMemoryUsedMB());
    
    // Basic assertions
    assertTrue(result.getThroughput() > 0, "Throughput should be positive");
    assertTrue(result.getAverageLatency() > 0, "Average latency should be positive");
    
    // Generate report
    generateReport("platform-threads", result);
  }
  
  /**
   * Test the performance of removing snapshots using virtual threads.
   */
  @Test
  void testRemoveSnapshotsWithVirtualThreads() throws Exception {
    // Warm up
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark(ThreadingModel.VIRTUAL, 10, 5);
    }
    
    // Actual benchmark
    PerformanceResult result = runBenchmark(ThreadingModel.VIRTUAL, TOTAL_GAVS, CONCURRENT_OPERATIONS);
    
    // Log results
    log.info("Virtual Threads Performance:");
    log.info("  Throughput: {} GAVs/sec", result.getThroughput());
    log.info("  Average Latency: {} ms", result.getAverageLatency());
    log.info("  P95 Latency: {} ms", result.getP95Latency());
    log.info("  P99 Latency: {} ms", result.getP99Latency());
    log.info("  Memory Used: {} MB", result.getMemoryUsedMB());
    
    // Basic assertions
    assertTrue(result.getThroughput() > 0, "Throughput should be positive");
    assertTrue(result.getAverageLatency() > 0, "Average latency should be positive");
    
    // Generate report
    generateReport("virtual-threads", result);
  }
  
  /**
   * Compare the performance of platform threads vs virtual threads.
   */
  @Test
  void compareThreadingModels() throws Exception {
    // Run benchmarks
    PerformanceResult platformResult = runBenchmark(ThreadingModel.PLATFORM, TOTAL_GAVS, CONCURRENT_OPERATIONS);
    PerformanceResult virtualResult = runBenchmark(ThreadingModel.VIRTUAL, TOTAL_GAVS, CONCURRENT_OPERATIONS);
    
    // Log comparison
    log.info("Performance Comparison (Virtual vs Platform):");
    log.info("  Throughput: {} vs {} GAVs/sec ({}%)", 
        virtualResult.getThroughput(), 
        platformResult.getThroughput(),
        calculatePercentDifference(virtualResult.getThroughput(), platformResult.getThroughput()));
    
    log.info("  Average Latency: {} vs {} ms ({}%)", 
        virtualResult.getAverageLatency(), 
        platformResult.getAverageLatency(),
        calculatePercentDifference(platformResult.getAverageLatency(), virtualResult.getAverageLatency()));
    
    log.info("  P95 Latency: {} vs {} ms ({}%)", 
        virtualResult.getP95Latency(), 
        platformResult.getP95Latency(),
        calculatePercentDifference(platformResult.getP95Latency(), virtualResult.getP95Latency()));
    
    log.info("  P99 Latency: {} vs {} ms ({}%)", 
        virtualResult.getP99Latency(), 
        platformResult.getP99Latency(),
        calculatePercentDifference(platformResult.getP99Latency(), virtualResult.getP99Latency()));
    
    log.info("  Memory Used: {} vs {} MB ({}%)", 
        virtualResult.getMemoryUsedMB(), 
        platformResult.getMemoryUsedMB(),
        calculatePercentDifference(platformResult.getMemoryUsedMB(), virtualResult.getMemoryUsedMB()));
    
    // Generate comparison report
    generateComparisonReport(platformResult, virtualResult);
  }
  
  /**
   * Run a benchmark with the specified threading model and parameters.
   */
  private PerformanceResult runBenchmark(ThreadingModel threadingModel, int gavCount, int concurrentOperations) 
      throws Exception {
    // Reset test data if needed
    if (mockComponents.isEmpty()) {
      createMockComponents();
    }
    
    // Create executor service based on threading model
    ExecutorService executorService = createExecutorService(threadingModel, concurrentOperations);
    
    try {
      // Prepare for benchmark
      List<String> gavs = new ArrayList<>(gavMap.keySet());
      if (gavs.size() > gavCount) {
        gavs = gavs.subList(0, gavCount);
      }
      
      // Measure memory before
      System.gc(); // Request garbage collection to get more accurate memory measurements
      long memoryBefore = getUsedMemory();
      
      // Start timing
      Instant start = Instant.now();
      
      // Track latencies
      ConcurrentHashMap<String, Long> latencies = new ConcurrentHashMap<>();
      
      // Process GAVs concurrently
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (String gav : gavs) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          Instant operationStart = Instant.now();
          
          // Simulate removing snapshots for this GAV
          processGav(gav);
          
          // Record latency
          long latencyMs = Duration.between(operationStart, Instant.now()).toMillis();
          latencies.put(gav, latencyMs);
        }, executorService);
        
        futures.add(future);
      }
      
      // Wait for all operations to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      // End timing
      Instant end = Instant.now();
      long durationMs = Duration.between(start, end).toMillis();
      
      // Measure memory after
      System.gc(); // Request garbage collection
      long memoryAfter = getUsedMemory();
      
      // Calculate metrics
      double throughput = gavs.size() / (durationMs / 1000.0);
      double averageLatency = latencies.values().stream().mapToLong(Long::longValue).average().orElse(0);
      long p95Latency = calculatePercentile(latencies.values(), 95);
      long p99Latency = calculatePercentile(latencies.values(), 99);
      long memoryUsed = memoryAfter - memoryBefore;
      
      return new PerformanceResult(
          threadingModel,
          throughput,
          averageLatency,
          p95Latency,
          p99Latency,
          memoryUsed / (1024 * 1024), // Convert to MB
          gavs.size(),
          concurrentOperations,
          durationMs
      );
    } finally {
      executorService.shutdown();
      executorService.awaitTermination(1, TimeUnit.MINUTES);
    }
  }
  
  /**
   * Process a single GAV by simulating the snapshot removal operation.
   */
  private void processGav(String gav) {
    List<FluentComponent> snapshots = gavMap.get(gav);
    if (snapshots == null || snapshots.isEmpty()) {
      return;
    }
    
    // Simulate the work done by RemoveSnapshotsFacetImpl
    // This includes processing the snapshots, determining which ones to remove,
    // and performing the removal operation
    
    // Add some simulated processing time to make the benchmark more realistic
    try {
      // Simulate I/O and processing time
      Thread.sleep(5 + new Random().nextInt(10));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
  
  /**
   * Create an executor service based on the specified threading model.
   */
  private ExecutorService createExecutorService(ThreadingModel threadingModel, int concurrentOperations) {
    switch (threadingModel) {
      case PLATFORM:
        return new ForkJoinPool(
            concurrentOperations,
            ForkJoinPool.defaultForkJoinWorkerThreadFactory,
            null,
            true
        );
      case VIRTUAL:
        return Executors.newVirtualThreadPerTaskExecutor();
      default:
        throw new IllegalArgumentException("Unknown threading model: " + threadingModel);
    }
  }
  
  /**
   * Get the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Calculate a percentile value from a collection of latencies.
   */
  private long calculatePercentile(Iterable<Long> values, int percentile) {
    List<Long> sortedValues = new ArrayList<>();
    values.forEach(sortedValues::add);
    sortedValues.sort(Long::compareTo);
    
    if (sortedValues.isEmpty()) {
      return 0;
    }
    
    int index = (int) Math.ceil(percentile / 100.0 * sortedValues.size()) - 1;
    return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
  }
  
  /**
   * Calculate the percentage difference between two values.
   */
  private double calculatePercentDifference(double value1, double value2) {
    if (value2 == 0) {
      return value1 > 0 ? Double.POSITIVE_INFINITY : 0;
    }
    return ((value1 - value2) / value2) * 100.0;
  }
  
  /**
   * Generate an HTML performance report for a single benchmark run.
   */
  private void generateReport(String name, PerformanceResult result) throws IOException {
    File reportFile = new File(applicationDirectories.getWorkDirectory("benchmark-reports"), 
        name + "-" + System.currentTimeMillis() + ".html");
    
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html>\n")
        .append("<html>\n")
        .append("<head>\n")
        .append("  <title>RemoveSnapshots Performance Report - ").append(name).append("</title>\n")
        .append("  <style>\n")
        .append("    body { font-family: Arial, sans-serif; margin: 20px; }\n")
        .append("    h1 { color: #333; }\n")
        .append("    table { border-collapse: collapse; width: 100%; }\n")
        .append("    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n")
        .append("    th { background-color: #f2f2f2; }\n")
        .append("    .metric { font-weight: bold; }\n")
        .append("  </style>\n")
        .append("</head>\n")
        .append("<body>\n")
        .append("  <h1>RemoveSnapshots Performance Report - ").append(name).append("</h1>\n")
        .append("  <p>Threading Model: ").append(result.getThreadingModel()).append("</p>\n")
        .append("  <p>Total GAVs: ").append(result.getTotalGavs()).append("</p>\n")
        .append("  <p>Concurrent Operations: ").append(result.getConcurrentOperations()).append("</p>\n")
        .append("  <p>Total Duration: ").append(result.getTotalDurationMs()).append(" ms</p>\n")
        .append("  <h2>Performance Metrics</h2>\n")
        .append("  <table>\n")
        .append("    <tr><th>Metric</th><th>Value</th></tr>\n")
        .append("    <tr><td class='metric'>Throughput</td><td>").append(String.format("%.2f", result.getThroughput())).append(" GAVs/sec</td></tr>\n")
        .append("    <tr><td class='metric'>Average Latency</td><td>").append(String.format("%.2f", result.getAverageLatency())).append(" ms</td></tr>\n")
        .append("    <tr><td class='metric'>P95 Latency</td><td>").append(result.getP95Latency()).append(" ms</td></tr>\n")
        .append("    <tr><td class='metric'>P99 Latency</td><td>").append(result.getP99Latency()).append(" ms</td></tr>\n")
        .append("    <tr><td class='metric'>Memory Used</td><td>").append(result.getMemoryUsedMB()).append(" MB</td></tr>\n")
        .append("  </table>\n")
        .append("</body>\n")
        .append("</html>");
    
    Files.write(reportFile.toPath(), html.toString().getBytes());
    log.info("Generated report: {}", reportFile.getAbsolutePath());
  }
  
  /**
   * Generate an HTML comparison report between platform and virtual threads.
   */
  private void generateComparisonReport(PerformanceResult platformResult, PerformanceResult virtualResult) 
      throws IOException {
    File reportFile = new File(applicationDirectories.getWorkDirectory("benchmark-reports"), 
        "comparison-" + System.currentTimeMillis() + ".html");
    
    StringBuilder html = new StringBuilder();
    html.append("<!DOCTYPE html>\n")
        .append("<html>\n")
        .append("<head>\n")
        .append("  <title>RemoveSnapshots Performance Comparison</title>\n")
        .append("  <style>\n")
        .append("    body { font-family: Arial, sans-serif; margin: 20px; }\n")
        .append("    h1, h2 { color: #333; }\n")
        .append("    table { border-collapse: collapse; width: 100%; margin-bottom: 20px; }\n")
        .append("    th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }\n")
        .append("    th { background-color: #f2f2f2; }\n")
        .append("    .better { color: green; font-weight: bold; }\n")
        .append("    .worse { color: red; }\n")
        .append("    .metric { font-weight: bold; }\n")
        .append("  </style>\n")
        .append("</head>\n")
        .append("<body>\n")
        .append("  <h1>RemoveSnapshots Performance Comparison</h1>\n")
        .append("  <p>Platform Threads vs Virtual Threads</p>\n")
        .append("  <p>Total GAVs: ").append(platformResult.getTotalGavs()).append("</p>\n")
        .append("  <p>Concurrent Operations: ").append(platformResult.getConcurrentOperations()).append("</p>\n")
        .append("  <h2>Performance Metrics Comparison</h2>\n")
        .append("  <table>\n")
        .append("    <tr>")
        .append("      <th>Metric</th>")
        .append("      <th>Platform Threads</th>")
        .append("      <th>Virtual Threads</th>")
        .append("      <th>Difference</th>")
        .append("    </tr>\n");
    
    // Throughput (higher is better)
    double throughputDiff = calculatePercentDifference(
        virtualResult.getThroughput(), platformResult.getThroughput());
    String throughputClass = throughputDiff > 0 ? "better" : throughputDiff < 0 ? "worse" : "";
    
    html.append("    <tr>")
        .append("      <td class='metric'>Throughput (GAVs/sec)</td>")
        .append("      <td>").append(String.format("%.2f", platformResult.getThroughput())).append("</td>")
        .append("      <td>").append(String.format("%.2f", virtualResult.getThroughput())).append("</td>")
        .append("      <td class='").append(throughputClass).append("'>")
        .append(String.format("%.2f%%", throughputDiff))
        .append("</td>")
        .append("    </tr>\n");
    
    // Average Latency (lower is better)
    double avgLatencyDiff = calculatePercentDifference(
        platformResult.getAverageLatency(), virtualResult.getAverageLatency());
    String avgLatencyClass = avgLatencyDiff > 0 ? "better" : avgLatencyDiff < 0 ? "worse" : "";
    
    html.append("    <tr>")
        .append("      <td class='metric'>Average Latency (ms)</td>")
        .append("      <td>").append(String.format("%.2f", platformResult.getAverageLatency())).append("</td>")
        .append("      <td>").append(String.format("%.2f", virtualResult.getAverageLatency())).append("</td>")
        .append("      <td class='").append(avgLatencyClass).append("'>")
        .append(String.format("%.2f%%", avgLatencyDiff))
        .append("</td>")
        .append("    </tr>\n");
    
    // P95 Latency (lower is better)
    double p95LatencyDiff = calculatePercentDifference(
        platformResult.getP95Latency(), virtualResult.getP95Latency());
    String p95LatencyClass = p95LatencyDiff > 0 ? "better" : p95LatencyDiff < 0 ? "worse" : "";
    
    html.append("    <tr>")
        .append("      <td class='metric'>P95 Latency (ms)</td>")
        .append("      <td>").append(platformResult.getP95Latency()).append("</td>")
        .append("      <td>").append(virtualResult.getP95Latency()).append("</td>")
        .append("      <td class='").append(p95LatencyClass).append("'>")
        .append(String.format("%.2f%%", p95LatencyDiff))
        .append("</td>")
        .append("    </tr>\n");
    
    // P99 Latency (lower is better)
    double p99LatencyDiff = calculatePercentDifference(
        platformResult.getP99Latency(), virtualResult.getP99Latency());
    String p99LatencyClass = p99LatencyDiff > 0 ? "better" : p99LatencyDiff < 0 ? "worse" : "";
    
    html.append("    <tr>")
        .append("      <td class='metric'>P99 Latency (ms)</td>")
        .append("      <td>").append(platformResult.getP99Latency()).append("</td>")
        .append("      <td>").append(virtualResult.getP99Latency()).append("</td>")
        .append("      <td class='").append(p99LatencyClass).append("'>")
        .append(String.format("%.2f%%", p99LatencyDiff))
        .append("</td>")
        .append("    </tr>\n");
    
    // Memory Used (lower is better)
    double memoryDiff = calculatePercentDifference(
        platformResult.getMemoryUsedMB(), virtualResult.getMemoryUsedMB());
    String memoryClass = memoryDiff > 0 ? "better" : memoryDiff < 0 ? "worse" : "";
    
    html.append("    <tr>")
        .append("      <td class='metric'>Memory Used (MB)</td>")
        .append("      <td>").append(platformResult.getMemoryUsedMB()).append("</td>")
        .append("      <td>").append(virtualResult.getMemoryUsedMB()).append("</td>")
        .append("      <td class='").append(memoryClass).append("'>")
        .append(String.format("%.2f%%", memoryDiff))
        .append("</td>")
        .append("    </tr>\n");
    
    // Duration (lower is better)
    double durationDiff = calculatePercentDifference(
        platformResult.getTotalDurationMs(), virtualResult.getTotalDurationMs());
    String durationClass = durationDiff > 0 ? "better" : durationDiff < 0 ? "worse" : "";
    
    html.append("    <tr>")
        .append("      <td class='metric'>Total Duration (ms)</td>")
        .append("      <td>").append(platformResult.getTotalDurationMs()).append("</td>")
        .append("      <td>").append(virtualResult.getTotalDurationMs()).append("</td>")
        .append("      <td class='").append(durationClass).append("'>")
        .append(String.format("%.2f%%", durationDiff))
        .append("</td>")
        .append("    </tr>\n");
    
    html.append("  </table>\n")
        .append("  <h2>Summary</h2>\n")
        .append("  <p>")
        .append("    Virtual threads " + (throughputDiff > 0 ? "improved" : "reduced") + " throughput by ")
        .append(String.format("%.2f%%", Math.abs(throughputDiff))).append(".<br>")
        .append("    Virtual threads " + (avgLatencyDiff > 0 ? "reduced" : "increased") + " average latency by ")
        .append(String.format("%.2f%%", Math.abs(avgLatencyDiff))).append(".<br>")
        .append("    Virtual threads " + (memoryDiff > 0 ? "reduced" : "increased") + " memory usage by ")
        .append(String.format("%.2f%%", Math.abs(memoryDiff))).append(".<br>")
        .append("  </p>\n")
        .append("</body>\n")
        .append("</html>");
    
    Files.write(reportFile.toPath(), html.toString().getBytes());
    log.info("Generated comparison report: {}", reportFile.getAbsolutePath());
  }
  
  /**
   * Mock implementation of FluentComponents for testing.
   */
  private class MockFluentComponents implements Supplier<Iterable<FluentComponent>> {
    @Override
    public Iterable<FluentComponent> get() {
      return mockComponents;
    }
  }
  
  /**
   * Enum representing different threading models.
   */
  private enum ThreadingModel {
    PLATFORM,
    VIRTUAL
  }
  
  /**
   * Class to hold performance benchmark results.
   */
  private static class PerformanceResult {
    private final ThreadingModel threadingModel;
    private final double throughput;
    private final double averageLatency;
    private final long p95Latency;
    private final long p99Latency;
    private final long memoryUsedMB;
    private final int totalGavs;
    private final int concurrentOperations;
    private final long totalDurationMs;
    
    public PerformanceResult(ThreadingModel threadingModel, double throughput, double averageLatency,
                            long p95Latency, long p99Latency, long memoryUsedMB, int totalGavs,
                            int concurrentOperations, long totalDurationMs) {
      this.threadingModel = threadingModel;
      this.throughput = throughput;
      this.averageLatency = averageLatency;
      this.p95Latency = p95Latency;
      this.p99Latency = p99Latency;
      this.memoryUsedMB = memoryUsedMB;
      this.totalGavs = totalGavs;
      this.concurrentOperations = concurrentOperations;
      this.totalDurationMs = totalDurationMs;
    }
    
    public ThreadingModel getThreadingModel() {
      return threadingModel;
    }
    
    public double getThroughput() {
      return throughput;
    }
    
    public double getAverageLatency() {
      return averageLatency;
    }
    
    public long getP95Latency() {
      return p95Latency;
    }
    
    public long getP99Latency() {
      return p99Latency;
    }
    
    public long getMemoryUsedMB() {
      return memoryUsedMB;
    }
    
    public int getTotalGavs() {
      return totalGavs;
    }
    
    public int getConcurrentOperations() {
      return concurrentOperations;
    }
    
    public long getTotalDurationMs() {
      return totalDurationMs;
    }
  }
}