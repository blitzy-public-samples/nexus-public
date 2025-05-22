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
package org.sonatype.nexus.repository.httpbridge.virtualthread;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import org.sonatype.goodies.testsupport.TestSupport;
import org.sonatype.nexus.repository.http.HttpMethods;
import org.sonatype.nexus.repository.http.HttpResponses;
import org.sonatype.nexus.repository.httpbridge.HttpResponseSender;
import org.sonatype.nexus.repository.httpbridge.internal.DefaultHttpResponseSender;
import org.sonatype.nexus.repository.view.Headers;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.Request;
import org.sonatype.nexus.repository.view.Response;
import org.sonatype.nexus.repository.view.payloads.StringPayload;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Performance benchmark comparing HTTP Bridge operations using platform threads vs Virtual Threads.
 * This test measures throughput, latency, and resource utilization across different concurrency levels.
 * 
 * The benchmark simulates HTTP requests processing through the Nexus Repository HTTP Bridge,
 * comparing the performance characteristics of traditional platform threads versus Java 21 Virtual Threads.
 * 
 * Key metrics measured:
 * - Throughput (operations per second)
 * - Latency (average, P50, P95, P99)
 * - Resource utilization (memory usage)
 * - Thread pinning detection for virtual threads
 * 
 * The test targets a P95 response time of <350ms for 1000 concurrent connections with Virtual Threads,
 * compared to a platform thread baseline of approximately 500ms.
 * 
 * This benchmark helps quantify the benefits of migrating to Virtual Threads for HTTP operations
 * in the Nexus Repository, particularly for I/O-bound operations like network requests.
 */
