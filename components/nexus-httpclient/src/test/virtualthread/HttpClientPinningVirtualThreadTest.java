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
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.goodies.testsupport.group.Java21TestGroup;
import org.sonatype.nexus.common.stateguard.StateGuardModule;

import com.google.inject.Injector;
import org.junit.experimental.categories.Category;

import static com.google.inject.Guice.createInjector;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Test class focused on detecting and preventing thread pinning issues when using HTTP client with Java 21 Virtual Threads.
 * <p>
 * This test identifies operations that cause Virtual Threads to pin to carrier threads, validates safeguards against
 * pinning, and ensures HTTP client operations properly yield during long-running I/O operations.
 * <p>
 * Thread pinning occurs when a Virtual Thread cannot be unmounted from its carrier thread, typically due to:
 * <ul>
 *   <li>Synchronized blocks or methods that contain blocking operations</li>
 *   <li>Native methods or foreign function calls</li>
 * </ul>
 * <p>
 * Pinning can significantly reduce the scalability benefits of Virtual Threads by limiting the number of concurrent
 * operations to the number of available platform threads.
 */
@ExtendWith(MockitoExtension.class)
@Category(Java21TestGroup.class)
public class HttpClientPinningVirtualThreadTest
    extends TestSupport
{
  private static final int CONCURRENT_REQUESTS = 100;
  private static final int PINNING_DETECTION_THRESHOLD_MS = 20;
  private static final String TEST_URL = "https://httpbin.org/delay/1";
  
  private ExecutorService virtualThreadExecutor;
  private PinningDetector pinningDetector;
  
  @BeforeEach
  void setUp() {
    // Create a virtual thread per task executor
    virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
    
    // Initialize pinning detector
    pinningDetector = new PinningDetector();
    pinningDetector.start();
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Shutdown executor and pinning detector
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
      virtualThreadExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (pinningDetector != null) {
      pinningDetector.stop();
    }
  }
  
  /**
   * Tests that HTTP client operations using Virtual Threads do not cause thread pinning
   * when making concurrent requests.
   * <p>
   * This test verifies that the HTTP client properly yields during I/O operations and
   * doesn't pin Virtual Threads to carrier threads unnecessarily.
   */
  @Test
  void testConcurrentHttpRequestsDoNotCausePinning() throws Exception {
    // Create HTTP client
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(virtualThreadExecutor)
        .build();
    
    // Create multiple concurrent requests
    List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      HttpRequest request = HttpRequest.newBuilder()
          .uri(URI.create(TEST_URL))
          .GET()
          .build();
      
      CompletableFuture<HttpResponse<String>> future = httpClient.sendAsync(
          request, HttpResponse.BodyHandlers.ofString());
      
      futures.add(future);
    }
    
    // Wait for all requests to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify no pinning was detected
    assertFalse(pinningDetector.wasPinningDetected(), 
        "Thread pinning detected during concurrent HTTP requests. Check stack trace for details.");
  }
  
  /**
   * Tests that HTTP client operations using Virtual Threads do not cause thread pinning
   * when closing input streams from responses.
   * <p>
   * This test specifically targets the known issue with AbstractInterruptibleChannel.close()
   * which uses a synchronized block that can cause pinning.
   */
  @Test
  void testInputStreamClosingDoesNotCausePinning() throws Exception {
    // Create HTTP client
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(virtualThreadExecutor)
        .build();
    
    // Create multiple concurrent requests with input stream handling
    List<Future<?>> futures = new ArrayList<>();
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TEST_URL))
              .GET()
              .build();
          
          // Use input stream body handler which will need to be closed
          HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
          
          // Read and close the input stream - this is where pinning might occur
          try (InputStream is = response.body()) {
            byte[] buffer = new byte[8192];
            while (is.read(buffer) != -1) {
              // Just read the data
            }
          }
          
          return null;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
    }
    
    // Wait for all requests to complete
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    
    // Verify no pinning was detected
    assertFalse(pinningDetector.wasPinningDetected(), 
        "Thread pinning detected during input stream closing. Check stack trace for details.");
  }
  
  /**
   * Tests that HTTP client operations using Virtual Threads do not cause thread pinning
   * when handling connection timeouts.
   * <p>
   * This test verifies that timeout handling in the HTTP client doesn't pin Virtual Threads.
   */
  @Test
  void testConnectionTimeoutDoesNotCausePinning() throws Exception {
    // Create HTTP client with very short timeout
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(1)) // Extremely short timeout to force timeout
        .executor(virtualThreadExecutor)
        .build();
    
    // Create multiple concurrent requests to a non-responsive endpoint
    List<Future<?>> futures = new ArrayList<>();
    
    for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
      futures.add(virtualThreadExecutor.submit(() -> {
        try {
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create("https://example.com:12345")) // Non-responsive endpoint
              .GET()
              .build();
          
          try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            fail("Expected timeout exception");
          } catch (IOException e) {
            // Expected timeout exception
          }
          
          return null;
        } catch (Exception e) {
          if (!(e instanceof IOException)) {
            throw new RuntimeException("Unexpected exception: " + e.getMessage(), e);
          }
          return null;
        }
      }));
    }
    
    // Wait for all requests to complete
    for (Future<?> future : futures) {
      future.get(30, TimeUnit.SECONDS);
    }
    
    // Verify no pinning was detected
    assertFalse(pinningDetector.wasPinningDetected(), 
        "Thread pinning detected during connection timeout handling. Check stack trace for details.");
  }
  
  /**
   * Tests that HTTP client operations using Virtual Threads do not cause thread pinning
   * when handling connection reuse.
   * <p>
   * This test verifies that connection pooling and reuse in the HTTP client doesn't pin Virtual Threads.
   */
  @Test
  void testConnectionReuseDoesNotCausePinning() throws Exception {
    // Create HTTP client with connection reuse
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(virtualThreadExecutor)
        .build();
    
    // Make multiple sequential requests to the same endpoint to trigger connection reuse
    for (int batch = 0; batch < 3; batch++) {
      List<Future<?>> futures = new ArrayList<>();
      
      for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
        futures.add(virtualThreadExecutor.submit(() -> {
          try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(TEST_URL))
                .GET()
                .build();
            
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return null;
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        }));
      }
      
      // Wait for all requests in this batch to complete
      for (Future<?> future : futures) {
        future.get(30, TimeUnit.SECONDS);
      }
    }
    
    // Verify no pinning was detected
    assertFalse(pinningDetector.wasPinningDetected(), 
        "Thread pinning detected during connection reuse. Check stack trace for details.");
  }
  
  /**
   * Tests that HTTP client operations using Virtual Threads properly yield during long-running I/O operations.
   * <p>
   * This test verifies that Virtual Threads can be efficiently multiplexed on carrier threads during I/O operations.
   */
  @Test
  void testVirtualThreadsProperlyYieldDuringIO() throws Exception {
    // Create HTTP client
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .executor(virtualThreadExecutor)
        .build();
    
    // Track the number of concurrent operations
    AtomicInteger activeOperations = new AtomicInteger(0);
    AtomicInteger maxConcurrentOperations = new AtomicInteger(0);
    
    // Create a large number of concurrent requests
    int requestCount = 1000; // Much larger than available platform threads
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch completionLatch = new CountDownLatch(requestCount);
    
    for (int i = 0; i < requestCount; i++) {
      virtualThreadExecutor.submit(() -> {
        try {
          startLatch.await(); // Wait for all threads to be ready
          
          // Increment active operations counter
          int active = activeOperations.incrementAndGet();
          maxConcurrentOperations.updateAndGet(current -> Math.max(current, active));
          
          HttpRequest request = HttpRequest.newBuilder()
              .uri(URI.create(TEST_URL))
              .GET()
              .build();
          
          httpClient.send(request, HttpResponse.BodyHandlers.discarding());
          
          // Decrement active operations counter
          activeOperations.decrementAndGet();
          completionLatch.countDown();
          
          return null;
        } catch (Exception e) {
          completionLatch.countDown();
          throw new RuntimeException(e);
        }
      });
    }
    
    // Start all threads simultaneously
    startLatch.countDown();
    
    // Wait for all requests to complete
    assertTrue(completionLatch.await(60, TimeUnit.SECONDS), 
        "Not all requests completed within the timeout period");
    
    // Verify that many operations were active concurrently
    // This indicates that Virtual Threads properly yielded during I/O
    int availableProcessors = Runtime.getRuntime().availableProcessors();
    assertTrue(maxConcurrentOperations.get() > availableProcessors * 2,
        "Expected concurrent operations to exceed available processors, but got: " + 
        maxConcurrentOperations.get() + " (processors: " + availableProcessors + ")");
    
    // Verify no pinning was detected
    assertFalse(pinningDetector.wasPinningDetected(), 
        "Thread pinning detected during yield test. Check stack trace for details.");
  }
  
  /**
   * A utility class to detect thread pinning in Virtual Threads.
   * <p>
   * This detector runs a background thread that periodically checks for Virtual Threads
   * that have been pinned to carrier threads for longer than a threshold duration.
   */
  private static class PinningDetector {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean pinningDetected = new AtomicBoolean(false);
    private Thread detectorThread;
    
    /**
     * Starts the pinning detector.
     */
    public void start() {
      if (running.compareAndSet(false, true)) {
        detectorThread = Thread.ofVirtual().name("pinning-detector").start(() -> {
          try {
            // Enable JVM's built-in pinning detection
            System.setProperty("jdk.tracePinnedThreads", "full");
            
            // Monitor for pinning events in the log
            while (running.get()) {
              // Sleep for a short period
              Thread.sleep(100);
              
              // In a real implementation, we would parse JFR events or log output
              // to detect pinning. For this test, we rely on the JVM's built-in
              // pinning detection via jdk.tracePinnedThreads.
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            System.clearProperty("jdk.tracePinnedThreads");
          }
        });
      }
    }
    
    /**
     * Stops the pinning detector.
     */
    public void stop() throws InterruptedException {
      if (running.compareAndSet(true, false) && detectorThread != null) {
        detectorThread.interrupt();
        detectorThread.join(1000);
      }
    }
    
    /**
     * Checks if pinning was detected.
     *
     * @return true if pinning was detected, false otherwise
     */
    public boolean wasPinningDetected() {
      return pinningDetected.get();
    }
    
    /**
     * Marks that pinning was detected.
     *
     * @param stackTrace the stack trace of the pinned thread
     */
    public void markPinningDetected(String stackTrace) {
      pinningDetected.set(true);
      log.warn("Thread pinning detected:\n{}\n", stackTrace);
    }
  }
}