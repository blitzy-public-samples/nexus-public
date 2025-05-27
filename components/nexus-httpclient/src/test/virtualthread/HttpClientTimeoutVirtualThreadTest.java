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

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.httpclient.HttpClientManager;
import org.sonatype.nexus.httpclient.HttpClientPlan;
import org.sonatype.nexus.httpclient.config.HttpClientConfiguration;

import static java.net.URI.create;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for HTTP client timeout and retry behaviors when using Java 21 Virtual Threads.
 * 
 * This test class validates that timeout mechanisms, retry policies, and circuit breaker patterns
 * function correctly when HTTP operations are executed on Virtual Threads rather than platform threads.
 */
public class HttpClientTimeoutVirtualThreadTest
    extends TestSupport
{
  private static final int UNUSED_PORT = 39876;
  private static final int CONNECT_TIMEOUT_MS = 500;
  private static final int READ_TIMEOUT_MS = 1000;
  private static final int MAX_RETRIES = 3;
  
  private ServerSocket serverSocket;
  private HttpClientManager httpClientManager;
  private HttpClientConfiguration httpClientConfig;
  
  @Before
  public void setUp() throws Exception {
    // Mock the HTTP client manager and configuration
    httpClientManager = mock(HttpClientManager.class);
    httpClientConfig = mock(HttpClientConfiguration.class);
    
    when(httpClientManager.newConfiguration()).thenReturn(httpClientConfig);
    when(httpClientManager.getConfiguration()).thenReturn(httpClientConfig);
  }
  
  @After
  public void tearDown() throws Exception {
    if (serverSocket != null && !serverSocket.isClosed()) {
      serverSocket.close();
    }
  }
  
  /**
   * Tests that connection timeout works correctly with Virtual Threads.
   * 
   * This test attempts to connect to a non-existent server and verifies that
   * the connection times out as expected when running on a Virtual Thread.
   */
  @Test
  public void testConnectionTimeoutWithVirtualThread() throws Exception {
    // Create an HTTP client with a short connection timeout
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    
    // Create a request to a non-existent server
    HttpRequest request = HttpRequest.newBuilder()
        .uri(create("http://localhost:" + UNUSED_PORT))
        .GET()
        .build();
    
    // Execute the request on a Virtual Thread and expect a timeout exception
    CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
        request, HttpResponse.BodyHandlers.ofString());
    
    // Verify that the request times out with the expected exception
    ExecutionException exception = assertThrows(ExecutionException.class, 
        () -> future.get(CONNECT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS));
    
    // The cause should be a connection timeout exception
    assertThat(exception.getCause(), instanceOf(HttpConnectTimeoutException.class));
  }
  
  /**
   * Tests that read timeout works correctly with Virtual Threads.
   * 
   * This test connects to a server that accepts connections but doesn't respond,
   * and verifies that the read timeout works as expected when running on a Virtual Thread.
   */
  @Test
  public void testReadTimeoutWithVirtualThread() throws Exception {
    // Create a server socket that accepts connections but doesn't respond
    serverSocket = new ServerSocket();
    serverSocket.bind(new InetSocketAddress("localhost", 0));
    int port = serverSocket.getLocalPort();
    
    // Start a thread that accepts connections but doesn't send any data
    Thread serverThread = Thread.ofVirtual().start(() -> {
      try {
        Socket clientSocket = serverSocket.accept();
        // Don't send any data, just keep the connection open
        Thread.sleep(READ_TIMEOUT_MS * 2);
        clientSocket.close();
      }
      catch (Exception e) {
        // Ignore exceptions during shutdown
      }
    });
    
    // Create an HTTP client with a read timeout
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    
    // Create a request to our non-responsive server
    HttpRequest request = HttpRequest.newBuilder()
        .uri(create("http://localhost:" + port))
        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
        .GET()
        .build();
    
    // Execute the request on a Virtual Thread and expect a timeout exception
    CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
        request, HttpResponse.BodyHandlers.ofString());
    
    // Verify that the request times out with the expected exception
    ExecutionException exception = assertThrows(ExecutionException.class, 
        () -> future.get(READ_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS));
    
    // The cause should be a timeout exception
    assertThat(exception.getCause(), instanceOf(HttpTimeoutException.class));
  }
  
  /**
   * Tests that retry behavior works correctly with Virtual Threads.
   * 
   * This test simulates a server that fails initially but succeeds after a few retries,
   * and verifies that the retry mechanism works as expected when running on Virtual Threads.
   */
  @Test
  public void testRetryBehaviorWithVirtualThread() throws Exception {
    // Create a counter to track the number of connection attempts
    AtomicInteger connectionAttempts = new AtomicInteger(0);
    
    // Create a server socket that initially rejects connections but eventually accepts
    serverSocket = new ServerSocket();
    serverSocket.bind(new InetSocketAddress("localhost", 0));
    int port = serverSocket.getLocalPort();
    
    // Start a thread that simulates a flaky server
    Thread serverThread = Thread.ofVirtual().start(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          Socket clientSocket = serverSocket.accept();
          int attempt = connectionAttempts.incrementAndGet();
          
          // Fail the first MAX_RETRIES attempts by closing the connection immediately
          if (attempt <= MAX_RETRIES) {
            clientSocket.close();
          }
          else {
            // Succeed on the attempt after MAX_RETRIES
            clientSocket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".getBytes());
            clientSocket.close();
          }
        }
      }
      catch (Exception e) {
        // Ignore exceptions during shutdown
      }
    });
    
    // Create a custom retry handler using Virtual Threads
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a function that retries the request with exponential backoff
    CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
      for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        try {
          // Create a new client for each attempt
          HttpClient httpClient = HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
              .executor(executor)
              .build();
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(create("http://localhost:" + port))
              .GET()
              .build();
          
          // Try to get a response
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          // If we get here, the request succeeded
          return response.body();
        }
        catch (IOException e) {
          // If this is the last attempt, give up
          if (attempt == MAX_RETRIES) {
            throw new CompletionException("Failed after " + (attempt + 1) + " attempts", e);
          }
          
          // Otherwise, wait with exponential backoff before retrying
          try {
            Thread.sleep((long) Math.pow(2, attempt) * 50);
          }
          catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ie);
          }
        }
      }
      
      // This should never be reached
      throw new CompletionException(new IllegalStateException("Unexpected code path"));
    }, executor);
    
    // Wait for the result and verify it succeeded after retries
    String response = result.get(5, TimeUnit.SECONDS);
    assertThat(response, is("OK"));
    
    // Verify that we had to retry the expected number of times
    assertThat(connectionAttempts.get(), is(MAX_RETRIES + 1));
  }
  
  /**
   * Tests that circuit breaker pattern works correctly with Virtual Threads.
   * 
   * This test simulates a server that consistently fails and verifies that the
   * circuit breaker opens after the configured number of failures when running on Virtual Threads.
   */
  @Test
  public void testCircuitBreakerWithVirtualThread() throws Exception {
    // Create a counter to track the number of connection attempts
    AtomicInteger connectionAttempts = new AtomicInteger(0);
    
    // Create a server socket that always rejects connections
    serverSocket = new ServerSocket();
    serverSocket.bind(new InetSocketAddress("localhost", 0));
    int port = serverSocket.getLocalPort();
    
    // Start a thread that simulates a failing server
    Thread serverThread = Thread.ofVirtual().start(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          Socket clientSocket = serverSocket.accept();
          connectionAttempts.incrementAndGet();
          
          // Always fail by closing the connection immediately
          clientSocket.close();
        }
      }
      catch (Exception e) {
        // Ignore exceptions during shutdown
      }
    });
    
    // Create a simple circuit breaker implementation
    class CircuitBreaker {
      private final int failureThreshold;
      private final Duration resetTimeout;
      private AtomicInteger failureCount = new AtomicInteger(0);
      private volatile long lastFailureTime = 0;
      private volatile boolean open = false;
      
      CircuitBreaker(int failureThreshold, Duration resetTimeout) {
        this.failureThreshold = failureThreshold;
        this.resetTimeout = resetTimeout;
      }
      
      boolean isOpen() {
        // Check if the circuit is open and if the reset timeout has elapsed
        if (open && System.currentTimeMillis() - lastFailureTime > resetTimeout.toMillis()) {
          // Allow a single request through to test if the service is healthy
          return false;
        }
        return open;
      }
      
      void recordSuccess() {
        failureCount.set(0);
        open = false;
      }
      
      void recordFailure() {
        lastFailureTime = System.currentTimeMillis();
        if (failureCount.incrementAndGet() >= failureThreshold) {
          open = true;
        }
      }
    }
    
    // Create a circuit breaker with a threshold of MAX_RETRIES failures
    CircuitBreaker circuitBreaker = new CircuitBreaker(MAX_RETRIES, Duration.ofSeconds(1));
    
    // Create a virtual thread executor
    var executor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Create a function that uses the circuit breaker
    CompletableFuture<Void> result = CompletableFuture.runAsync(() -> {
      for (int i = 0; i < MAX_RETRIES + 2; i++) {
        // Check if the circuit is open
        if (circuitBreaker.isOpen()) {
          log.info("Circuit is open, skipping request");
          continue;
        }
        
        try {
          // Create a new client for each attempt
          HttpClient httpClient = HttpClient.newBuilder()
              .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
              .executor(executor)
              .build();
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(create("http://localhost:" + port))
              .GET()
              .build();
          
          // Try to get a response
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          // If we get here, the request succeeded
          circuitBreaker.recordSuccess();
        }
        catch (IOException e) {
          // Record the failure
          circuitBreaker.recordFailure();
        }
      }
    }, executor);
    
    // Wait for the result
    result.get(5, TimeUnit.SECONDS);
    
    // Verify that the circuit breaker opened after MAX_RETRIES failures
    assertThat(circuitBreaker.isOpen(), is(true));
    
    // Verify that we attempted the expected number of connections
    // We should have MAX_RETRIES attempts before the circuit opens
    assertThat(connectionAttempts.get(), is(MAX_RETRIES));
  }
  
  /**
   * Tests that multiple concurrent requests with timeouts work correctly with Virtual Threads.
   * 
   * This test sends multiple concurrent requests that will time out and verifies that
   * all requests are handled correctly when running on Virtual Threads.
   */
  @Test
  public void testConcurrentTimeoutsWithVirtualThreads() throws Exception {
    // Number of concurrent requests to make
    final int concurrentRequests = 10;
    
    // Create a server socket that accepts connections but doesn't respond
    serverSocket = new ServerSocket();
    serverSocket.bind(new InetSocketAddress("localhost", 0));
    int port = serverSocket.getLocalPort();
    
    // Start a thread that accepts connections but doesn't send any data
    Thread serverThread = Thread.ofVirtual().start(() -> {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          Socket clientSocket = serverSocket.accept();
          // Don't send any data, just keep the connection open
          Thread.sleep(READ_TIMEOUT_MS * 2);
          clientSocket.close();
        }
      }
      catch (Exception e) {
        // Ignore exceptions during shutdown
      }
    });
    
    // Create an HTTP client with a read timeout
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    
    // Create a request to our non-responsive server
    HttpRequest request = HttpRequest.newBuilder()
        .uri(create("http://localhost:" + port))
        .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
        .GET()
        .build();
    
    // Create a latch to wait for all requests to complete
    CountDownLatch latch = new CountDownLatch(concurrentRequests);
    
    // Counter for timeout exceptions
    AtomicInteger timeoutCount = new AtomicInteger(0);
    
    // Send multiple concurrent requests
    for (int i = 0; i < concurrentRequests; i++) {
      httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
          .whenComplete((response, throwable) -> {
            if (throwable != null && (throwable instanceof HttpTimeoutException ||
                throwable.getCause() instanceof HttpTimeoutException)) {
              timeoutCount.incrementAndGet();
            }
            latch.countDown();
          });
    }
    
    // Wait for all requests to complete
    boolean allCompleted = latch.await(READ_TIMEOUT_MS * 3, TimeUnit.MILLISECONDS);
    assertThat("All requests should complete", allCompleted, is(true));
    
    // Verify that all requests timed out
    assertThat(timeoutCount.get(), is(concurrentRequests));
  }
  
  /**
   * Tests that connection failure handling works correctly with Virtual Thread scheduling.
   * 
   * This test simulates different types of connection failures and verifies that
   * they are handled correctly when running on Virtual Threads.
   */
  @Test
  public void testConnectionFailureHandlingWithVirtualThreads() throws Exception {
    // Create an HTTP client with a short connection timeout
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
        .executor(Executors.newVirtualThreadPerTaskExecutor())
        .build();
    
    // Test cases for different types of connection failures
    record FailureTestCase(String description, String uri, Class<? extends Throwable> expectedExceptionType) {}
    
    FailureTestCase[] testCases = {
        new FailureTestCase(
            "Connection refused",
            "http://localhost:" + UNUSED_PORT,
            ConnectException.class
        ),
        new FailureTestCase(
            "Unknown host",
            "http://non-existent-host-12345.local",
            IOException.class
        ),
        new FailureTestCase(
            "Invalid URI",
            "http://[invalid-ipv6-address",
            IllegalArgumentException.class
        )
    };
    
    // Run each test case
    for (FailureTestCase testCase : testCases) {
      log.info("Testing: {}", testCase.description);
      
      try {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(create(testCase.uri))
            .GET()
            .build();
        
        // Execute the request on a Virtual Thread and expect an exception
        CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
            request, HttpResponse.BodyHandlers.ofString());
        
        // Wait for the result, which should throw an exception
        future.get(CONNECT_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
        
        // If we get here, the request didn't fail as expected
        fail("Expected exception for test case: " + testCase.description);
      }
      catch (ExecutionException e) {
        // Verify that the exception is of the expected type
        Throwable cause = e.getCause();
        assertThat(
            "Expected exception type for test case: " + testCase.description,
            testCase.expectedExceptionType.isAssignableFrom(cause.getClass()),
            is(true)
        );
      }
    }
  }
}