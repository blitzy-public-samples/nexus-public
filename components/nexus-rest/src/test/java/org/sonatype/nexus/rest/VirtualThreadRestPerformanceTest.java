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
package org.sonatype.nexus.rest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Performance benchmark test for REST operations using Java 21's Virtual Threads.
 * Compares the performance of platform threads vs virtual threads when handling
 * REST responses and exceptions under high concurrency.
 *
 * @since 3.60
 */
@Tag("java21")
public class VirtualThreadRestPerformanceTest
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 3;
  private static final int OPERATIONS_PER_ITERATION = 10_000;
  private static final int CONCURRENT_THREADS = 1_000;
  
  private ExecutorService platformExecutor;
  private ExecutorService virtualExecutor;
  
  @BeforeEach
  void setUp() {
    // Create platform thread executor with fixed thread pool
    platformExecutor = Executors.newFixedThreadPool(100, Thread.ofPlatform().factory());
    
    // Create virtual thread executor with virtual thread per task
    virtualExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    if (platformExecutor != null) {
      platformExecutor.shutdown();
      platformExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
    
    if (virtualExecutor != null) {
      virtualExecutor.shutdown();
      virtualExecutor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
  
  /**
   * Benchmark test comparing SimpleApiResponse creation performance between platform threads and virtual threads.
   * This test measures the throughput and latency of creating SimpleApiResponse objects under high concurrency.
   */
  @Test
  void benchmarkSimpleApiResponseCreation() throws Exception {
    System.out.println("\n=== SimpleApiResponse Creation Benchmark ===\n");
    
    // Warm up to avoid JIT compilation effects
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runSimpleApiResponseBenchmark(platformExecutor, 1000, "Platform Threads (Warmup)");
      runSimpleApiResponseBenchmark(virtualExecutor, 1000, "Virtual Threads (Warmup)");
    }
    
    // Run actual benchmark
    System.out.println("\nRunning benchmark...");
    List<Duration> platformDurations = new ArrayList<>();
    List<Duration> virtualDurations = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformDurations.add(runSimpleApiResponseBenchmark(platformExecutor, OPERATIONS_PER_ITERATION, 
          "Platform Threads (Run " + (i + 1) + ")"));
      virtualDurations.add(runSimpleApiResponseBenchmark(virtualExecutor, OPERATIONS_PER_ITERATION, 
          "Virtual Threads (Run " + (i + 1) + ")"));
    }
    
    // Calculate average durations
    Duration avgPlatformDuration = calculateAverageDuration(platformDurations);
    Duration avgVirtualDuration = calculateAverageDuration(virtualDurations);
    
    System.out.println("\nResults:");
    System.out.println("Platform Threads Avg: " + avgPlatformDuration.toMillis() + "ms");
    System.out.println("Virtual Threads Avg: " + avgVirtualDuration.toMillis() + "ms");
    
    // Verify that virtual threads perform better than platform threads
    assertThat("Virtual threads should be faster than platform threads", 
        avgVirtualDuration.toMillis(), lessThan(avgPlatformDuration.toMillis()));
  }
  
  /**
   * Benchmark test comparing WebApplicationMessageException handling performance between platform threads and virtual threads.
   * This test measures the throughput and latency of creating and handling exceptions under high concurrency.
   */
  @Test
  void benchmarkWebApplicationMessageExceptionHandling() throws Exception {
    System.out.println("\n=== WebApplicationMessageException Handling Benchmark ===\n");
    
    // Warm up to avoid JIT compilation effects
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runExceptionHandlingBenchmark(platformExecutor, 1000, "Platform Threads (Warmup)");
      runExceptionHandlingBenchmark(virtualExecutor, 1000, "Virtual Threads (Warmup)");
    }
    
    // Run actual benchmark
    System.out.println("\nRunning benchmark...");
    List<Duration> platformDurations = new ArrayList<>();
    List<Duration> virtualDurations = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformDurations.add(runExceptionHandlingBenchmark(platformExecutor, OPERATIONS_PER_ITERATION, 
          "Platform Threads (Run " + (i + 1) + ")"));
      virtualDurations.add(runExceptionHandlingBenchmark(virtualExecutor, OPERATIONS_PER_ITERATION, 
          "Virtual Threads (Run " + (i + 1) + ")"));
    }
    
    // Calculate average durations
    Duration avgPlatformDuration = calculateAverageDuration(platformDurations);
    Duration avgVirtualDuration = calculateAverageDuration(virtualDurations);
    
    System.out.println("\nResults:");
    System.out.println("Platform Threads Avg: " + avgPlatformDuration.toMillis() + "ms");
    System.out.println("Virtual Threads Avg: " + avgVirtualDuration.toMillis() + "ms");
    
    // Verify that virtual threads perform better than platform threads
    assertThat("Virtual threads should be faster than platform threads", 
        avgVirtualDuration.toMillis(), lessThan(avgPlatformDuration.toMillis()));
  }
  
  /**
   * Tests the scalability of virtual threads with a very high number of concurrent operations.
   * This test creates a large number of virtual threads to verify that the system can handle
   * high concurrency without significant performance degradation.
   */
  @Test
  void testVirtualThreadScalability() throws Exception {
    System.out.println("\n=== Virtual Thread Scalability Test ===\n");
    
    // Number of concurrent operations - much higher than what would be practical with platform threads
    final int highConcurrency = 10_000;
    final CountDownLatch latch = new CountDownLatch(highConcurrency);
    final AtomicInteger errorCount = new AtomicInteger(0);
    final LongAdder completedCount = new LongAdder();
    
    // Create a large number of virtual threads directly
    Instant start = Instant.now();
    
    for (int i = 0; i < highConcurrency; i++) {
      Thread.startVirtualThread(() -> {
        try {
          // Simulate a REST operation
          Response response = SimpleApiResponse.ok("Success", new TestData("test-value"));
          assertThat(response, is(notNullValue()));
          assertThat(response.getStatus(), is(Status.OK.getStatusCode()));
          
          completedCount.increment();
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all operations to complete
    boolean completed = latch.await(30, TimeUnit.SECONDS);
    Duration duration = Duration.between(start, Instant.now());
    
    System.out.println("Completed: " + completed);
    System.out.println("Duration: " + duration.toMillis() + "ms");
    System.out.println("Error count: " + errorCount.get());
    System.out.println("Completed count: " + completedCount.sum());
    System.out.println("Operations per second: " + 
        (completedCount.sum() * 1000.0 / duration.toMillis()));
    
    // Verify that all operations completed successfully
    assertThat("All operations should complete", completed, is(true));
    assertThat("No errors should occur", errorCount.get(), is(0));
    assertThat("All operations should be counted", completedCount.sum(), is((long) highConcurrency));
    
    // Verify that the throughput is reasonable (at least 1000 ops/sec)
    double opsPerSecond = completedCount.sum() * 1000.0 / duration.toMillis();
    assertThat("Virtual threads should achieve high throughput", opsPerSecond, greaterThan(1000.0));
  }
  
  /**
   * Tests the direct creation and execution of virtual threads for REST operations.
   * This test demonstrates how to use Thread.startVirtualThread() for creating virtual threads
   * without an executor service.
   */
  @Test
  void testDirectVirtualThreadCreation() throws Exception {
    System.out.println("\n=== Direct Virtual Thread Creation Test ===\n");
    
    final int threadCount = 100;
    final CountDownLatch latch = new CountDownLatch(threadCount);
    final List<Thread> threads = new ArrayList<>();
    
    // Create and start virtual threads directly
    for (int i = 0; i < threadCount; i++) {
      final int index = i;
      Thread thread = Thread.startVirtualThread(() -> {
        try {
          // Create a WebApplicationMessageException and verify its response
          WebApplicationMessageException exception = 
              new WebApplicationMessageException(Status.BAD_REQUEST, "Test message " + index, APPLICATION_JSON);
          
          Response response = exception.getResponse();
          assertThat(response.getStatus(), is(Status.BAD_REQUEST.getStatusCode()));
          
          Object entity = response.getEntity();
          assertThat(entity, is(notNullValue()));
        } finally {
          latch.countDown();
        }
      });
      
      threads.add(thread);
    }
    
    // Wait for all threads to complete
    boolean completed = latch.await(10, TimeUnit.SECONDS);
    
    System.out.println("All threads completed: " + completed);
    assertThat("All threads should complete", completed, is(true));
  }
  
  /**
   * Tests concurrent REST operations using CompletableFuture with virtual threads.
   * This test demonstrates how to use CompletableFuture with virtual threads for
   * asynchronous REST operations.
   */
  @Test
  void testCompletableFutureWithVirtualThreads() throws Exception {
    System.out.println("\n=== CompletableFuture with Virtual Threads Test ===\n");
    
    final int operationCount = 1000;
    
    // Create CompletableFuture tasks using virtual threads
    List<CompletableFuture<Response>> futures = new ArrayList<>();
    
    Instant start = Instant.now();
    
    for (int i = 0; i < operationCount; i++) {
      final String message = "Test message " + i;
      CompletableFuture<Response> future = CompletableFuture.supplyAsync(
          () -> SimpleApiResponse.ok(message, new TestData("value-" + i)),
          virtualExecutor
      );
      
      futures.add(future);
    }
    
    // Wait for all futures to complete
    CompletableFuture<Void> allFutures = CompletableFuture.allOf(
        futures.toArray(new CompletableFuture[0])
    );
    
    allFutures.join();
    
    Duration duration = Duration.between(start, Instant.now());
    System.out.println("Duration: " + duration.toMillis() + "ms");
    System.out.println("Operations per second: " + 
        (operationCount * 1000.0 / duration.toMillis()));
    
    // Verify results
    for (int i = 0; i < operationCount; i++) {
      Response response = futures.get(i).get();
      assertThat(response.getStatus(), is(Status.OK.getStatusCode()));
      
      SimpleApiResponse apiResponse = (SimpleApiResponse) response.getEntity();
      assertThat(apiResponse.status(), is(Status.OK.getStatusCode()));
      assertThat(apiResponse.message(), is("Test message " + i));
    }
  }
  
  /**
   * Runs a benchmark for SimpleApiResponse creation using the specified executor.
   *
   * @param executor the executor service to use
   * @param operations the number of operations to perform
   * @param label the label for reporting
   * @return the duration of the benchmark
   */
  private Duration runSimpleApiResponseBenchmark(ExecutorService executor, int operations, String label) 
      throws Exception {
    final CountDownLatch latch = new CountDownLatch(operations);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    Instant start = Instant.now();
    
    // Submit tasks to the executor
    for (int i = 0; i < operations; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Create a SimpleApiResponse and verify it
          Response response = SimpleApiResponse.ok("Test message " + index, new TestData("value-" + index));
          
          SimpleApiResponse apiResponse = (SimpleApiResponse) response.getEntity();
          if (apiResponse.status() != Status.OK.getStatusCode() || 
              !apiResponse.message().equals("Test message " + index)) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    latch.await();
    
    Duration duration = Duration.between(start, Instant.now());
    double opsPerSecond = operations * 1000.0 / duration.toMillis();
    
    System.out.printf("%s: %d operations in %dms (%.2f ops/sec), errors: %d%n", 
        label, operations, duration.toMillis(), opsPerSecond, errorCount.get());
    
    // Verify no errors occurred
    assertThat("No errors should occur during benchmark", errorCount.get(), is(0));
    
    return duration;
  }
  
  /**
   * Runs a benchmark for WebApplicationMessageException handling using the specified executor.
   *
   * @param executor the executor service to use
   * @param operations the number of operations to perform
   * @param label the label for reporting
   * @return the duration of the benchmark
   */
  private Duration runExceptionHandlingBenchmark(ExecutorService executor, int operations, String label) 
      throws Exception {
    final CountDownLatch latch = new CountDownLatch(operations);
    final AtomicInteger errorCount = new AtomicInteger(0);
    
    Instant start = Instant.now();
    
    // Submit tasks to the executor
    for (int i = 0; i < operations; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Create different types of exceptions based on the index
          WebApplicationMessageException exception;
          
          // Use pattern matching for switch to determine the exception type
          exception = switch (index % 5) {
            case 0 -> new WebApplicationMessageException(Status.BAD_REQUEST, "Bad request " + index, APPLICATION_JSON);
            case 1 -> new WebApplicationMessageException(Status.NOT_FOUND, "Not found " + index);
            case 2 -> new WebApplicationMessageException(Status.UNAUTHORIZED, "Unauthorized " + index);
            case 3 -> WebApplicationMessageException.forStatus(Status.FORBIDDEN);
            case 4 -> WebApplicationMessageException.forStatus(429); // Too Many Requests
            default -> throw new IllegalStateException("Unexpected value");
          };
          
          // Verify the exception response
          Response response = exception.getResponse();
          if (response == null) {
            errorCount.incrementAndGet();
          }
        } catch (Exception e) {
          errorCount.incrementAndGet();
        } finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    latch.await();
    
    Duration duration = Duration.between(start, Instant.now());
    double opsPerSecond = operations * 1000.0 / duration.toMillis();
    
    System.out.printf("%s: %d operations in %dms (%.2f ops/sec), errors: %d%n", 
        label, operations, duration.toMillis(), opsPerSecond, errorCount.get());
    
    // Verify no errors occurred
    assertThat("No errors should occur during benchmark", errorCount.get(), is(0));
    
    return duration;
  }
  
  /**
   * Calculates the average duration from a list of durations.
   *
   * @param durations the list of durations
   * @return the average duration
   */
  private Duration calculateAverageDuration(List<Duration> durations) {
    long totalMillis = 0;
    for (Duration duration : durations) {
      totalMillis += duration.toMillis();
    }
    return Duration.ofMillis(totalMillis / durations.size());
  }
  
  /**
   * Simple test data class for use in benchmarks.
   */
  private static class TestData {
    private final String value;
    
    public TestData(String value) {
      this.value = value;
    }
    
    public String getValue() {
      return value;
    }
  }
}