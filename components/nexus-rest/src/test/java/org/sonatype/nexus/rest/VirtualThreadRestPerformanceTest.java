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
import org.junit.jupiter.api.Timeout;

import static jakarta.ws.rs.core.MediaType.APPLICATION_JSON;
import static jakarta.ws.rs.core.Response.Status.BAD_REQUEST;
import static jakarta.ws.rs.core.Response.Status.NOT_FOUND;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Performance benchmark test for comparing platform threads vs virtual threads
 * when handling REST operations in Nexus Repository.
 * 
 * This test class measures the performance characteristics of REST operations
 * using both traditional platform threads and Java 21's virtual threads under
 * high concurrency scenarios.
 *
 * @since 3.60
 */
@Tag("java21")
public class VirtualThreadRestPerformanceTest
{
  private static final int WARMUP_ITERATIONS = 5;
  private static final int BENCHMARK_ITERATIONS = 3;
  private static final int OPERATIONS_PER_ITERATION = 10_000;
  private static final int MAX_CONCURRENT_OPERATIONS = 1_000;
  
  private ExecutorService platformExecutor;
  private ExecutorService virtualExecutor;
  
  /**
   * Test data class used in benchmark operations
   */
  private static class TestData
  {
    private final String value;
    
    public TestData(String value) {
      this.value = value;
    }
    
    public String getValue() {
      return value;
    }
  }
  
