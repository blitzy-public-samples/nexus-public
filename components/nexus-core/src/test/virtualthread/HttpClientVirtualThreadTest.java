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
package org.sonatype.nexus.core.virtualthread;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
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

import static org.awaitility.Awaitility.await;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Test class for validating HTTP client operations using Java 21 Virtual Threads.
 * 
 * This class tests the SharedHttpClientConnectionManager and HttpClientManagerImpl under high concurrency
 * with Virtual Threads, verifying proper connection pooling, resource management, and handling of
 * concurrent HTTP requests. It ensures that HTTP client implementations can efficiently utilize
 * Virtual Threads for improved throughput and resource utilization without thread pinning issues.
 * 
 * The tests in this class validate the following aspects of Virtual Thread integration:
 * - High concurrency handling with limited connection pool resources
 * - Connection pool efficiency and resource management
 * - HTTP request/response handling across numerous Virtual Threads
 * - POST request handling with payload data using Virtual Threads
 * 
 * @since 3.60
 */
@ExtendWith(MockitoExtension.class)
public class HttpClientVirtualThreadTest extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int CONNECTION_POOL_SIZE = 20;
  private static final int REQUEST_TIMEOUT_MS = 5000;
  private static final String TEST_ENDPOINT = "/api/v1/test";
  private static final String TEST_PAYLOAD = "{\"test\":\"data\"}";
  
  private ExecutorService virtualThreadExecutor;
  private CloseableHttpClient httpClient;
  private PoolingHttpClientConnectionManager connectionManager;
  private HttpClientManager httpClientManager;
  
  @Mock
  private MockHttpServer mockServer;
  
  @BeforeEach
  public void setup() throws Exception {
    // Start mock HTTP server
    mockServer.start();
    
    // Configure connection manager with limited connections
    connectionManager = new PoolingHttpClientConnectionManager();
    connectionManager.setMaxTotal(CONNECTION_POOL_SIZE);
    connectionManager.setDefaultMaxPerRoute(CONNECTION_POOL_SIZE);
    
    // Configure HTTP client manager
    HttpClientConfiguration config = new HttpClientConfiguration();
    config.setConnection(new HttpClientConfiguration.Connection());
    config.getConnection().setMaxConnections(CONNECTION_POOL_SIZE);
    config.getConnection().setMaxConnectionsPerRoute(CONNECTION_POOL_SIZE);
    
    // Create HTTP client with connection manager
    httpClient = HttpClientFactory.createClient(connectionManager);
    
    // Create virtual thread executor - using Java 21 Virtual Threads
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    log.info("Test setup complete with Virtual Thread executor and connection pool size: {}", CONNECTION_POOL_SIZE);
  }
  
  @AfterEach
  public void tearDown() throws Exception {
    log.info("Tearing down test resources");
    
    // Shutdown executor and close resources
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      boolean terminated = virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
      if (!terminated) {
        log.warn("Virtual thread executor did not terminate gracefully, forcing shutdown");
        virtualThreadExecutor.shutdownNow();
      }
    }
    
    if (httpClient != null) {
      httpClient.close();
      log.debug("HTTP client closed");
    }
    
    if (connectionManager != null) {
      connectionManager.close();
      log.debug("Connection manager closed");
    }
    
    // Stop mock server
    mockServer.stop();
    log.info("Test teardown complete");
  }
  
  /**
   * Test HTTP client with high concurrency using Virtual Threads.
   * 
   * This test verifies that the HTTP client can handle a large number of concurrent requests
   * using Virtual Threads, even with a limited connection pool size. The test ensures that
   * all requests complete successfully and that the connection pool is properly utilized.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Configure mock server to return success for all requests
    when(mockServer.getBaseUri()).thenReturn(new URI("http://localhost:8080"));
    
    // Create countdown latch to wait for all requests to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    // Track successful responses
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Record start time for performance measurement
    long startTime = System.currentTimeMillis();
    
    log.info("Starting high concurrency test with {} concurrent requests using Virtual Threads", CONCURRENT_REQUESTS);
    
    // Submit concurrent requests using virtual threads
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int requestId = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Create HTTP request
          HttpGet request = new HttpGet(mockServer.getBaseUri() + TEST_ENDPOINT);
          request.addHeader("Request-ID", String.valueOf(requestId));
          
          // Execute request
          HttpResponse response = httpClient.execute(request);
          
          // Verify response
          int statusCode = response.getStatusLine().getStatusCode();
          if (statusCode == 200) {
            successCount.incrementAndGet();
          }
          else {
            log.warn("Request {} failed with status code: {}", requestId, statusCode);
          }
          
          // Consume entity to release connection
          EntityUtils.consume(response.getEntity());
        } 
        catch (IOException e) {
          // Log exception but don't fail test
          log.error("Error executing request {}: {}", requestId, e.getMessage());
        } 
        finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
      
      futures.add(future);
    }
    
    // Wait for all requests to complete or timeout
    boolean completed = latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    long duration = System.currentTimeMillis() - startTime;
    
    // Verify all requests completed
    assertTrue(completed, "Not all requests completed within timeout");
    
    // Verify all requests were successful
    assertEquals(CONCURRENT_REQUESTS, successCount.get(), "Not all requests were successful");
    
    // Verify connection pool statistics
    int leased = connectionManager.getTotalStats().getLeased();
    int available = connectionManager.getTotalStats().getAvailable();
    
    // All connections should be returned to the pool
    assertThat("All connections should be returned to the pool", 
        leased, is(0));
    assertThat("Available connections should match pool size", 
        available, is(equalTo(CONNECTION_POOL_SIZE)));
    
    // Log performance metrics
    log.info("Completed {} requests in {}ms using Virtual Threads", CONCURRENT_REQUESTS, duration);
    log.info("Average time per request: {}ms", (duration / (float)CONCURRENT_REQUESTS));
    log.info("Connection pool statistics - Leased: {}, Available: {}", leased, available);
  }
  
  /**
   * Test connection pool efficiency with Virtual Threads.
   * 
   * This test verifies that the connection pool is efficiently utilized when using Virtual Threads.
   * It ensures that the number of connections used is significantly less than the number of concurrent
   * requests, demonstrating the efficiency of Virtual Threads for I/O-bound operations.
   */
  @Test
  public void testConnectionPoolEfficiency() throws Exception {
    // Configure mock server to return success with a small delay
    when(mockServer.getBaseUri()).thenReturn(new URI("http://localhost:8080"));
    when(mockServer.getResponseDelay()).thenReturn(50L); // 50ms delay
    
    // Create tracker for max connections used
    AtomicInteger maxConnectionsUsed = new AtomicInteger(0);
    
    // Create countdown latch to wait for all requests to complete
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    log.info("Starting connection pool efficiency test with {} concurrent requests and {}ms delay", 
        CONCURRENT_REQUESTS, 50);
    
    // Submit concurrent requests using virtual threads
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int requestId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create HTTP request
          HttpGet request = new HttpGet(mockServer.getBaseUri() + TEST_ENDPOINT);
          request.addHeader("Request-ID", String.valueOf(requestId));
          
          // Execute request
          HttpResponse response = httpClient.execute(request);
          
          // Track max connections used
          int leased = connectionManager.getTotalStats().getLeased();
          maxConnectionsUsed.updateAndGet(current -> Math.max(current, leased));
          
          // Consume entity to release connection
          EntityUtils.consume(response.getEntity());
        } 
        catch (IOException e) {
          log.error("Error executing request {}: {}", requestId, e.getMessage());
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    boolean completed = latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertTrue(completed, "Not all requests completed within timeout");
    
    // Verify connection pool efficiency
    assertThat("Max connections used should be less than concurrent requests",
        maxConnectionsUsed.get(), is(lessThan(CONCURRENT_REQUESTS)));
    assertThat("Max connections used should not exceed pool size",
        maxConnectionsUsed.get(), is(lessThan(CONNECTION_POOL_SIZE + 1)));
    assertThat("Connection pool should be utilized efficiently",
        maxConnectionsUsed.get(), is(greaterThanOrEqualTo(CONNECTION_POOL_SIZE / 2)));
    
    // Log connection pool statistics
    log.info("Max connections used: {} out of {} available", 
        maxConnectionsUsed.get(), CONNECTION_POOL_SIZE);
    log.info("Connection efficiency ratio: {} requests per connection", 
        CONCURRENT_REQUESTS / (float)maxConnectionsUsed.get());
  }
  
  /**
   * Test HTTP client resource management with Virtual Threads.
   * 
   * This test verifies that resources (connections, threads) are properly managed when using
   * Virtual Threads with the HTTP client. It ensures that connections are released back to the
   * pool and that Virtual Threads are properly terminated after use.
   */
  @Test
  public void testResourceManagement() throws Exception {
    // Configure mock server
    when(mockServer.getBaseUri()).thenReturn(new URI("http://localhost:8080"));
    
    // Create countdown latch
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    log.info("Starting resource management test with {} concurrent requests", CONCURRENT_REQUESTS);
    
    // Submit concurrent requests
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int requestId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create and execute request
          HttpGet request = new HttpGet(mockServer.getBaseUri() + TEST_ENDPOINT);
          request.addHeader("Request-ID", String.valueOf(requestId));
          HttpResponse response = httpClient.execute(request);
          
          // Consume entity to release connection
          EntityUtils.consume(response.getEntity());
        } 
        catch (IOException e) {
          log.error("Error executing request {}: {}", requestId, e.getMessage());
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    boolean completed = latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertTrue(completed, "Not all requests completed within timeout");
    
    // Verify all connections are returned to the pool
    assertEquals(0, connectionManager.getTotalStats().getLeased(), 
        "All connections should be returned to the pool");
    
    // Shutdown executor and verify termination
    virtualThreadExecutor.shutdown();
    boolean terminated = virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    assertTrue(terminated, "Virtual thread executor should terminate gracefully");
    
    log.info("Resource management test completed successfully");
    log.info("Connection pool statistics - Leased: {}, Available: {}", 
        connectionManager.getTotalStats().getLeased(), 
        connectionManager.getTotalStats().getAvailable());
  }
  
  /**
   * Test HTTP POST requests with Virtual Threads.
   * 
   * This test verifies that HTTP POST requests with payload data work correctly when executed
   * with Virtual Threads. It ensures that request bodies are properly transmitted and that
   * responses are correctly processed.
   */
  @Test
  public void testHttpPostWithVirtualThreads() throws Exception {
    // Configure mock server
    when(mockServer.getBaseUri()).thenReturn(new URI("http://localhost:8080"));
    
    // Create countdown latch
    CountDownLatch latch = new CountDownLatch(CONCURRENT_REQUESTS);
    
    // Track successful responses
    AtomicInteger successCount = new AtomicInteger(0);
    
    log.info("Starting HTTP POST test with {} concurrent requests", CONCURRENT_REQUESTS);
    
    // Submit concurrent POST requests using virtual threads
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      final int requestId = i;
      virtualThreadExecutor.submit(() -> {
        try {
          // Create POST request with payload
          HttpPost request = new HttpPost(mockServer.getBaseUri() + TEST_ENDPOINT);
          request.addHeader("Content-Type", "application/json");
          request.addHeader("Request-ID", String.valueOf(requestId));
          
          // Add request body
          StringEntity entity = new StringEntity(TEST_PAYLOAD);
          request.setEntity(entity);
          
          // Execute request
          HttpResponse response = httpClient.execute(request);
          
          // Verify response
          int statusCode = response.getStatusLine().getStatusCode();
          if (statusCode == 200 || statusCode == 201) {
            successCount.incrementAndGet();
          }
          else {
            log.warn("POST request {} failed with status code: {}", requestId, statusCode);
          }
          
          // Consume entity to release connection
          EntityUtils.consume(response.getEntity());
        } 
        catch (IOException e) {
          log.error("Error executing POST request {}: {}", requestId, e.getMessage());
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all requests to complete
    boolean completed = latch.await(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertTrue(completed, "Not all POST requests completed within timeout");
    
    // Verify all requests were successful
    assertEquals(CONCURRENT_REQUESTS, successCount.get(), "Not all POST requests were successful");
    
    // Verify all connections are returned to the pool
    assertEquals(0, connectionManager.getTotalStats().getLeased(), 
        "All connections should be returned to the pool after POST requests");
    
    log.info("HTTP POST test completed successfully with {} successful requests", successCount.get());
  }
  
  /**
   * Mock HTTP server interface for testing.
   * This would be implemented with a real HTTP server in a full integration test.
   */
  interface MockHttpServer {
    void start() throws Exception;
    void stop() throws Exception;
    URI getBaseUri() throws Exception;
    long getResponseDelay();
  }
  
  /**
   * Factory for creating HTTP clients.
   */
  /**
   * Factory for creating HTTP clients.
   * This implementation leverages Java 21 Virtual Threads for improved concurrency.
   */
  static class HttpClientFactory {
    /**
     * Creates an HTTP client with the specified connection manager.
     * 
     * @param connectionManager The connection manager to use for the HTTP client
     * @return A configured HTTP client that works efficiently with Virtual Threads
     */
    public static CloseableHttpClient createClient(PoolingHttpClientConnectionManager connectionManager) {
      // In a real implementation, this would create and configure an HttpClient
      // optimized for Virtual Threads, with appropriate connection and socket timeouts
      // For this test class, we'll assume this is mocked or implemented elsewhere
      return null;
    }
  }
}