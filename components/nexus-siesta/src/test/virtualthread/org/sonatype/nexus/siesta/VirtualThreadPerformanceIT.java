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
package org.sonatype.nexus.siesta;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.rest.ValidationErrorXO;

import org.junit.Before;
import org.junit.Test;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_XML_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_JSON_TYPE;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_XML_TYPE;

/**
 * Comprehensive performance test suite for the Siesta REST layer using Java 21 Virtual Threads.
 * 
 * This test conducts detailed performance comparisons between platform threads and virtual threads
 * across all test endpoints. It measures throughput, latency, memory usage, and thread scaling efficiency
 * under various concurrency levels.
 */
public class VirtualThreadPerformanceIT
    extends VirtualThreadSiestaTestSupport
{
  // Performance test configuration
  private static final int SHORT_TEST_DURATION_SECONDS = 10;
  private static final int LONG_TEST_DURATION_SECONDS = 30;
  
  // Concurrency levels for testing
  private static final int[] CONCURRENCY_LEVELS = {10, 50, 100, 200, 500};
  
  // Performance thresholds
  private static final double MIN_THROUGHPUT_IMPROVEMENT_PERCENT = 10.0;
  private static final double MIN_LATENCY_IMPROVEMENT_PERCENT = 5.0;
  private static final double MAX_ERROR_RATE_PERCENT = 1.0;
  
  // Test data
  private UserXO testUser;
  
  @Before
  public void setupTestData() {
    testUser = new UserXO().withName(UUID.randomUUID().toString());
  }
  
  /**
   * Tests the performance of the Echo endpoint with increasing concurrency levels.
   */
  @Test
  public void testEchoEndpointPerformance() throws Exception {
    log("Testing Echo endpoint performance");
    
    // Test with different concurrency levels
    Map<Integer, Map<String, PerformanceMetrics>> results = new HashMap<>();
    
    for (int concurrency : CONCURRENCY_LEVELS) {
      log("Testing with {} concurrent users", concurrency);
      
      Map<String, PerformanceMetrics> metrics = compareThreadPerformance(
          "echo",
          () -> target("echo").queryParam("foo", "test-value").request().get(),
          concurrency,
          SHORT_TEST_DURATION_SECONDS
      );
      
      results.put(concurrency, metrics);
      
      // Validate performance improvements
      validatePerformanceImprovement(metrics, "Echo endpoint with " + concurrency + " users");
      
      // Short pause between tests
      TimeUnit.SECONDS.sleep(2);
    }
    
    // Log summary of results
    logPerformanceSummary("Echo Endpoint", results);
  }
  
  /**
   * Tests the performance of the User endpoint with increasing concurrency levels.
   */
  @Test
  public void testUserEndpointPerformance() throws Exception {
    log("Testing User endpoint performance");
    
    // Test with different concurrency levels
    Map<Integer, Map<String, PerformanceMetrics>> results = new HashMap<>();
    
    for (int concurrency : CONCURRENCY_LEVELS) {
      log("Testing with {} concurrent users", concurrency);
      
      Map<String, PerformanceMetrics> metrics = compareThreadPerformance(
          "user",
          () -> {
            UserXO user = new UserXO().withName(UUID.randomUUID().toString());
            return target("user").request()
                .accept(APPLICATION_JSON_TYPE)
                .put(Entity.entity(user, APPLICATION_JSON_TYPE));
          },
          concurrency,
          SHORT_TEST_DURATION_SECONDS
      );
      
      results.put(concurrency, metrics);
      
      // Validate performance improvements
      validatePerformanceImprovement(metrics, "User endpoint with " + concurrency + " users");
      
      // Short pause between tests
      TimeUnit.SECONDS.sleep(2);
    }
    
    // Log summary of results
    logPerformanceSummary("User Endpoint", results);
  }
  
  /**
   * Tests the performance of the ValidationErrors endpoint with increasing concurrency levels.
   */
  @Test
  public void testValidationErrorsEndpointPerformance() throws Exception {
    log("Testing ValidationErrors endpoint performance");
    
    // Test with different concurrency levels
    Map<Integer, Map<String, PerformanceMetrics>> results = new HashMap<>();
    
    for (int concurrency : CONCURRENCY_LEVELS) {
      log("Testing with {} concurrent users", concurrency);
      
      Map<String, PerformanceMetrics> metrics = compareThreadPerformance(
          "validationErrors/manual/multiple",
          () -> {
            UserXO user = new UserXO(); // Empty user to trigger validation errors
            return target("validationErrors/manual/multiple").request()
                .accept(VND_VALIDATION_ERRORS_V1_JSON_TYPE)
                .put(Entity.entity(user, APPLICATION_JSON_TYPE));
          },
          concurrency,
          SHORT_TEST_DURATION_SECONDS
      );
      
      results.put(concurrency, metrics);
      
      // Validate performance improvements
      validatePerformanceImprovement(metrics, "ValidationErrors endpoint with " + concurrency + " users");
      
      // Short pause between tests
      TimeUnit.SECONDS.sleep(2);
    }
    
    // Log summary of results
    logPerformanceSummary("ValidationErrors Endpoint", results);
  }
  
  /**
   * Tests the performance of the Errors endpoint with increasing concurrency levels.
   */
  @Test
  public void testErrorsEndpointPerformance() throws Exception {
    log("Testing Errors endpoint performance");
    
    // Test with different concurrency levels
    Map<Integer, Map<String, PerformanceMetrics>> results = new HashMap<>();
    
    for (int concurrency : CONCURRENCY_LEVELS) {
      log("Testing with {} concurrent users", concurrency);
      
      Map<String, PerformanceMetrics> metrics = compareThreadPerformance(
          "errors/406",
          () -> target("errors/406").request().get(),
          concurrency,
          SHORT_TEST_DURATION_SECONDS
      );
      
      results.put(concurrency, metrics);
      
      // Validate performance improvements
      validatePerformanceImprovement(metrics, "Errors endpoint with " + concurrency + " users");
      
      // Short pause between tests
      TimeUnit.SECONDS.sleep(2);
    }
    
    // Log summary of results
    logPerformanceSummary("Errors Endpoint", results);
  }
  
  /**
   * Tests the performance of all endpoints under sustained load.
   */
  @Test
  public void testSustainedLoadPerformance() throws Exception {
    log("Testing sustained load performance across all endpoints");
    
    // Use a higher concurrency level for sustained load test
    final int concurrency = 200;
    
    // Test each endpoint under sustained load
    Map<String, Map<String, PerformanceMetrics>> results = new HashMap<>();
    
    // Echo endpoint
    results.put("echo", compareThreadPerformance(
        "echo",
        () -> target("echo").queryParam("foo", "test-value").request().get(),
        concurrency,
        LONG_TEST_DURATION_SECONDS
    ));
    
    // Short pause between tests
    TimeUnit.SECONDS.sleep(5);
    
    // User endpoint
    results.put("user", compareThreadPerformance(
        "user",
        () -> {
          UserXO user = new UserXO().withName(UUID.randomUUID().toString());
          return target("user").request()
              .accept(APPLICATION_JSON_TYPE)
              .put(Entity.entity(user, APPLICATION_JSON_TYPE));
        },
        concurrency,
        LONG_TEST_DURATION_SECONDS
    ));
    
    // Short pause between tests
    TimeUnit.SECONDS.sleep(5);
    
    // ValidationErrors endpoint
    results.put("validationErrors", compareThreadPerformance(
        "validationErrors/manual/multiple",
        () -> {
          UserXO user = new UserXO(); // Empty user to trigger validation errors
          return target("validationErrors/manual/multiple").request()
              .accept(VND_VALIDATION_ERRORS_V1_JSON_TYPE)
              .put(Entity.entity(user, APPLICATION_JSON_TYPE));
        },
        concurrency,
        LONG_TEST_DURATION_SECONDS
    ));
    
    // Short pause between tests
    TimeUnit.SECONDS.sleep(5);
    
    // Errors endpoint
    results.put("errors", compareThreadPerformance(
        "errors/406",
        () -> target("errors/406").request().get(),
        concurrency,
        LONG_TEST_DURATION_SECONDS
    ));
    
    // Log summary of results for sustained load test
    log("Sustained Load Test Results (Concurrency: {}, Duration: {} seconds):", 
        concurrency, LONG_TEST_DURATION_SECONDS);
    
    for (Map.Entry<String, Map<String, PerformanceMetrics>> entry : results.entrySet()) {
      String endpoint = entry.getKey();
      Map<String, PerformanceMetrics> metrics = entry.getValue();
      
      PerformanceMetrics platformMetrics = metrics.get("platform");
      PerformanceMetrics virtualMetrics = metrics.get("virtual");
      
      log("{} Endpoint:", endpoint);
      log("  Platform Threads: {}", platformMetrics);
      log("  Virtual Threads:  {}", virtualMetrics);
      
      // Calculate improvement percentages
      double throughputImprovement = calculateImprovement(
          platformMetrics.getSuccessfulRequests(), virtualMetrics.getSuccessfulRequests());
      double latencyImprovement = calculateImprovement(
          platformMetrics.getAverageResponseTime(), virtualMetrics.getAverageResponseTime(), true);
      
      log("  Throughput improvement: {:.2f}%", throughputImprovement);
      log("  Latency improvement:    {:.2f}%", latencyImprovement);
      
      // Validate performance improvements
      validatePerformanceImprovement(metrics, endpoint + " sustained load");
    }
  }
  
  /**
   * Tests the memory usage of platform threads vs virtual threads under high load.
   */
  @Test
  public void testMemoryUsage() throws Exception {
    log("Testing memory usage under high load");
    
    // Use the highest concurrency level for memory usage test
    final int concurrency = 500;
    
    // Force garbage collection before starting
    System.gc();
    TimeUnit.SECONDS.sleep(1);
    
    // Measure memory before platform thread test
    long memoryBeforePlatform = getUsedMemory();
    log("Memory before platform thread test: {} MB", memoryBeforePlatform / (1024 * 1024));
    
    // Run platform thread test
    PerformanceMetrics platformMetrics = runPlatformThreadLoadTest(
        "echo",
        () -> target("echo").queryParam("foo", "test-value").request().get(),
        concurrency,
        SHORT_TEST_DURATION_SECONDS
    );
    
    // Force garbage collection
    System.gc();
    TimeUnit.SECONDS.sleep(1);
    
    // Measure memory after platform thread test
    long memoryAfterPlatform = getUsedMemory();
    log("Memory after platform thread test: {} MB", memoryAfterPlatform / (1024 * 1024));
    
    // Short pause between tests
    TimeUnit.SECONDS.sleep(5);
    
    // Force garbage collection before virtual thread test
    System.gc();
    TimeUnit.SECONDS.sleep(1);
    
    // Measure memory before virtual thread test
    long memoryBeforeVirtual = getUsedMemory();
    log("Memory before virtual thread test: {} MB", memoryBeforeVirtual / (1024 * 1024));
    
    // Run virtual thread test
    PerformanceMetrics virtualMetrics = runLoadTest(
        "echo",
        () -> target("echo").queryParam("foo", "test-value").request().get(),
        concurrency,
        SHORT_TEST_DURATION_SECONDS
    );
    
    // Force garbage collection
    System.gc();
    TimeUnit.SECONDS.sleep(1);
    
    // Measure memory after virtual thread test
    long memoryAfterVirtual = getUsedMemory();
    log("Memory after virtual thread test: {} MB", memoryAfterVirtual / (1024 * 1024));
    
    // Calculate memory usage
    long platformMemoryUsage = memoryAfterPlatform - memoryBeforePlatform;
    long virtualMemoryUsage = memoryAfterVirtual - memoryBeforeVirtual;
    
    log("Platform thread memory usage: {} MB", platformMemoryUsage / (1024 * 1024));
    log("Virtual thread memory usage: {} MB", virtualMemoryUsage / (1024 * 1024));
    
    // Calculate memory improvement
    double memoryImprovement = calculateImprovement(platformMemoryUsage, virtualMemoryUsage, true);
    log("Memory usage improvement: {:.2f}%", memoryImprovement);
    
    // Assert that virtual threads use less memory
    assertThat("Virtual threads should use less memory than platform threads",
        virtualMemoryUsage, is(lessThan(platformMemoryUsage)));
  }
  
  /**
   * Tests the thread scaling efficiency of platform threads vs virtual threads.
   */
  @Test
  public void testThreadScalingEfficiency() throws Exception {
    log("Testing thread scaling efficiency");
    
    // Test with extreme concurrency to demonstrate virtual thread scaling
    final int[] scalingLevels = {100, 500, 1000};
    
    Map<Integer, Map<String, PerformanceMetrics>> results = new HashMap<>();
    
    for (int concurrency : scalingLevels) {
      log("Testing with {} concurrent users", concurrency);
      
      Map<String, PerformanceMetrics> metrics = compareThreadPerformance(
          "echo",
          () -> target("echo").queryParam("foo", "test-value").request().get(),
          concurrency,
          SHORT_TEST_DURATION_SECONDS
      );
      
      results.put(concurrency, metrics);
      
      // Short pause between tests
      TimeUnit.SECONDS.sleep(5);
    }
    
    // Log scaling efficiency results
    log("Thread Scaling Efficiency Results:");
    
    // Calculate scaling factors for platform threads
    double[] platformScalingFactors = calculateScalingFactors(results, "platform");
    
    // Calculate scaling factors for virtual threads
    double[] virtualScalingFactors = calculateScalingFactors(results, "virtual");
    
    // Log scaling factors
    for (int i = 0; i < scalingLevels.length - 1; i++) {
      int fromConcurrency = scalingLevels[i];
      int toConcurrency = scalingLevels[i + 1];
      
      log("Scaling from {} to {} concurrent users:", fromConcurrency, toConcurrency);
      log("  Platform thread scaling factor: {:.2f}", platformScalingFactors[i]);
      log("  Virtual thread scaling factor:  {:.2f}", virtualScalingFactors[i]);
      log("  Improvement: {:.2f}%", 
          calculateImprovement(platformScalingFactors[i], virtualScalingFactors[i]));
      
      // Assert that virtual threads scale better
      assertThat("Virtual threads should scale better than platform threads",
          virtualScalingFactors[i], is(greaterThan(platformScalingFactors[i])));
    }
  }
  
  /**
   * Validates that the performance metrics meet the required thresholds.
   */
  private void validatePerformanceImprovement(Map<String, PerformanceMetrics> metrics, String testName) {
    PerformanceMetrics platformMetrics = metrics.get("platform");
    PerformanceMetrics virtualMetrics = metrics.get("virtual");
    
    // Calculate improvement percentages
    double throughputImprovement = calculateImprovement(
        platformMetrics.getSuccessfulRequests(), virtualMetrics.getSuccessfulRequests());
    double latencyImprovement = calculateImprovement(
        platformMetrics.getAverageResponseTime(), virtualMetrics.getAverageResponseTime(), true);
    
    // Validate throughput improvement
    assertThat(testName + ": Virtual threads should improve throughput by at least " + 
        MIN_THROUGHPUT_IMPROVEMENT_PERCENT + "%",
        throughputImprovement, is(greaterThanOrEqualTo(MIN_THROUGHPUT_IMPROVEMENT_PERCENT)));
    
    // Validate latency improvement
    assertThat(testName + ": Virtual threads should improve latency by at least " + 
        MIN_LATENCY_IMPROVEMENT_PERCENT + "%",
        latencyImprovement, is(greaterThanOrEqualTo(MIN_LATENCY_IMPROVEMENT_PERCENT)));
    
    // Validate error rate
    assertThat(testName + ": Virtual threads should maintain an error rate below " + 
        MAX_ERROR_RATE_PERCENT + "%",
        virtualMetrics.getErrorRate() * 100, is(lessThan(MAX_ERROR_RATE_PERCENT)));
  }
  
  /**
   * Logs a summary of performance results across different concurrency levels.
   */
  private void logPerformanceSummary(String endpointName, 
                                   Map<Integer, Map<String, PerformanceMetrics>> results) {
    log("{} Performance Summary:", endpointName);
    log("Concurrency | Platform Throughput | Virtual Throughput | Improvement | Platform Latency | Virtual Latency | Improvement");
    log("-----------|-------------------|------------------|------------|-----------------|----------------|------------");
    
    for (Map.Entry<Integer, Map<String, PerformanceMetrics>> entry : results.entrySet()) {
      int concurrency = entry.getKey();
      Map<String, PerformanceMetrics> metrics = entry.getValue();
      
      PerformanceMetrics platformMetrics = metrics.get("platform");
      PerformanceMetrics virtualMetrics = metrics.get("virtual");
      
      double throughputImprovement = calculateImprovement(
          platformMetrics.getSuccessfulRequests(), virtualMetrics.getSuccessfulRequests());
      double latencyImprovement = calculateImprovement(
          platformMetrics.getAverageResponseTime(), virtualMetrics.getAverageResponseTime(), true);
      
      log("{:10d} | {:18.2f} | {:17.2f} | {:10.2f}% | {:15.2f} | {:14.2f} | {:10.2f}%",
          concurrency,
          platformMetrics.getSuccessfulRequests() / (double) SHORT_TEST_DURATION_SECONDS,
          virtualMetrics.getSuccessfulRequests() / (double) SHORT_TEST_DURATION_SECONDS,
          throughputImprovement,
          platformMetrics.getAverageResponseTime(),
          virtualMetrics.getAverageResponseTime(),
          latencyImprovement);
    }
  }
  
  /**
   * Calculates scaling factors for throughput as concurrency increases.
   */
  private double[] calculateScalingFactors(Map<Integer, Map<String, PerformanceMetrics>> results, 
                                         String threadType) {
    int[] concurrencyLevels = results.keySet().stream()
        .sorted()
        .mapToInt(Integer::intValue)
        .toArray();
    
    double[] scalingFactors = new double[concurrencyLevels.length - 1];
    
    for (int i = 0; i < concurrencyLevels.length - 1; i++) {
      int fromConcurrency = concurrencyLevels[i];
      int toConcurrency = concurrencyLevels[i + 1];
      
      double fromThroughput = results.get(fromConcurrency).get(threadType).getSuccessfulRequests() / 
          (double) SHORT_TEST_DURATION_SECONDS;
      double toThroughput = results.get(toConcurrency).get(threadType).getSuccessfulRequests() / 
          (double) SHORT_TEST_DURATION_SECONDS;
      
      // Calculate scaling factor (ideal is 1.0, meaning perfect linear scaling)
      double concurrencyRatio = (double) toConcurrency / fromConcurrency;
      double throughputRatio = toThroughput / fromThroughput;
      
      scalingFactors[i] = throughputRatio / concurrencyRatio;
    }
    
    return scalingFactors;
  }
  
  /**
   * Gets the current used memory in bytes.
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * Calculates the percentage improvement between two values.
   */
  private double calculateImprovement(double baseline, double current, boolean lowerIsBetter) {
    if (baseline == 0) {
      return 0.0;
    }
    
    if (lowerIsBetter) {
      return ((baseline - current) / baseline) * 100.0;
    } else {
      return ((current - baseline) / baseline) * 100.0;
    }
  }
  
  /**
   * Calculates the percentage improvement between two values (higher is better).
   */
  private double calculateImprovement(double baseline, double current) {
    return calculateImprovement(baseline, current, false);
  }
}