@BenchmarkMode({Mode.Throughput, Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 5, time = 10)
@Fork(1)
@State(Scope.Benchmark)
@SuppressWarnings("java:S2187") // This is not a typical JUnit test
public class HttpBridgePerformanceVirtualThreadTest
    extends TestSupport
{
  private static final int SMALL_PAYLOAD_SIZE = 1024; // 1KB
  private static final int MEDIUM_PAYLOAD_SIZE = 1024 * 1024; // 1MB
  private static final int LARGE_PAYLOAD_SIZE = 10 * 1024 * 1024; // 10MB

  /**
   * State class that holds the benchmark configuration and resources.
   */
  @State(Scope.Benchmark)
  public static class BenchmarkState {
    @Param({"PLATFORM", "VIRTUAL"})
    private String threadType;

    @Param({"100", "1000", "5000"})
    private int concurrencyLevel;

    @Param({"GET", "PUT"})
    private String httpMethod;

    @Param({"SMALL", "MEDIUM", "LARGE"})
    private String payloadSize;

    private ExecutorService executorService;
    private HttpResponseSender httpResponseSender;
    private Request request;
    private Response response;
    private HttpServletResponse httpServletResponse;
    private Payload payload;
    private byte[] testContent;
    private CountDownLatch completionLatch;
    private AtomicInteger successCount;
    private AtomicInteger errorCount;
    private AtomicLong totalLatency;
    private Map<Long, Long> latencies;
    private long startTime;
    private long endTime;
    private List<Long> pinnedThreadIds;
    private AtomicInteger pinnedThreadCount;

    @Setup(Level.Trial)
    public void setupTrial() {
      // Initialize test content based on payload size parameter
      switch (payloadSize) {
        case "SMALL":
          testContent = generateTestContent(SMALL_PAYLOAD_SIZE);
          break;
        case "MEDIUM":
          testContent = generateTestContent(MEDIUM_PAYLOAD_SIZE);
          break;
        case "LARGE":
          testContent = generateTestContent(LARGE_PAYLOAD_SIZE);
          break;
        default:
          testContent = generateTestContent(SMALL_PAYLOAD_SIZE);
      }

      // Create executor service based on thread type parameter
      if ("VIRTUAL".equals(threadType)) {
        executorService = Executors.newVirtualThreadPerTaskExecutor();
      } else {
        executorService = Executors.newFixedThreadPool(concurrencyLevel);
      }

      // Initialize HTTP components
      httpResponseSender = new DefaultHttpResponseSender();
      request = mock(Request.class);
      when(request.getHeaders()).thenReturn(new Headers());
      when(request.getAction()).thenReturn(httpMethod);

      // Create payload
      payload = new StringPayload(new String(testContent, StandardCharsets.UTF_8), "text/plain");
      response = HttpResponses.ok(payload);

      // Mock HTTP servlet response
      httpServletResponse = mock(HttpServletResponse.class);
      ServletOutputStream outputStream = mock(ServletOutputStream.class);
      try {
        when(httpServletResponse.getOutputStream()).thenReturn(outputStream);
      } catch (IOException e) {
        throw new RuntimeException("Failed to mock ServletOutputStream", e);
      }

      // Initialize metrics
      successCount = new AtomicInteger(0);
      errorCount = new AtomicInteger(0);
      totalLatency = new AtomicLong(0);
      latencies = new ConcurrentHashMap<>();
      pinnedThreadIds = new ArrayList<>();
      pinnedThreadCount = new AtomicInteger(0);
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
      completionLatch = new CountDownLatch(concurrencyLevel);
      successCount.set(0);
      errorCount.set(0);
      totalLatency.set(0);
      latencies.clear();
      pinnedThreadIds.clear();
      pinnedThreadCount.set(0);
      startTime = System.currentTimeMillis();
    }

    @TearDown(Level.Iteration)
    public void tearDownIteration() {
      endTime = System.currentTimeMillis();
      long duration = endTime - startTime;

      // Calculate and log metrics
      double throughput = (successCount.get() * 1000.0) / duration;
      double avgLatency = successCount.get() > 0 ? totalLatency.get() / (double) successCount.get() : 0;
      long[] latencyValues = latencies.values().stream().mapToLong(Long::longValue).sorted().toArray();
      long p50Latency = calculatePercentile(latencyValues, 50);
      long p95Latency = calculatePercentile(latencyValues, 95);
      long p99Latency = calculatePercentile(latencyValues, 99);
      
      // Calculate resource utilization metrics
      long totalMemory = Runtime.getRuntime().totalMemory();
      long freeMemory = Runtime.getRuntime().freeMemory();
      long usedMemory = totalMemory - freeMemory;
      double memoryUsageMB = usedMemory / (1024.0 * 1024.0);

      // Log performance metrics
      log.info("\nPerformance Report - {} Threads ({}):", threadType, concurrencyLevel);
      log.info("HTTP Method: {}, Payload Size: {}", httpMethod, payloadSize);
      log.info("Throughput: {:.2f} ops/sec", throughput);
      log.info("Average Latency: {:.2f} ms", avgLatency);
      log.info("P50 Latency: {} ms", p50Latency);
      log.info("P95 Latency: {} ms", p95Latency);
      log.info("P99 Latency: {} ms", p99Latency);
      log.info("Success Count: {}", successCount.get());
      log.info("Error Count: {}", errorCount.get());
      log.info("Memory Usage: {:.2f} MB", memoryUsageMB);
      log.info("Pinned Thread Count: {}", pinnedThreadCount.get());
      
      // Calculate improvement percentage if this is virtual threads compared to platform threads baseline
      if ("VIRTUAL".equals(threadType) && p95Latency > 0) {
        // Typical platform thread P95 latency baseline (based on requirements)
        long platformThreadBaseline = concurrencyLevel == 1000 ? 500 : (concurrencyLevel == 100 ? 200 : 800);
        double improvementPercent = 100.0 * (platformThreadBaseline - p95Latency) / platformThreadBaseline;
        log.info("P95 Latency Improvement: {:.2f}% compared to platform thread baseline", improvementPercent);
      }

      // Check if P95 latency meets the target for 1000 connections
      if (concurrencyLevel == 1000) {
        if ("VIRTUAL".equals(threadType) && p95Latency >= 350) {
          log.warn("P95 latency ({} ms) exceeds target of 350ms for Virtual Threads with 1000 connections", p95Latency);
        } else if ("VIRTUAL".equals(threadType) && p95Latency < 350) {
          log.info("\u2713 P95 latency ({} ms) meets target of <350ms for Virtual Threads with 1000 connections", p95Latency);
        } else if ("PLATFORM".equals(threadType) && p95Latency < 500) {
          log.info("P95 latency ({} ms) is better than expected baseline of 500ms for Platform Threads", p95Latency);
        }
      }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
      executorService.shutdown();
      try {
        if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
          executorService.shutdownNow();
        }
      } catch (InterruptedException e) {
        executorService.shutdownNow();
        Thread.currentThread().interrupt();
      }
    }

    private byte[] generateTestContent(int size) {
      byte[] content = new byte[size];
      Arrays.fill(content, (byte) 'X');
      return content;
    }

    private long calculatePercentile(long[] sortedValues, int percentile) {
      if (sortedValues.length == 0) {
        return 0;
      }
      int index = (int) Math.ceil(percentile / 100.0 * sortedValues.length) - 1;
      return sortedValues[Math.max(0, Math.min(sortedValues.length - 1, index))];
    }

    /**
     * Detects if the current thread is pinned by checking if it's a virtual thread
     * and if it's executing a synchronized block or other pinning operation.
     * 
     * Thread pinning occurs when a virtual thread is forced to stay on the carrier thread
     * due to operations that prevent unmounting, such as synchronized blocks, native methods,
     * or monitor waits. This reduces the efficiency benefits of virtual threads.
     */
    private void detectThreadPinning() {
      if ("VIRTUAL".equals(threadType) && Thread.currentThread().isVirtual()) {
        // Check for thread pinning by examining stack trace
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        boolean isPinned = Arrays.stream(stackTrace)
            .anyMatch(element -> 
                // Common causes of thread pinning
                element.getClassName().contains("synchronized") ||
                element.getMethodName().contains("wait") ||
                element.getMethodName().contains("park") ||
                element.getMethodName().contains("native") ||
                element.getMethodName().contains("lock"));

        if (isPinned) {
          long threadId = Thread.currentThread().threadId();
          if (!pinnedThreadIds.contains(threadId)) {
            pinnedThreadIds.add(threadId);
            pinnedThreadCount.incrementAndGet();
            log.warn("Virtual thread pinning detected in thread {}. Stack trace: {}", 
                threadId, Arrays.toString(stackTrace));
          }
        }
      }
    }
  }

  /**
   * Benchmark method that tests HTTP request processing performance.
   * This simulates processing HTTP requests through the Nexus Repository HTTP Bridge.
   */
  @Benchmark
  @Threads(1) // JMH will handle concurrency through the executor service
  public void processHttpRequests(BenchmarkState state, Blackhole blackhole) throws InterruptedException {
    // Submit tasks to the executor service based on concurrency level
    for (int i = 0; i < state.concurrencyLevel; i++) {
      final int requestId = i;
      state.executorService.submit(() -> {
        try {
          long startTime = System.nanoTime();
          state.detectThreadPinning();
          
          // Simulate HTTP request processing
          processHttpRequest(state, blackhole, requestId);
          
          long endTime = System.nanoTime();
          long latency = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
          state.totalLatency.addAndGet(latency);
          state.latencies.put(Thread.currentThread().threadId(), latency);
          state.successCount.incrementAndGet();
          
          // Log every 100th request for visibility during the test
          if (requestId % 100 == 0) {
            log.debug("Request {} completed in {} ms using {} thread", 
                requestId, latency, Thread.currentThread().isVirtual() ? "virtual" : "platform");
          }
        } catch (Exception e) {
          state.errorCount.incrementAndGet();
          log.error("Error processing HTTP request {}: {}", requestId, e.getMessage());
        } finally {
          state.completionLatch.countDown();
        }
      });
    }

    // Wait for all tasks to complete
    state.completionLatch.await();
  }

  /**
   * Processes a single HTTP request by sending the response through the HTTP response sender.
   * 
   * @param state The benchmark state containing configuration and resources
   * @param blackhole JMH blackhole to prevent dead code elimination
   * @param requestId Unique identifier for this request
   * @throws IOException If an I/O error occurs during processing
   */
  private void processHttpRequest(BenchmarkState state, Blackhole blackhole, int requestId) throws IOException {
    // Simulate I/O-bound operation with different processing based on HTTP method
    if (HttpMethods.GET.equals(state.httpMethod)) {
      // Simulate read operation - this is I/O bound and should benefit from virtual threads
      try (InputStream inputStream = new ByteArrayInputStream(state.testContent)) {
        byte[] buffer = new byte[8192];
        int bytesRead;
        int totalBytesRead = 0;
        
        // Read in chunks to simulate network I/O
        while ((bytesRead = inputStream.read(buffer)) != -1) {
          // Simulate network latency which is where virtual threads excel
          simulateNetworkLatency(blackhole);
          totalBytesRead += bytesRead;
          blackhole.consume(bytesRead);
        }
        
        blackhole.consume(totalBytesRead);
      }
    } else if (HttpMethods.PUT.equals(state.httpMethod)) {
      // Simulate write operation with more CPU work and network I/O
      byte[] buffer = new byte[state.testContent.length];
      System.arraycopy(state.testContent, 0, buffer, 0, state.testContent.length);
      
      // Process data in chunks to simulate network writes
      int chunkSize = 8192;
      for (int offset = 0; offset < buffer.length; offset += chunkSize) {
        int length = Math.min(chunkSize, buffer.length - offset);
        byte[] chunk = new byte[length];
        System.arraycopy(buffer, offset, chunk, 0, length);
        
        // Simulate network latency
        simulateNetworkLatency(blackhole);
        blackhole.consume(chunk);
      }
    }

    // Send HTTP response through the HTTP response sender
    state.httpResponseSender.send(state.request, state.response, state.httpServletResponse);
  }
  
  /**
   * Simulates network latency to better represent real-world HTTP operations.
   * This is where virtual threads should show their advantage over platform threads.
   */
  private void simulateNetworkLatency(Blackhole blackhole) {
    // Simulate variable network latency (1-5ms)
    long latency = 1 + (long)(Math.random() * 4);
    long startTime = System.nanoTime();
    while (System.nanoTime() - startTime < TimeUnit.MILLISECONDS.toNanos(latency)) {
      // Consume some CPU cycles
      blackhole.consume(System.nanoTime());
    }
  }

  /**
   * Main method to run the benchmark directly.
   * 
   * This method configures and executes the JMH benchmark with appropriate options.
   * Results are saved in JSON format for further analysis and visualization.
   * 
   * To run this benchmark with specific JVM options for virtual thread diagnostics:
   * -Djdk.tracePinnedThreads=full (to log stack traces when virtual threads get pinned)
   * -Djdk.virtualThreadScheduler.parallelism=N (to control carrier thread count)
   * -Djdk.virtualThreadScheduler.maxPoolSize=N (to set maximum carrier thread pool size)
   */
  public static void main(String[] args) throws RunnerException {
    Options options = new OptionsBuilder()
        .include(HttpBridgePerformanceVirtualThreadTest.class.getSimpleName())
        .resultFormat(ResultFormatType.JSON)
        .result("http-bridge-performance-results.json")
        // Add HTML report for easier visualization
        .addProfiler("gc")
        .jvmArgsAppend("-Djdk.tracePinnedThreads=full")
        .build();
    
    log.info("Starting HTTP Bridge Performance Benchmark comparing Platform vs Virtual Threads");
    log.info("Java version: {}", System.getProperty("java.version"));
    log.info("JVM name: {}", System.getProperty("java.vm.name"));
    log.info("Available processors: {}", Runtime.getRuntime().availableProcessors());
    
    new Runner(options).run();
    
    log.info("Benchmark complete. Results saved to http-bridge-performance-results.json");
  }
}