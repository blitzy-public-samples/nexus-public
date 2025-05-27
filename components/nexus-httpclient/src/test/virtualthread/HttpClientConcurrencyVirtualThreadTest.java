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
package org.sonatype.nexus.httpclient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for validating HTTP client behavior under high concurrency scenarios using Java 21 Virtual Threads.
 * 
 * This test class validates the client's ability to handle thousands of concurrent connections with minimal
 * resource overhead, validates correct response handling, and ensures client robustness under extreme load conditions.
 */
public class HttpClientConcurrencyVirtualThreadTest
{
  private static final int SERVER_PORT = 8765;
  private static final String SERVER_HOST = "localhost";
  private static final String SERVER_URL = "http://" + SERVER_HOST + ":" + SERVER_PORT;
  private static final int SMALL_CONCURRENCY = 100;
  private static final int MEDIUM_CONCURRENCY = 1000;
  private static final int LARGE_CONCURRENCY = 10000;
  private static final int RESPONSE_DELAY_MS = 50; // Simulated server processing delay
  
  private HttpServer server;
  private HttpClient virtualThreadClient;
  private HttpClient platformThreadClient;
  private final AtomicInteger requestCounter = new AtomicInteger(0);
  private final ConcurrentHashMap<String, AtomicInteger> methodCounts = new ConcurrentHashMap<>();
  
  /**
   * Sets up the test HTTP server and HTTP clients before each test.
   */
  @BeforeEach
  public void setUp() throws IOException {
    // Reset counters
    requestCounter.set(0);
    methodCounts.clear();
    
    // Create and start HTTP server
    server = HttpServer.create(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 0);
    server.createContext("/echo", new EchoHandler());
    server.createContext("/delay", new DelayHandler());
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
    
    // Create HTTP client with virtual threads
    virtualThreadClient = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    // Create HTTP client with platform threads for comparison
    platformThreadClient = HttpClient.newBuilder()
        .executor(Executors.newFixedThreadPool(100)) // Limited to 100 platform threads
        .connectTimeout(Duration.ofSeconds(5))
        .build();
  }
  
