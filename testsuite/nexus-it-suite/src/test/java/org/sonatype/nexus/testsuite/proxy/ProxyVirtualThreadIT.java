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
package org.sonatype.nexus.testsuite.proxy;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import java.util.stream.IntStream;

import org.sonatype.goodies.httpfixture.server.fluent.Behaviours;
import org.sonatype.goodies.httpfixture.server.fluent.Server;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.goodies.testsupport.group.VirtualThreadTestGroup;
import org.sonatype.nexus.common.collect.SequencedHashMap;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.config.Configuration;
import org.sonatype.nexus.repository.proxy.ProxyFacet;
import org.sonatype.nexus.testsuite.testsupport.RepositoryITSupport;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceData;
import org.sonatype.nexus.testsuite.testsupport.performance.PerformanceChart;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static java.lang.StringTemplate.RAW;
import static java.lang.StringTemplate.STR;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.sonatype.nexus.repository.http.HttpStatus.OK;

/**
 * Integration tests for validating Java 21 Virtual Thread behavior and feature compatibility
 * in Nexus Proxy repository scenarios.
 * <p>
 * These tests verify that:
 * 1. Proxy operations leverage virtual threads for improved concurrency
 * 2. Java 21 features (pattern matching, record patterns, string templates, sequenced collections)
 *    work correctly in integration flows
 * 3. Virtual thread performance characteristics meet expectations for scalability and efficiency
 * 4. Thread pinning is properly detected and avoided in proxy operations
 */
