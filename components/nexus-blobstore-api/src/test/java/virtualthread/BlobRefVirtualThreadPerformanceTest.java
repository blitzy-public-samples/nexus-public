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
package virtualthread;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sonatype.nexus.blobstore.api.BlobRef;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance test comparing Java 21 virtual threads vs platform threads for BlobRef operations.
 * This test measures and compares the performance of concurrent BlobRef parsing and formatting
 * operations when executed with virtual threads versus platform threads.
 *
 * @since 3.60
 */
@DisplayName("BlobRef Virtual Thread Performance Test")
public class BlobRefVirtualThreadPerformanceTest
{
  private static final String STORE_NAME = "test-store";
  private static final String NODE_ID = "ab761d55-5d9c22b6-3f38315a-75b3db34-0922a4d5";
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASUREMENT_ITERATIONS = 5;
  
  // Test data for benchmarking
  private List<String> blobRefStrings;
  private List<BlobRef> blobRefs;
  
  @BeforeEach
  void setUp() {
    // Generate test data for benchmarking
    blobRefStrings = new ArrayList<>();
    blobRefs = new ArrayList<>();
    
    // Create a mix of different BlobRef formats for testing
    for (int i = 0; i < 1000; i++) {
      String blobId = UUID.randomUUID().toString();
      
      // Add canonical format
      String canonicalFormat = String.format("%s@%s", STORE_NAME, blobId);
      blobRefStrings.add(canonicalFormat);
      
      // Add legacy orient format
      String legacyOrientFormat = String.format("%s@%s:%s", STORE_NAME, NODE_ID, blobId);
      blobRefStrings.add(legacyOrientFormat);
      
      // Add legacy SQL format
      String legacySqlFormat = String.format("%s:%s@%s", STORE_NAME, blobId, NODE_ID);
      blobRefStrings.add(legacySqlFormat);
      
      // Add date-based format
      OffsetDateTime dateTime = OffsetDateTime.now().withNano(0).plusSeconds(i);
      String dateBasedFormat = String.format("%s@%s@%s", STORE_NAME, blobId, 
          dateTime.format(BlobRef.DATE_TIME_FORMATTER));
      blobRefStrings.add(dateBasedFormat);
      
      // Add BlobRef objects for toString testing
      blobRefs.add(new BlobRef(NODE_ID, STORE_NAME, blobId));
    }
  }
  
  /**
   * Tests parsing performance with virtual threads vs platform threads at different concurrency levels.
   * This test demonstrates that virtual threads provide better scalability with high concurrency loads.
   *
   * @param concurrency the number of concurrent operations to perform
   */
  @ParameterizedTest(name = "Parse performance with {0} concurrent operations")
  @ValueSource(ints = {10, 100, 1000, 10000})
  void testParsePerformance(int concurrency) throws Exception {
    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runParseTest(concurrency, true, 1);
      runParseTest(concurrency, false, 1);
    }
    
