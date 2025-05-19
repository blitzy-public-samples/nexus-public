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
package org.sonatype.nexus.httpclient.virtualthread;

import java.io.IOException;
import java.net.InetSocketAddress;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;

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
 * Performance comparison test for HTTP client operations using platform threads versus Virtual Threads.
 * This class conducts controlled benchmarks to measure throughput, latency, and resource utilization
 * differences between the two threading models when performing HTTP operations.
 */
@ExtendWith(MockitoExtension.class)
public class HttpClientPerformanceVirtualThreadTest
    extends TestSupport
{
  private static final int SERVER_PORT = 8765;
  private static final String SERVER_URL = "http://localhost:" + SERVER_PORT;
  private static final int WARMUP_ITERATIONS = 5;
  private static final int MEASUREMENT_ITERATIONS = 10;
  
  // Performance thresholds for Virtual Threads
  private static final int MAX_CONCURRENT_CONNECTIONS_PLATFORM = 1000;
  private static final int MAX_CONCURRENT_CONNECTIONS_VIRTUAL = 10000;
  private static final double P95_RESPONSE_TIME_THRESHOLD_VIRTUAL = 250.0; // ms
  private static final double P99_RESPONSE_TIME_THRESHOLD_VIRTUAL = 500.0; // ms
  private static final double THREAD_SCALING_EFFICIENCY_THRESHOLD = 0.85; // 85%
  
  @Mock
  private HttpClientManager httpClientManager;
  
  private HttpServer httpServer;
  private CloseableHttpClient httpClient;
  
  @BeforeEach
  void setUp() throws Exception {
    // Setup a simple HTTP server for testing
    httpServer = HttpServer.create(new InetSocketAddress(SERVER_PORT), 0);
    httpServer.createContext("/echo", new EchoHandler());
    httpServer.createContext("/delay", new DelayHandler());
    httpServer.createContext("/large", new LargeResponseHandler());
    httpServer.setExecutor(null); // Use the default executor
    httpServer.start();
    
    // Setup HTTP client
    HttpClientConfiguration config = new HttpClientConfiguration();
    when(httpClientManager.newConfiguration()).thenReturn(config);
    httpClient = httpClientManager.create(config);
  }
  
  @AfterEach
  void tearDown() {
    if (httpClient != null) {
      try {
        httpClient.close();
      }
      catch (IOException e) {
        log.error("Error closing HTTP client", e);
      }
    }
    
    if (httpServer != null) {
      httpServer.stop(0);
    }
  }
  
  /**
   * Test comparing GET request performance between platform threads and Virtual Threads.
   * This test measures throughput and latency for HTTP GET operations under
   * varying concurrency levels.
   */
  @Test
  void testGetRequestPerformanceComparison() throws Exception {
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        () -> performGetRequest("/echo"),
        100, // Start with 100 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM, // Max concurrent operations
        100 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        () -> performGetRequest("/echo"),
        100, // Start with 100 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL, // Max concurrent operations
        500 // Step size
    );
    
    // Log results
    log.info("Platform Thread GET Results: {}", platformResult);
    log.info("Virtual Thread GET Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency for GET requests",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(MAX_CONCURRENT_CONNECTIONS_PLATFORM * 5));
    
    assertThat("Virtual thread P95 response time for GET requests should be under threshold",
        virtualResult.getP95ResponseTime(), lessThan(P95_RESPONSE_TIME_THRESHOLD_VIRTUAL));
    
    assertThat("Virtual thread P99 response time for GET requests should be under threshold",
        virtualResult.getP99ResponseTime(), lessThan(P99_RESPONSE_TIME_THRESHOLD_VIRTUAL));
    
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better throughput than platform threads for GET requests",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.5));
    
    double scalingEfficiency = calculateScalingEfficiency(virtualResult);
    assertThat("Virtual thread scaling efficiency for GET requests should exceed threshold",
        scalingEfficiency, greaterThanOrEqualTo(THREAD_SCALING_EFFICIENCY_THRESHOLD));
  }
  
  /**
   * Test comparing POST request performance between platform threads and Virtual Threads.
   * This test measures throughput and latency for HTTP POST operations under
   * varying concurrency levels.
   */
  @Test
  void testPostRequestPerformanceComparison() throws Exception {
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        () -> performPostRequest("/echo"),
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM / 2, // Max concurrent operations
        50 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        () -> performPostRequest("/echo"),
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL / 2, // Max concurrent operations
        250 // Step size
    );
    
    // Log results
    log.info("Platform Thread POST Results: {}", platformResult);
    log.info("Virtual Thread POST Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency for POST requests",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(platformResult.getMaxConcurrency() * 5));
    
    assertThat("Virtual thread P95 response time for POST requests should be under threshold",
        virtualResult.getP95ResponseTime(), lessThan(P95_RESPONSE_TIME_THRESHOLD_VIRTUAL * 1.2)); // Allow slightly higher threshold for POST
    
    assertThat("Virtual thread P99 response time for POST requests should be under threshold",
        virtualResult.getP99ResponseTime(), lessThan(P99_RESPONSE_TIME_THRESHOLD_VIRTUAL * 1.2)); // Allow slightly higher threshold for POST
    
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better throughput than platform threads for POST requests",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.3)); // 30% improvement
  }
  
  /**
   * Test comparing performance with delayed responses between platform threads and Virtual Threads.
   * This test measures how well each threading model handles I/O wait times.
   */
  @Test
  void testDelayedResponsePerformanceComparison() throws Exception {
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        () -> performGetRequest("/delay"),
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM / 2, // Max concurrent operations
        50 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        () -> performGetRequest("/delay"),
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL / 2, // Max concurrent operations
        250 // Step size
    );
    
    // Log results
    log.info("Platform Thread Delayed Response Results: {}", platformResult);
    log.info("Virtual Thread Delayed Response Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency for delayed responses",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(platformResult.getMaxConcurrency() * 5));
    
    // For delayed responses, the absolute response time is less important than the relative improvement
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better throughput than platform threads for delayed responses",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 2.0)); // Expect significant improvement
  }
  
  /**
   * Test comparing performance with large responses between platform threads and Virtual Threads.
   * This test measures how well each threading model handles large data transfers.
   */
  @Test
  void testLargeResponsePerformanceComparison() throws Exception {
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        () -> performGetRequest("/large"),
        20, // Start with 20 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM / 5, // Max concurrent operations
        20 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        () -> performGetRequest("/large"),
        20, // Start with 20 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_VIRTUAL / 5, // Max concurrent operations
        100 // Step size
    );
    
    // Log results
    log.info("Platform Thread Large Response Results: {}", platformResult);
    log.info("Virtual Thread Large Response Results: {}", virtualResult);
    
    // Verify virtual thread targets are met
    assertThat("Virtual threads should support high concurrency for large responses",
        virtualResult.getMaxConcurrency(), greaterThanOrEqualTo(platformResult.getMaxConcurrency() * 5));
    
    // Verify relative improvement over platform threads
    assertThat("Virtual threads should provide better throughput than platform threads for large responses",
        virtualResult.getThroughput(), greaterThan(platformResult.getThroughput() * 1.5)); // 50% improvement
  }
  
  /**
   * Test measuring memory consumption differences between platform threads and Virtual Threads
   * under high concurrency.
   */
  @Test
  void testMemoryConsumptionComparison() throws Exception {
    // Measure memory before platform thread test
    long beforePlatformMemory = getUsedMemory();
    
    // Run platform thread test with high concurrency
    runConcurrentOperations(ThreadingModel.PLATFORM, () -> performGetRequest("/echo"), 500, 5);
    
    // Measure memory after platform thread test
    long afterPlatformMemory = getUsedMemory();
    long platformMemoryUsage = afterPlatformMemory - beforePlatformMemory;
    
    // Force GC to clean up before virtual thread test
    System.gc();
    Thread.sleep(1000);
    
    // Measure memory before virtual thread test
    long beforeVirtualMemory = getUsedMemory();
    
    // Run virtual thread test with high concurrency
    runConcurrentOperations(ThreadingModel.VIRTUAL, () -> performGetRequest("/echo"), 5000, 5);
    
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
   * Test comparing mixed HTTP operations performance between platform threads and Virtual Threads.
   * This test measures throughput and latency for a mix of HTTP operations under
   * varying concurrency levels.
   */
  @Test
  void testMixedOperationsPerformanceComparison() throws Exception {
    // Run benchmarks with both thread types
    PerformanceResult platformResult = benchmarkOperation(
        ThreadingModel.PLATFORM,
        this::performMixedOperation,
        50, // Start with 50 concurrent operations
        MAX_CONCURRENT_CONNECTIONS_PLATFORM / 2, // Max concurrent operations
        50 // Step size
    );
    
    PerformanceResult virtualResult = benchmarkOperation(
        ThreadingModel.VIRTUAL,
        this::performMixedOperation,
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
  }
  
  /**
   * Performs an HTTP GET request to the specified path.
   */
  private Void performGetRequest(String path) throws IOException {
    HttpGet request = new HttpGet(SERVER_URL + path);
    HttpResponse response = httpClient.execute(request);
    EntityUtils.consume(response.getEntity()); // Ensure connection is released
    return null;
  }
  
  /**
   * Performs an HTTP POST request to the specified path.
   */
  private Void performPostRequest(String path) throws IOException {
    HttpPost request = new HttpPost(SERVER_URL + path);
    request.setEntity(new StringEntity("Test payload for POST request"));
    HttpResponse response = httpClient.execute(request);
    EntityUtils.consume(response.getEntity()); // Ensure connection is released
    return null;
  }
  
  /**
   * Performs a mixed HTTP operation (GET or POST).
   */
  private Void performMixedOperation() throws IOException {
    // 70% GET, 30% POST
    if (Math.random() < 0.7) {
      return performGetRequest(Math.random() < 0.3 ? "/delay" : "/echo");
    }
    else {
      return performPostRequest("/echo");
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
  
  /**
   * HTTP handler that echoes back the request.
   */
  private static class EchoHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      byte[] response = "Echo response".getBytes();
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.getResponseBody().close();
    }
  }
  
  /**
   * HTTP handler that introduces a delay before responding.
   */
  private static class DelayHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        // Random delay between 50-150ms to simulate network latency
        Thread.sleep(50 + (long) (Math.random() * 100));
      }
      catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      
      byte[] response = "Delayed response".getBytes();
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.getResponseBody().close();
    }
  }
  
  /**
   * HTTP handler that returns a large response.
   */
  private static class LargeResponseHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      // Generate a large response (approximately 1MB)
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < 10000; i++) {
        sb.append("Line ").append(i).append(": This is a large response to test HTTP client performance with data transfer. ");
        sb.append("The quick brown fox jumps over the lazy dog. ").append(System.lineSeparator());
      }
      
      byte[] response = sb.toString().getBytes();
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.getResponseBody().close();
    }
  }
}