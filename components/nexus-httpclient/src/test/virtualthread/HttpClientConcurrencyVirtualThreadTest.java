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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;

/**
 * Tests HTTP client behavior under high concurrency scenarios using Java 21 Virtual Threads.
 * 
 * @since 3.60
 */
public class HttpClientConcurrencyVirtualThreadTest
    extends TestSupport
{
  private static final int PORT = 8765;
  private static final String BASE_URL = "http://localhost:" + PORT;
  private static final int CONCURRENT_REQUESTS = 10_000;
  private static final int SUSTAINED_CONCURRENCY_SECONDS = 5;
  
  private HttpServer server;
  private ExecutorService virtualThreadExecutor;
  private HttpClient httpClient;
  
  @Before
  public void setUp() throws Exception {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create HTTP client using virtual threads
    httpClient = HttpClient.newBuilder()
        .executor(virtualThreadExecutor)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    
    // Setup test HTTP server
    server = HttpServer.create(new InetSocketAddress(PORT), 0);
    server.createContext("/get", new GetHandler());
    server.createContext("/post", new PostHandler());
    server.createContext("/put", new PutHandler());
    server.createContext("/delay", new DelayHandler());
    server.setExecutor(virtualThreadExecutor);
    server.start();
    
    log.info("Test server started on port {}", PORT);
  }
  
  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop(0);
      log.info("Test server stopped");
    }
    
    if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Tests the HTTP client's ability to handle a massive number of concurrent GET requests using Virtual Threads.
   * This validates that the client can scale to 10,000+ concurrent connections with minimal resource overhead.
   */
  @Test
  public void testMassiveConcurrentGetRequests() throws Exception {
    log.info("Starting massive concurrent GET requests test with {} requests", CONCURRENT_REQUESTS);
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(CONCURRENT_REQUESTS);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create and start all virtual threads
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int requestId = i;
      Thread.startVirtualThread(() -> {
        try {
          // Wait for all threads to be ready before starting
          startLatch.await();
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(BASE_URL + "/get?id=" + requestId))
              .GET()
              .build();
          
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          if (response.statusCode() == 200) {
            successCount.incrementAndGet();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in request {}: {}", requestId, e.getMessage());
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all requests simultaneously
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for all requests to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    long duration = System.currentTimeMillis() - startTime;
    
    log.info("Completed {} GET requests in {} ms", successCount.get(), duration);
    log.info("Success: {}, Errors: {}", successCount.get(), errorCount.get());
    
    // Verify all requests completed successfully
    assertThat("All requests should complete within timeout", completed, is(true));
    assertThat("All requests should succeed", successCount.get(), equalTo(CONCURRENT_REQUESTS));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }
  
  /**
   * Tests concurrent HTTP requests with different methods (GET, POST, PUT) to validate
   * that the client handles mixed workloads correctly with Virtual Threads.
   */
  @Test
  public void testConcurrentMixedHttpMethods() throws Exception {
    log.info("Starting concurrent mixed HTTP methods test");
    
    final int requestsPerMethod = 1000;
    final int totalRequests = requestsPerMethod * 3; // GET, POST, PUT
    
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(totalRequests);
    ConcurrentHashMap<String, AtomicInteger> successCountByMethod = new ConcurrentHashMap<>();
    successCountByMethod.put("GET", new AtomicInteger(0));
    successCountByMethod.put("POST", new AtomicInteger(0));
    successCountByMethod.put("PUT", new AtomicInteger(0));
    AtomicInteger errorCount = new AtomicInteger(0);
    
    // Create GET requests
    for (int i = 0; i < requestsPerMethod; i++) {
      final int requestId = i;
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await();
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(BASE_URL + "/get?id=" + requestId))
              .GET()
              .build();
          
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          if (response.statusCode() == 200) {
            successCountByMethod.get("GET").incrementAndGet();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in GET request {}: {}", requestId, e.getMessage());
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Create POST requests
    for (int i = 0; i < requestsPerMethod; i++) {
      final int requestId = i;
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await();
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(BASE_URL + "/post"))
              .POST(HttpRequest.BodyPublishers.ofString("data=" + requestId))
              .build();
          
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          if (response.statusCode() == 200) {
            successCountByMethod.get("POST").incrementAndGet();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in POST request {}: {}", requestId, e.getMessage());
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Create PUT requests
    for (int i = 0; i < requestsPerMethod; i++) {
      final int requestId = i;
      Thread.startVirtualThread(() -> {
        try {
          startLatch.await();
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(BASE_URL + "/put"))
              .PUT(HttpRequest.BodyPublishers.ofString("data=" + requestId))
              .build();
          
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          if (response.statusCode() == 200) {
            successCountByMethod.get("PUT").incrementAndGet();
          } else {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          log.error("Error in PUT request {}: {}", requestId, e.getMessage());
          errorCount.incrementAndGet();
        } finally {
          completionLatch.countDown();
        }
      });
    }
    
    // Start all requests simultaneously
    long startTime = System.currentTimeMillis();
    startLatch.countDown();
    
    // Wait for all requests to complete
    boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
    long duration = System.currentTimeMillis() - startTime;
    
    log.info("Completed mixed HTTP method requests in {} ms", duration);
    log.info("GET Success: {}, POST Success: {}, PUT Success: {}, Errors: {}", 
        successCountByMethod.get("GET").get(),
        successCountByMethod.get("POST").get(),
        successCountByMethod.get("PUT").get(),
        errorCount.get());
    
    // Verify all requests completed successfully
    assertThat("All requests should complete within timeout", completed, is(true));
    assertThat("All GET requests should succeed", successCountByMethod.get("GET").get(), equalTo(requestsPerMethod));
    assertThat("All POST requests should succeed", successCountByMethod.get("POST").get(), equalTo(requestsPerMethod));
    assertThat("All PUT requests should succeed", successCountByMethod.get("PUT").get(), equalTo(requestsPerMethod));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
  }
  
  /**
   * Tests connection stability under sustained high concurrency by maintaining a constant
   * number of active connections over a period of time. This validates that the HTTP client
   * can handle long-running concurrent workloads with Virtual Threads.
   */
  @Test
  public void testSustainedHighConcurrency() throws Exception {
    log.info("Starting sustained high concurrency test for {} seconds", SUSTAINED_CONCURRENCY_SECONDS);
    
    final int concurrentConnections = 1000;
    final AtomicInteger activeConnections = new AtomicInteger(0);
    final AtomicInteger completedRequests = new AtomicInteger(0);
    final AtomicInteger errorCount = new AtomicInteger(0);
    final AtomicInteger maxConcurrent = new AtomicInteger(0);
    
    // Flag to control the test duration
    final boolean[] running = {true};
    
    // Start the connection manager thread
    Thread connectionManager = Thread.startVirtualThread(() -> {
      try {
        while (running[0]) {
          // Ensure we maintain the target number of concurrent connections
          int current = activeConnections.get();
          int needed = concurrentConnections - current;
          
          if (needed > 0) {
            // Start new connections to reach the target
            for (int i = 0; i < needed; i++) {
              startDelayedRequest(activeConnections, completedRequests, errorCount, maxConcurrent);
            }
          }
          
          // Brief pause before checking again
          Thread.sleep(50);
        }
      } catch (Exception e) {
        log.error("Error in connection manager: {}", e.getMessage());
      }
    });
    
    // Run the test for the specified duration
    Thread.sleep(TimeUnit.SECONDS.toMillis(SUSTAINED_CONCURRENCY_SECONDS));
    running[0] = false;
    
    // Allow time for remaining connections to complete
    Thread.sleep(2000);
    
    log.info("Sustained concurrency test completed");
    log.info("Completed requests: {}, Errors: {}, Max concurrent: {}", 
        completedRequests.get(), errorCount.get(), maxConcurrent.get());
    
    // Verify the test results
    assertThat("Should maintain target concurrent connections", maxConcurrent.get(), 
        greaterThanOrEqualTo(concurrentConnections));
    assertThat("Should complete a significant number of requests", completedRequests.get(), 
        greaterThanOrEqualTo(concurrentConnections));
    assertThat("Error rate should be very low", errorCount.get(), 
        lessThan(completedRequests.get() / 100)); // Less than 1% error rate
  }
  
  /**
   * Tests connection pool behavior with Virtual Threads by creating and releasing connections
   * in a pattern that would stress traditional connection pools. This validates that the
   * HTTP client efficiently manages connections when using Virtual Threads.
   */
  @Test
  public void testConnectionPoolBehavior() throws Exception {
    log.info("Starting connection pool behavior test");
    
    final int batchSize = 500;
    final int batchCount = 20;
    final AtomicInteger successCount = new AtomicInteger(0);
    final AtomicInteger errorCount = new AtomicInteger(0);
    final List<Long> batchTimes = new ArrayList<>();
    
    for (int batch = 0; batch < batchCount; batch++) {
      CountDownLatch batchLatch = new CountDownLatch(batchSize);
      long startTime = System.currentTimeMillis();
      
      // Create a batch of requests
      for (int i = 0; i < batchSize; i++) {
        final int requestId = (batch * batchSize) + i;
        Thread.startVirtualThread(() -> {
          try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/get?id=" + requestId))
                .GET()
                .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() == 200) {
              successCount.incrementAndGet();
            } else {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in request {}: {}", requestId, e.getMessage());
            errorCount.incrementAndGet();
          } finally {
            batchLatch.countDown();
          }
        });
      }
      
      // Wait for the batch to complete
      batchLatch.await(10, TimeUnit.SECONDS);
      long batchTime = System.currentTimeMillis() - startTime;
      batchTimes.add(batchTime);
      
      log.info("Batch {} completed in {} ms", batch + 1, batchTime);
      
      // Small delay between batches to simulate real-world usage patterns
      Thread.sleep(100);
    }
    
    log.info("Connection pool test completed");
    log.info("Success: {}, Errors: {}", successCount.get(), errorCount.get());
    
    // Calculate average batch time, excluding the first batch (warm-up)
    double avgBatchTime = batchTimes.stream()
        .skip(1) // Skip the first batch (warm-up)
        .mapToLong(Long::longValue)
        .average()
        .orElse(0.0);
    
    log.info("Average batch time (excluding first): {} ms", avgBatchTime);
    
    // Verify the test results
    int totalRequests = batchSize * batchCount;
    assertThat("All requests should succeed", successCount.get(), equalTo(totalRequests));
    assertThat("No errors should occur", errorCount.get(), equalTo(0));
    
    // Verify that later batches don't slow down significantly (connection pool working efficiently)
    long firstBatchTime = batchTimes.get(0);
    for (int i = 1; i < batchTimes.size(); i++) {
      // Each batch should not be significantly slower than the first (allowing for some variance)
      assertThat("Batch " + (i + 1) + " should not be significantly slower than first batch",
          batchTimes.get(i), lessThan(firstBatchTime * 2));
    }
  }
  
  /**
   * Helper method to start a delayed request that simulates a long-running connection.
   */
  private void startDelayedRequest(
      AtomicInteger activeConnections,
      AtomicInteger completedRequests,
      AtomicInteger errorCount,
      AtomicInteger maxConcurrent)
  {
    activeConnections.incrementAndGet();
    updateMaxConcurrent(activeConnections.get(), maxConcurrent);
    
    Thread.startVirtualThread(() -> {
      try {
        // Random delay between 500ms and 2000ms
        int delay = 500 + (int)(Math.random() * 1500);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(BASE_URL + "/delay?ms=" + delay))
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() == 200) {
          completedRequests.incrementAndGet();
        } else {
          errorCount.incrementAndGet();
        }
      } catch (Exception e) {
        log.error("Error in delayed request: {}", e.getMessage());
        errorCount.incrementAndGet();
      } finally {
        activeConnections.decrementAndGet();
      }
    });
  }
  
  /**
   * Thread-safe method to update the maximum concurrent connections counter.
   */
  private void updateMaxConcurrent(int current, AtomicInteger maxConcurrent) {
    int max;
    do {
      max = maxConcurrent.get();
      if (current <= max) {
        break;
      }
    } while (!maxConcurrent.compareAndSet(max, current));
  }
  
  /**
   * Handler for GET requests.
   */
  private static class GetHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"GET".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, 0);
        exchange.close();
        return;
      }
      
      String response = "OK";
      exchange.sendResponseHeaders(200, response.length());
      exchange.getResponseBody().write(response.getBytes(UTF_8));
      exchange.close();
    }
  }
  
  /**
   * Handler for POST requests.
   */
  private static class PostHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"POST".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, 0);
        exchange.close();
        return;
      }
      
      String response = "OK";
      exchange.sendResponseHeaders(200, response.length());
      exchange.getResponseBody().write(response.getBytes(UTF_8));
      exchange.close();
    }
  }
  
  /**
   * Handler for PUT requests.
   */
  private static class PutHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      if (!"PUT".equals(exchange.getRequestMethod())) {
        exchange.sendResponseHeaders(405, 0);
        exchange.close();
        return;
      }
      
      String response = "OK";
      exchange.sendResponseHeaders(200, response.length());
      exchange.getResponseBody().write(response.getBytes(UTF_8));
      exchange.close();
    }
  }
  
  /**
   * Handler for delayed responses to simulate long-running connections.
   */
  private static class DelayHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      try {
        // Extract delay parameter
        String query = exchange.getRequestURI().getQuery();
        int delay = 1000; // Default delay
        
        if (query != null && query.startsWith("ms=")) {
          try {
            delay = Integer.parseInt(query.substring(3));
          } catch (NumberFormatException e) {
            // Use default delay
          }
        }
        
        // Simulate processing delay
        Thread.sleep(delay);
        
        String response = "Delayed response after " + delay + "ms";
        exchange.sendResponseHeaders(200, response.length());
        exchange.getResponseBody().write(response.getBytes(UTF_8));
      } catch (InterruptedException e) {
        String error = "Processing interrupted";
        exchange.sendResponseHeaders(500, error.length());
        exchange.getResponseBody().write(error.getBytes(UTF_8));
      } finally {
        exchange.close();
      }
    }
  }
}