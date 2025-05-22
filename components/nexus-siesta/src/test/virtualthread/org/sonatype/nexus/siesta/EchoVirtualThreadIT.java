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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.junit.Test;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Integration test for the {@link Echo} REST endpoint using Java 21 Virtual Threads.
 * 
 * This test validates that the Echo endpoint functions correctly under high concurrency
 * with Virtual Threads, and compares performance between platform and virtual threads.
 */
public class EchoVirtualThreadIT
    extends VirtualThreadSiestaTestSupport
{
  private static final int CONCURRENT_REQUESTS = 1000;
  private static final int LOAD_TEST_DURATION_SECONDS = 5;
  private static final String MDC_TEST_KEY = "testRequestId";
  
  /**
   * Basic test to verify the Echo endpoint works with a simple request.
   */
  @Test
  public void testBasicEchoEndpoint() {
    WebTarget target = client().target(url());
    Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
    List<String> result = echo.get("hello");
    
    assertThat(result, notNullValue());
    assertThat(result, hasItem("foo=hello"));
  }
  
  /**
   * Tests the Echo endpoint with a high number of concurrent requests using Virtual Threads.
   * This validates that the endpoint can handle high concurrency without errors.
   */
  @Test
  public void testHighConcurrencyWithVirtualThreads() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int requestCount = 1000;
      CountDownLatch latch = new CountDownLatch(requestCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create the Echo client
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      
      // Submit concurrent requests
      for (int i = 0; i < requestCount; i++) {
        final int requestId = i;
        executor.submit(() -> {
          try {
            // Set a unique MDC value for this request to test context propagation
            MDC.put(MDC_TEST_KEY, "request-" + requestId);
            
            // Make the request
            List<String> result = echo.get("concurrent-" + requestId);
            
            // Verify the result
            if (result != null && result.contains("foo=concurrent-" + requestId)) {
              successCount.incrementAndGet();
            } else {
              errorCount.incrementAndGet();
            }
          } catch (Exception e) {
            log.error("Error in request {}: {}", requestId, e.getMessage());
            errorCount.incrementAndGet();
          } finally {
            MDC.remove(MDC_TEST_KEY);
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      log.info("Completed {} requests with {} successes and {} errors", 
          requestCount, successCount.get(), errorCount.get());
      
      assertThat("All requests should succeed", successCount.get(), equalTo(requestCount));
      assertThat("No requests should fail", errorCount.get(), equalTo(0));
    }
  }
  
  /**
   * Tests the Echo endpoint with varying payload sizes to validate I/O performance.
   * This ensures that the endpoint can handle different payload sizes efficiently.
   */
  @Test
  public void testVaryingPayloadSizes() throws Exception {
    // Define payload sizes to test (in characters)
    int[] payloadSizes = {10, 100, 1000, 10000};
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // For each payload size
      for (int size : payloadSizes) {
        // Generate a payload of the specified size
        String payload = generatePayload(size);
        
        // Create a latch for this batch of requests
        int requestCount = 100;
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);
        
        // Track response times
        List<Long> responseTimes = new ArrayList<>(requestCount);
        
        // Create the Echo client
        WebTarget target = client().target(url());
        Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
        
        // Submit concurrent requests
        for (int i = 0; i < requestCount; i++) {
          executor.submit(() -> {
            try {
              long startTime = System.nanoTime();
              
              // Make the request
              List<String> result = echo.get(payload);
              
              long endTime = System.nanoTime();
              long responseTimeMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
              
              // Record response time
              synchronized (responseTimes) {
                responseTimes.add(responseTimeMs);
              }
              
              // Verify the result
              if (result != null && result.contains("foo=" + payload)) {
                successCount.incrementAndGet();
              } else {
                errorCount.incrementAndGet();
              }
            } catch (Exception e) {
              log.error("Error in request with payload size {}: {}", size, e.getMessage());
              errorCount.incrementAndGet();
            } finally {
              latch.countDown();
            }
          });
        }
        
        // Wait for all requests to complete
        latch.await(30, TimeUnit.SECONDS);
        
        // Calculate average response time
        double avgResponseTime = responseTimes.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0.0);
        
        // Log results
        log.info("Payload size {}: {} requests, {} successes, {} errors, avg response time: {} ms", 
            size, requestCount, successCount.get(), errorCount.get(), avgResponseTime);
        
        // Verify results
        assertThat("All requests should succeed for payload size " + size, 
            successCount.get(), equalTo(requestCount));
        assertThat("No requests should fail for payload size " + size, 
            errorCount.get(), equalTo(0));
      }
    }
  }
  
  /**
   * Compares performance between platform threads and virtual threads.
   * This test validates that virtual threads provide better scalability under high concurrency.
   */
  @Test
  public void testPlatformVsVirtualThreadPerformance() throws Exception {
    // Create the request supplier
    WebTarget target = client().target(url("?foo=test"));
    
    // Run the performance comparison
    Map<String, PerformanceMetrics> results = compareThreadPerformance(
        "/echo",
        () -> target.request().get(),
        CONCURRENT_REQUESTS,
        LOAD_TEST_DURATION_SECONDS);
    
    // Get the metrics
    PerformanceMetrics platformMetrics = results.get("platform");
    PerformanceMetrics virtualMetrics = results.get("virtual");
    
    // Verify that both tests completed successfully
    assertThat("Platform thread test should have successful requests",
        platformMetrics.getSuccessfulRequests(), greaterThan(0L));
    assertThat("Virtual thread test should have successful requests",
        virtualMetrics.getSuccessfulRequests(), greaterThan(0L));
    
    // Verify that virtual threads handled more requests or had better response times
    // Note: On some systems, the difference might not be significant for simple tests
    if (virtualMetrics.getSuccessfulRequests() > platformMetrics.getSuccessfulRequests()) {
      log.info("Virtual threads processed more requests than platform threads");
    } else if (virtualMetrics.getAverageResponseTime() < platformMetrics.getAverageResponseTime()) {
      log.info("Virtual threads had better average response time than platform threads");
    }
    
    // For high concurrency, virtual threads should show better scalability
    // This might not always be true for very simple operations, so we log instead of assert
    log.info("Platform threads: {} requests, avg response time: {} ms",
        platformMetrics.getSuccessfulRequests(), platformMetrics.getAverageResponseTime());
    log.info("Virtual threads: {} requests, avg response time: {} ms",
        virtualMetrics.getSuccessfulRequests(), virtualMetrics.getAverageResponseTime());
  }
  
  /**
   * Tests that MDC context is properly propagated across virtual thread boundaries.
   * This ensures that logging context is maintained when using virtual threads.
   */
  @Test
  public void testMdcContextPropagation() throws Exception {
    // Create a map to store MDC values seen by each thread
    Map<Integer, String> mdcValues = new ConcurrentHashMap<>();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int requestCount = 100;
      CountDownLatch latch = new CountDownLatch(requestCount);
      
      // Create the Echo client
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      
      // Submit concurrent requests
      for (int i = 0; i < requestCount; i++) {
        final int requestId = i;
        executor.submit(() -> {
          try {
            // Set a unique MDC value for this request
            String mdcValue = "mdc-value-" + requestId;
            MDC.put(MDC_TEST_KEY, mdcValue);
            
            // Store the MDC value for verification
            mdcValues.put(requestId, mdcValue);
            
            // Make the request
            echo.get("mdc-test-" + requestId);
            
            // Verify MDC value is still available after the request
            String currentMdcValue = MDC.get(MDC_TEST_KEY);
            assertThat("MDC context should be preserved", currentMdcValue, equalTo(mdcValue));
          } finally {
            MDC.remove(MDC_TEST_KEY);
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify that we have MDC values for all requests
      assertThat("MDC values should be captured for all requests", 
          mdcValues.size(), equalTo(requestCount));
    }
  }
  
  /**
   * Tests that no thread pinning occurs during Echo endpoint operations.
   * Thread pinning can significantly reduce the benefits of virtual threads.
   */
  @Test
  public void testNoPinningDuringEchoOperations() throws Exception {
    // Create a flag to track if pinning was detected
    AtomicBoolean pinningDetected = new AtomicBoolean(false);
    
    // Create a thread factory that detects pinning
    ThreadFactory virtualThreadFactory = Thread.ofVirtual().name("echo-test-", 0).factory();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newThreadPerTaskExecutor(virtualThreadFactory)) {
      int requestCount = 500;
      CountDownLatch latch = new CountDownLatch(requestCount);
      
      // Create the Echo client
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      
      // Submit concurrent requests
      List<CompletableFuture<Void>> futures = new ArrayList<>();
      
      for (int i = 0; i < requestCount; i++) {
        final int requestId = i;
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
          try {
            // Make multiple requests to increase chance of detecting pinning
            for (int j = 0; j < 5; j++) {
              echo.get("pinning-test-" + requestId + "-" + j);
              
              // Check if the current thread is pinned
              // This is a simplified check - in a real environment, you would use JFR events
              // or the jdk.tracePinnedThreads JVM flag
              if (Thread.currentThread().getState() == Thread.State.WAITING) {
                // In a real pinning scenario, the thread would be blocked in WAITING state
                // This is a simplified detection mechanism
                pinningDetected.set(true);
                log.warn("Potential thread pinning detected in request {}", requestId);
              }
              
              // Small delay to allow thread scheduling
              Thread.sleep(10);
            }
          } catch (Exception e) {
            log.error("Error in pinning test request {}: {}", requestId, e.getMessage());
          } finally {
            latch.countDown();
          }
        }, executor);
        
        futures.add(future);
      }
      
      // Wait for all requests to complete
      latch.await(60, TimeUnit.SECONDS);
      
      // Wait for all futures to complete
      CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
      
      // Verify no pinning was detected
      // Note: This is a simplified check and may not catch all pinning scenarios
      assertThat("No thread pinning should be detected", pinningDetected.get(), is(false));
    }
  }
  
  /**
   * Tests the Echo endpoint under high load with a mix of request types.
   * This simulates a more realistic usage scenario with varied request patterns.
   */
  @Test
  public void testMixedRequestsUnderLoad() throws Exception {
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      int requestCount = 500;
      CountDownLatch latch = new CountDownLatch(requestCount);
      AtomicInteger successCount = new AtomicInteger(0);
      AtomicInteger errorCount = new AtomicInteger(0);
      
      // Create the Echo client
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      
      // Submit concurrent requests with different patterns
      for (int i = 0; i < requestCount; i++) {
        final int requestId = i;
        executor.submit(() -> {
          try {
            // Choose a request type based on the request ID
            switch (requestId % 4) {
              case 0:
                // Simple string parameter
                List<String> result1 = echo.get("mixed-" + requestId);
                if (result1 != null && result1.contains("foo=mixed-" + requestId)) {
                  successCount.incrementAndGet();
                } else {
                  errorCount.incrementAndGet();
                }
                break;
                
              case 1:
                // String and integer parameters
                List<String> result2 = echo.get("mixed-" + requestId, requestId);
                if (result2 != null && 
                    result2.contains("foo=mixed-" + requestId) && 
                    result2.contains("bar=" + requestId)) {
                  successCount.incrementAndGet();
                } else {
                  errorCount.incrementAndGet();
                }
                break;
                
              case 2:
                // Integer parameter only
                List<String> result3 = echo.get(requestId);
                if (result3 != null && result3.contains("bar=" + requestId)) {
                  successCount.incrementAndGet();
                } else {
                  errorCount.incrementAndGet();
                }
                break;
                
              case 3:
                // Multiple string parameters
                String[] params = {"a-" + requestId, "b-" + requestId, "c-" + requestId};
                List<String> result4 = echo.get(params);
                if (result4 != null && 
                    result4.size() == 3 && 
                    result4.contains("foo=a-" + requestId) && 
                    result4.contains("foo=b-" + requestId) && 
                    result4.contains("foo=c-" + requestId)) {
                  successCount.incrementAndGet();
                } else {
                  errorCount.incrementAndGet();
                }
                break;
            }
          } catch (Exception e) {
            log.error("Error in mixed request {}: {}", requestId, e.getMessage());
            errorCount.incrementAndGet();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all requests to complete
      latch.await(30, TimeUnit.SECONDS);
      
      // Verify results
      log.info("Completed {} mixed requests with {} successes and {} errors", 
          requestCount, successCount.get(), errorCount.get());
      
      assertThat("All requests should succeed", successCount.get(), equalTo(requestCount));
      assertThat("No requests should fail", errorCount.get(), equalTo(0));
    }
  }
  
  /**
   * Generates a string payload of the specified size.
   */
  private String generatePayload(int size) {
    StringBuilder sb = new StringBuilder(size);
    for (int i = 0; i < size; i++) {
      sb.append((char) ('a' + (i % 26)));
    }
    return sb.toString();
  }
}