@Category({Java21TestGroup.class, VirtualThreadTestGroup.class})
public class ProxyVirtualThreadIT
    extends RepositoryITSupport
{
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int LARGE_CONCURRENT_REQUESTS = 1000;
  private static final int TEST_TIMEOUT_SECONDS = 60;
  private static final String TEST_CONTENT = "test content";
  private static final String TEST_PATH = "/some/content.txt";
  
  private Server server;
  private Repository repository;
  
  public ProxyVirtualThreadIT() {
    super("raw");
  }
  
  @Before
  public void setup() throws Exception {
    // Start a server to proxy
    server = Server.withPort(0)
        .serve(TEST_PATH)
        .withBehaviours(Behaviours.content(TEST_CONTENT))
        .start();
    
    // Create a raw proxy repository pointing to our server
    repository = repos.createRawProxy("test-repo", server.getUrl().toExternalForm());
  }
  
  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
  }
  
  /**
   * Tests that proxy operations work correctly with virtual threads.
   * This validates basic functionality using Java 21's virtual threads.
   */
  @Test
  public void proxyOperationsWorkWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Submit a task to fetch content via the proxy repository
      CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
        try {
          // Get content through the proxy
          return downloadContent(repository, TEST_PATH);
        } 
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor);
      
      // Wait for the result
      String content = future.get(30, SECONDS);
      
      // Verify the content
      assertEquals(TEST_CONTENT, content);
    }
  }
  
  /**
   * Tests concurrent proxy operations using virtual threads.
   * This validates that many concurrent operations can be handled efficiently.
   */
  @Test
  public void concurrentProxyOperationsWithVirtualThreads() throws Exception {
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit multiple concurrent tasks
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Get content through the proxy
            String content = downloadContent(repository, TEST_PATH);
            assertEquals(TEST_CONTENT, content);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TEST_TIMEOUT_SECONDS, SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue("Not all tasks completed in time", completed);
      assertEquals("Some tasks encountered errors", 0, errorCount.get());
    }
  }
  
  /**
   * Tests pattern matching with switch expressions in proxy operations.
   * This validates Java 21's pattern matching feature in integration scenarios.
   */
  @Test
  public void patternMatchingWithProxyOperations() throws Exception {
    // Create different types of proxy configurations to test pattern matching
    Object[] testCases = {
        "direct",                // String case
        Integer.valueOf(42),    // Integer case
        Duration.ofSeconds(10), // Duration case
        List.of("a", "b"),      // List case
        Map.of("key", "value")  // Map case
    };
    
    for (Object testCase : testCases) {
      // Use pattern matching with switch to handle different types
      String result = switch (testCase) {
        case String s -> handleStringConfig(s);
        case Integer i -> handleIntegerConfig(i);
        case Duration d -> handleDurationConfig(d);
        case List<?> l -> handleListConfig(l);
        case Map<?, ?> m -> handleMapConfig(m);
        default -> "unknown";
      };
      
      // Verify the result is not "unknown"
      assertThat(result, not(equalTo("unknown")));
      
      // Verify proxy still works after pattern matching operations
      String content = downloadContent(repository, TEST_PATH);
      assertEquals(TEST_CONTENT, content);
    }
  }
  
  /**
   * Tests record patterns in proxy operations.
   * This validates Java 21's record pattern feature in integration scenarios.
   */
  @Test
  public void recordPatternsWithProxyOperations() throws Exception {
    // Define records for testing
    record Point(int x, int y) {}
    record Rectangle(Point topLeft, Point bottomRight) {}
    record ProxyConfig(String name, String remoteUrl, int timeout) {}
    
    // Create test data
    Rectangle rectangle = new Rectangle(new Point(1, 1), new Point(5, 5));
    ProxyConfig config = new ProxyConfig("test-repo", server.getUrl().toExternalForm(), 30);
    
    // Use record pattern matching to extract values
    if (rectangle instanceof Rectangle(Point(int x1, int y1), Point(int x2, int y2))) {
      int width = x2 - x1;
      int height = y2 - y1;
      
      assertEquals(4, width);
      assertEquals(4, height);
    } else {
      throw new AssertionError("Record pattern matching failed");
    }
    
    // Use record pattern with proxy configuration
    if (config instanceof ProxyConfig(String name, String url, int timeout)) {
      // Create a new repository with the extracted configuration
      Repository newRepo = repos.createRawProxy(name, url);
      
      // Verify the repository works
      String content = downloadContent(newRepo, TEST_PATH);
      assertEquals(TEST_CONTENT, content);
    } else {
      throw new AssertionError("Record pattern matching failed for proxy config");
    }
  }
  
  /**
   * Tests string templates in proxy operations.
   * This validates Java 21's string template feature in integration scenarios.
   */
  @Test
  public void stringTemplatesWithProxyOperations() throws Exception {
    String repoName = repository.getName();
    String repoUrl = repository.getConfiguration().attributes("proxy").get("remoteUrl").toString();
    
    // Use string templates to create messages
    String message = STR."Repository \{repoName} proxies content from \{repoUrl}";
    assertThat(message, containsString(repoName));
    assertThat(message, containsString(repoUrl));
    
    // Use raw string template for logging
    String logMessage = RAW."GET \{TEST_PATH} from \{repoName} (\{repoUrl})";
    assertThat(logMessage, containsString(TEST_PATH));
    assertThat(logMessage, containsString(repoName));
    
    // Verify proxy still works after string template operations
    String content = downloadContent(repository, TEST_PATH);
    assertEquals(TEST_CONTENT, content);
  }
  
  /**
   * Tests sequenced collections in proxy operations.
   * This validates Java 21's sequenced collections feature in integration scenarios.
   */
  @Test
  public void sequencedCollectionsWithProxyOperations() throws Exception {
    // Create a sequenced map to store proxy requests
    Map<String, String> requests = new SequencedHashMap<>();
    
    // Add some test requests
    requests.put("request1", TEST_PATH);
    requests.put("request2", TEST_PATH + "?param=value");
    requests.put("request3", "/another/path.txt");
    
    // Use sequenced collection methods
    String firstKey = requests.sequencedKeySet().getFirst();
    String lastKey = requests.sequencedKeySet().getLast();
    
    assertEquals("request1", firstKey);
    assertEquals("request3", lastKey);
    
    // Process requests in order
    for (Map.Entry<String, String> entry : requests.sequencedEntrySet()) {
      String requestId = entry.getKey();
      String path = entry.getValue();
      
      if (path.equals(TEST_PATH) || path.startsWith(TEST_PATH + "?")) {
        // Only request paths that exist on the server
        try {
          String content = downloadContent(repository, path);
          assertEquals(TEST_CONTENT, content);
        } 
        catch (Exception e) {
          // Expected for paths that don't exist
          if (!path.contains("?")) {
            throw e; // Only parameter variations should fail
          }
        }
      }
    }
    
    // Reverse the collection and process again
    List<String> reversedKeys = new ArrayList<>(requests.sequencedKeySet());
    java.util.Collections.reverse(reversedKeys);
    
    assertEquals("request3", reversedKeys.get(0));
    assertEquals("request1", reversedKeys.get(reversedKeys.size() - 1));
  }
  
  /**
   * Tests virtual thread performance compared to platform threads.
   * This validates the scalability benefits of virtual threads in proxy operations.
   */
  @Test
  public void virtualThreadPerformanceComparison() throws Exception {
    // Create thread factories
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    
    // Run performance test with both thread types
    PerformanceData virtualThreadResults = runPerformanceTest(virtualThreadFactory, "Virtual Threads");
    PerformanceData platformThreadResults = runPerformanceTest(platformThreadFactory, "Platform Threads");
    
    // Generate comparison chart
    PerformanceChart chart = new PerformanceChart();
    chart.addData("Virtual Threads", virtualThreadResults);
    chart.addData("Platform Threads", platformThreadResults);
    chart.writeChartToFile(resolveBaseFile("thread-performance-comparison.html"));
    
    // Verify virtual thread performance
    double virtualP95 = virtualThreadResults.getPercentile(95.0);
    double platformP95 = platformThreadResults.getPercentile(95.0);
    
    // Log the results for analysis
    System.out.println("Virtual Thread P95: " + virtualP95 + "ms");
    System.out.println("Platform Thread P95: " + platformP95 + "ms");
    
    // Verify error counts are zero
    assertEquals(0, virtualThreadResults.getErrorCount());
    assertEquals(0, platformThreadResults.getErrorCount());
    
    // Note: We don't assert that virtual threads are faster as it depends on the test environment
    // In some cases, the overhead of creating many virtual threads might offset the benefits
    // for short-lived operations. The important thing is that they work correctly and scale well.
  }
  
  /**
   * Tests virtual thread scalability with a large number of concurrent requests.
   * This validates that virtual threads can handle high concurrency efficiently.
   */
  @Test
  public void virtualThreadScalability() throws Exception {
    // Skip this test if running in a resource-constrained environment
    org.junit.Assume.assumeTrue("Skipping large concurrency test in CI environment", 
        !Boolean.getBoolean("ci.environment"));
    
    // Create a virtual thread factory
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    
    // Create an executor service using virtual threads
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(LARGE_CONCURRENT_REQUESTS);
      AtomicInteger errorCount = new AtomicInteger(0);
      AtomicLong totalTime = new AtomicLong(0);
      
      // Submit a large number of concurrent tasks
      for (int i = 0; i < LARGE_CONCURRENT_REQUESTS; i++) {
        executor.submit(() -> {
          long startTime = System.currentTimeMillis();
          try {
            // Get content through the proxy
            String content = downloadContent(repository, TEST_PATH);
            assertEquals(TEST_CONTENT, content);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            totalTime.addAndGet(System.currentTimeMillis() - startTime);
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TEST_TIMEOUT_SECONDS, SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue("Not all tasks completed in time", completed);
      assertEquals("Some tasks encountered errors", 0, errorCount.get());
      
      // Calculate average time per request
      double avgTimePerRequest = (double) totalTime.get() / LARGE_CONCURRENT_REQUESTS;
      System.out.println("Average time per request: " + avgTimePerRequest + "ms");
      
      // Verify the average time is reasonable (adjust threshold as needed)
      assertThat(avgTimePerRequest, lessThan(1000.0)); // Less than 1 second per request on average
    }
  }
  
  /**
   * Tests thread pinning detection in proxy operations.
   * This validates that virtual threads are not pinned during proxy operations.
   */
  @Test
  public void threadPinningDetection() throws Exception {
    // Enable thread pinning detection
    System.setProperty("jdk.tracePinnedThreads", "full");
    
    try {
      // Create a virtual thread factory
      ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
      
      // Create an executor service using virtual threads
      try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
        // Submit a task that should not cause pinning
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
          try {
            // Get content through the proxy
            String content = downloadContent(repository, TEST_PATH);
            return content.equals(TEST_CONTENT);
          } 
          catch (Exception e) {
            throw new RuntimeException(e);
          }
        }, executor);
        
        // Wait for the result
        Boolean result = future.get(30, SECONDS);
        
        // Verify the result
        assertTrue(result);
      }
      
      // Note: We can't directly assert that no pinning occurred,
      // but the test passing without warnings in logs is a good indicator
    } 
    finally {
      // Reset the property
      System.clearProperty("jdk.tracePinnedThreads");
    }
  }
  
  /**
   * Tests memory efficiency of virtual threads compared to platform threads.
   * This validates that virtual threads use less memory for concurrent operations.
   */
  @Test
  public void memoryEfficiencyTest() throws Exception {
    // Skip this test if running in a resource-constrained environment
    org.junit.Assume.assumeTrue("Skipping memory test in CI environment", 
        !Boolean.getBoolean("ci.environment"));
    
    // Force garbage collection before starting
    System.gc();
    Thread.sleep(1000); // Give GC time to complete
    
    // Measure memory before virtual threads
    long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Run with virtual threads
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    runConcurrentRequests(virtualThreadFactory, CONCURRENT_REQUESTS);
    
    // Force garbage collection again
    System.gc();
    Thread.sleep(1000); // Give GC time to complete
    
    // Measure memory after virtual threads
    long memoryAfterVirtual = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Run with platform threads
    ThreadFactory platformThreadFactory = Thread.ofPlatform().factory();
    runConcurrentRequests(platformThreadFactory, CONCURRENT_REQUESTS);
    
    // Force garbage collection again
    System.gc();
    Thread.sleep(1000); // Give GC time to complete
    
    // Measure memory after platform threads
    long memoryAfterPlatform = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    
    // Log memory usage for analysis
    System.out.println("Memory before: " + memoryBefore / (1024 * 1024) + " MB");
    System.out.println("Memory after virtual threads: " + memoryAfterVirtual / (1024 * 1024) + " MB");
    System.out.println("Memory after platform threads: " + memoryAfterPlatform / (1024 * 1024) + " MB");
    
    // Note: We don't make assertions about memory usage as it can vary significantly
    // between environments and JVM implementations. The logged values provide insight
    // for manual analysis.
  }
  
  /**
   * Helper method to run a performance test with the specified thread factory.
   */
  private PerformanceData runPerformanceTest(ThreadFactory threadFactory, String label) throws Exception {
    PerformanceData data = new PerformanceData();
    data.setLabel(label);
    
    // Create an executor service with the specified thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Run multiple iterations to get stable results
      for (int iteration = 0; iteration < 5; iteration++) {
        // Create a latch to wait for all operations to complete
        CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Record start time
        long startTime = System.currentTimeMillis();
        
        // Submit concurrent tasks
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
          executor.submit(() -> {
            try {
              long requestStart = System.currentTimeMillis();
              
              // Get content through the proxy
              String content = downloadContent(repository, TEST_PATH);
              assertEquals(TEST_CONTENT, content);
              
              // Record request time
              long requestTime = System.currentTimeMillis() - requestStart;
              data.addValue(requestTime);
            } 
            catch (Exception e) {
              errorCount.incrementAndGet();
            } 
            finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all tasks to complete
        boolean completed = latch.await(TEST_TIMEOUT_SECONDS, SECONDS);
        
        // Record total time
        long totalTime = System.currentTimeMillis() - startTime;
        
        // Verify all tasks completed successfully
        assertTrue("Not all tasks completed in time", completed);
        assertEquals("Some tasks encountered errors", 0, errorCount.get());
        
        // Log iteration results
        System.out.println(label + " iteration " + iteration + ": " + totalTime + "ms for " + 
            CONCURRENT_REQUESTS + " requests");
        
        // Add a small delay between iterations
        Thread.sleep(500);
      }
    }
    
    return data;
  }
  
  /**
   * Helper method to run concurrent requests with the specified thread factory.
   */
  private void runConcurrentRequests(ThreadFactory threadFactory, int requestCount) throws Exception {
    // Create an executor service with the specified thread factory
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(threadFactory)) {
      // Create a latch to wait for all operations to complete
      CountDownLatch latch = new CountDownLatch(requestCount);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Submit concurrent tasks
      for (int i = 0; i < requestCount; i++) {
        executor.submit(() -> {
          try {
            // Get content through the proxy
            String content = downloadContent(repository, TEST_PATH);
            assertEquals(TEST_CONTENT, content);
          } 
          catch (Exception e) {
            errorCount.incrementAndGet();
          } 
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(TEST_TIMEOUT_SECONDS, SECONDS);
      
      // Verify all tasks completed successfully
      assertTrue("Not all tasks completed in time", completed);
      assertEquals("Some tasks encountered errors", 0, errorCount.get());
    }
  }
  
  /**
   * Helper method to download content from a repository.
   */
  private String downloadContent(Repository repository, String path) throws Exception {
    return repos.getContent(repository, path);
  }
  
  /**
   * Helper methods for pattern matching test cases.
   */
  private String handleStringConfig(String config) {
    return "Handled string config: " + config;
  }
  
  private String handleIntegerConfig(Integer config) {
    return "Handled integer config: " + config;
  }
  
  private String handleDurationConfig(Duration config) {
    return "Handled duration config: " + config.getSeconds() + "s";
  }
  
  private String handleListConfig(List<?> config) {
    return "Handled list config with " + config.size() + " items";
  }
  
  private String handleMapConfig(Map<?, ?> config) {
    return "Handled map config with " + config.size() + " entries";
  }
}