  @BeforeEach
  void setUp() {
    // Create executors for both thread types
    platformExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_OPERATIONS);
    virtualExecutor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
  }
  
  @AfterEach
  void tearDown() throws Exception {
    // Shutdown executors
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
   * Benchmark SimpleApiResponse creation with platform threads vs virtual threads.
   * This test measures the performance difference when creating a large number of
   * SimpleApiResponse objects concurrently using both thread types.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void benchmarkSimpleApiResponseCreation() throws Exception {
    System.out.println("\nBenchmarking SimpleApiResponse creation:");
    System.out.println("----------------------------------------");
    
    // Warm up
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runSimpleApiResponseBenchmark(platformExecutor, 1000, "Platform Warmup " + i);
      runSimpleApiResponseBenchmark(virtualExecutor, 1000, "Virtual Warmup " + i);
    }
    
    // Run actual benchmarks
    System.out.println("\nRunning benchmarks...");
    List<Duration> platformDurations = new ArrayList<>();
    List<Duration> virtualDurations = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformDurations.add(runSimpleApiResponseBenchmark(platformExecutor, 
          OPERATIONS_PER_ITERATION, "Platform Benchmark " + i));
      
      virtualDurations.add(runSimpleApiResponseBenchmark(virtualExecutor, 
          OPERATIONS_PER_ITERATION, "Virtual Benchmark " + i));
    }
    
    // Calculate and print average durations
    Duration avgPlatformDuration = calculateAverageDuration(platformDurations);
    Duration avgVirtualDuration = calculateAverageDuration(virtualDurations);
    
    System.out.println("\nResults:");
    System.out.println("  Platform threads avg: " + avgPlatformDuration.toMillis() + "ms");
    System.out.println("  Virtual threads avg:  " + avgVirtualDuration.toMillis() + "ms");
    
    // Virtual threads should be faster or at least not significantly slower
    assertThat("Virtual threads should not be significantly slower than platform threads",
        avgVirtualDuration.toMillis(), lessThan(avgPlatformDuration.toMillis() * 1.2));
  }
  
  /**
   * Benchmark WebApplicationMessageException handling with platform threads vs virtual threads.
   * This test measures the performance difference when creating and handling exceptions
   * concurrently using both thread types.
   */
  @Test
  @Timeout(value = 2, unit = TimeUnit.MINUTES)
  void benchmarkWebApplicationMessageExceptionHandling() throws Exception {
    System.out.println("\nBenchmarking WebApplicationMessageException handling:");
    System.out.println("----------------------------------------------------");
    
    // Warm up
    System.out.println("Warming up...");
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runExceptionHandlingBenchmark(platformExecutor, 1000, "Platform Warmup " + i);
      runExceptionHandlingBenchmark(virtualExecutor, 1000, "Virtual Warmup " + i);
    }
    
    // Run actual benchmarks
    System.out.println("\nRunning benchmarks...");
    List<Duration> platformDurations = new ArrayList<>();
    List<Duration> virtualDurations = new ArrayList<>();
    
    for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
      platformDurations.add(runExceptionHandlingBenchmark(platformExecutor, 
          OPERATIONS_PER_ITERATION, "Platform Benchmark " + i));
      
      virtualDurations.add(runExceptionHandlingBenchmark(virtualExecutor, 
          OPERATIONS_PER_ITERATION, "Virtual Benchmark " + i));
    }
    
    // Calculate and print average durations
    Duration avgPlatformDuration = calculateAverageDuration(platformDurations);
    Duration avgVirtualDuration = calculateAverageDuration(virtualDurations);
    
    System.out.println("\nResults:");
    System.out.println("  Platform threads avg: " + avgPlatformDuration.toMillis() + "ms");
    System.out.println("  Virtual threads avg:  " + avgVirtualDuration.toMillis() + "ms");
    
    // Virtual threads should be faster or at least not significantly slower
    assertThat("Virtual threads should not be significantly slower than platform threads",
        avgVirtualDuration.toMillis(), lessThan(avgPlatformDuration.toMillis() * 1.2));
  }
  
  /**
   * Test direct creation of virtual threads for REST operations.
   * This test demonstrates how to use Thread.startVirtualThread() for direct
   * creation of lightweight threads for REST operations.
   */
  @Test
  void testDirectVirtualThreadCreation() throws Exception {
    int numThreads = 100;
    CountDownLatch latch = new CountDownLatch(numThreads);
    AtomicInteger successCount = new AtomicInteger(0);
    
    // Create and start virtual threads directly
    for (int i = 0; i < numThreads; i++) {
      final int index = i;
      Thread.startVirtualThread(() -> {
        try {
          // Simulate REST operation
          Response response = SimpleApiResponse.ok("Success from thread " + index, 
              new TestData("data-" + index));
          
          // Verify response
          if (response.getStatus() == 200) {
            successCount.incrementAndGet();
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all threads to complete
    assertThat("All virtual threads should complete in time",
        latch.await(10, TimeUnit.SECONDS), is(true));
    
    // Verify all operations were successful
    assertThat("All operations should succeed", 
        successCount.get(), is(numThreads));
  }
  
  /**
   * Test high concurrency with virtual threads for REST operations.
   * This test demonstrates the ability of virtual threads to handle
   * a very large number of concurrent operations efficiently.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void testHighConcurrencyWithVirtualThreads() throws Exception {
    int numThreads = 10_000; // Much higher than would be practical with platform threads
    CountDownLatch latch = new CountDownLatch(numThreads);
    LongAdder successCount = new LongAdder();
    LongAdder failureCount = new LongAdder();
    
    // Create a virtual thread executor
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      // Submit tasks
      for (int i = 0; i < numThreads; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            // Alternate between different response types to exercise various code paths
            Response response;
            switch (index % 4) {
              case 0 -> response = SimpleApiResponse.ok("Success " + index);
              case 1 -> response = SimpleApiResponse.notFound("Not found " + index);
              case 2 -> response = SimpleApiResponse.badRequest("Bad request " + index);
              default -> response = SimpleApiResponse.ok("Success with data " + index, 
                  new TestData("value-" + index));
            }
            
            // Verify response has expected status
            Status expectedStatus = switch (index % 4) {
              case 0 -> OK;
              case 1 -> NOT_FOUND;
              case 2 -> BAD_REQUEST;
              default -> OK;
            };
            
            if (response.getStatus() == expectedStatus.getStatusCode()) {
              successCount.increment();
            } else {
              failureCount.increment();
            }
          } 
          catch (Exception e) {
            failureCount.increment();
          }
          finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all threads to complete
      assertThat("All virtual threads should complete in time",
          latch.await(20, TimeUnit.SECONDS), is(true));
    }
    
    // Verify all operations were successful
    assertThat("All operations should succeed", 
        successCount.sum(), is((long) numThreads));
    assertThat("No operations should fail", 
        failureCount.sum(), is(0L));
  }
  
  /**
   * Test concurrent exception handling with virtual threads.
   * This test demonstrates how virtual threads handle exceptions
   * in highly concurrent scenarios.
   */
  @Test
  void testConcurrentExceptionHandlingWithVirtualThreads() throws Exception {
    int numThreads = 1000;
    CountDownLatch latch = new CountDownLatch(numThreads);
    AtomicInteger correctExceptionCount = new AtomicInteger(0);
    
    // Create tasks that will throw and handle exceptions
    List<CompletableFuture<Void>> futures = new ArrayList<>();
    
    for (int i = 0; i < numThreads; i++) {
      final int index = i;
      CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
        try {
          // Create and throw different types of exceptions based on index
          Status status = switch (index % 3) {
            case 0 -> BAD_REQUEST;
            case 1 -> NOT_FOUND;
            default -> Status.UNAUTHORIZED;
          };
          
          String message = "Test exception " + index;
          
          // Throw the exception
          throw new WebApplicationMessageException(status, message, APPLICATION_JSON);
        } 
        catch (WebApplicationMessageException e) {
          // Verify the exception has the expected properties
          Response response = e.getResponse();
          assertThat(response, is(notNullValue()));
          
          // Verify response entity is a ValidationErrorXO
          Object entity = response.getEntity();
          assertThat(entity, is(notNullValue()));
          assertThat(entity instanceof ValidationErrorXO, is(true));
          
          // Verify the status code matches what we set
          int expectedStatus = switch (index % 3) {
            case 0 -> BAD_REQUEST.getStatusCode();
            case 1 -> NOT_FOUND.getStatusCode();
            default -> Status.UNAUTHORIZED.getStatusCode();
          };
          
          if (response.getStatus() == expectedStatus) {
            correctExceptionCount.incrementAndGet();
          }
        }
        finally {
          latch.countDown();
        }
      }, virtualExecutor);
      
      futures.add(future);
    }
    
    // Wait for all tasks to complete
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    
    // Verify all exceptions were handled correctly
    assertThat("All exceptions should be handled correctly",
        correctExceptionCount.get(), is(numThreads));
  }
  
  /**
   * Compare thread creation overhead between platform and virtual threads.
   * This test measures the time it takes to create a large number of threads
   * of each type without doing any actual work.
   */
  @Test
  void compareThreadCreationOverhead() throws Exception {
    int numThreads = 10_000;
    
    System.out.println("\nComparing thread creation overhead:");
    System.out.println("------------------------------------");
    
    // Measure platform thread creation time
    long platformStartTime = System.currentTimeMillis();
    ThreadFactory platformFactory = Thread.ofPlatform().factory();
    CountDownLatch platformLatch = new CountDownLatch(numThreads);
    
    for (int i = 0; i < numThreads; i++) {
      Thread t = platformFactory.newThread(platformLatch::countDown);
      t.start();
    }
    
    // Wait for platform threads to complete
    platformLatch.await(30, TimeUnit.SECONDS);
    long platformDuration = System.currentTimeMillis() - platformStartTime;
    
    // Measure virtual thread creation time
    long virtualStartTime = System.currentTimeMillis();
    ThreadFactory virtualFactory = Thread.ofVirtual().factory();
    CountDownLatch virtualLatch = new CountDownLatch(numThreads);
    
    for (int i = 0; i < numThreads; i++) {
      Thread t = virtualFactory.newThread(virtualLatch::countDown);
      t.start();
    }
    
    // Wait for virtual threads to complete
    virtualLatch.await(30, TimeUnit.SECONDS);
    long virtualDuration = System.currentTimeMillis() - virtualStartTime;
    
    System.out.println("Platform thread creation time: " + platformDuration + "ms");
    System.out.println("Virtual thread creation time: " + virtualDuration + "ms");
    
    // Virtual thread creation should be faster
    assertThat("Virtual thread creation should be faster than platform threads",
        virtualDuration, lessThan(platformDuration));
  }
  
  /**
   * Run a benchmark for SimpleApiResponse creation using the specified executor.
   *
   * @param executor the executor service to use
   * @param operations the number of operations to perform
   * @param label a label for this benchmark run
   * @return the duration of the benchmark
   */
  private Duration runSimpleApiResponseBenchmark(ExecutorService executor, int operations, String label) 
      throws Exception {
    CountDownLatch latch = new CountDownLatch(operations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks to create SimpleApiResponse objects
    for (int i = 0; i < operations; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Create different types of responses to exercise various code paths
          Response response;
          if (index % 4 == 0) {
            response = SimpleApiResponse.ok("Success " + index);
          } 
          else if (index % 4 == 1) {
            response = SimpleApiResponse.ok("Success with data " + index, 
                new TestData("value-" + index));
          }
          else if (index % 4 == 2) {
            response = SimpleApiResponse.notFound("Not found " + index);
          }
          else {
            response = SimpleApiResponse.badRequest("Bad request " + index, 
                new TestData("error-" + index));
          }
          
          // Verify response is valid
          if (response != null && response.getEntity() instanceof SimpleApiResponse) {
            successCount.incrementAndGet();
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    Duration duration = Duration.ofMillis(endTime - startTime);
    
    // Print results
    System.out.printf("%-20s: %5d ms, %d/%d operations completed%n", 
        label, duration.toMillis(), successCount.get(), operations);
    
    // Verify all operations completed successfully
    assertThat("All operations should complete in time", completed, is(true));
    assertThat("All operations should succeed", successCount.get(), is(operations));
    
    return duration;
  }
  
  /**
   * Run a benchmark for WebApplicationMessageException handling using the specified executor.
   *
   * @param executor the executor service to use
   * @param operations the number of operations to perform
   * @param label a label for this benchmark run
   * @return the duration of the benchmark
   */
  private Duration runExceptionHandlingBenchmark(ExecutorService executor, int operations, String label) 
      throws Exception {
    CountDownLatch latch = new CountDownLatch(operations);
    AtomicInteger successCount = new AtomicInteger(0);
    
    long startTime = System.currentTimeMillis();
    
    // Submit tasks to create and handle exceptions
    for (int i = 0; i < operations; i++) {
      final int index = i;
      executor.submit(() -> {
        try {
          // Create and throw different types of exceptions based on index
          Status status = switch (index % 3) {
            case 0 -> BAD_REQUEST;
            case 1 -> NOT_FOUND;
            default -> Status.UNAUTHORIZED;
          };
          
          String message = "Test exception " + index;
          
          // Create the exception (but don't throw it to avoid stack trace overhead)
          WebApplicationMessageException exception = 
              new WebApplicationMessageException(status, message, APPLICATION_JSON);
          
          // Verify the exception has the expected properties
          Response response = exception.getResponse();
          if (response != null && 
              response.getEntity() instanceof ValidationErrorXO && 
              response.getStatus() == status.getStatusCode()) {
            successCount.incrementAndGet();
          }
        } 
        finally {
          latch.countDown();
        }
      });
    }
    
    // Wait for all tasks to complete
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    long endTime = System.currentTimeMillis();
    Duration duration = Duration.ofMillis(endTime - startTime);
    
    // Print results
    System.out.printf("%-20s: %5d ms, %d/%d operations completed%n", 
        label, duration.toMillis(), successCount.get(), operations);
    
    // Verify all operations completed successfully
    assertThat("All operations should complete in time", completed, is(true));
    assertThat("All operations should succeed", successCount.get(), is(operations));
    
    return duration;
  }
  
  /**
   * Calculate the average duration from a list of durations.
   *
   * @param durations the list of durations
   * @return the average duration
   */
  private Duration calculateAverageDuration(List<Duration> durations) {
    if (durations.isEmpty()) {
      return Duration.ZERO;
    }
    
    long totalMillis = 0;
    for (Duration duration : durations) {
      totalMillis += duration.toMillis();
    }
    
    return Duration.ofMillis(totalMillis / durations.size());
  }
}