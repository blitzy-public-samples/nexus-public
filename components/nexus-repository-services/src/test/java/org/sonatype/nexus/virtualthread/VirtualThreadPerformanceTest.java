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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.event.EventManager;
import org.sonatype.nexus.repository.Format;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.cache.CacheController;
import org.sonatype.nexus.repository.cache.CacheControllerHolder;
import org.sonatype.nexus.repository.cache.CacheInfo;
import org.sonatype.nexus.repository.proxy.ProxyFacetSupport;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Request;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Spy;

import static com.google.common.base.Charsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static org.sonatype.nexus.repository.http.HttpMethods.GET;

/**
 * Performance benchmark comparing Virtual Threads vs Platform Threads in Nexus Repository Services.
 * This test measures throughput, response times, memory utilization, and scalability under varying load conditions.
 */
public class VirtualThreadPerformanceTest
    extends TestSupport
{
  // Test configuration constants
  private static final int WARMUP_ITERATIONS = 3;
  private static final int BENCHMARK_ITERATIONS = 5;
  private static final int[] CONCURRENCY_LEVELS = {100, 1000, 5000, 10000};
  private static final int MAX_PATHS = 1000;
  private static final int SIMULATED_IO_DELAY_MS = 50; // Simulates network/disk I/O delay
  private static final int BENCHMARK_DURATION_SECONDS = 10;
  
  // Content constants
  private static final byte[] ASSET_CONTENT = "REPOSITORY ASSET CONTENT".getBytes(UTF_8);
  private static final String ASSET_PREFIX = "asset/";
  
  // Mocks for repository infrastructure
  @Mock
  Repository repository;
  
  @Mock
  CacheController cacheController;
  
  @Mock
  CacheControllerHolder cacheControllerHolder;
  
  @Mock
  CacheInfo cacheInfo;
  
  @Mock
  AttributesMap attributesMap;
  
  @Mock
  Content assetContent;
  
  @Mock
  EventManager eventManager;
  
  @Mock
  Format format;
  
  // Test state
  private final Random random = new Random(42); // Fixed seed for reproducibility
  private final Map<String, Content> storage = new ConcurrentHashMap<>();
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  // Proxy facet implementation for testing
  @Spy
  ProxyFacetSupport platformThreadProxyFacet = createProxyFacet(false);
  
  @Spy
  ProxyFacetSupport virtualThreadProxyFacet = createProxyFacet(true);
  
  /**
   * Creates a proxy facet implementation for testing.
   * 
   * @param useVirtualThreads whether to use virtual threads for I/O operations
   * @return the proxy facet implementation
   */
  private ProxyFacetSupport createProxyFacet(final boolean useVirtualThreads) {
    return new ProxyFacetSupport() {
      @Nullable
      @Override
      protected Content getCachedContent(final Context context) {
        return storage.get(context.getRequest().getPath());
      }
      
      @Override
      protected Content store(final Context context, final Content content) {
        storage.put(context.getRequest().getPath(), content);
        return content;
      }
      
      @Override
      protected void indicateVerified(final Context context, final Content content, final CacheInfo cacheInfo) {
        // no-op
      }
      
      @Override
      protected String getUrl(@Nonnull final Context context) {
        return ASSET_PREFIX + context.getRequest().getPath();
      }
      
      @Override
      protected Content fetch(final String url, final Context context, final Content stale) throws IOException {
        // Simulate I/O delay that would benefit from virtual threads
        try {
          if (useVirtualThreads) {
            // Virtual threads should handle this efficiently
            Thread.sleep(SIMULATED_IO_DELAY_MS);
          } else {
            // Platform threads will be blocked during this time
            Thread.sleep(SIMULATED_IO_DELAY_MS);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted during simulated I/O", e);
        }
        
        return assetContent;
      }
    };
  }
  
  @Before
  public void setUp() throws Exception {
    // Set up mock behavior
    when(attributesMap.get(CacheInfo.class)).thenReturn(cacheInfo);
    when(assetContent.getAttributes()).thenReturn(attributesMap);
    when(assetContent.openInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(ASSET_CONTENT));
    when(cacheController.isStale(cacheInfo)).thenReturn(false);
    when(cacheControllerHolder.getContentCacheController()).thenReturn(cacheController);
    when(repository.getName()).thenReturn("test-repo");
    when(format.getValue()).thenReturn("raw");
    when(repository.getFormat()).thenReturn(format);
    
    // Initialize executors
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Set up proxy facets
    platformThreadProxyFacet.installDependencies(eventManager);
    platformThreadProxyFacet.cacheControllerHolder = cacheControllerHolder;
    platformThreadProxyFacet.attach(repository);
    
    virtualThreadProxyFacet.installDependencies(eventManager);
    virtualThreadProxyFacet.cacheControllerHolder = cacheControllerHolder;
    virtualThreadProxyFacet.attach(repository);
    
    // Clear storage before each test
    storage.clear();
  }
  
  @After
  public void tearDown() throws Exception {
    platformThreadExecutor.shutdownNow();
    virtualThreadExecutor.shutdownNow();
  }
  
  /**
   * Creates a request for the given path.
   */
  private Request createRequest(final String path) {
    return new Request.Builder().action(GET).path(path).build();
  }
  
  /**
   * Generates a list of random paths for testing.
   */
  private List<String> generateRandomPaths(final int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> "path-" + random.nextInt(MAX_PATHS))
        .collect(Collectors.toList());
  }
  
  /**
   * Benchmark results data structure.
   */
  private static class BenchmarkResult {
    final long totalRequests;
    final long totalDurationMs;
    final double requestsPerSecond;
    final long p50ResponseTimeMs;
    final long p95ResponseTimeMs;
    final long p99ResponseTimeMs;
    final long maxResponseTimeMs;
    final long memoryUsedBytes;
    
    BenchmarkResult(long totalRequests, long totalDurationMs, List<Long> responseTimes, long memoryUsedBytes) {
      this.totalRequests = totalRequests;
      this.totalDurationMs = totalDurationMs;
      this.requestsPerSecond = totalRequests * 1000.0 / totalDurationMs;
      
      // Sort response times for percentile calculations
      Collections.sort(responseTimes);
      
      // Calculate percentiles
      this.p50ResponseTimeMs = responseTimes.get((int) (responseTimes.size() * 0.5));
      this.p95ResponseTimeMs = responseTimes.get((int) (responseTimes.size() * 0.95));
      this.p99ResponseTimeMs = responseTimes.get((int) (responseTimes.size() * 0.99));
      this.maxResponseTimeMs = responseTimes.get(responseTimes.size() - 1);
      this.memoryUsedBytes = memoryUsedBytes;
    }
    
    @Override
    public String toString() {
      return String.format(
          "Throughput: %.2f req/s, Response Times (ms) - P50: %d, P95: %d, P99: %d, Max: %d, Memory: %.2f MB",
          requestsPerSecond, p50ResponseTimeMs, p95ResponseTimeMs, p99ResponseTimeMs, maxResponseTimeMs,
          memoryUsedBytes / (1024.0 * 1024.0));
    }
  }
  
  /**
   * Runs a benchmark with the given concurrency level and proxy facet.
   */
  private BenchmarkResult runBenchmark(
      final int concurrencyLevel,
      final ProxyFacetSupport proxyFacet,
      final ExecutorService executor,
      final boolean isWarmup) throws Exception {
    
    // Generate random paths for the benchmark
    List<String> paths = generateRandomPaths(concurrencyLevel);
    
    // Create a countdown latch to coordinate the start of all threads
    CountDownLatch startLatch = new CountDownLatch(1);
    
    // Create a countdown latch to track completion
    CountDownLatch completionLatch = new CountDownLatch(concurrencyLevel);
    
    // Track response times
    List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>(concurrencyLevel * 10));
    
    // Track total requests completed
    AtomicInteger requestsCompleted = new AtomicInteger(0);
    
    // Create tasks
    List<Future<?>> futures = new ArrayList<>(concurrencyLevel);
    
    // Measure memory before the test
    System.gc(); // Request garbage collection to get more accurate measurements
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Submit tasks to the executor
    for (int i = 0; i < concurrencyLevel; i++) {
      final String path = paths.get(i % paths.size());
      futures.add(executor.submit(() -> {
        try {
          // Wait for the signal to start
          startLatch.await();
          
          // Run until the benchmark duration is reached
          long endTime = System.currentTimeMillis() + SECONDS.toMillis(isWarmup ? 2 : BENCHMARK_DURATION_SECONDS);
          
          while (System.currentTimeMillis() < endTime) {
            long startRequestTime = System.currentTimeMillis();
            
            try {
              // Execute the request
              Content content = proxyFacet.get(new Context(repository, createRequest(path)));
              try (InputStream in = content.openInputStream()) {
                // Read the content to ensure the operation completes
                byte[] buffer = new byte[1024];
                while (in.read(buffer) != -1) {
                  // Just consume the content
                }
              }
              
              // Record response time
              long responseTime = System.currentTimeMillis() - startRequestTime;
              responseTimes.add(responseTime);
              
              // Increment completed requests counter
              requestsCompleted.incrementAndGet();
              
            } catch (IOException e) {
              log.error("Error during benchmark", e);
            }
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          completionLatch.countDown();
        }
      }));
    }
    
    // Start the benchmark
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for completion
    completionLatch.await(isWarmup ? 5 : BENCHMARK_DURATION_SECONDS + 5, SECONDS);
    long endTime = System.currentTimeMillis();
    
    // Measure memory after the test
    System.gc(); // Request garbage collection to get more accurate measurements
    long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Calculate memory used during the test
    long memoryUsed = memoryAfter - memoryBefore;
    if (memoryUsed < 0) {
      // If negative (due to GC), use a conservative estimate
      memoryUsed = 0;
    }
    
    // Cancel any remaining tasks
    for (Future<?> future : futures) {
      future.cancel(true);
    }
    
    // Return benchmark results
    return new BenchmarkResult(
        requestsCompleted.get(),
        endTime - startTime,
        responseTimes,
        memoryUsed);
  }
  
  /**
   * Runs a complete benchmark comparing platform threads vs virtual threads at the given concurrency level.
   */
  private void runConcurrencyBenchmark(final int concurrencyLevel) throws Exception {
    log.info("Running benchmark with concurrency level: {}", concurrencyLevel);
    
    // Warm up
    log.info("Warming up platform threads...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark(concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, true);
    }
    
    log.info("Warming up virtual threads...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runBenchmark(concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, true);
    }
    
    // Run platform thread benchmark
    log.info("Benchmarking platform threads...");
    List<BenchmarkResult> platformResults = new ArrayList<>();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      BenchmarkResult result = runBenchmark(
          concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, false);
      platformResults.add(result);
      log.info("Platform threads iteration {}: {}", i + 1, result);
    }
    
    // Run virtual thread benchmark
    log.info("Benchmarking virtual threads...");
    List<BenchmarkResult> virtualResults = new ArrayList<>();
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      BenchmarkResult result = runBenchmark(
          concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, false);
      virtualResults.add(result);
      log.info("Virtual threads iteration {}: {}", i + 1, result);
    }
    
    // Calculate average results
    BenchmarkResult avgPlatformResult = calculateAverageResult(platformResults);
    BenchmarkResult avgVirtualResult = calculateAverageResult(virtualResults);
    
    // Log summary
    log.info("\nBenchmark Summary for Concurrency Level: {}\n" +
        "Platform Threads: {}\n" +
        "Virtual Threads:  {}\n" +
        "Improvement:      {:.2f}x throughput, {:.2f}x memory efficiency",
        concurrencyLevel,
        avgPlatformResult,
        avgVirtualResult,
        avgVirtualResult.requestsPerSecond / avgPlatformResult.requestsPerSecond,
        (double) avgPlatformResult.memoryUsedBytes / avgVirtualResult.memoryUsedBytes);
    
    // Verify that virtual threads perform better
    if (concurrencyLevel >= 1000) { // Only assert for higher concurrency levels where the difference should be clear
      assertThat("Virtual threads should have higher throughput",
          avgVirtualResult.requestsPerSecond, greaterThan(avgPlatformResult.requestsPerSecond));
      
      assertThat("Virtual threads should have lower P99 response time",
          avgVirtualResult.p99ResponseTimeMs, lessThan(avgPlatformResult.p99ResponseTimeMs));
      
      assertThat("Virtual threads should use less memory",
          avgVirtualResult.memoryUsedBytes, lessThan(avgPlatformResult.memoryUsedBytes));
    }
  }
  
  /**
   * Calculates the average benchmark result from a list of results.
   */
  private BenchmarkResult calculateAverageResult(List<BenchmarkResult> results) {
    long totalRequests = 0;
    long totalDurationMs = 0;
    long totalP50 = 0;
    long totalP95 = 0;
    long totalP99 = 0;
    long totalMax = 0;
    long totalMemory = 0;
    
    for (BenchmarkResult result : results) {
      totalRequests += result.totalRequests;
      totalDurationMs += result.totalDurationMs;
      totalP50 += result.p50ResponseTimeMs;
      totalP95 += result.p95ResponseTimeMs;
      totalP99 += result.p99ResponseTimeMs;
      totalMax += result.maxResponseTimeMs;
      totalMemory += result.memoryUsedBytes;
    }
    
    // Create a synthetic result with the averages
    List<Long> syntheticResponseTimes = new ArrayList<>();
    syntheticResponseTimes.add(totalP50 / results.size());
    syntheticResponseTimes.add(totalP95 / results.size());
    syntheticResponseTimes.add(totalP99 / results.size());
    syntheticResponseTimes.add(totalMax / results.size());
    
    return new BenchmarkResult(
        totalRequests / results.size(),
        totalDurationMs / results.size(),
        syntheticResponseTimes,
        totalMemory / results.size());
  }
  
  /**
   * Tests throughput with low concurrency (100 concurrent requests).
   */
  @Test
  public void testLowConcurrencyThroughput() throws Exception {
    runConcurrencyBenchmark(CONCURRENCY_LEVELS[0]);
  }
  
  /**
   * Tests throughput with medium concurrency (1000 concurrent requests).
   */
  @Test
  public void testMediumConcurrencyThroughput() throws Exception {
    runConcurrencyBenchmark(CONCURRENCY_LEVELS[1]);
  }
  
  /**
   * Tests throughput with high concurrency (5000 concurrent requests).
   */
  @Test
  public void testHighConcurrencyThroughput() throws Exception {
    runConcurrencyBenchmark(CONCURRENCY_LEVELS[2]);
  }
  
  /**
   * Tests throughput with very high concurrency (10000 concurrent requests).
   */
  @Test
  public void testVeryHighConcurrencyThroughput() throws Exception {
    runConcurrencyBenchmark(CONCURRENCY_LEVELS[3]);
  }
  
  /**
   * Tests memory utilization under increasing load.
   */
  @Test
  public void testMemoryUtilizationUnderLoad() throws Exception {
    log.info("Testing memory utilization under increasing load");
    
    // Track memory usage at different concurrency levels
    Map<Integer, Long> platformThreadMemory = new ConcurrentHashMap<>();
    Map<Integer, Long> virtualThreadMemory = new ConcurrentHashMap<>();
    
    // Test each concurrency level
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      // Warm up
      runBenchmark(concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, true);
      runBenchmark(concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, true);
      
      // Measure platform thread memory
      System.gc();
      long beforePlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      BenchmarkResult platformResult = runBenchmark(
          concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, false);
      System.gc();
      long afterPlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      platformThreadMemory.put(concurrencyLevel, afterPlatform - beforePlatform);
      
      // Measure virtual thread memory
      System.gc();
      long beforeVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      BenchmarkResult virtualResult = runBenchmark(
          concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, false);
      System.gc();
      long afterVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
      virtualThreadMemory.put(concurrencyLevel, afterVirtual - beforeVirtual);
      
      log.info("Concurrency {}: Platform memory: {:.2f} MB, Virtual memory: {:.2f} MB, Ratio: {:.2f}x",
          concurrencyLevel,
          platformThreadMemory.get(concurrencyLevel) / (1024.0 * 1024.0),
          virtualThreadMemory.get(concurrencyLevel) / (1024.0 * 1024.0),
          (double) platformThreadMemory.get(concurrencyLevel) / virtualThreadMemory.get(concurrencyLevel));
      
      // For higher concurrency levels, virtual threads should use significantly less memory
      if (concurrencyLevel >= 5000) {
        assertThat("Virtual threads should use less memory at high concurrency",
            virtualThreadMemory.get(concurrencyLevel), lessThan(platformThreadMemory.get(concurrencyLevel)));
      }
    }
  }
  
  /**
   * Tests response time percentiles under high load.
   */
  @Test
  public void testResponseTimePercentilesUnderHighLoad() throws Exception {
    log.info("Testing response time percentiles under high load");
    
    // Use high concurrency for this test
    int concurrencyLevel = 5000;
    
    // Warm up
    runBenchmark(concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, true);
    runBenchmark(concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, true);
    
    // Run platform thread benchmark
    BenchmarkResult platformResult = runBenchmark(
        concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, false);
    
    // Run virtual thread benchmark
    BenchmarkResult virtualResult = runBenchmark(
        concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, false);
    
    // Log results
    log.info("Platform Thread Response Times (ms) - P50: {}, P95: {}, P99: {}, Max: {}",
        platformResult.p50ResponseTimeMs, platformResult.p95ResponseTimeMs,
        platformResult.p99ResponseTimeMs, platformResult.maxResponseTimeMs);
    
    log.info("Virtual Thread Response Times (ms) - P50: {}, P95: {}, P99: {}, Max: {}",
        virtualResult.p50ResponseTimeMs, virtualResult.p95ResponseTimeMs,
        virtualResult.p99ResponseTimeMs, virtualResult.maxResponseTimeMs);
    
    // Verify that virtual threads have better response times
    assertThat("Virtual threads should have lower P50 response time",
        virtualResult.p50ResponseTimeMs, lessThan(platformResult.p50ResponseTimeMs));
    
    assertThat("Virtual threads should have lower P95 response time",
        virtualResult.p95ResponseTimeMs, lessThan(platformResult.p95ResponseTimeMs));
    
    assertThat("Virtual threads should have lower P99 response time",
        virtualResult.p99ResponseTimeMs, lessThan(platformResult.p99ResponseTimeMs));
  }
  
  /**
   * Tests scalability with increasing concurrency levels.
   */
  @Test
  public void testScalabilityWithIncreasingConcurrency() throws Exception {
    log.info("Testing scalability with increasing concurrency");
    
    // Track throughput at different concurrency levels
    Map<Integer, Double> platformThreadThroughput = new ConcurrentHashMap<>();
    Map<Integer, Double> virtualThreadThroughput = new ConcurrentHashMap<>();
    
    // Test each concurrency level
    for (int concurrencyLevel : CONCURRENCY_LEVELS) {
      // Warm up
      runBenchmark(concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, true);
      runBenchmark(concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, true);
      
      // Run platform thread benchmark
      BenchmarkResult platformResult = runBenchmark(
          concurrencyLevel, platformThreadProxyFacet, platformThreadExecutor, false);
      platformThreadThroughput.put(concurrencyLevel, platformResult.requestsPerSecond);
      
      // Run virtual thread benchmark
      BenchmarkResult virtualResult = runBenchmark(
          concurrencyLevel, virtualThreadProxyFacet, virtualThreadExecutor, false);
      virtualThreadThroughput.put(concurrencyLevel, virtualResult.requestsPerSecond);
      
      log.info("Concurrency {}: Platform throughput: {:.2f} req/s, Virtual throughput: {:.2f} req/s, Improvement: {:.2f}x",
          concurrencyLevel,
          platformThreadThroughput.get(concurrencyLevel),
          virtualThreadThroughput.get(concurrencyLevel),
          virtualThreadThroughput.get(concurrencyLevel) / platformThreadThroughput.get(concurrencyLevel));
    }
    
    // Verify that virtual threads scale better with increasing concurrency
    double platformScalingFactor = platformThreadThroughput.get(CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1]) /
        platformThreadThroughput.get(CONCURRENCY_LEVELS[0]);
    
    double virtualScalingFactor = virtualThreadThroughput.get(CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1]) /
        virtualThreadThroughput.get(CONCURRENCY_LEVELS[0]);
    
    log.info("Scaling from {} to {} concurrent requests:\n" +
        "Platform threads scaling factor: {:.2f}x\n" +
        "Virtual threads scaling factor: {:.2f}x",
        CONCURRENCY_LEVELS[0], CONCURRENCY_LEVELS[CONCURRENCY_LEVELS.length - 1],
        platformScalingFactor, virtualScalingFactor);
    
    assertThat("Virtual threads should scale better with increasing concurrency",
        virtualScalingFactor, greaterThan(platformScalingFactor));
  }
}