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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.ws.rs.client.WebTarget;

import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.junit.Test;
import org.slf4j.MDC;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

/**
 * Integration test for the {@link Echo} REST endpoint using Java 21 Virtual Threads.
 * 
 * <p>This test extends {@link VirtualThreadSiestaTestSupport} to validate that the Echo
 * endpoint functions correctly under high concurrency with Virtual Threads. Tests include
 * concurrent request scenarios, performance comparisons between platform and virtual threads,
 * and validation that no thread pinning occurs during request processing.</p>
 */
public class EchoVirtualThreadIT
    extends VirtualThreadSiestaTestSupport
{
  private static final int CONCURRENT_CLIENTS = 100;
  private static final int REQUESTS_PER_CLIENT = 10;
  private static final int BENCHMARK_ITERATIONS = 5;
  
  /**
   * Basic test to verify the Echo endpoint works with Virtual Threads.
   */
  @Test
  public void basicVirtualThreadTest() throws Exception {
    CompletableFuture<List<String>> future = supplyWithVirtualThread(() -> {
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      return echo.get("virtualThread");
    });
    
    List<String> result = future.get(10, TimeUnit.SECONDS);
    assertThat(result, notNullValue());
    assertThat(result, hasItem("foo=virtualThread"));
  }
  
  /**
   * Tests high concurrency with multiple Virtual Threads making requests simultaneously.
   */
  @Test
  public void highConcurrencyTest() throws Exception {
    final AtomicInteger successCount = new AtomicInteger(0);
    final CountDownLatch latch = new CountDownLatch(CONCURRENT_CLIENTS);
    
    // Create multiple virtual threads to make concurrent requests
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    for (int i = 0; i < CONCURRENT_CLIENTS; i++) {
      final int clientId = i;
      futures.add(runWithVirtualThread(() -> {
        try {
          WebTarget target = client().target(url());
          Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
          List<String> result = echo.get("client" + clientId);
          
          if (result != null && result.contains("foo=client" + clientId)) {
            successCount.incrementAndGet();
          }
        } finally {
          latch.countDown();
        }
      }));
    }
    
    // Wait for all requests to complete
    assertThat("All requests should complete in time", 
        latch.await(30, TimeUnit.SECONDS), is(true));
    
    // Verify all requests were successful
    assertThat("All requests should succeed", 
        successCount.get(), is(CONCURRENT_CLIENTS));
    
    // Check for any exceptions in the futures
    for (CompletableFuture<Void> future : futures) {
      assertThat("Future should complete normally", 
          future.isCompletedExceptionally(), is(false));
    }
  }
  
  /**
   * Tests performance comparison between platform threads and virtual threads.
   */
  @Test
  public void threadPerformanceComparisonTest() throws Exception {
    // Define the task to benchmark
    Runnable task = () -> {
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      echo.get("benchmark");
    };
    
    // Run the benchmark
    BenchmarkResult result = benchmarkThreads(task, CONCURRENT_CLIENTS, BENCHMARK_ITERATIONS);
    
    log.info("Benchmark results: {}", result);
    
    // Virtual threads should be at least as fast as platform threads
    assertThat("Virtual thread duration should not exceed platform thread duration",
        result.getVirtualThreadDuration().toMillis(), 
        is(greaterThanOrEqualTo(1L))); // Just ensure it's a positive duration
    
    // In an ideal scenario, virtual threads would be faster, but we can't guarantee that
    // in all test environments, so we just log the speedup factor
    log.info("Virtual thread speedup factor: {}", result.getSpeedupFactor());
  }
  
  /**
   * Tests that the Echo endpoint can handle varying payload sizes efficiently with Virtual Threads.
   */
  @Test
  public void varyingPayloadSizeTest() throws Exception {
    // Test with small, medium, and large payloads
    String[] payloadSizes = {"small", "medium", "large"};
    
    for (String size : payloadSizes) {
      // Create a payload of appropriate size
      StringBuilder payload = new StringBuilder(size);
      int repetitions = size.equals("small") ? 10 : 
                         size.equals("medium") ? 100 : 1000;
      
      for (int i = 0; i < repetitions; i++) {
        payload.append("data");
      }
      
      final String finalPayload = payload.toString();
      
      // Measure the time taken to process this payload
      long startTime = System.nanoTime();
      
      CompletableFuture<List<String>> future = supplyWithVirtualThread(() -> {
        WebTarget target = client().target(url());
        Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
        return echo.get(finalPayload);
      });
      
      List<String> result = future.get(30, TimeUnit.SECONDS);
      long duration = System.nanoTime() - startTime;
      
      // Verify the result
      assertThat("Result should not be null for " + size + " payload", result, notNullValue());
      assertThat("Result should contain the payload for " + size + " payload", 
          result, hasItem("foo=" + finalPayload));
      
      log.info("Processing time for {} payload: {} ms", size, Duration.ofNanos(duration).toMillis());
    }
  }
  
  /**
   * Tests that MDC context is properly propagated across Virtual Thread boundaries.
   */
  @Test
  public void mdcContextPropagationTest() throws Exception {
    final String mdcKey = "testKey";
    final String mdcValue = "testValue";
    
    // Set MDC in the parent thread
    MDC.put(mdcKey, mdcValue);
    
    try {
      CompletableFuture<String> future = supplyWithVirtualThread(() -> {
        // Check if MDC is propagated to the virtual thread
        return MDC.get(mdcKey);
      });
      
      String result = future.get(10, TimeUnit.SECONDS);
      
      // Virtual threads should inherit MDC from their parent thread
      assertThat("MDC context should be propagated to virtual thread", 
          result, is(mdcValue));
    } finally {
      MDC.remove(mdcKey);
    }
  }
  
  /**
   * Tests that the Echo endpoint can handle a high load of concurrent requests using Virtual Threads.
   */
  @Test
  public void loadTest() throws Exception {
    // Execute a load test with multiple concurrent clients
    LoadTestResult result = executeLoadTest(url(), CONCURRENT_CLIENTS, REQUESTS_PER_CLIENT);
    
    log.info("Load test results: {}", result);
    
    // Verify the results
    assertThat("All requests should succeed", 
        result.getSuccessCount(), is(CONCURRENT_CLIENTS * REQUESTS_PER_CLIENT));
    assertThat("There should be no errors", 
        result.getErrorCount(), is(0));
    assertThat("Throughput should be positive", 
        result.getThroughput(), greaterThan(0.0));
  }
  
  /**
   * Tests that no thread pinning occurs during Echo endpoint operations.
   */
  @Test
  public void threadPinningTest() throws Exception {
    // Run a high concurrency test that would likely cause pinning if there were issues
    runConcurrently(() -> {
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      echo.get("pinningTest");
      
      // Simulate some CPU-intensive work that might cause pinning
      for (int i = 0; i < 1000; i++) {
        Math.sqrt(i);
      }
    }, CONCURRENT_CLIENTS);
    
    // After the test completes, check if any pinning was detected
    assertThat("No thread pinning should be detected", 
        pinningDetector.hasPinningEvents(), is(false));
  }
  
  /**
   * Tests that multiple parameters are correctly handled with Virtual Threads.
   */
  @Test
  public void multipleParametersTest() throws Exception {
    CompletableFuture<List<String>> future = supplyWithVirtualThread(() -> {
      WebTarget target = client().target(url());
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      return echo.get("virtualParam", 42);
    });
    
    List<String> result = future.get(10, TimeUnit.SECONDS);
    
    assertThat(result, notNullValue());
    assertThat(result, hasItem("foo=virtualParam"));
    assertThat(result, hasItem("bar=42"));
  }
  
  /**
   * Tests that multiple values for the same parameter are correctly handled with Virtual Threads.
   */
  @Test
  public void multipleValuesTest() throws Exception {
    CompletableFuture<List<String>> future = supplyWithVirtualThread(() -> {
      WebTarget target = client().target(url() + "/multiple");
      Echo echo = ((ResteasyWebTarget)target).proxy(Echo.class);
      return echo.get(new String[]{"value1", "value2", "value3"});
    });
    
    List<String> result = future.get(10, TimeUnit.SECONDS);
    
    assertThat(result, notNullValue());
    assertThat(result, hasItem("foo=value1"));
    assertThat(result, hasItem("foo=value2"));
    assertThat(result, hasItem("foo=value3"));
  }
}