    // Measure performance
    List<Long> virtualThreadTimes = new ArrayList<>();
    List<Long> platformThreadTimes = new ArrayList<>();
    
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
      virtualThreadTimes.add(runParseTest(concurrency, true, 1));
      platformThreadTimes.add(runParseTest(concurrency, false, 1));
    }
    
    // Calculate average times
    long avgVirtualThreadTime = calculateAverage(virtualThreadTimes);
    long avgPlatformThreadTime = calculateAverage(platformThreadTimes);
    
    // Log results
    System.out.printf("Parse performance with %d concurrent operations:%n", concurrency);
    System.out.printf("  Virtual Threads: %d ms%n", avgVirtualThreadTime);
    System.out.printf("  Platform Threads: %d ms%n", avgPlatformThreadTime);
    System.out.printf("  Improvement: %.2f%%%n", calculateImprovement(avgPlatformThreadTime, avgVirtualThreadTime));
    
    // For high concurrency, virtual threads should perform better
    if (concurrency >= 1000) {
      assertTrue(avgVirtualThreadTime < avgPlatformThreadTime, 
          "Virtual threads should be faster than platform threads at high concurrency");
    }
  }
  
  /**
   * Tests toString performance with virtual threads vs platform threads at different concurrency levels.
   * This test demonstrates that virtual threads provide better scalability with high concurrency loads.
   *
   * @param concurrency the number of concurrent operations to perform
   */
  @ParameterizedTest(name = "ToString performance with {0} concurrent operations")
  @ValueSource(ints = {10, 100, 1000, 10000})
  void testToStringPerformance(int concurrency) throws Exception {
    // Warm up to avoid JIT compilation effects
    for (int i = 0; i < WARMUP_ITERATIONS; i++) {
      runToStringTest(concurrency, true, 1);
      runToStringTest(concurrency, false, 1);
    }
    
    // Measure performance
    List<Long> virtualThreadTimes = new ArrayList<>();
    List<Long> platformThreadTimes = new ArrayList<>();
    
    for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
      virtualThreadTimes.add(runToStringTest(concurrency, true, 1));
      platformThreadTimes.add(runToStringTest(concurrency, false, 1));
    }
    
    // Calculate average times
    long avgVirtualThreadTime = calculateAverage(virtualThreadTimes);
    long avgPlatformThreadTime = calculateAverage(platformThreadTimes);
    
    // Log results
    System.out.printf("ToString performance with %d concurrent operations:%n", concurrency);
    System.out.printf("  Virtual Threads: %d ms%n", avgVirtualThreadTime);
    System.out.printf("  Platform Threads: %d ms%n", avgPlatformThreadTime);
    System.out.printf("  Improvement: %.2f%%%n", calculateImprovement(avgPlatformThreadTime, avgVirtualThreadTime));
    
    // For high concurrency, virtual threads should perform better
    if (concurrency >= 1000) {
      assertTrue(avgVirtualThreadTime < avgPlatformThreadTime, 
          "Virtual threads should be faster than platform threads at high concurrency");
    }
  }
  
  /**
   * Tests memory efficiency of virtual threads vs platform threads under high load.
   * This test demonstrates that virtual threads are more memory-efficient than platform threads.
   */
  @Test
  @DisplayName("Memory efficiency under high load")
  void testMemoryEfficiency() throws Exception {
    int concurrency = 10000;
    int iterations = 10;
    
    // Measure memory usage with platform threads
    System.gc(); // Request garbage collection to get a cleaner baseline
    long memoryBefore = getUsedMemory();
    runParseTest(concurrency, false, iterations);
    long memoryAfter = getUsedMemory();
    long platformThreadMemoryUsage = memoryAfter - memoryBefore;
    
    // Reset and measure memory usage with virtual threads
    System.gc();
    memoryBefore = getUsedMemory();
    runParseTest(concurrency, true, iterations);
    memoryAfter = getUsedMemory();
    long virtualThreadMemoryUsage = memoryAfter - memoryBefore;
    
    // Log results
    System.out.printf("Memory usage for %d concurrent operations with %d iterations:%n", concurrency, iterations);
    System.out.printf("  Platform Threads: %d bytes%n", platformThreadMemoryUsage);
    System.out.printf("  Virtual Threads: %d bytes%n", virtualThreadMemoryUsage);
    System.out.printf("  Memory savings: %.2f%%%n", 
        calculateImprovement(platformThreadMemoryUsage, virtualThreadMemoryUsage));
    
    // Virtual threads should use less memory
    assertTrue(virtualThreadMemoryUsage < platformThreadMemoryUsage, 
        "Virtual threads should use less memory than platform threads");
  }
  
  /**
   * Tests throughput scaling with increasing concurrency levels.
   * This test demonstrates that virtual threads maintain better throughput as concurrency increases.
   */
  @Test
  @DisplayName("Throughput scaling with increasing concurrency")
  void testThroughputScaling() throws Exception {
    int[] concurrencyLevels = {10, 100, 1000, 5000, 10000};
    int operationsPerThread = 100;
    
    System.out.println("Throughput (operations/second) at different concurrency levels:");
    System.out.println("Concurrency | Virtual Threads | Platform Threads | Ratio");
    System.out.println("-----------|-----------------|-----------------|---------");
    
    for (int concurrency : concurrencyLevels) {
      // Warm up
      runThroughputTest(concurrency, operationsPerThread, true);
      runThroughputTest(concurrency, operationsPerThread, false);
      
      // Measure throughput
      double virtualThreadThroughput = runThroughputTest(concurrency, operationsPerThread, true);
      double platformThreadThroughput = runThroughputTest(concurrency, operationsPerThread, false);
      double ratio = virtualThreadThroughput / platformThreadThroughput;
      
      System.out.printf("%10d | %15.2f | %15.2f | %7.2f%n", 
          concurrency, virtualThreadThroughput, platformThreadThroughput, ratio);
      
      // At high concurrency, virtual threads should have significantly better throughput
      if (concurrency >= 1000) {
        assertTrue(virtualThreadThroughput > platformThreadThroughput, 
            "Virtual threads should have better throughput at high concurrency");
      }
    }
  }
  
  /**
   * Runs a parse test with the specified concurrency level and thread type.
   *
   * @param concurrency the number of concurrent operations to perform
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @param iterations the number of iterations to run for each thread
   * @return the execution time in milliseconds
   */
  private long runParseTest(int concurrency, boolean useVirtualThreads, int iterations) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    ExecutorService executor = createExecutor(useVirtualThreads, concurrency);
    
    Instant start = Instant.now();
    
    try {
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        final int threadIndex = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < iterations; j++) {
              // Each thread parses a subset of the test data
              for (int k = 0; k < 10; k++) {
                int index = (threadIndex * 10 + k) % blobRefStrings.size();
                BlobRef blobRef = BlobRef.parse(blobRefStrings.get(index));
                assertNotNull(blobRef);
              }
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Tasks did not complete within timeout");
      assertEquals(0, errorCount.get(), "Some tasks encountered errors");
      
    } finally {
      executor.shutdown();
    }
    
    return Duration.between(start, Instant.now()).toMillis();
  }
  
  /**
   * Runs a toString test with the specified concurrency level and thread type.
   *
   * @param concurrency the number of concurrent operations to perform
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @param iterations the number of iterations to run for each thread
   * @return the execution time in milliseconds
   */
  private long runToStringTest(int concurrency, boolean useVirtualThreads, int iterations) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    
    ExecutorService executor = createExecutor(useVirtualThreads, concurrency);
    
    Instant start = Instant.now();
    
    try {
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        final int threadIndex = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < iterations; j++) {
              // Each thread formats a subset of the test data
              for (int k = 0; k < 10; k++) {
                int index = (threadIndex * 10 + k) % blobRefs.size();
                String blobRefString = blobRefs.get(index).toString();
                assertNotNull(blobRefString);
                assertFalse(blobRefString.isEmpty());
              }
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(30, TimeUnit.SECONDS);
      assertTrue(completed, "Tasks did not complete within timeout");
      assertEquals(0, errorCount.get(), "Some tasks encountered errors");
      
    } finally {
      executor.shutdown();
    }
    
    return Duration.between(start, Instant.now()).toMillis();
  }
  
  /**
   * Runs a throughput test with the specified concurrency level and thread type.
   *
   * @param concurrency the number of concurrent operations to perform
   * @param operationsPerThread the number of operations per thread
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @return the throughput in operations per second
   */
  private double runThroughputTest(int concurrency, int operationsPerThread, boolean useVirtualThreads) throws Exception {
    CountDownLatch latch = new CountDownLatch(concurrency);
    AtomicInteger errorCount = new AtomicInteger(0);
    AtomicLong operationCount = new AtomicLong(0);
    
    ExecutorService executor = createExecutor(useVirtualThreads, concurrency);
    
    Instant start = Instant.now();
    
    try {
      // Submit tasks
      for (int i = 0; i < concurrency; i++) {
        final int threadIndex = i;
        executor.submit(() -> {
          try {
            for (int j = 0; j < operationsPerThread; j++) {
              // Each thread performs both parse and toString operations
              int parseIndex = (threadIndex * operationsPerThread + j) % blobRefStrings.size();
              BlobRef blobRef = BlobRef.parse(blobRefStrings.get(parseIndex));
              
              int toStringIndex = (threadIndex * operationsPerThread + j) % blobRefs.size();
              String blobRefString = blobRefs.get(toStringIndex).toString();
              
              operationCount.addAndGet(2); // Count both operations
            }
          } catch (Exception e) {
            errorCount.incrementAndGet();
            e.printStackTrace();
          } finally {
            latch.countDown();
          }
        });
      }
      
      // Wait for all tasks to complete
      boolean completed = latch.await(60, TimeUnit.SECONDS);
      assertTrue(completed, "Tasks did not complete within timeout");
      assertEquals(0, errorCount.get(), "Some tasks encountered errors");
      
    } finally {
      executor.shutdown();
    }
    
    long durationMillis = Duration.between(start, Instant.now()).toMillis();
    return (operationCount.get() * 1000.0) / durationMillis; // Operations per second
  }
  
  /**
   * Creates an executor service based on the specified thread type and concurrency level.
   *
   * @param useVirtualThreads whether to use virtual threads or platform threads
   * @param concurrency the maximum number of threads in the pool (for platform threads)
   * @return the executor service
   */
  private ExecutorService createExecutor(boolean useVirtualThreads, int concurrency) {
    if (useVirtualThreads) {
      return Executors.newVirtualThreadPerTaskExecutor();
    } else {
      return Executors.newFixedThreadPool(Math.min(concurrency, 200)); // Cap platform threads
    }
  }
  
  /**
   * Calculates the average of a list of long values.
   *
   * @param values the values to average
   * @return the average value
   */
  private long calculateAverage(List<Long> values) {
    return (long) values.stream()
        .mapToLong(Long::longValue)
        .average()
        .orElse(0);
  }
  
  /**
   * Calculates the percentage improvement between two values.
   *
   * @param before the before value
   * @param after the after value
   * @return the percentage improvement
   */
  private double calculateImprovement(long before, long after) {
    return ((double) (before - after) / before) * 100.0;
  }
  
  /**
   * Gets the current used memory in bytes.
   *
   * @return the used memory in bytes
   */
  private long getUsedMemory() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}