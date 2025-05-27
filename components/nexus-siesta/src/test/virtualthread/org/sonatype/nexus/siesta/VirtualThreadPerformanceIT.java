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

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.sonatype.nexus.rest.ValidationErrorXO;
import org.sonatype.nexus.testcommon.virtualthread.VirtualThreadTestSupport;

import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.sonatype.nexus.rest.MediaTypes.VND_VALIDATION_ERRORS_V1_JSON_TYPE;

/**
 * Performance test suite for the Siesta REST layer using Java 21 Virtual Threads.
 * Compares platform threads vs. virtual threads across multiple metrics including
 * throughput, latency, memory usage, and thread scaling efficiency.
 */
public class VirtualThreadPerformanceIT
    extends SiestaTestSupport
{
  private static final int WARMUP_ITERATIONS = 10;
  private static final int MEASUREMENT_ITERATIONS = 100;
  private static final int MAX_CLIENTS = 1000;
  private static final int CLIENT_STEP_SIZE = 100;
  private static final int TIMEOUT_SECONDS = 60;
  
  // Performance thresholds from technical specification
  private static final double VIRTUAL_THREAD_P95_TARGET_MS = 350.0;
  private static final double VIRTUAL_THREAD_P99_TARGET_MS = 600.0;
  private static final double THREAD_SCALING_EFFICIENCY_TARGET = 0.9; // 90%
  
  private ExecutorService platformThreadExecutor;
  private ExecutorService virtualThreadExecutor;
  
  private final Map<String, PerformanceData> results = new HashMap<>();
  
  @Before
  public void setupExecutors() {
    // Create platform thread executor with fixed thread pool
    platformThreadExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    
    // Create virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    log("Java version: {}", System.getProperty("java.version"));
    log("Available processors: {}", Runtime.getRuntime().availableProcessors());
    log("Common ForkJoinPool parallelism: {}", ForkJoinPool.commonPool().getParallelism());
  }
  
  @After
  public void shutdownExecutors() throws Exception {
    if (platformThreadExecutor != null) {
      platformThreadExecutor.shutdown();
      platformThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
    
    // Generate performance comparison chart
    if (!results.isEmpty()) {
      PerformanceChart chart = new PerformanceChart();
      results.forEach(chart::addData);
      File outputDir = new File("target");
      if (!outputDir.exists()) {
        outputDir.mkdirs();
      }
      chart.writeChartToFile(new File(outputDir, "siesta-thread-model-comparison.html"));
      
      // Log summary of results
      log("Performance Test Results Summary:");
      results.forEach((name, data) -> {
        log("{} - Avg: {}ms, P95: {}ms, P99: {}ms, Max: {}ms, Errors: {}",
            name, data.getAverage(), data.getPercentile(95.0), data.getPercentile(99.0),
            data.getMaxResponseTime(), data.getErrorCount());
      });
      
      // Verify performance targets for virtual threads
      if (results.containsKey("virtual-threads") && results.containsKey("platform-threads")) {
        PerformanceData virtualData = results.get("virtual-threads");
        PerformanceData platformData = results.get("platform-threads");
        
        log("Verifying performance targets:");
        double virtualP95 = virtualData.getPercentile(95.0);
        double platformP95 = platformData.getPercentile(95.0);
        log("P95 Response Time - Virtual: {}ms, Platform: {}ms, Target: <{}ms",
            virtualP95, platformP95, VIRTUAL_THREAD_P95_TARGET_MS);
        
        double virtualP99 = virtualData.getPercentile(99.0);
        double platformP99 = platformData.getPercentile(99.0);
        log("P99 Response Time - Virtual: {}ms, Platform: {}ms, Target: <{}ms",
            virtualP99, platformP99, VIRTUAL_THREAD_P99_TARGET_MS);
        
        // Calculate thread scaling efficiency (how well virtual threads scale compared to platform threads)
        double scalingEfficiency = platformData.getAverage() / virtualData.getAverage();
        log("Thread Scaling Efficiency: {} (Target: >{})", scalingEfficiency, THREAD_SCALING_EFFICIENCY_TARGET);
        
        // Verify that virtual threads meet performance targets
        assertThat("Virtual thread P95 response time should be under target",
            virtualP95, lessThan(VIRTUAL_THREAD_P95_TARGET_MS));
        assertThat("Virtual thread P99 response time should be under target",
            virtualP99, lessThan(VIRTUAL_THREAD_P99_TARGET_MS));
        assertThat("Thread scaling efficiency should exceed target",
            scalingEfficiency, greaterThan(THREAD_SCALING_EFFICIENCY_TARGET));
        
        // Verify that virtual threads outperform platform threads
        assertThat("Virtual threads should have lower average response time than platform threads",
            virtualData.getAverage(), lessThan(platformData.getAverage()));
        assertThat("Virtual threads should have lower P95 response time than platform threads",
            virtualP95, lessThan(platformP95));
        assertThat("Virtual threads should have lower P99 response time than platform threads",
            virtualP99, lessThan(platformP99));
      }
    }
  }
  
  /**
   * Test Echo endpoint performance with both platform and virtual threads.
   */
  @Test
  public void testEchoEndpointPerformance() throws Exception {
    log("Testing Echo endpoint performance");
    
    // Test with platform threads
    PerformanceData platformData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(platformThreadExecutor)
        .measure(this::executeEchoRequest);
    results.put("platform-threads-echo", platformData);
    
    // Test with virtual threads
    PerformanceData virtualData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(virtualThreadExecutor)
        .measure(this::executeEchoRequest);
    results.put("virtual-threads-echo", virtualData);
    
    // Verify that virtual threads outperform platform threads for Echo endpoint
    assertThat("Virtual threads should have lower average response time for Echo endpoint",
        virtualData.getAverage(), lessThan(platformData.getAverage()));
  }
  
  /**
   * Test User endpoint performance with both platform and virtual threads.
   */
  @Test
  public void testUserEndpointPerformance() throws Exception {
    log("Testing User endpoint performance");
    
    // Test with platform threads
    PerformanceData platformData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(platformThreadExecutor)
        .measure(this::executeUserRequest);
    results.put("platform-threads-user", platformData);
    
    // Test with virtual threads
    PerformanceData virtualData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(virtualThreadExecutor)
        .measure(this::executeUserRequest);
    results.put("virtual-threads-user", virtualData);
    
    // Verify that virtual threads outperform platform threads for User endpoint
    assertThat("Virtual threads should have lower average response time for User endpoint",
        virtualData.getAverage(), lessThan(platformData.getAverage()));
  }
  
  /**
   * Test ValidationErrors endpoint performance with both platform and virtual threads.
   */
  @Test
  public void testValidationErrorsEndpointPerformance() throws Exception {
    log("Testing ValidationErrors endpoint performance");
    
    // Test with platform threads
    PerformanceData platformData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(platformThreadExecutor)
        .measure(this::executeValidationErrorsRequest);
    results.put("platform-threads-validation", platformData);
    
    // Test with virtual threads
    PerformanceData virtualData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(virtualThreadExecutor)
        .measure(this::executeValidationErrorsRequest);
    results.put("virtual-threads-validation", virtualData);
    
    // Verify that virtual threads outperform platform threads for ValidationErrors endpoint
    assertThat("Virtual threads should have lower average response time for ValidationErrors endpoint",
        virtualData.getAverage(), lessThan(platformData.getAverage()));
  }
  
  /**
   * Test Errors endpoint performance with both platform and virtual threads.
   */
  @Test
  public void testErrorsEndpointPerformance() throws Exception {
    log("Testing Errors endpoint performance");
    
    // Test with platform threads
    PerformanceData platformData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(platformThreadExecutor)
        .measure(this::executeErrorsRequest);
    results.put("platform-threads-errors", platformData);
    
    // Test with virtual threads
    PerformanceData virtualData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(virtualThreadExecutor)
        .measure(this::executeErrorsRequest);
    results.put("virtual-threads-errors", virtualData);
    
    // Verify that virtual threads outperform platform threads for Errors endpoint
    assertThat("Virtual threads should have lower average response time for Errors endpoint",
        virtualData.getAverage(), lessThan(platformData.getAverage()));
  }
  
  /**
   * Test overall Siesta performance with both platform and virtual threads across all endpoints.
   */
  @Test
  public void testOverallSiestaPerformance() throws Exception {
    log("Testing overall Siesta performance");
    
    // Test with platform threads
    PerformanceData platformData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(platformThreadExecutor)
        .measure(this::executeRandomRequest);
    results.put("platform-threads", platformData);
    
    // Test with virtual threads
    PerformanceData virtualData = new EscalatingClientLoadExecutor()
        .maxClients(MAX_CLIENTS)
        .clientStepSize(CLIENT_STEP_SIZE)
        .warmUpIterations(WARMUP_ITERATIONS)
        .measurementIterations(MEASUREMENT_ITERATIONS)
        .executor(virtualThreadExecutor)
        .measure(this::executeRandomRequest);
    results.put("virtual-threads", virtualData);
    
    // Verify that virtual threads outperform platform threads overall
    assertThat("Virtual threads should have lower average response time overall",
        virtualData.getAverage(), lessThan(platformData.getAverage()));
  }
  
  /**
   * Execute a request to the Echo endpoint.
   */
  private boolean executeEchoRequest() {
    try {
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      List<String> result = echo.get(UUID.randomUUID().toString());
      return result != null && !result.isEmpty();
    }
    catch (Exception e) {
      log.error("Error executing Echo request", e);
      return false;
    }
  }
  
  /**
   * Execute a request to the User endpoint.
   */
  private boolean executeUserRequest() {
    try {
      UserXO sent = new UserXO().withName(UUID.randomUUID().toString());
      
      WebTarget target = client().target(url("user"));
      Response response = target.request()
          .accept(APPLICATION_JSON_TYPE)
          .put(Entity.entity(sent, APPLICATION_JSON_TYPE), Response.class);
      
      if (response.getStatusInfo().getFamily() != Response.Status.Family.SUCCESSFUL) {
        return false;
      }
      
      UserXO received = response.readEntity(UserXO.class);
      return received != null && received.getName().equals(sent.getName());
    }
    catch (Exception e) {
      log.error("Error executing User request", e);
      return false;
    }
  }
  
  /**
   * Execute a request to the ValidationErrors endpoint.
   */
  private boolean executeValidationErrorsRequest() {
    try {
      UserXO sent = new UserXO(); // Empty user to trigger validation errors
      
      Response response = client().target(url("validationErrors/manual/multiple")).request()
          .accept(APPLICATION_JSON_TYPE, VND_VALIDATION_ERRORS_V1_JSON_TYPE)
          .put(Entity.entity(sent, APPLICATION_JSON_TYPE), Response.class);
      
      if (response.getStatusInfo() != Response.Status.BAD_REQUEST) {
        return false;
      }
      
      List<ValidationErrorXO> errors = response.readEntity(new GenericType<List<ValidationErrorXO>>() {});
      return errors != null && errors.size() == 2;
    }
    catch (Exception e) {
      log.error("Error executing ValidationErrors request", e);
      return false;
    }
  }
  
  /**
   * Execute a request to the Errors endpoint.
   */
  private boolean executeErrorsRequest() {
    try {
      WebTarget target = client().target(url("errors/406"));
      Response response = target.request().get(Response.class);
      
      return response.getStatusInfo().getStatusCode() == 406;
    }
    catch (Exception e) {
      log.error("Error executing Errors request", e);
      return false;
    }
  }
  
  /**
   * Execute a random request to one of the Siesta endpoints.
   */
  private boolean executeRandomRequest() {
    // Randomly select one of the endpoints to test
    int endpoint = (int) (Math.random() * 4);
    switch (endpoint) {
      case 0:
        return executeEchoRequest();
      case 1:
        return executeUserRequest();
      case 2:
        return executeValidationErrorsRequest();
      case 3:
        return executeErrorsRequest();
      default:
        return executeEchoRequest();
    }
  }
  
  /**
   * Executor that measures performance with an escalating number of concurrent clients.
   */
  private static class EscalatingClientLoadExecutor {
    private int maxClients = 1000;
    private int clientStepSize = 100;
    private int warmUpIterations = 10;
    private int measurementIterations = 100;
    private ExecutorService executor;
    
    public EscalatingClientLoadExecutor maxClients(int maxClients) {
      this.maxClients = maxClients;
      return this;
    }
    
    public EscalatingClientLoadExecutor clientStepSize(int clientStepSize) {
      this.clientStepSize = clientStepSize;
      return this;
    }
    
    public EscalatingClientLoadExecutor warmUpIterations(int warmUpIterations) {
      this.warmUpIterations = warmUpIterations;
      return this;
    }
    
    public EscalatingClientLoadExecutor measurementIterations(int measurementIterations) {
      this.measurementIterations = measurementIterations;
      return this;
    }
    
    public EscalatingClientLoadExecutor executor(ExecutorService executor) {
      this.executor = executor;
      return this;
    }
    
    /**
     * Measure performance with an escalating number of concurrent clients.
     */
    public PerformanceData measure(Supplier<Boolean> operation) throws Exception {
      PerformanceData data = new PerformanceData();
      
      // Warm up
      System.out.println("Warming up...");
      for (int i = 0; i < warmUpIterations; i++) {
        operation.get();
      }
      
      // Test with escalating client counts
      for (int clientCount = clientStepSize; clientCount <= maxClients; clientCount += clientStepSize) {
        System.out.println("Testing with " + clientCount + " clients");
        
        // Create a latch to wait for all clients to complete
        CountDownLatch latch = new CountDownLatch(clientCount * measurementIterations);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Track memory usage before test
        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        
        // Start timing
        long startTime = System.nanoTime();
        
        // Submit tasks for each client
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
          CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            for (int j = 0; j < measurementIterations; j++) {
              long requestStartTime = System.nanoTime();
              boolean success = false;
              
              try {
                success = operation.get();
              }
              catch (Exception e) {
                // Count errors but continue
                errorCount.incrementAndGet();
              }
              finally {
                long requestEndTime = System.nanoTime();
                long requestDuration = TimeUnit.NANOSECONDS.toMillis(requestEndTime - requestStartTime);
                
                if (success) {
                  data.recordResponseTime(requestDuration);
                }
                else {
                  errorCount.incrementAndGet();
                }
                
                latch.countDown();
              }
            }
          }, executor);
          
          futures.add(future);
        }
        
        // Wait for all clients to complete or timeout
        boolean completed = latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!completed) {
          System.out.println("Timeout waiting for clients to complete");
          errorCount.addAndGet((int) latch.getCount());
        }
        
        // End timing
        long endTime = System.nanoTime();
        long duration = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
        
        // Track memory usage after test
        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memoryUsed = memoryAfter - memoryBefore;
        
        // Record client count metrics
        data.recordClientCount(clientCount, duration, memoryUsed, errorCount.get());
        
        // Cancel any remaining futures
        for (CompletableFuture<Void> future : futures) {
          future.cancel(true);
        }
        
        // Force garbage collection to clean up between runs
        System.gc();
        Thread.sleep(1000); // Give GC time to run
      }
      
      return data;
    }
  }
  
  /**
   * Data structure to hold performance test results.
   */
  private static class PerformanceData {
    private final List<Long> responseTimes = new ArrayList<>();
    private final Map<Integer, ClientCountMetrics> clientCountMetrics = new HashMap<>();
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong maxResponseTime = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong responseCount = new AtomicLong(0);
    
    public synchronized void recordResponseTime(long responseTime) {
      responseTimes.add(responseTime);
      totalResponseTime.addAndGet(responseTime);
      responseCount.incrementAndGet();
      
      // Update max response time if needed
      long currentMax = maxResponseTime.get();
      while (responseTime > currentMax) {
        if (maxResponseTime.compareAndSet(currentMax, responseTime)) {
          break;
        }
        currentMax = maxResponseTime.get();
      }
    }
    
    public void recordClientCount(int clientCount, long duration, long memoryUsed, int errors) {
      clientCountMetrics.put(clientCount, new ClientCountMetrics(duration, memoryUsed, errors));
      totalErrors.addAndGet(errors);
    }
    
    public double getAverage() {
      return responseCount.get() > 0 ? (double) totalResponseTime.get() / responseCount.get() : 0;
    }
    
    public long getMaxResponseTime() {
      return maxResponseTime.get();
    }
    
    public long getErrorCount() {
      return totalErrors.get();
    }
    
    public double getPercentile(double percentile) {
      if (responseTimes.isEmpty()) {
        return 0;
      }
      
      List<Long> sortedTimes;
      synchronized (this) {
        sortedTimes = new ArrayList<>(responseTimes);
      }
      sortedTimes.sort(Long::compare);
      
      int index = (int) Math.ceil(percentile / 100.0 * sortedTimes.size()) - 1;
      if (index < 0) {
        index = 0;
      }
      return sortedTimes.get(index);
    }
    
    public Map<Integer, ClientCountMetrics> getClientCountMetrics() {
      return clientCountMetrics;
    }
    
    private static class ClientCountMetrics {
      private final long duration;
      private final long memoryUsed;
      private final int errors;
      
      public ClientCountMetrics(long duration, long memoryUsed, int errors) {
        this.duration = duration;
        this.memoryUsed = memoryUsed;
        this.errors = errors;
      }
      
      public long getDuration() {
        return duration;
      }
      
      public long getMemoryUsed() {
        return memoryUsed;
      }
      
      public int getErrors() {
        return errors;
      }
    }
  }
  
  /**
   * Utility class to generate HTML performance comparison charts.
   */
  private static class PerformanceChart {
    private final Map<String, PerformanceData> dataMap = new HashMap<>();
    
    public void addData(String name, PerformanceData data) {
      dataMap.put(name, data);
    }
    
    public void writeChartToFile(File file) throws IOException {
      StringBuilder html = new StringBuilder();
      html.append("<!DOCTYPE html>\n")
          .append("<html>\n")
          .append("<head>\n")
          .append("  <title>Siesta Thread Model Performance Comparison</title>\n")
          .append("  <script type=\"text/javascript\" src=\"https://www.gstatic.com/charts/loader.js\"></script>\n")
          .append("  <script type=\"text/javascript\">\n")
          .append("    google.charts.load('current', {'packages':['corechart']});\n")
          .append("    google.charts.setOnLoadCallback(drawCharts);\n\n")
          .append("    function drawCharts() {\n")
          .append(generateResponseTimeChart())
          .append(generateThroughputChart())
          .append(generateMemoryUsageChart())
          .append(generateErrorRateChart())
          .append("    }\n")
          .append("  </script>\n")
          .append("  <style>\n")
          .append("    .chart {\n")
          .append("      width: 900px;\n")
          .append("      height: 500px;\n")
          .append("      margin: 20px auto;\n")
          .append("    }\n")
          .append("    h1, h2 {\n")
          .append("      text-align: center;\n")
          .append("    }\n")
          .append("    .summary {\n")
          .append("      width: 80%;\n")
          .append("      margin: 20px auto;\n")
          .append("      border-collapse: collapse;\n")
          .append("    }\n")
          .append("    .summary th, .summary td {\n")
          .append("      border: 1px solid #ddd;\n")
          .append("      padding: 8px;\n")
          .append("      text-align: center;\n")
          .append("    }\n")
          .append("    .summary th {\n")
          .append("      background-color: #f2f2f2;\n")
          .append("    }\n")
          .append("  </style>\n")
          .append("</head>\n")
          .append("<body>\n")
          .append("  <h1>Siesta Thread Model Performance Comparison</h1>\n")
          .append("  <h2>Platform Threads vs. Virtual Threads</h2>\n")
          .append(generateSummaryTable())
          .append("  <div id=\"response_time_chart\" class=\"chart\"></div>\n")
          .append("  <div id=\"throughput_chart\" class=\"chart\"></div>\n")
          .append("  <div id=\"memory_usage_chart\" class=\"chart\"></div>\n")
          .append("  <div id=\"error_rate_chart\" class=\"chart\"></div>\n")
          .append("</body>\n")
          .append("</html>");
      
      java.nio.file.Files.writeString(file.toPath(), html.toString());
      System.out.println("Performance chart written to: " + file.getAbsolutePath());
    }
    
    private String generateResponseTimeChart() {
      StringBuilder js = new StringBuilder();
      js.append("      // Response Time Chart\n")
          .append("      var responseTimeData = google.visualization.arrayToDataTable([\n")
          .append("        ['Client Count'")
          .append(dataMap.keySet().stream()
              .map(name -> ",'" + name + " P95'").reduce("", String::concat))
          .append("],\n");
      
      // Find all client counts across all data sets
      Set<Integer> allClientCounts = new TreeSet<>();
      dataMap.values().forEach(data -> allClientCounts.addAll(data.getClientCountMetrics().keySet()));
      
      // Generate data rows
      for (Integer clientCount : allClientCounts) {
        js.append("        [" + clientCount);
        
        for (String name : dataMap.keySet()) {
          PerformanceData data = dataMap.get(name);
          // Filter response times for this client count
          double p95 = data.getPercentile(95.0);
          js.append(", " + p95);
        }
        
        js.append("],\n");
      }
      
      js.append("      ]);\n\n")
          .append("      var responseTimeOptions = {\n")
          .append("        title: 'P95 Response Time by Client Count',\n")
          .append("        hAxis: {title: 'Client Count'},\n")
          .append("        vAxis: {title: 'Response Time (ms)'},\n")
          .append("        legend: {position: 'bottom'}\n")
          .append("      };\n\n")
          .append("      var responseTimeChart = new google.visualization.LineChart(document.getElementById('response_time_chart'));\n")
          .append("      responseTimeChart.draw(responseTimeData, responseTimeOptions);\n\n");
      
      return js.toString();
    }
    
    private String generateThroughputChart() {
      StringBuilder js = new StringBuilder();
      js.append("      // Throughput Chart\n")
          .append("      var throughputData = google.visualization.arrayToDataTable([\n")
          .append("        ['Client Count'")
          .append(dataMap.keySet().stream()
              .map(name -> ",'" + name + "'").reduce("", String::concat))
          .append("],\n");
      
      // Find all client counts across all data sets
      Set<Integer> allClientCounts = new TreeSet<>();
      dataMap.values().forEach(data -> allClientCounts.addAll(data.getClientCountMetrics().keySet()));
      
      // Generate data rows
      for (Integer clientCount : allClientCounts) {
        js.append("        [" + clientCount);
        
        for (String name : dataMap.keySet()) {
          PerformanceData data = dataMap.get(name);
          PerformanceData.ClientCountMetrics metrics = data.getClientCountMetrics().get(clientCount);
          
          if (metrics != null) {
            // Calculate throughput as operations per second
            double throughput = (clientCount * MEASUREMENT_ITERATIONS) / (metrics.getDuration() / 1000.0);
            js.append(", " + throughput);
          }
          else {
            js.append(", 0");
          }
        }
        
        js.append("],\n");
      }
      
      js.append("      ]);\n\n")
          .append("      var throughputOptions = {\n")
          .append("        title: 'Throughput by Client Count',\n")
          .append("        hAxis: {title: 'Client Count'},\n")
          .append("        vAxis: {title: 'Operations per Second'},\n")
          .append("        legend: {position: 'bottom'}\n")
          .append("      };\n\n")
          .append("      var throughputChart = new google.visualization.LineChart(document.getElementById('throughput_chart'));\n")
          .append("      throughputChart.draw(throughputData, throughputOptions);\n\n");
      
      return js.toString();
    }
    
    private String generateMemoryUsageChart() {
      StringBuilder js = new StringBuilder();
      js.append("      // Memory Usage Chart\n")
          .append("      var memoryData = google.visualization.arrayToDataTable([\n")
          .append("        ['Client Count'")
          .append(dataMap.keySet().stream()
              .map(name -> ",'" + name + "'").reduce("", String::concat))
          .append("],\n");
      
      // Find all client counts across all data sets
      Set<Integer> allClientCounts = new TreeSet<>();
      dataMap.values().forEach(data -> allClientCounts.addAll(data.getClientCountMetrics().keySet()));
      
      // Generate data rows
      for (Integer clientCount : allClientCounts) {
        js.append("        [" + clientCount);
        
        for (String name : dataMap.keySet()) {
          PerformanceData data = dataMap.get(name);
          PerformanceData.ClientCountMetrics metrics = data.getClientCountMetrics().get(clientCount);
          
          if (metrics != null) {
            // Convert memory usage to MB
            double memoryMB = metrics.getMemoryUsed() / (1024.0 * 1024.0);
            js.append(", " + memoryMB);
          }
          else {
            js.append(", 0");
          }
        }
        
        js.append("],\n");
      }
      
      js.append("      ]);\n\n")
          .append("      var memoryOptions = {\n")
          .append("        title: 'Memory Usage by Client Count',\n")
          .append("        hAxis: {title: 'Client Count'},\n")
          .append("        vAxis: {title: 'Memory Usage (MB)'},\n")
          .append("        legend: {position: 'bottom'}\n")
          .append("      };\n\n")
          .append("      var memoryChart = new google.visualization.LineChart(document.getElementById('memory_usage_chart'));\n")
          .append("      memoryChart.draw(memoryData, memoryOptions);\n\n");
      
      return js.toString();
    }
    
    private String generateErrorRateChart() {
      StringBuilder js = new StringBuilder();
      js.append("      // Error Rate Chart\n")
          .append("      var errorData = google.visualization.arrayToDataTable([\n")
          .append("        ['Client Count'")
          .append(dataMap.keySet().stream()
              .map(name -> ",'" + name + "'").reduce("", String::concat))
          .append("],\n");
      
      // Find all client counts across all data sets
      Set<Integer> allClientCounts = new TreeSet<>();
      dataMap.values().forEach(data -> allClientCounts.addAll(data.getClientCountMetrics().keySet()));
      
      // Generate data rows
      for (Integer clientCount : allClientCounts) {
        js.append("        [" + clientCount);
        
        for (String name : dataMap.keySet()) {
          PerformanceData data = dataMap.get(name);
          PerformanceData.ClientCountMetrics metrics = data.getClientCountMetrics().get(clientCount);
          
          if (metrics != null) {
            // Calculate error rate as percentage
            double errorRate = 100.0 * metrics.getErrors() / (clientCount * MEASUREMENT_ITERATIONS);
            js.append(", " + errorRate);
          }
          else {
            js.append(", 0");
          }
        }
        
        js.append("],\n");
      }
      
      js.append("      ]);\n\n")
          .append("      var errorOptions = {\n")
          .append("        title: 'Error Rate by Client Count',\n")
          .append("        hAxis: {title: 'Client Count'},\n")
          .append("        vAxis: {title: 'Error Rate (%)'},\n")
          .append("        legend: {position: 'bottom'}\n")
          .append("      };\n\n")
          .append("      var errorChart = new google.visualization.LineChart(document.getElementById('error_rate_chart'));\n")
          .append("      errorChart.draw(errorData, errorOptions);\n\n");
      
      return js.toString();
    }
    
    private String generateSummaryTable() {
      StringBuilder html = new StringBuilder();
      html.append("  <table class=\"summary\">\n")
          .append("    <tr>\n")
          .append("      <th>Thread Model</th>\n")
          .append("      <th>Avg Response Time (ms)</th>\n")
          .append("      <th>P95 Response Time (ms)</th>\n")
          .append("      <th>P99 Response Time (ms)</th>\n")
          .append("      <th>Max Response Time (ms)</th>\n")
          .append("      <th>Error Count</th>\n")
          .append("    </tr>\n");
      
      for (Map.Entry<String, PerformanceData> entry : dataMap.entrySet()) {
        String name = entry.getKey();
        PerformanceData data = entry.getValue();
        
        html.append("    <tr>\n")
            .append("      <td>" + name + "</td>\n")
            .append("      <td>" + String.format("%.2f", data.getAverage()) + "</td>\n")
            .append("      <td>" + String.format("%.2f", data.getPercentile(95.0)) + "</td>\n")
            .append("      <td>" + String.format("%.2f", data.getPercentile(99.0)) + "</td>\n")
            .append("      <td>" + data.getMaxResponseTime() + "</td>\n")
            .append("      <td>" + data.getErrorCount() + "</td>\n")
            .append("    </tr>\n");
      }
      
      html.append("  </table>\n");
      return html.toString();
    }
  }
}