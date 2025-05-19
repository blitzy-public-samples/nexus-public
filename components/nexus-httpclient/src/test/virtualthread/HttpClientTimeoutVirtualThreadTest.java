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
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for HTTP client timeout and retry behaviors when using Java 21 Virtual Threads.
 */
@ExtendWith(MockitoExtension.class)
public class HttpClientTimeoutVirtualThreadTest
{
  private static final int CONNECT_TIMEOUT_MS = 500;
  private static final int READ_TIMEOUT_MS = 1000;
  private static final int RETRY_COUNT = 3;
  private static final String TEST_URL = "http://localhost:8080/test";
  
  @Mock
  private HttpClientManager httpClientManager;
  
  @Mock
  private HttpClient httpClient;
  
  private ExecutorService virtualThreadExecutor;
  
  @BeforeEach
  void setUp() {
    // Create a virtual thread executor
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().factory();
    virtualThreadExecutor = Executors.newThreadPerTaskExecutor(virtualThreadFactory);
  }
  
  @AfterEach
  void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }
  
  /**
   * Tests that connection timeouts are handled correctly when using Virtual Threads.
   * This verifies that a connection timeout exception is properly propagated when
   * the HTTP client is executed on a Virtual Thread.
   */
  @Test
  void testConnectionTimeoutWithVirtualThread() throws Exception {
    // Simulate a connection timeout
    when(httpClient.send(any(), any())).thenThrow(new ConnectException("Connection timeout"));
    when(httpClientManager.create()).thenReturn(httpClient);
    
    // Execute HTTP request on a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(TEST_URL))
          .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
          .build();
      
      // This should throw a ConnectException
      assertThrows(ConnectException.class, () -> {
        try {
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
          throw e;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      });
    }, virtualThreadExecutor);
    
    // Wait for the virtual thread to complete
    future.get(5, TimeUnit.SECONDS);
  }
  
  /**
   * Tests that read timeouts are handled correctly when using Virtual Threads.
   * This verifies that a read timeout exception is properly propagated when
   * the HTTP client is executed on a Virtual Thread.
   */
  @Test
  void testReadTimeoutWithVirtualThread() throws Exception {
    // Simulate a read timeout
    when(httpClient.send(any(), any())).thenThrow(new SocketTimeoutException("Read timeout"));
    when(httpClientManager.create()).thenReturn(httpClient);
    
    // Execute HTTP request on a virtual thread
    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(TEST_URL))
          .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
          .build();
      
      // This should throw a SocketTimeoutException
      assertThrows(SocketTimeoutException.class, () -> {
        try {
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
          throw e;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      });
    }, virtualThreadExecutor);
    
    // Wait for the virtual thread to complete
    future.get(5, TimeUnit.SECONDS);
  }
  
  /**
   * Tests that retry policies work correctly with Virtual Threads.
   * This verifies that a request is retried the expected number of times
   * when executed on a Virtual Thread.
   */
  @Test
  void testRetryPolicyWithVirtualThread() throws Exception {
    // Track the number of retry attempts
    AtomicInteger retryCount = new AtomicInteger(0);
    
    // Simulate a connection failure that should be retried
    when(httpClient.send(any(), any())).thenAnswer(invocation -> {
      int currentRetry = retryCount.incrementAndGet();
      if (currentRetry < RETRY_COUNT) {
        throw new ConnectException("Connection failed, retry attempt: " + currentRetry);
      }
      // Succeed on the final retry
      return HttpResponse.newBuilder()
          .statusCode(200)
          .request(invocation.getArgument(0))
          .body("Success after retries")
          .build();
    });
    when(httpClientManager.create()).thenReturn(httpClient);
    
    // Execute HTTP request with retries on a virtual thread
    CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(TEST_URL))
          .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
          .build();
      
      // Implement a simple retry mechanism
      for (int attempt = 1; attempt <= RETRY_COUNT; attempt++) {
        try {
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          return response.statusCode();
        } catch (IOException e) {
          if (attempt == RETRY_COUNT) {
            throw new RuntimeException("Failed after " + RETRY_COUNT + " attempts", e);
          }
          // Exponential backoff
          try {
            Thread.sleep(50 * attempt);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new RuntimeException(e);
        }
      }
      return -1; // Should not reach here
    }, virtualThreadExecutor);
    
    // Wait for the virtual thread to complete and verify the result
    int statusCode = future.get(5, TimeUnit.SECONDS);
    assertThat(statusCode, is(200));
    assertThat(retryCount.get(), is(RETRY_COUNT));
  }
  
  /**
   * Tests that circuit breaker patterns work correctly with Virtual Threads.
   * This verifies that a circuit breaker properly opens after a threshold of failures
   * when executed on Virtual Threads.
   */
  @Test
  void testCircuitBreakerWithVirtualThread() throws Exception {
    // Simple circuit breaker implementation
    AtomicInteger failureCount = new AtomicInteger(0);
    AtomicBoolean circuitOpen = new AtomicBoolean(false);
    int failureThreshold = 5;
    
    // Simulate a service that consistently fails
    when(httpClient.send(any(), any())).thenThrow(new IOException("Service unavailable"));
    when(httpClientManager.create()).thenReturn(httpClient);
    
    // Create multiple virtual threads to simulate concurrent requests
    int concurrentRequests = 10;
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger circuitOpenDetections = new AtomicInteger(0);
    
    for (int i = 0; i < concurrentRequests; i++) {
      CompletableFuture.runAsync(() -> {
        try {
          // Check if circuit is open
          if (circuitOpen.get()) {
            circuitOpenDetections.incrementAndGet();
            return;
          }
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TEST_URL))
              .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
              .build();
          
          try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          } catch (IOException e) {
            // Increment failure count and check if circuit should open
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
              circuitOpen.set(true);
            }
            throw e;
          }
        } catch (Exception e) {
          // Expected exception
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all virtual threads to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify that the circuit breaker opened
    assertThat(circuitOpen.get(), is(true));
    assertThat(failureCount.get(), greaterThanOrEqualTo(failureThreshold));
    // Some requests should have detected the open circuit
    assertThat(circuitOpenDetections.get(), greaterThanOrEqualTo(1));
  }
  
  /**
   * Tests that connection failure handling works correctly with Virtual Thread scheduling.
   * This verifies that connection failures are properly handled when multiple Virtual Threads
   * are executing HTTP requests concurrently.
   */
  @Test
  void testConnectionFailureHandlingWithVirtualThreads() throws Exception {
    // Simulate a mix of successful and failed connections
    AtomicInteger requestCount = new AtomicInteger(0);
    when(httpClient.send(any(), any())).thenAnswer(invocation -> {
      int count = requestCount.incrementAndGet();
      // Every third request fails with a connection exception
      if (count % 3 == 0) {
        throw new ConnectException("Connection refused");
      }
      // Otherwise succeed
      return HttpResponse.newBuilder()
          .statusCode(200)
          .request(invocation.getArgument(0))
          .body("Success")
          .build();
    });
    when(httpClientManager.create()).thenReturn(httpClient);
    
    // Execute multiple concurrent requests on virtual threads
    int concurrentRequests = 9; // Should result in 3 failures
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    
    for (int i = 0; i < concurrentRequests; i++) {
      CompletableFuture.runAsync(() -> {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(TEST_URL))
            .timeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();
        
        try {
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          if (response.statusCode() == 200) {
            successCount.incrementAndGet();
          }
        } catch (ConnectException e) {
          failureCount.incrementAndGet();
        } catch (IOException | InterruptedException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
        } finally {
          latch.countDown();
        }
      }, virtualThreadExecutor);
    }
    
    // Wait for all virtual threads to complete
    latch.await(5, TimeUnit.SECONDS);
    
    // Verify the expected success and failure counts
    assertThat(successCount.get(), is(6)); // 2/3 of requests should succeed
    assertThat(failureCount.get(), is(3)); // 1/3 of requests should fail
  }
}