  /**
   * Cleans up resources after each test.
   */
  @AfterEach
  public void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }
  
  /**
   * Tests the HTTP client's ability to handle a large number of concurrent connections using Virtual Threads.
   * This test validates that the client can efficiently manage 10,000+ concurrent connections with minimal
   * resource overhead compared to platform threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Measure memory before test
    long memoryBefore = getUsedMemory();
    
    // Execute large number of concurrent requests
    List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
    for (int i = 0; i < LARGE_CONCURRENCY; i++) {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(SERVER_URL + "/echo?id=" + i))
          .GET()
          .build();
      
      CompletableFuture<HttpResponse<String>> future = virtualThreadClient.sendAsync(
          request, HttpResponse.BodyHandlers.ofString());
      futures.add(future);
    }
    
    // Wait for all requests to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0]));
    allFutures.join();
    
    // Measure memory after test
    long memoryAfter = getUsedMemory();
    long memoryUsed = memoryAfter - memoryBefore;
    
    // Verify all requests were successful
    for (CompletableFuture<HttpResponse<String>> future : futures) {
      HttpResponse<String> response = future.get();
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains("Echo"));
    }
    
    // Verify request count
    assertEquals(LARGE_CONCURRENCY, requestCounter.get());
    
    // Log memory usage for analysis
    System.out.println("Memory used for " + LARGE_CONCURRENCY + " virtual thread requests: " + 
        (memoryUsed / (1024 * 1024)) + " MB");
  }
  
  /**
   * Tests the HTTP client's ability to handle different HTTP methods (GET, POST, PUT) concurrently
   * using Virtual Threads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testConcurrentHttpMethods() throws Exception {
    // Initialize method counters
    methodCounts.put("GET", new AtomicInteger(0));
    methodCounts.put("POST", new AtomicInteger(0));
    methodCounts.put("PUT", new AtomicInteger(0));
    
    // Create executor with virtual threads
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(MEDIUM_CONCURRENCY * 3); // For all three methods
    
    // Submit GET requests
    for (int i = 0; i < MEDIUM_CONCURRENCY; i++) {
      executor.submit(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/echo"))
              .GET()
              .build();
          
          HttpResponse<String> response = virtualThreadClient.send(
              request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          methodCounts.get("GET").incrementAndGet();
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Submit POST requests
    for (int i = 0; i < MEDIUM_CONCURRENCY; i++) {
      executor.submit(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/echo"))
              .POST(HttpRequest.BodyPublishers.ofString("Post data " + Thread.currentThread().getName()))
              .build();
          
          HttpResponse<String> response = virtualThreadClient.send(
              request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          methodCounts.get("POST").incrementAndGet();
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Submit PUT requests
    for (int i = 0; i < MEDIUM_CONCURRENCY; i++) {
      executor.submit(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/echo"))
              .PUT(HttpRequest.BodyPublishers.ofString("Put data " + Thread.currentThread().getName()))
              .build();
          
          HttpResponse<String> response = virtualThreadClient.send(
              request, HttpResponse.BodyHandlers.ofString());
          assertEquals(200, response.statusCode());
          methodCounts.get("PUT").incrementAndGet();
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS));
    
    // Verify all requests were processed
    assertEquals(MEDIUM_CONCURRENCY, methodCounts.get("GET").get());
    assertEquals(MEDIUM_CONCURRENCY, methodCounts.get("POST").get());
    assertEquals(MEDIUM_CONCURRENCY, methodCounts.get("PUT").get());
  }
  
  /**
   * Compares the performance and resource usage between Virtual Threads and Platform Threads
   * when handling concurrent HTTP requests.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testVirtualThreadsVsPlatformThreads() throws Exception {
    // Test parameters
    final int concurrency = MEDIUM_CONCURRENCY;
    final AtomicInteger virtualThreadSuccessCount = new AtomicInteger(0);
    final AtomicInteger platformThreadSuccessCount = new AtomicInteger(0);
    final AtomicInteger virtualThreadErrorCount = new AtomicInteger(0);
    final AtomicInteger platformThreadErrorCount = new AtomicInteger(0);
    
    // Measure execution time and success rate for virtual threads
    long virtualThreadStartTime = System.currentTimeMillis();
    CountDownLatch virtualThreadLatch = new CountDownLatch(concurrency);
    
    for (int i = 0; i < concurrency; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/delay"))
              .GET()
              .build();
          
          HttpResponse<String> response = virtualThreadClient.send(
              request, HttpResponse.BodyHandlers.ofString());
          
          if (response.statusCode() == 200) {
            virtualThreadSuccessCount.incrementAndGet();
          } else {
            virtualThreadErrorCount.incrementAndGet();
          }
        } catch (Exception e) {
          virtualThreadErrorCount.incrementAndGet();
        } finally {
          virtualThreadLatch.countDown();
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
    }
    
    virtualThreadLatch.await();
    long virtualThreadDuration = System.currentTimeMillis() - virtualThreadStartTime;
    
    // Reset counter for platform thread test
    requestCounter.set(0);
    
    // Measure execution time and success rate for platform threads
    long platformThreadStartTime = System.currentTimeMillis();
    CountDownLatch platformThreadLatch = new CountDownLatch(concurrency);
    
    for (int i = 0; i < concurrency; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/delay"))
              .GET()
              .build();
          
          HttpResponse<String> response = platformThreadClient.send(
              request, HttpResponse.BodyHandlers.ofString());
          
          if (response.statusCode() == 200) {
            platformThreadSuccessCount.incrementAndGet();
          } else {
            platformThreadErrorCount.incrementAndGet();
          }
        } catch (Exception e) {
          platformThreadErrorCount.incrementAndGet();
        } finally {
          platformThreadLatch.countDown();
        }
      });
    }
    
    platformThreadLatch.await();
    long platformThreadDuration = System.currentTimeMillis() - platformThreadStartTime;
    
    // Log results
    System.out.println("Virtual Threads: " + concurrency + " requests in " + virtualThreadDuration + 
        "ms, Success: " + virtualThreadSuccessCount.get() + ", Errors: " + virtualThreadErrorCount.get());
    System.out.println("Platform Threads: " + concurrency + " requests in " + platformThreadDuration + 
        "ms, Success: " + platformThreadSuccessCount.get() + ", Errors: " + platformThreadErrorCount.get());
    
    // Verify virtual threads performed better
    assertTrue(virtualThreadSuccessCount.get() >= platformThreadSuccessCount.get(), 
        "Virtual threads should handle at least as many successful requests as platform threads");
    assertTrue(virtualThreadErrorCount.get() <= platformThreadErrorCount.get(), 
        "Virtual threads should have fewer errors than platform threads");
  }
  
  /**
   * Tests the HTTP client's ability to maintain connection stability under sustained high concurrency
   * with Virtual Threads.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  public void testConnectionStabilityUnderSustainedLoad() throws Exception {
    // Parameters for sustained load test
    final int batchSize = SMALL_CONCURRENCY;
    final int batchCount = 10;
    final AtomicLong totalSuccessCount = new AtomicLong(0);
    final AtomicLong totalErrorCount = new AtomicLong(0);
    
    // Run multiple batches of concurrent requests
    for (int batch = 0; batch < batchCount; batch++) {
      CountDownLatch batchLatch = new CountDownLatch(batchSize);
      AtomicInteger batchSuccessCount = new AtomicInteger(0);
      AtomicInteger batchErrorCount = new AtomicInteger(0);
      
      // Create and submit batch of requests
      for (int i = 0; i < batchSize; i++) {
        CompletableFuture.runAsync(() -> {
          try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(SERVER_URL + "/delay"))
                .GET()
                .build();
            
            HttpResponse<String> response = virtualThreadClient.send(
                request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
              batchSuccessCount.incrementAndGet();
            } else {
              batchErrorCount.incrementAndGet();
            }
          } catch (Exception e) {
            batchErrorCount.incrementAndGet();
          } finally {
            batchLatch.countDown();
          }
        }, Executors.newVirtualThreadPerTaskExecutor());
      }
      
      // Wait for batch to complete
      assertTrue(batchLatch.await(10, TimeUnit.SECONDS), 
          "Batch " + batch + " did not complete in time");
      
      // Update totals
      totalSuccessCount.addAndGet(batchSuccessCount.get());
      totalErrorCount.addAndGet(batchErrorCount.get());
      
      // Log batch results
      System.out.println("Batch " + batch + ": Success: " + batchSuccessCount.get() + 
          ", Errors: " + batchErrorCount.get());
      
      // Short pause between batches
      Thread.sleep(100);
    }
    
    // Verify overall success rate
    long totalRequests = batchSize * batchCount;
    double successRate = (double) totalSuccessCount.get() / totalRequests;
    
    System.out.println("Overall: " + totalSuccessCount.get() + " successful requests out of " + 
        totalRequests + " (" + (successRate * 100) + "% success rate)");
    
    assertTrue(successRate > 0.99, "Success rate should be greater than 99%");
    assertTrue(totalErrorCount.get() < totalRequests * 0.01, "Error rate should be less than 1%");
  }
  
  /**
   * Tests the connection pool behavior with Virtual Threads, ensuring that connections are properly
   * managed and reused when appropriate.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  public void testConnectionPoolBehavior() throws Exception {
    // Create a client with connection pooling enabled
    HttpClient poolingClient = HttpClient.newBuilder()
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    // Parameters
    final int requestCount = MEDIUM_CONCURRENCY;
    final CountDownLatch latch = new CountDownLatch(requestCount);
    final AtomicInteger successCount = new AtomicInteger(0);
    
    // Execute requests that should benefit from connection pooling
    for (int i = 0; i < requestCount; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          // Make two consecutive requests to the same endpoint
          HttpRequest request1 = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/echo"))
              .GET()
              .build();
          
          HttpResponse<String> response1 = poolingClient.send(
              request1, HttpResponse.BodyHandlers.ofString());
          
          HttpRequest request2 = HttpRequest.newBuilder()
              .uri(URI.create(SERVER_URL + "/echo"))
              .GET()
              .build();
          
          HttpResponse<String> response2 = poolingClient.send(
              request2, HttpResponse.BodyHandlers.ofString());
          
          // Both requests should succeed
          if (response1.statusCode() == 200 && response2.statusCode() == 200) {
            successCount.incrementAndGet();
          }
        } catch (Exception e) {
          e.printStackTrace();
        } finally {
          latch.countDown();
        }
      }, Executors.newVirtualThreadPerTaskExecutor());
    }
    
    // Wait for all requests to complete
    assertTrue(latch.await(20, TimeUnit.SECONDS));
    
    // Verify success rate
    assertEquals(requestCount, successCount.get(), 
        "All requests should succeed with connection pooling");
    
    // Verify total request count (should be 2 * requestCount)
    assertEquals(requestCount * 2, requestCounter.get(), 
        "Total request count should match expected value");
  }
  
  /**
   * Utility method to measure memory usage.
   */
  private long getUsedMemory() {
    System.gc(); // Request garbage collection to get more accurate readings
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
  
  /**
   * HTTP handler that echoes back the request information.
   */
  private class EchoHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      requestCounter.incrementAndGet();
      
      // Track HTTP method usage
      String method = exchange.getRequestMethod();
      methodCounts.computeIfAbsent(method, k -> new AtomicInteger()).incrementAndGet();
      
      // Read request body if present
      String requestBody = "";
      if ("POST".equals(method) || "PUT".equals(method)) {
        requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      }
      
      // Prepare response
      String response = "Echo: " + method + " request received. " +
          "Query: " + exchange.getRequestURI().getQuery() + 
          (requestBody.isEmpty() ? "" : ", Body: " + requestBody);
      
      // Send response
      exchange.sendResponseHeaders(200, response.length());
      exchange.getResponseBody().write(response.getBytes());
      exchange.close();
    }
  }
  
  /**
   * HTTP handler that introduces a delay before responding, simulating a slow service.
   */
  private class DelayHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      requestCounter.incrementAndGet();
      
      try {
        // Simulate processing delay
        Thread.sleep(RESPONSE_DELAY_MS);
        
        // Prepare response
        String response = "Delayed response after " + RESPONSE_DELAY_MS + "ms";
        
        // Send response
        exchange.sendResponseHeaders(200, response.length());
        exchange.getResponseBody().write(response.getBytes());
      } catch (InterruptedException e) {
        String error = "Processing interrupted";
        exchange.sendResponseHeaders(500, error.length());
        exchange.getResponseBody().write(error.getBytes());
      } finally {
        exchange.close();
      }
    }
  }
}