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
package org.sonatype.nexus.content.maven.internal.search.table;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Perf;
import org.sonatype.nexus.common.entity.Continuation;
import org.sonatype.nexus.content.maven.internal.search.MavenSearchCustomFieldContributor;
import org.sonatype.nexus.content.maven.internal.search.MavenSearchFacet;
import org.sonatype.nexus.repository.content.search.SearchResultData;
import org.sonatype.nexus.repository.content.search.SearchResultDataExtractor;
import org.sonatype.nexus.repository.content.search.table.SqlSearchQueryBuilder;
import org.sonatype.nexus.repository.content.search.table.SqlSearchResponse;
import org.sonatype.nexus.repository.content.search.table.SqlSearchService;
import org.sonatype.nexus.repository.search.query.SearchFilter;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceChart;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Performance benchmark test that compares platform threads with virtual threads for Maven search operations.
 * <p>
 * This test measures throughput, latency, and memory usage to validate the scalability benefits of
 * virtual threads for search operations.
 * <p>
 * The test is categorized with {@code @Tag("Perf")} and {@code @Tag("VirtualThreadTestGroup")} to allow
 * selective execution in CI/CD pipelines.
 *
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
@Tag("Perf") // Equivalent to @Category(Perf.class) in JUnit 4
@Tag("VirtualThreadTestGroup") // Equivalent to @Category(VirtualThreadTestGroup.class) in JUnit 4
public class MavenSearchVirtualThreadBenchmarkTest
    extends TestSupport
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  private static final int MAX_CONCURRENT_OPERATIONS = 1000;
  private static final int STEP_SIZE = 100;
  private static final int TIMEOUT_SECONDS = 60;

  @Mock
  private SqlSearchService sqlSearchService;

  @Mock
  private SearchResultDataExtractor searchResultDataExtractor;

  @Mock
  private MavenSearchCustomFieldContributor mavenSearchCustomFieldContributor;

  private MavenSqlSearchResultDecorator underTest;

  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;

  @BeforeEach
  void setUp() {
    underTest = new MavenSqlSearchResultDecorator(searchResultDataExtractor, mavenSearchCustomFieldContributor);

    // Set up mock responses
    SqlSearchResponse mockResponse = createMockSearchResponse();
    when(sqlSearchService.search(any(SqlSearchQueryBuilder.class), anyInt(), anyString()))
        .thenReturn(mockResponse);

    // Create executors for platform and virtual threads
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2,
        createNamedThreadFactory("platform-thread-"));
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual()
        .name("virtual-thread-", 0)
        .factory());
  }

  @AfterEach
  void tearDown() {
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
    }
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }

  /**
   * Tests the performance of Maven search operations using platform threads vs virtual threads.
   * <p>
   * This test measures throughput, latency, and memory usage for both thread types with
   * increasing concurrency levels. It validates that virtual threads provide better
   * scalability and resource efficiency compared to platform threads.
   */
  @Test
  @DisplayName("Compare platform threads vs virtual threads for Maven search operations")
  void compareThreadModelsForMavenSearch() throws Exception {
    // Run benchmarks for both thread types
    log.info("Starting platform thread benchmark");
    PerformanceData platformThreadData = runBenchmark(platformThreadExecutor, "Platform Threads");

    log.info("Starting virtual thread benchmark");
    PerformanceData virtualThreadData = runBenchmark(virtualThreadExecutor, "Virtual Threads");

    // Generate performance comparison chart
    PerformanceChart chart = new PerformanceChart();
    chart.addData(platformThreadData);
    chart.addData(virtualThreadData);
    chart.writeChartToFile(new File("target/maven-search-thread-comparison.html"));

    // Log performance metrics
    log.info("Platform Thread P95 Response Time: {} ms", platformThreadData.getPercentile(95.0));
    log.info("Virtual Thread P95 Response Time: {} ms", virtualThreadData.getPercentile(95.0));
    log.info("Platform Thread Max Memory: {} MB", platformThreadData.getMaxMemoryUsage());
    log.info("Virtual Thread Max Memory: {} MB", virtualThreadData.getMaxMemoryUsage());

    // Verify performance improvements with virtual threads
    // 1. Virtual threads should handle higher concurrency
    assertThat("Virtual threads should support higher concurrency",
        virtualThreadData.getMaxConcurrency(), greaterThan(platformThreadData.getMaxConcurrency()));

    // 2. Virtual threads should have better response times at high concurrency
    double virtualP95 = virtualThreadData.getPercentile(95.0);
    double platformP95 = platformThreadData.getPercentile(95.0);
    assertThat("Virtual threads should provide better P95 response time",
        virtualP95, lessThan(platformP95 * 0.8)); // At least 20% improvement

    // 3. Virtual threads should use less memory
    assertThat("Virtual threads should use less memory",
        virtualThreadData.getMaxMemoryUsage(), lessThanOrEqualTo(platformThreadData.getMaxMemoryUsage()));
  }

  /**
   * Tests the scalability of Maven search operations with virtual threads under high concurrency.
   * <p>
   * This test validates that virtual threads can handle a large number of concurrent operations
   * efficiently, with minimal degradation in performance as concurrency increases.
   */
  @Test
  @DisplayName("Test virtual thread scalability for Maven search operations")
  void testVirtualThreadScalability() throws Exception {
    int maxConcurrency = 5000; // Test with higher concurrency for virtual threads
    int stepSize = 500;

    log.info("Starting virtual thread scalability test with max concurrency: {}", maxConcurrency);

    // Create a custom performance data object for this test
    PerformanceData scalabilityData = new PerformanceData("Virtual Thread Scalability");

    // Warm up
    runSearchOperations(virtualThreadExecutor, 100, 5);

    // Run with increasing concurrency
    for (int concurrency = stepSize; concurrency <= maxConcurrency; concurrency += stepSize) {
      log.info("Testing with concurrency level: {}", concurrency);

      // Measure memory before test
      long memoryBefore = getUsedMemory();

      // Run the test and measure time
      long startTime = System.nanoTime();
      int successCount = runSearchOperations(virtualThreadExecutor, concurrency, 1);
      long endTime = System.nanoTime();

      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;

      // Calculate metrics
      double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
      double avgResponseTime = elapsedTimeMs / successCount;

      // Record data
      scalabilityData.addConcurrencyResult(concurrency, avgResponseTime, memoryUsage / (1024 * 1024));

      log.info("Concurrency: {}, Avg Response Time: {} ms, Memory Usage: {} MB",
          concurrency, avgResponseTime, memoryUsage / (1024 * 1024));

      // Verify that response time doesn't degrade exponentially with increased concurrency
      if (concurrency > stepSize) {
        double previousAvgTime = scalabilityData.getAverageResponseTime(concurrency - stepSize);
        double ratio = avgResponseTime / previousAvgTime;

        // The response time should not increase more than 3x when concurrency increases by stepSize
        assertThat("Response time should scale reasonably with increased concurrency",
            ratio, lessThan(3.0));
      }

      // Force garbage collection to prepare for next iteration
      System.gc();
      Thread.sleep(1000);
    }

    // Generate scalability chart
    PerformanceChart chart = new PerformanceChart();
    chart.addData(scalabilityData);
    chart.writeChartToFile(new File("target/maven-search-virtual-thread-scalability.html"));
  }

  /**
   * Runs a benchmark with increasing concurrency levels and collects performance metrics.
   *
   * @param executor the executor service to use for the benchmark
   * @param label    the label for the performance data
   * @return performance data collected during the benchmark
   */
  private PerformanceData runBenchmark(final ExecutorService executor, final String label) throws Exception {
    PerformanceData performanceData = new PerformanceData(label);

    // Warm up
    log.info("Warming up with {} iterations", WARMUP_ITERATIONS);
    runSearchOperations(executor, STEP_SIZE, WARMUP_ITERATIONS);

    // Run with increasing concurrency
    for (int concurrency = STEP_SIZE; concurrency <= MAX_CONCURRENT_OPERATIONS; concurrency += STEP_SIZE) {
      log.info("Testing with concurrency level: {}", concurrency);

      // Measure memory before test
      long memoryBefore = getUsedMemory();

      // Run the test and measure time
      long startTime = System.nanoTime();
      int successCount = runSearchOperations(executor, concurrency, MEASUREMENT_ITERATIONS);
      long endTime = System.nanoTime();

      // Measure memory after test
      long memoryAfter = getUsedMemory();
      long memoryUsage = memoryAfter - memoryBefore;

      // Calculate metrics
      double elapsedTimeMs = (endTime - startTime) / 1_000_000.0;
      double avgResponseTime = elapsedTimeMs / successCount;

      // Record data
      performanceData.addConcurrencyResult(concurrency, avgResponseTime, memoryUsage / (1024 * 1024));

      log.info("Concurrency: {}, Avg Response Time: {} ms, Memory Usage: {} MB",
          concurrency, avgResponseTime, memoryUsage / (1024 * 1024));

      // Force garbage collection to prepare for next iteration
      System.gc();
      Thread.sleep(1000);
    }

    return performanceData;
  }

  /**
   * Runs search operations with the specified concurrency level and number of iterations.
   *
   * @param executor    the executor service to use
   * @param concurrency the number of concurrent operations
   * @param iterations  the number of iterations to run
   * @return the number of successful operations
   */
  private int runSearchOperations(final ExecutorService executor, final int concurrency, final int iterations)
      throws Exception {
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);

    for (int i = 0; i < iterations; i++) {
      CountDownLatch latch = new CountDownLatch(concurrency);

      // Submit tasks
      for (int j = 0; j < concurrency; j++) {
        executor.submit(() -> {
          try {
            // Perform search operation
            performSearchOperation();
            successCount.incrementAndGet();
          }
          catch (Exception e) {
            log.error("Error during search operation", e);
            errorCount.incrementAndGet();
          }
          finally {
            latch.countDown();
          }
        });
      }

      // Wait for all tasks to complete
      if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        log.warn("Timeout waiting for search operations to complete");
      }
    }

    // Log and verify results
    log.info("Completed {} successful operations, {} errors", successCount.get(), errorCount.get());
    assertThat("No errors should occur during search operations", errorCount.get(), is(0));

    return successCount.get();
  }

  /**
   * Performs a single search operation using the component under test.
   */
  private void performSearchOperation() {
    // Create search filters
    List<SearchFilter> filters = new ArrayList<>();
    filters.add(new SearchFilter("keyword", "test-artifact"));

    // Create search query builder
    SqlSearchQueryBuilder queryBuilder = new SqlSearchQueryBuilder()
        .searchFilters(filters)
        .repositoryPermissions(emptyList())
        .format(MavenSearchFacet.NAME);

    // Execute search
    SqlSearchResponse response = sqlSearchService.search(queryBuilder, 50, null);

    // Process results
    underTest.updateSearchResultData(response.getSearchResultData());
  }

  /**
   * Creates a mock search response for testing.
   */
  private SqlSearchResponse createMockSearchResponse() {
    List<SearchResultData> results = new ArrayList<>();

    // Create 20 mock search results
    for (int i = 0; i < 20; i++) {
      SearchResultData resultData = new SearchResultData();
      resultData.setId("test-id-" + i);
      resultData.setPath("org/example/test-artifact/1.0." + i + "/test-artifact-1.0." + i + ".jar");
      resultData.setRepository("maven-central");
      resultData.setFormat("maven2");

      Map<String, Object> attributes = new HashMap<>();
      attributes.put("maven2.groupId", "org.example");
      attributes.put("maven2.artifactId", "test-artifact");
      attributes.put("maven2.version", "1.0." + i);
      resultData.setAttributes(attributes);

      results.add(resultData);
    }

    Continuation<SearchResultData> continuation = new Continuation<>(results, null);
    return new SqlSearchResponse(continuation, 20);
  }

  /**
   * Creates a named thread factory for platform threads.
   */
  private ThreadFactory createNamedThreadFactory(final String prefix) {
    AtomicLong counter = new AtomicLong(0);
    return r -> {
      Thread thread = new Thread(r);
      thread.setName(prefix + counter.getAndIncrement());
      return thread;
    };
  }

  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
    return memoryMXBean.getHeapMemoryUsage().getUsed() + memoryMXBean.getNonHeapMemoryUsage().getUsed();
  }
}