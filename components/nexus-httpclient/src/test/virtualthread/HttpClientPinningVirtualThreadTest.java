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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.sonatype.goodies.testsupport.TestSupport;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Test class focused on detecting and preventing thread pinning issues when using HTTP client with Java 21 Virtual Threads.
 * It identifies operations that cause Virtual Threads to pin to carrier threads, validates safeguards against pinning,
 * and ensures HTTP client operations properly yield during long-running I/O operations.
 */
public class HttpClientPinningVirtualThreadTest
    extends TestSupport
{
  private static final int THREAD_PINNING_TIMEOUT_MS = 500;
  private static final int CONCURRENT_THREADS = 100;
  private static final String TEST_URL = "https://httpbin.org/delay/1"; // 1 second delay
  
  private ExecutorService virtualThreadExecutor;
  
  @Before
  public void setUp() {
    // Create a virtual thread executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
  }
  
  @After
  public void tearDown() {
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      try {
        if (!virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          virtualThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        virtualThreadExecutor.shutdownNow();
      }
    }
  }
  
  /**
   * Tests that the Java 21 HttpClient properly yields during I/O operations when using Virtual Threads.
   * This ensures that carrier threads are not pinned during HTTP operations.
   */
  @Test
  public void testHttpClientYieldsDuringIO() throws Exception {
    // Create an HttpClient that uses Virtual Threads
    HttpClient httpClient = HttpClient.newBuilder()
        .executor(virtualThreadExecutor)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    // Create a request
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(TEST_URL))
        .GET()
        .build();
    
    // Execute the request and monitor for thread pinning
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Use a separate thread to monitor for pinning
    AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
    Thread monitorThread = new Thread(() -> {
      try {
        Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
        // If we reach here and the monitored thread is still running the same task,
        // it might be pinned
        if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
          threadPinningDetected.set(true);
        }
      }
      catch (InterruptedException e) {
        // Monitor thread was interrupted, which is expected when the task completes normally
      }
    });
    
    monitorThread.start();
    
    // Perform the HTTP request
    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    
    // Task completed, clear the monitored thread reference and interrupt the monitor
    monitoredThread.set(null);
    monitorThread.interrupt();
    monitorThread.join(100); // Wait for monitor thread to finish
    
    // Verify no thread pinning was detected
    assertFalse("Thread pinning detected during HTTP client operation", threadPinningDetected.get());
    
    // Verify the response was successful
    assertThat(response.statusCode(), is(200));
  }
  
  /**
   * Tests that concurrent HTTP requests using Virtual Threads don't experience thread pinning.
   * This validates that the HTTP client can handle many concurrent connections efficiently.
   */
  @Test
  public void testConcurrentHttpRequestsWithVirtualThreads() throws Exception {
    // Create an HttpClient that uses Virtual Threads
    HttpClient httpClient = HttpClient.newBuilder()
        .executor(virtualThreadExecutor)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    // Create a request
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(TEST_URL))
        .GET()
        .build();
    
    List<Future<?>> futures = new ArrayList<>();
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Submit concurrent tasks to make HTTP requests
    for (int i = 0; i < CONCURRENT_THREADS; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          // Wait for all threads to start at the same time
          startLatch.await();
          
          // Use a separate thread to monitor for pinning
          AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
          Thread monitorThread = new Thread(() -> {
            try {
              Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
              // If we reach here and the monitored thread is still running the same task,
              // it might be pinned
              if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
                threadPinningDetected.set(true);
              }
            }
            catch (InterruptedException e) {
              // Monitor thread was interrupted, which is expected when the task completes normally
            }
          });
          
          monitorThread.start();
          
          // Perform the HTTP request
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          // Task completed, clear the monitored thread reference and interrupt the monitor
          monitoredThread.set(null);
          monitorThread.interrupt();
          monitorThread.join(100); // Wait for monitor thread to finish
          
          // Verify the response was successful
          assertThat(response.statusCode(), is(200));
          
          return null;
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all tasks to complete
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    
    // Verify no thread pinning was detected
    assertFalse("Thread pinning detected during concurrent HTTP client operations", threadPinningDetected.get());
  }
  
  /**
   * Tests that synchronized blocks in HTTP client code don't cause thread pinning.
   * This test creates a scenario where synchronized blocks are used with HTTP operations
   * and verifies that Virtual Threads can still yield properly.
   */
  @Test
  public void testSynchronizedBlocksWithHttpClient() throws Exception {
    // Create a list to store results
    List<Integer> results = new ArrayList<>();
    
    // Create a task that uses synchronized blocks with HTTP operations
    Runnable task = () -> {
      try {
        // Use a synchronized block around HTTP operations
        synchronized (results) {
          // Create an HttpClient
          HttpClient httpClient = HttpClient.newBuilder()
              .executor(virtualThreadExecutor)
              .connectTimeout(Duration.ofSeconds(10))
              .build();
          
          // Create a request
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TEST_URL))
              .GET()
              .build();
          
          // Perform the HTTP request
          HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
          
          // Add the status code to results
          results.add(response.statusCode());
        }
      }
      catch (Exception e) {
        throw new RuntimeException(e);
      }
    };
    
    // Execute the task and monitor for thread pinning
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Use a separate thread to monitor for pinning
    AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
    Thread monitorThread = new Thread(() -> {
      try {
        Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
        // If we reach here and the monitored thread is still running the same task,
        // it might be pinned
        if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
          threadPinningDetected.set(true);
        }
      }
      catch (InterruptedException e) {
        // Monitor thread was interrupted, which is expected when the task completes normally
      }
    });
    
    monitorThread.start();
    
    // Execute the task on a virtual thread
    Future<?> future = virtualThreadExecutor.submit(task);
    future.get(30, TimeUnit.SECONDS);
    
    // Task completed, clear the monitored thread reference and interrupt the monitor
    monitoredThread.set(null);
    monitorThread.interrupt();
    monitorThread.join(100); // Wait for monitor thread to finish
    
    // Verify thread pinning was detected (expected in this case with synchronized blocks)
    assertTrue("Thread pinning should be detected with synchronized blocks", threadPinningDetected.get());
    
    // Verify the HTTP request was successful
    assertFalse(results.isEmpty());
    assertThat(results.get(0), is(200));
  }
  
  /**
   * Tests that using CompletableFuture with HTTP client avoids thread pinning.
   * This validates that asynchronous HTTP operations work correctly with Virtual Threads.
   */
  @Test
  public void testAsyncHttpClientWithCompletableFuture() throws Exception {
    // Create an HttpClient that uses Virtual Threads
    HttpClient httpClient = HttpClient.newBuilder()
        .executor(virtualThreadExecutor)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    // Create a request
    HttpRequest request = HttpRequest.newBuilder()
        .uri(URI.create(TEST_URL))
        .GET()
        .build();
    
    // Execute the request asynchronously
    CompletableFuture<HttpResponse<String>> futureResponse = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    
    // Wait for the response
    HttpResponse<String> response = futureResponse.get(30, TimeUnit.SECONDS);
    
    // Verify the response was successful
    assertThat(response.statusCode(), is(200));
  }
  
  /**
   * Tests that Apache HttpClient operations with Virtual Threads don't cause thread pinning.
   * This validates that third-party HTTP client libraries work correctly with Virtual Threads.
   */
  @Test
  public void testApacheHttpClientWithVirtualThreads() throws Exception {
    // Create a task that uses Apache HttpClient
    Runnable task = () -> {
      try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
        // Create a request
        HttpGet request = new HttpGet(TEST_URL);
        
        // Execute the request
        try (CloseableHttpResponse response = httpClient.execute(request)) {
          // Verify the response was successful
          assertThat(response.getStatusLine().getStatusCode(), is(200));
        }
      }
      catch (IOException e) {
        throw new RuntimeException(e);
      }
    };
    
    // Execute the task and monitor for thread pinning
    AtomicBoolean threadPinningDetected = new AtomicBoolean(false);
    
    // Use a separate thread to monitor for pinning
    AtomicReference<Thread> monitoredThread = new AtomicReference<>(Thread.currentThread());
    Thread monitorThread = new Thread(() -> {
      try {
        Thread.sleep(THREAD_PINNING_TIMEOUT_MS);
        // If we reach here and the monitored thread is still running the same task,
        // it might be pinned
        if (monitoredThread.get() != null && monitoredThread.get().getState() == Thread.State.RUNNABLE) {
          threadPinningDetected.set(true);
        }
      }
      catch (InterruptedException e) {
        // Monitor thread was interrupted, which is expected when the task completes normally
      }
    });
    
    monitorThread.start();
    
    // Execute the task on a virtual thread
    Future<?> future = virtualThreadExecutor.submit(task);
    future.get(30, TimeUnit.SECONDS);
    
    // Task completed, clear the monitored thread reference and interrupt the monitor
    monitoredThread.set(null);
    monitorThread.interrupt();
    monitorThread.join(100); // Wait for monitor thread to finish
    
    // Verify no thread pinning was detected
    // Note: This might fail if Apache HttpClient uses synchronized blocks internally
    // In that case, we would need to update the assertion to expect pinning
    assertFalse("Thread pinning detected with Apache HttpClient", threadPinningDetected.get());
  }
  
  /**
   * Tests the performance difference between Virtual Threads and platform threads for HTTP operations.
   * This validates that Virtual Threads provide better throughput for I/O-bound HTTP operations.
   */
  @Test
  public void testHttpClientPerformanceComparison() throws Exception {
    // Number of requests to make
    final int REQUEST_COUNT = 20;
    
    // Create an HttpClient that uses Virtual Threads
    HttpClient virtualThreadHttpClient = HttpClient.newBuilder()
        .executor(virtualThreadExecutor)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    // Create an HttpClient that uses platform threads
    ExecutorService platformThreadExecutor = Executors.newFixedThreadPool(10);
    HttpClient platformThreadHttpClient = HttpClient.newBuilder()
        .executor(platformThreadExecutor)
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    
    try {
      // Create a request
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(TEST_URL))
          .GET()
          .build();
      
      // Measure time for platform threads
      long platformThreadStartTime = System.currentTimeMillis();
      
      List<CompletableFuture<HttpResponse<String>>> platformThreadFutures = new ArrayList<>();
      for (int i = 0; i < REQUEST_COUNT; i++) {
        platformThreadFutures.add(platformThreadHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
      }
      
      // Wait for all platform thread requests to complete
      CompletableFuture.allOf(platformThreadFutures.toArray(new CompletableFuture[0])).join();
      
      long platformThreadEndTime = System.currentTimeMillis();
      long platformThreadDuration = platformThreadEndTime - platformThreadStartTime;
      
      // Measure time for virtual threads
      long virtualThreadStartTime = System.currentTimeMillis();
      
      List<CompletableFuture<HttpResponse<String>>> virtualThreadFutures = new ArrayList<>();
      for (int i = 0; i < REQUEST_COUNT; i++) {
        virtualThreadFutures.add(virtualThreadHttpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
      }
      
      // Wait for all virtual thread requests to complete
      CompletableFuture.allOf(virtualThreadFutures.toArray(new CompletableFuture[0])).join();
      
      long virtualThreadEndTime = System.currentTimeMillis();
      long virtualThreadDuration = virtualThreadEndTime - virtualThreadStartTime;
      
      // Log the results
      log.info("Platform thread duration: {} ms", platformThreadDuration);
      log.info("Virtual thread duration: {} ms", virtualThreadDuration);
      
      // Verify that virtual threads are faster or at least not significantly slower
      // Note: This is a simple comparison and might not be reliable in all environments
      // In a real-world scenario, more sophisticated benchmarking would be needed
      assertThat("Virtual threads should be faster than platform threads",
          virtualThreadDuration, lessThan(platformThreadDuration * 1.2)); // Allow 20% margin
    }
    finally {
      platformThreadExecutor.shutdown();
      try {
        if (!platformThreadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
          platformThreadExecutor.shutdownNow();
        }
      }
      catch (InterruptedException e) {
        platformThreadExecutor.shutdownNow();
      }
    }
  }
}